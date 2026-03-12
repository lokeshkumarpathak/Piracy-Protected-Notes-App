package com.ppn.piracyprotectednotesapp.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ui.login.LoginActivity

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID   = "ppn_approval_channel"
        const val CHANNEL_NAME = "Approval Notifications"

        fun refreshAndSaveToken(userPhone: String) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                saveTokenToFirestore(userPhone, token)
            }
        }

        fun saveTokenToFirestore(userPhone: String, token: String) {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(userPhone)
                .update("fcmToken", token)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize FcmHelper with context so it can read secrets.json from assets
        FcmHelper.init(this)
    }

    // Called for ALL messages (foreground + background + killed) when data-only
    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM_DEBUG", "Message received: ${message.data}")
        super.onMessageReceived(message)

        // Read from data payload only — we send data-only messages so this
        // always fires regardless of app state
        val title = message.data["title"] ?: "PPN App"
        val body  = message.data["body"]  ?: ""
        val type  = message.data["type"]  ?: ""

        showNotification(title, body, type)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs      = getSharedPreferences(LoginActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedPhone = prefs.getString(LoginActivity.KEY_PHONE, null)
        if (!savedPhone.isNullOrEmpty()) {
            saveTokenToFirestore(savedPhone, token)
        }
    }

    private fun showNotification(title: String, body: String, type: String) {
        createChannel()

        // Tap on notification → open LoginActivity (which will auto-proceed if session valid)
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notification_type", type)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifies user when their access is approved by managers"
                enableVibration(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}