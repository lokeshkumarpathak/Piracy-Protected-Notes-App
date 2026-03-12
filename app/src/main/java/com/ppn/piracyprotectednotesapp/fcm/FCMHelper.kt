package com.ppn.piracyprotectednotesapp.fcm

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object FcmHelper {

    private const val TAG = "FcmHelperUser"
    private const val GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"

    // ── In-memory OAuth2 token cache (valid ~1 hour) ──────────────────────────
    private var cachedToken: String? = null
    private var tokenExpiryMs: Long  = 0L

    private val client = OkHttpClient()

    // ── Application context — set once from MyFirebaseMessagingService or App ─
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read service account JSON from assets/secrets.json
    // ─────────────────────────────────────────────────────────────────────────

    private fun getServiceAccountJson(): String {
        val ctx = appContext
            ?: throw IllegalStateException("FcmHelper not initialized. Call FcmHelper.init(context) first.")
        return ctx.assets.open("secrets.json").bufferedReader().use { it.readText() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun saveTokenToFirestore(collection: String, phone: String, token: String) {
        FirebaseFirestore.getInstance()
            .collection(collection)
            .document(phone)
            .update("fcmToken", token)
            .addOnFailureListener {
                FirebaseFirestore.getInstance()
                    .collection(collection)
                    .document(phone)
                    .set(mapOf("fcmToken" to token),
                        com.google.firebase.firestore.SetOptions.merge())
            }
    }

    /**
     * Send an FCM V1 push notification to a single device.
     * Runs on a background thread — safe to call from any context.
     */
    fun sendNotification(
        targetFcmToken: String,
        title:          String,
        body:           String,
        data:           Map<String, String> = emptyMap(),
        onSuccess:      () -> Unit = {},
        onFailure:      (String) -> Unit = {}
    ) {
        Thread {
            try {
                val serviceAccountJson = getServiceAccountJson()
                val projectId          = JSONObject(serviceAccountJson).getString("project_id")
                val accessToken        = getAccessToken(serviceAccountJson)
                Log.d("FCM_DEBUG", "Sending notification to: $targetFcmToken")
                val message = JSONObject().apply {
                    put("token", targetFcmToken)
                    // Data-only — no "notification" block so onMessageReceived
                    // always fires regardless of app state (foreground/background/killed)
                    put("data", JSONObject().apply {
                        put("title", title)
                        put("body",  body)
                        data.forEach { (k, v) -> put(k, v) }
                    })
                    put("android", JSONObject().apply {
                        put("priority", "high")   // lowercase required by FCM v1
                        put("ttl",      "86400s") // 24-hour delivery window
                    })
                }

                val payload = JSONObject().put("message", message).toString()

                val request = Request.Builder()
                    .url("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type",  "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ FCM sent to $targetFcmToken")
                        onSuccess()
                    } else {
                        val err = response.body?.string() ?: "Unknown error ${response.code}"
                        Log.e(TAG, "❌ FCM failed: $err")
                        onFailure(err)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM exception: ${e.message}")
                onFailure(e.message ?: "Unknown exception")
            }
        }.start()
    }

    /**
     * Send an FCM notification to multiple devices at once.
     */
    fun sendNotificationToAll(
        tokens: List<String>,
        title:  String,
        body:   String,
        data:   Map<String, String> = emptyMap()
    ) {
        Log.d(TAG, "Sending to ${tokens.size} tokens")
        if (tokens.isEmpty()) {
            Log.e(TAG, "Token list is EMPTY — no notifications will be sent!")
            return
        }
        tokens.forEach { token ->
            sendNotification(
                targetFcmToken = token,
                title          = title,
                body           = body,
                data           = data,
                onSuccess      = { Log.d(TAG, "✅ FCM success for token: $token") },
                onFailure      = { err -> Log.e(TAG, "❌ FCM failed for token: $token — $err") }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OAuth2 — exchange Service Account JWT for a Bearer token
    // ─────────────────────────────────────────────────────────────────────────

    // Uses HttpURLConnection instead of OkHttp to guarantee Content-Type
    // is never overridden — OkHttp was silently ignoring it causing 400 errors
    private fun getAccessToken(serviceAccountJson: String): String {
        Log.d(TAG, ">>> getAccessToken called, using HttpURLConnection")
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryMs - 300_000) {
            return cachedToken!!
        }

        val sa          = JSONObject(serviceAccountJson)
        val clientEmail = sa.getString("client_email")
        val privateKey  = sa.getString("private_key")
        val jwt         = buildJwt(clientEmail, privateKey)

        val postBody =
            "grant_type=" + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
                    "&assertion=" + java.net.URLEncoder.encode(jwt, "UTF-8")

        val conn = (java.net.URL(GOOGLE_TOKEN_URI).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod  = "POST"
            doOutput       = true
            doInput        = true
            connectTimeout = 15_000
            readTimeout    = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }

        try {
            conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Empty error response"
            }

            Log.d(TAG, "Token response ($responseCode): $responseBody")

            if (responseCode !in 200..299)
                throw IOException("Token request failed ($responseCode): $responseBody")

            val json      = JSONObject(responseBody)
            cachedToken   = json.getString("access_token")
            tokenExpiryMs = System.currentTimeMillis() + (json.optLong("expires_in", 3600) * 1000)
            return cachedToken!!
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build a signed RS256 JWT — no third-party JWT library needed
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildJwt(clientEmail: String, privateKeyPem: String): String {
        val now = System.currentTimeMillis() / 1000

        val header = base64Url("""{"alg":"RS256","typ":"JWT"}""")
        val claims = base64Url(JSONObject().apply {
            put("iss",   clientEmail)
            put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            put("aud",   GOOGLE_TOKEN_URI) // must match POST endpoint exactly
            put("iat",   now)
            put("exp",   now + 3600)
        }.toString())

        val signingInput = "$header.$claims"

        val pemContent = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()

        val keyBytes   = Base64.decode(pemContent, Base64.DEFAULT)
        val privateKey = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

        val signatureBytes = java.security.Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
        }.sign()

        val sig = Base64.encodeToString(
            signatureBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$signingInput.$sig"
    }

    private fun base64Url(input: String): String =
        Base64.encodeToString(
            input.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}