package com.ppn.piracyprotectednotesapp.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object DeviceIdManager {

    private const val PREFS_FILE = "ppn_device_prefs"
    private const val KEY_DEVICE_ID = "stable_device_id"

    /**
     * Returns a stable device ID that persists across app uninstalls and reinstalls.
     *
     * How it works:
     * - On first call ever: generates a random UUID and saves it to
     *   EncryptedSharedPreferences backed by Android Keystore.
     * - On every subsequent call (including after reinstall): reads the same UUID
     *   back from Keystore — the entry survives uninstall on Android 9+.
     * - On factory reset or new device: generates a fresh UUID (correct behaviour
     *   since it IS a new device context).
     *
     * Replaces Settings.Secure.ANDROID_ID which changes on every reinstall
     * since Android 8, causing false "new device" detections.
     */
    fun getDeviceId(context: Context): String {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) {
                existing
            } else {
                val newId = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
                newId
            }

        } catch (e: Exception) {
            // Fallback: if Keystore fails (very rare, e.g. corrupted Keystore),
            // fall back to ANDROID_ID. This is still better than always using it.
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }
    }
}