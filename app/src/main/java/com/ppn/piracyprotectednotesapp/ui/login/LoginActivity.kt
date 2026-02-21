package com.ppn.piracyprotectednotesapp.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import com.ppn.piracyprotectednotesapp.BuildConfig
import com.ppn.piracyprotectednotesapp.MainActivity
import com.ppn.piracyprotectednotesapp.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val client = OkHttpClient()

    // Stores the OTP we generated, mapped to the phone number
    private var generatedOtp: String? = null
    private var enteredPhone: String? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Already logged in — check device lock then proceed
        if (auth.currentUser != null) {
            val uid = auth.currentUser!!.uid
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            handleDeviceLock(uid, deviceId)
            return
        }

        val phoneField = findViewById<EditText>(R.id.phone_field)
        val otpField = findViewById<EditText>(R.id.otp_field)
        val getOtpBtn = findViewById<Button>(R.id.getOtp)
        val loginBtn = findViewById<Button>(R.id.login)

        otpField.visibility = View.GONE
        loginBtn.visibility = View.GONE

        // Step 1: Generate OTP and send via Twilio
        getOtpBtn.setOnClickListener {
            val phone = phoneField.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!phone.startsWith("+")) {
                Toast.makeText(this, "Include country code e.g. +91XXXXXXXXXX", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enteredPhone = phone
            getOtpBtn.isEnabled = false
            getOtpBtn.text = "Sending..."
            sendOtpViaTwilio(phone, otpField, loginBtn, getOtpBtn)
        }

        // Step 2: Verify OTP entered by user
        loginBtn.setOnClickListener {
            val code = otpField.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "Please enter the OTP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            verifyOtp(code)
        }
    }

    private fun generateOtp(): String {
        // 6-digit OTP
        return String.format("%06d", Random.nextInt(0, 999999))
    }

    private fun sendOtpViaTwilio(
        phone: String,
        otpField: EditText,
        loginBtn: Button,
        getOtpBtn: Button
    ) {
        val otp = generateOtp()
        generatedOtp = otp

        val accountSid = BuildConfig.TWILIO_ACCOUNT_SID
        val authToken = BuildConfig.TWILIO_AUTH_TOKEN
        val fromNumber = BuildConfig.TWILIO_PHONE_NUMBER

        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"

        val body = FormBody.Builder()
            .add("To", phone)
            .add("From", fromNumber)
            .add("Body", "Your PPN App OTP is: $otp. Valid for 5 minutes. Do not share.")
            .build()

        val credential = Credentials.basic(accountSid, authToken)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", credential)
            .build()

        // OkHttp runs this on background thread automatically
        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    getOtpBtn.text = "Get OTP"
                    getOtpBtn.isEnabled = true
                    Toast.makeText(this@LoginActivity, "Failed to send OTP: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        otpField.visibility = View.VISIBLE
                        loginBtn.visibility = View.VISIBLE
                        getOtpBtn.text = "Resend OTP"
                        getOtpBtn.isEnabled = true
                        Toast.makeText(this@LoginActivity, "OTP sent!", Toast.LENGTH_SHORT).show()
                    } else {
                        val errorBody = response.body?.string()
                        getOtpBtn.text = "Get OTP"
                        getOtpBtn.isEnabled = true

                        // Parse Twilio error message for cleaner display
                        try {
                            val json = JSONObject(errorBody ?: "")
                            val twilioMessage = json.getString("message")
                            Toast.makeText(this@LoginActivity, "Twilio error: $twilioMessage", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@LoginActivity, "Error: $errorBody", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    private fun verifyOtp(enteredCode: String) {
        if (enteredCode == generatedOtp) {
            // OTP correct — sign in anonymously to Firebase to get a UID
            // We use phone number as the document key in Firestore
            val phone = enteredPhone ?: return
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            handleDeviceLock(phone, deviceId)
        } else {
            Toast.makeText(this, "Incorrect OTP. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeviceLock(identifier: String, deviceId: String) {

        // Step 1: Check if this ANDROID_ID is permanently blocked
        db.collection("blocked_devices").document(deviceId).get()
            .addOnSuccessListener { blockedDoc ->

                if (blockedDoc.exists()) {
                    Toast.makeText(
                        this,
                        "This device is permanently blocked. Contact IT Team.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@addOnSuccessListener
                }

                val ref = db.collection("users").document(identifier)

                ref.get().addOnSuccessListener { doc ->

                    if (!doc.exists()) {
                        // Brand new user — register with first device
                        ref.set(
                            mapOf(
                                "identifier" to identifier,
                                "currentDeviceId" to deviceId,
                                "deviceHistory" to listOf(deviceId),
                                "blocked" to false,
                                "registeredAt" to Timestamp.now()
                            )
                        )
                        goNext()

                    } else {

                        val currentDeviceId = doc.getString("currentDeviceId")
                        val deviceHistory = doc.get("deviceHistory") as? List<*> ?: emptyList<String>()
                        val isBlocked = doc.getBoolean("blocked") ?: false

                        // Account itself is blocked
                        if (isBlocked) {
                            Toast.makeText(
                                this,
                                "Your account is blocked. Contact IT Team.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                            return@addOnSuccessListener
                        }

                        when {

                            // Same device as registered — let them in
                            currentDeviceId == deviceId -> goNext()

                            // First time switching device — allow but block old device permanently
                            deviceHistory.size == 1 -> {
                                val oldDeviceId = deviceHistory[0] as String

                                // Permanently block the old device
                                db.collection("blocked_devices").document(oldDeviceId).set(
                                    mapOf(
                                        "identifier" to identifier,
                                        "blockedAt" to Timestamp.now(),
                                        "reason" to "Replaced by newer device"
                                    )
                                )

                                // Update user record with new device
                                ref.update(
                                    mapOf(
                                        "currentDeviceId" to deviceId,
                                        "deviceHistory" to listOf(oldDeviceId, deviceId),
                                        "lastDeviceChangeAt" to Timestamp.now()
                                    )
                                )

                                Toast.makeText(
                                    this,
                                    "New device registered. Previous device is now permanently blocked.",
                                    Toast.LENGTH_LONG
                                ).show()
                                goNext()
                            }

                            // Already used 2 devices — block account and this new device
                            deviceHistory.size >= 2 -> {

                                // Block this new device
                                db.collection("blocked_devices").document(deviceId).set(
                                    mapOf(
                                        "identifier" to identifier,
                                        "blockedAt" to Timestamp.now(),
                                        "reason" to "Device limit exceeded"
                                    )
                                )

                                // Block the account entirely
                                ref.update(
                                    mapOf(
                                        "blocked" to true,
                                        "blockedAt" to Timestamp.now(),
                                        "blockedReason" to "Attempted login from 3rd device"
                                    )
                                )

                                Toast.makeText(
                                    this,
                                    "Device limit exceeded. Account permanently blocked. Contact IT Team.",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        }
                    }
                }.addOnFailureListener {
                    Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun goNext() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}