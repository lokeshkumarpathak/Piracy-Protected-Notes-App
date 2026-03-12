package com.ppn.piracyprotectednotesapp.ui.login

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.ppn.piracyprotectednotesapp.BuildConfig
import com.ppn.piracyprotectednotesapp.MainActivity
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.fcm.FcmHelper
import com.ppn.piracyprotectednotesapp.fcm.MyFirebaseMessagingService
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.utils.DeviceIdManager
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.random.Random

class LoginActivity : BaseActivity() {

    private lateinit var db: FirebaseFirestore
    private val client = OkHttpClient()

    private var generatedEmailOtp: String? = null
    private var generatedPhoneOtp: String? = null

    private var emailVerified = false
    private var phoneVerified = false

    private var verifiedEmail: String? = null
    private var verifiedPhone: String? = null

    // Foreground-only snapshot listener — supplements FCM when app is open
    private var approvalListener: ListenerRegistration? = null

    private val loginFormIds = listOf(
        R.id.email, R.id.verifyEmailBtn, R.id.email_otp_field, R.id.confirmEmailOtpBtn,
        R.id.layout_email_verified_row, R.id.divider1,
        R.id.phone_field, R.id.getOtp, R.id.otp_field, R.id.confirmPhoneOtpBtn,
        R.id.layout_phone_verified_row, R.id.login, R.id.resend_request_btn,
        R.id.tv_title
    )

