package com.ppn.piracyprotectednotesapp.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.ppn.piracyprotectednotesapp.BuildConfig
import com.ppn.piracyprotectednotesapp.data.ChatMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object OpenRouterApiClient {

    /**
     * "openrouter/auto" automatically picks the best available free vision model.
     * Pinned alternatives:
     *   google/gemma-3-12b-it:free
     *   google/gemma-3-27b-it:free
     */
    private const val MODEL    = "openrouter/auto"
    private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

    private const val HISTORY_WINDOW     = 4
    private const val HISTORY_TEXT_LIMIT = 500

    /**
     * Max dimension for image resize. 512px is enough for OCR/reading tasks
     * and keeps base64 payload under ~100 KB even for full PDF pages.
     */
    private const val IMAGE_MAX_DIM = 512

    /** JPEG quality — 65 gives good readability at minimal size. */
    private const val IMAGE_QUALITY = 65

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Send a prompt to OpenRouter.
     * Image compression + base64 encoding is done on a background thread
     * before the HTTP request is built, so the main thread is never blocked.
     */
    fun sendMessage(
        history:        List<ChatMessage>,
        userText:       String,
        tempScreenshot: File?,
        onSuccess:      (String) -> Unit,
        onFailure:      (String) -> Unit
    ) {
        // Run everything (encoding + network) on a background thread
        Thread {
            // Encode image on background thread — never on main thread
            val base64Image: String? = if (tempScreenshot != null && tempScreenshot.exists()) {
                compressAndEncode(tempScreenshot)
            } else {
                null
            }

            val body = buildRequestBody(history, userText, base64Image)

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.OPEN_ROUTER_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            try {
                val response = client.newCall(request).execute()  // blocking — fine on bg thread
                val bodyStr  = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val text = runCatching {
                            JSONObject(bodyStr)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                        }.getOrElse { "Could not parse response." }
                        onSuccess(text.trim())
                    }
                    429 -> onFailure("Rate limit reached. Please wait a moment and try again.")
                    401, 403 -> onFailure("API key error. Check your OpenRouter API key.")
                    else -> {
                        val errMsg = runCatching {
                            JSONObject(bodyStr)
                                .getJSONObject("error")
                                .getString("message")
                        }.getOrElse { "API error ${response.code}" }
                        onFailure(errMsg)
                    }
                }
            } catch (e: IOException) {
                onFailure("Network error: ${e.message}")
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build JSON request body (OpenAI-compatible format)
    // Text first, then image — OpenRouter requirement
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRequestBody(
        history:     List<ChatMessage>,
        userText:    String,
        base64Image: String?
    ): RequestBody {
        val messagesArray = JSONArray()

        // Replay last HISTORY_WINDOW messages — text only
        history.takeLast(HISTORY_WINDOW).forEach { msg ->
            val truncatedText = msg.text
                .take(HISTORY_TEXT_LIMIT)
                .ifEmpty { "(image attached)" }
            messagesArray.put(
                JSONObject()
                    .put("role", msg.role.toOpenRouterRole())
                    .put("content", truncatedText)
            )
        }

        // Current user turn
        val contentArray = JSONArray()

        // 1. Text first
        contentArray.put(
            JSONObject()
                .put("type", "text")
                .put("text", userText.ifEmpty { "What is shown in this image?" })
        )

        // 2. Image after text (if present)
        if (base64Image != null) {
            contentArray.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$base64Image")
                    )
            )
        }

        messagesArray.put(
            JSONObject()
                .put("role", "user")
                .put("content", contentArray)
        )

        return JSONObject()
            .put("model", MODEL)
            .put("messages", messagesArray)
            .toString()
            .toRequestBody("application/json".toMediaType())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resize to IMAGE_MAX_DIM on longest side, compress to JPEG
    // ─────────────────────────────────────────────────────────────────────────

    private fun compressAndEncode(file: File): String? {
        return try {
            // Sample down while decoding to save memory on large PDF bitmaps
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val rawMax   = maxOf(opts.outWidth, opts.outHeight)
            val sample   = (rawMax / IMAGE_MAX_DIM).coerceAtLeast(1)
            opts.inSampleSize      = sample
            opts.inJustDecodeBounds = false

            val sampled  = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

            // Fine-scale down to exact max dim if still over
            val scaled = if (sampled.width > IMAGE_MAX_DIM || sampled.height > IMAGE_MAX_DIM) {
                val ratio = IMAGE_MAX_DIM.toFloat() / maxOf(sampled.width, sampled.height)
                val newW  = (sampled.width  * ratio).toInt()
                val newH  = (sampled.height * ratio).toInt()
                val out   = Bitmap.createScaledBitmap(sampled, newW, newH, true)
                sampled.recycle()
                out
            } else {
                sampled
            }

            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, bos)
            scaled.recycle()

            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gemini uses "model" for assistant role; OpenRouter uses "assistant"
    // ─────────────────────────────────────────────────────────────────────────

    private fun String.toOpenRouterRole(): String =
        if (this == "model") "assistant" else this
}