    companion object {
        const val PREFS_NAME         = "ppn_user_session"
        const val KEY_PHONE          = "logged_in_phone"
        const val KEY_VERIFIED_EMAIL = "verified_email"
        const val KEY_VERIFIED_PHONE = "verified_phone"
        private const val CHANNEL_ID = "ppn_approval_channel"
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        db = FirebaseFirestore.getInstance()
        FcmHelper.init(this) // Initialize so FcmHelper can read secrets.json
        createNotificationChannel()
        requestNotificationPermission()

        val prefs    = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val deviceId = DeviceIdManager.getDeviceId(this)

        val savedPhone = prefs.getString(KEY_PHONE, null)
        if (!savedPhone.isNullOrEmpty()) {
            hideLoginForm()
            showLoading(true)
            verifySessionAndProceed(savedPhone, deviceId, prefs)
            return
        }

        showLoginForm()
        setupLoginForm(prefs, deviceId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun hideLoginForm() {
        loginFormIds.forEach { id -> findViewById<View>(id)?.visibility = View.INVISIBLE }
    }

    private fun showLoginForm() {
        loginFormIds.forEach { id -> findViewById<View>(id)?.visibility = View.VISIBLE }
        listOf(
            R.id.email_otp_field, R.id.confirmEmailOtpBtn,
            R.id.otp_field, R.id.confirmPhoneOtpBtn,
            R.id.resend_request_btn
        ).forEach { id -> findViewById<View>(id)?.visibility = View.GONE }
        findViewById<TextView>(R.id.tv_email_verified).visibility = View.GONE
        findViewById<TextView>(R.id.tv_phone_verified).visibility = View.GONE
    }

    private fun showLoading(show: Boolean) {
        findViewById<LinearLayout>(R.id.loading).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setLoginBusy(busy: Boolean) {
        findViewById<Button>(R.id.login).apply {
            isEnabled = !busy
            text      = if (busy) "Checking..." else "Login"
        }
        findViewById<ProgressBar>(R.id.login_progress)?.visibility =
            if (busy) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Login form setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupLoginForm(prefs: android.content.SharedPreferences, deviceId: String) {
        val emailField         = findViewById<EditText>(R.id.email)
        val verifyEmailBtn     = findViewById<Button>(R.id.verifyEmailBtn)
        val emailOtpField      = findViewById<EditText>(R.id.email_otp_field)
        val confirmEmailOtpBtn = findViewById<Button>(R.id.confirmEmailOtpBtn)
        val editEmailBtn       = findViewById<Button>(R.id.btn_edit_email)

        val phoneField         = findViewById<EditText>(R.id.phone_field)
        val getOtpBtn          = findViewById<Button>(R.id.getOtp)
        val otpField           = findViewById<EditText>(R.id.otp_field)
        val confirmPhoneOtpBtn = findViewById<Button>(R.id.confirmPhoneOtpBtn)
        val editPhoneBtn       = findViewById<Button>(R.id.btn_edit_phone)

        val loginBtn           = findViewById<Button>(R.id.login)
        val resendBtn          = findViewById<Button>(R.id.resend_request_btn)

        // ── Remembered verification restore ───────────────────────────────────
        val rememberedEmail = prefs.getString(KEY_VERIFIED_EMAIL, null)
        val rememberedPhone = prefs.getString(KEY_VERIFIED_PHONE, null)

        if (!rememberedEmail.isNullOrEmpty() && !rememberedPhone.isNullOrEmpty()) {
            emailVerified = true
            phoneVerified = true
            verifiedEmail = rememberedEmail
            verifiedPhone = rememberedPhone

            emailField.setText(rememberedEmail)
            emailField.isEnabled          = false
            verifyEmailBtn.isEnabled      = false
            verifyEmailBtn.text           = "Sent"
            emailOtpField.visibility      = View.GONE
            confirmEmailOtpBtn.visibility = View.GONE
            findViewById<TextView>(R.id.tv_email_verified).visibility = View.VISIBLE

            phoneField.setText(rememberedPhone)
            phoneField.isEnabled          = false
            getOtpBtn.isEnabled           = false
            getOtpBtn.text                = "Sent"
            otpField.visibility           = View.GONE
            confirmPhoneOtpBtn.visibility = View.GONE
            findViewById<TextView>(R.id.tv_phone_verified).visibility = View.VISIBLE

            db.collection("users").document(rememberedPhone).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists() && doc.getBoolean("allowed") != true) {
                        resendBtn.visibility = View.VISIBLE
                        MyFirebaseMessagingService.refreshAndSaveToken(rememberedPhone)
                        // Foreground listener — handles the case where app is open
                        // when approval arrives (FCM handles background/killed)
                        startForegroundApprovalListener(rememberedPhone)
                    }
                }
        }

        // ── OTP TextWatchers ──────────────────────────────────────────────────
        emailOtpField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { confirmEmailOtpBtn.isEnabled = (s?.length == 6) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        otpField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { confirmPhoneOtpBtn.isEnabled = (s?.length == 6) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // ── Edit Email ────────────────────────────────────────────────────────
        editEmailBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Edit Email")
                .setMessage("You will need to re-enter and re-verify your email via OTP. Continue?")
                .setPositiveButton("Yes, Edit") { _, _ ->
                    emailVerified = false; verifiedEmail = null
                    prefs.edit().remove(KEY_VERIFIED_EMAIL).apply()
                    stopForegroundApprovalListener()
                    emailField.isEnabled = true; emailField.text.clear()
                    verifyEmailBtn.isEnabled = true; verifyEmailBtn.text = "Send OTP"
                    emailOtpField.text.clear(); emailOtpField.isEnabled = true
                    emailOtpField.visibility = View.GONE
                    confirmEmailOtpBtn.isEnabled = false; confirmEmailOtpBtn.text = "Confirm"
                    confirmEmailOtpBtn.visibility = View.GONE
                    findViewById<TextView>(R.id.tv_email_verified).visibility = View.GONE
                    resendBtn.visibility = View.GONE
                }
                .setNegativeButton("Cancel", null).show()
        }

        // ── Edit Phone ────────────────────────────────────────────────────────
        editPhoneBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Edit Phone Number")
                .setMessage("You will need to re-enter and re-verify your phone number via OTP. Continue?")
                .setPositiveButton("Yes, Edit") { _, _ ->
                    phoneVerified = false; verifiedPhone = null
                    prefs.edit().remove(KEY_VERIFIED_PHONE).apply()
                    stopForegroundApprovalListener()
                    phoneField.isEnabled = true; phoneField.text.clear()
                    getOtpBtn.isEnabled = true; getOtpBtn.text = "Send OTP"
                    otpField.text.clear(); otpField.isEnabled = true
                    otpField.visibility = View.GONE
                    confirmPhoneOtpBtn.isEnabled = false; confirmPhoneOtpBtn.text = "Confirm"
                    confirmPhoneOtpBtn.visibility = View.GONE
                    findViewById<TextView>(R.id.tv_phone_verified).visibility = View.GONE
                    resendBtn.visibility = View.GONE
                }
                .setNegativeButton("Cancel", null).show()
        }

        // ── Send Email OTP ────────────────────────────────────────────────────
        verifyEmailBtn.setOnClickListener {
            val email = emailField.text.toString().trim()
            if (!isValidNitkkrEmail(email)) {
                Toast.makeText(this, "Email must be @nitkkr.ac.in", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            verifyEmailBtn.isEnabled = false; verifyEmailBtn.text = "Sending..."
            val otp = generateOtp().also { generatedEmailOtp = it }
            sendEmailOtp(email, otp,
                onSent = {
                    runOnUiThread {
                        emailOtpField.visibility = View.VISIBLE
                        confirmEmailOtpBtn.visibility = View.VISIBLE
                        confirmEmailOtpBtn.isEnabled = false
                        verifyEmailBtn.isEnabled = true; verifyEmailBtn.text = "Resend OTP"
                        emailField.isEnabled = false
                        Toast.makeText(this, "OTP sent to $email", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailed = { err ->
                    runOnUiThread {
                        verifyEmailBtn.isEnabled = true; verifyEmailBtn.text = "Send OTP"
                        Toast.makeText(this, "Failed to send email OTP: $err", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // ── Confirm Email OTP ─────────────────────────────────────────────────
        confirmEmailOtpBtn.setOnClickListener {
            val entered = emailOtpField.text.toString().trim()
            if (entered != generatedEmailOtp) {
                Toast.makeText(this, "Incorrect email OTP. Try again.", Toast.LENGTH_SHORT).show()
                emailOtpField.text.clear(); return@setOnClickListener
            }
            emailVerified = true
            verifiedEmail = emailField.text.toString().trim()
            prefs.edit().putString(KEY_VERIFIED_EMAIL, verifiedEmail).apply()
            emailOtpField.isEnabled = false
            confirmEmailOtpBtn.isEnabled = false; confirmEmailOtpBtn.text = "Confirmed ✓"
            verifyEmailBtn.isEnabled = false
            findViewById<TextView>(R.id.tv_email_verified).visibility = View.VISIBLE
            Toast.makeText(this, "Email verified!", Toast.LENGTH_SHORT).show()
        }

        // ── Send Phone OTP ────────────────────────────────────────────────────
        getOtpBtn.setOnClickListener {
            val phone = phoneField.text.toString().trim()
            if (phone.isEmpty()) { Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!phone.startsWith("+")) { Toast.makeText(this, "Include country code e.g. +91XXXXXXXXXX", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            getOtpBtn.isEnabled = false; getOtpBtn.text = "Sending..."
            sendOtpViaTwilio(phone,
                onSent = {
                    runOnUiThread {
                        otpField.visibility = View.VISIBLE
                        confirmPhoneOtpBtn.visibility = View.VISIBLE
                        confirmPhoneOtpBtn.isEnabled = false
                        getOtpBtn.isEnabled = true; getOtpBtn.text = "Resend OTP"
                        phoneField.isEnabled = false
                        Toast.makeText(this, "Phone OTP sent!", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailed = { err ->
                    runOnUiThread {
                        getOtpBtn.isEnabled = true; getOtpBtn.text = "Send OTP"
                        Toast.makeText(this, "Failed to send phone OTP: $err", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // ── Confirm Phone OTP ─────────────────────────────────────────────────
        confirmPhoneOtpBtn.setOnClickListener {
            val entered = otpField.text.toString().trim()
            if (entered != generatedPhoneOtp) {
                Toast.makeText(this, "Incorrect phone OTP. Try again.", Toast.LENGTH_SHORT).show()
                otpField.text.clear(); return@setOnClickListener
            }
            phoneVerified = true
            verifiedPhone = phoneField.text.toString().trim()
            prefs.edit().putString(KEY_VERIFIED_PHONE, verifiedPhone).apply()
            otpField.isEnabled = false
            confirmPhoneOtpBtn.isEnabled = false; confirmPhoneOtpBtn.text = "Confirmed ✓"
            getOtpBtn.isEnabled = false
            findViewById<TextView>(R.id.tv_phone_verified).visibility = View.VISIBLE
            Toast.makeText(this, "Phone verified!", Toast.LENGTH_SHORT).show()
        }

        // ── Login ─────────────────────────────────────────────────────────────
        loginBtn.setOnClickListener {
            when {
                !emailVerified -> Toast.makeText(this, "Please verify your email first.", Toast.LENGTH_SHORT).show()
                !phoneVerified -> Toast.makeText(this, "Please verify your phone number first.", Toast.LENGTH_SHORT).show()
                else -> {
                    setLoginBusy(true)
                    handleDeviceLock(verifiedPhone!!, verifiedEmail!!, deviceId)
                }
            }
        }

        // ── Resend approval request ───────────────────────────────────────────
        resendBtn.setOnClickListener {
            resendPendingRequests(verifiedPhone ?: return@setOnClickListener)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground approval listener
    // Only used when the app is open — FCM handles background/killed state
    // No popup — just silently proceeds to MainActivity when approved
    // ─────────────────────────────────────────────────────────────────────────

    private fun startForegroundApprovalListener(userPhone: String) {
        stopForegroundApprovalListener()
        approvalListener = db.collection("users").document(userPhone)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (snapshot.getBoolean("allowed") == true) {
                    stopForegroundApprovalListener()
                    // Show dialog — user taps "Login Now" themselves, no auto-login
                    runOnUiThread {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("🎉 Access Approved!")
                            .setMessage("All managers have approved your request. You can now log in.")
                            .setPositiveButton("Login Now") { _, _ -> goNext(userPhone) }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
    }

    private fun stopForegroundApprovalListener() {
        approvalListener?.remove()
        approvalListener = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gmail SMTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendEmailOtp(toEmail: String, otp: String, onSent: () -> Unit, onFailed: (String) -> Unit) {
        Thread {
            try {
                val props = Properties().apply {
                    put("mail.smtp.auth", "true"); put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com"); put("mail.smtp.port", "587")
                    put("mail.smtp.ssl.trust", "smtp.gmail.com")
                }
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(BuildConfig.SMTP_EMAIL, BuildConfig.SMTP_PASSWORD)
                })
                MimeMessage(session).apply {
                    setFrom(InternetAddress(BuildConfig.SMTP_EMAIL, "PPN App"))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                    subject = "PPN App — Email Verification OTP"
                    setText("Your PPN App email verification OTP is:\n\n  $otp\n\nThis OTP is valid for this session only. Do not share it.")
                }.let { Transport.send(it) }
                onSent()
            } catch (e: Exception) { onFailed(e.message ?: "Unknown error") }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Twilio OTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateOtp(): String = String.format("%06d", Random.nextInt(0, 999999))

    private fun sendOtpViaTwilio(phone: String, onSent: () -> Unit, onFailed: (String) -> Unit) {
        val otp = generateOtp().also { generatedPhoneOtp = it }
        val accountSid = BuildConfig.TWILIO_ACCOUNT_SID
        val authToken  = BuildConfig.TWILIO_AUTH_TOKEN
        val fromNumber = BuildConfig.TWILIO_PHONE_NUMBER
        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"
        val body = FormBody.Builder()
            .add("To", phone).add("From", fromNumber)
            .add("Body", "Your PPN App phone OTP is: $otp. Valid for this session only. Do not share.")
            .build()
        val request = Request.Builder().url(url).post(body)
            .header("Authorization", Credentials.basic(accountSid, authToken)).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onFailed(e.message ?: "Network error") }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) onSent()
                else onFailed(runCatching { JSONObject(response.body?.string() ?: "").getString("message") }.getOrElse { "Error ${response.code}" })
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device lock + approval flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleDeviceLock(identifier: String, email: String, deviceId: String) {
        db.collection("blocked_devices").document(deviceId).get()
            .addOnSuccessListener { blockedDoc ->
                if (blockedDoc.exists()) {
                    setLoginBusy(false)
                    Toast.makeText(this, "This device is permanently blocked. Contact IT Team.", Toast.LENGTH_LONG).show()
                    finish(); return@addOnSuccessListener
                }
                val ref = db.collection("users").document(identifier)
                ref.get()
                    .addOnSuccessListener { doc ->
                        if (!doc.exists()) {
                            ref.set(mapOf(
                                "identifier" to identifier, "email" to email,
                                "currentDeviceId" to deviceId, "deviceHistory" to listOf(deviceId),
                                "blocked" to false, "allowed" to false, "registeredAt" to Timestamp.now()
                            )).addOnSuccessListener {
                                MyFirebaseMessagingService.refreshAndSaveToken(identifier)
                                sendApprovalRequestsToManagers(identifier, email, deviceId)
                                // Foreground listener in case app stays open while waiting
                                startForegroundApprovalListener(identifier)
                            }.addOnFailureListener {
                                setLoginBusy(false)
                                Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val isBlocked       = doc.getBoolean("blocked") ?: false
                            val isAllowed       = doc.getBoolean("allowed") ?: false
                            val currentDeviceId = doc.getString("currentDeviceId")
                            val deviceHistory   = doc.get("deviceHistory") as? List<*> ?: emptyList<String>()
                            when {
                                isBlocked -> {
                                    setLoginBusy(false)
                                    Toast.makeText(this, "Your account is blocked. Contact IT Team.", Toast.LENGTH_LONG).show()
                                    finish()
                                }
                                !isAllowed -> {
                                    MyFirebaseMessagingService.refreshAndSaveToken(identifier)
                                    startForegroundApprovalListener(identifier)
                                    showApprovalStatus(identifier)
                                }
                                currentDeviceId == deviceId -> goNext(identifier)
                                deviceHistory.size == 1 -> {
                                    setLoginBusy(false)
                                    showDeviceSwitchDialog(ref, identifier, currentDeviceId!!, deviceId)
                                }
                                else -> {
                                    db.collection("blocked_devices").document(deviceId).set(
                                        mapOf("identifier" to identifier, "blockedAt" to Timestamp.now(), "reason" to "Device limit exceeded")
                                    )
                                    ref.update(mapOf("blocked" to true, "blockedAt" to Timestamp.now(), "blockedReason" to "Attempted login from 3rd device"))
                                    setLoginBusy(false)
                                    Toast.makeText(this, "Device limit exceeded. Account permanently blocked. Contact IT Team.", Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        setLoginBusy(false)
                        Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                setLoginBusy(false)
                Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Approval flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendApprovalRequestsToManagers(userPhone: String, userEmail: String, deviceId: String) {
        db.collection("managers").get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    db.collection("users").document(userPhone).update("allowed", true)
                    goNext(userPhone); return@addOnSuccessListener
                }
                snapshot.documents.forEach { managerDoc ->

                    val managerToken = managerDoc.getString("fcmToken")

                    // 1️⃣ Create request document
                    managerDoc.reference.collection("requests").document(userPhone).set(
                        mapOf(
                            "userPhone" to userPhone,
                            "userEmail" to userEmail,
                            "deviceId" to deviceId,
                            "approved" to false,
                            "timestamp" to Timestamp.now()
                        )
                    )

                    // 2️⃣ Send FCM notification to manager
                    if (!managerToken.isNullOrEmpty()) {
                        FcmHelper.sendNotification(
                            targetFcmToken = managerToken,
                            title = "🔔 New Access Request",
                            body = "$userPhone requested access to PPN",
                            data = mapOf(
                                "type" to "new_request",
                                "userPhone" to userPhone
                            )
                        )
                    }
                }
                setLoginBusy(false)
                Toast.makeText(this, "Access request sent to ${snapshot.size()} manager(s). You will get a notification when approved.", Toast.LENGTH_LONG).show()
                showApprovalStatus(userPhone)
            }
            .addOnFailureListener {
                setLoginBusy(false)
                Toast.makeText(this, "Error sending request: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showApprovalStatus(userPhone: String) {
        val resendBtn = findViewById<Button>(R.id.resend_request_btn)
        db.collection("managers").get()
            .addOnSuccessListener { snapshot ->
                val total = snapshot.size()
                if (total == 0) {
                    db.collection("users").document(userPhone).update("allowed", true)
                    goNext(userPhone); return@addOnSuccessListener
                }
                var checked = 0; var approved = 0; var pending = 0
                snapshot.documents.forEach { managerDoc ->
                    managerDoc.reference.collection("requests").document(userPhone).get()
                        .addOnSuccessListener { req ->
                            checked++
                            if (req.exists() && req.getBoolean("approved") == true) approved++ else pending++
                            if (checked == total) {
                                when {
                                    approved == total -> {
                                        db.collection("users").document(userPhone)
                                            .update("allowed", true)
                                            .addOnSuccessListener { goNext(userPhone) }
                                            .addOnFailureListener {
                                                setLoginBusy(false)
                                                Toast.makeText(this, "Database error: ${it.message}", Toast.LENGTH_LONG).show()
                                            }
                                    }
                                    approved > 0 -> {
                                        setLoginBusy(false)
                                        Toast.makeText(this, "Partially approved ($approved / $total). You'll get a notification when fully approved.", Toast.LENGTH_LONG).show()
                                        resendBtn.visibility = View.VISIBLE
                                    }
                                    else -> {
                                        setLoginBusy(false)
                                        Toast.makeText(this, "Request pending (0 / $total approved). You'll get a notification when approved.", Toast.LENGTH_LONG).show()
                                        resendBtn.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                }
            }
            .addOnFailureListener {
                setLoginBusy(false)
                Toast.makeText(this, "Error checking approval: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun resendPendingRequests(userPhone: String) {
        db.collection("managers").get().addOnSuccessListener { snapshot ->
            snapshot.documents.forEach { managerDoc ->
                val reqRef = managerDoc.reference.collection("requests").document(userPhone)
                reqRef.get().addOnSuccessListener { req ->
                    if (req.exists() && req.getBoolean("approved") == false)
                        reqRef.update("timestamp", Timestamp.now())
                }
            }
            Toast.makeText(this, "Pending requests resent.", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device switch dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun showDeviceSwitchDialog(
        ref: com.google.firebase.firestore.DocumentReference,
        identifier: String, oldDeviceId: String, newDeviceId: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("New Device Detected")
            .setMessage("You are logging in from a new device.\n\nIf you proceed, your previous device will be PERMANENTLY BLOCKED.\n\nOnly proceed if you intentionally switched devices.")
            .setPositiveButton("Proceed — Block Old Device") { _, _ ->
                db.collection("blocked_devices").document(oldDeviceId).set(
                    mapOf("identifier" to identifier, "blockedAt" to Timestamp.now(), "reason" to "Replaced by newer device")
                )
                ref.update(mapOf("currentDeviceId" to newDeviceId, "deviceHistory" to listOf(oldDeviceId, newDeviceId), "lastDeviceChangeAt" to Timestamp.now()))
                Toast.makeText(this, "New device registered. Previous device permanently blocked.", Toast.LENGTH_LONG).show()
                goNext(identifier)
            }
            .setNegativeButton("Cancel — Stay on Old Device") { _, _ ->
                setLoginBusy(false)
                Toast.makeText(this, "Login cancelled. Your previous device remains active.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session check on reopen
    // ─────────────────────────────────────────────────────────────────────────

    private fun verifySessionAndProceed(
        savedPhone: String, deviceId: String,
        prefs: android.content.SharedPreferences
    ) {
        fun clearAndShowLogin(reason: String = "") {
            if (reason.isNotEmpty())
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            prefs.edit().remove(KEY_PHONE).apply()
            showLoading(false)
            showLoginForm()
            setupLoginForm(prefs, deviceId)
        }

        db.collection("blocked_devices").document(deviceId).get()
            .addOnSuccessListener { blockedDoc ->
                if (blockedDoc.exists()) {
                    showLoading(false)
                    Toast.makeText(this, "This device is permanently blocked. Contact IT Team.", Toast.LENGTH_LONG).show()
                    prefs.edit().remove(KEY_PHONE).apply(); finish(); return@addOnSuccessListener
                }
                db.collection("users").document(savedPhone).get()
                    .addOnSuccessListener { doc ->
                        if (!doc.exists()) { clearAndShowLogin("Account not found. Please log in again."); return@addOnSuccessListener }
                        when {
                            doc.getBoolean("blocked") == true -> {
                                showLoading(false)
                                Toast.makeText(this, "Your account is blocked. Contact IT Team.", Toast.LENGTH_LONG).show()
                                prefs.edit().remove(KEY_PHONE).apply(); finish()
                            }
                            doc.getBoolean("allowed") != true            -> clearAndShowLogin("Your account is not yet approved. Please log in again.")
                            doc.getString("currentDeviceId") != deviceId -> clearAndShowLogin("Session expired. Please log in again.")
                            else -> {
                                MyFirebaseMessagingService.refreshAndSaveToken(savedPhone)
                                goNext(savedPhone)
                            }
                        }
                    }
                    .addOnFailureListener { clearAndShowLogin("Failed to verify account: ${it.message}") }
            }
            .addOnFailureListener { clearAndShowLogin("Failed to check device status: ${it.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel + permission
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Approval Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifies when access is approved by all managers"
                enableVibration(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun isValidNitkkrEmail(email: String): Boolean =
        Regex("^[A-Za-z0-9+_.-]+@nitkkr\\.ac\\.in$").matches(email)

    private fun goNext(phone: String) {
        stopForegroundApprovalListener()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_PHONE, phone)
            .remove(KEY_VERIFIED_EMAIL)
            .remove(KEY_VERIFIED_PHONE)
            .apply()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundApprovalListener()
    }
}