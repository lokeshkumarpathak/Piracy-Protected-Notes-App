package com.ppn.piracyprotectednotesapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.ui.ai.AIHelpActivity
import com.ppn.piracyprotectednotesapp.ui.login.LoginActivity
import com.ppn.piracyprotectednotesapp.ui.notes.AllNotesActivity
import com.ppn.piracyprotectednotesapp.ui.notes.OfflineNotesActivity
import com.ppn.piracyprotectednotesapp.ui.screenshots.SavedScreenshotsActivity

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageButton>(R.id.allNotes).setOnClickListener {
            startActivity(Intent(this, AllNotesActivity::class.java))
        }

        findViewById<ImageButton>(R.id.offlineNotes).setOnClickListener {
            startActivity(Intent(this, OfflineNotesActivity::class.java))
        }

        findViewById<ImageButton>(R.id.savedScreenshots).setOnClickListener {
            startActivity(Intent(this, SavedScreenshotsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.aiHelp).setOnClickListener {
            startActivity(Intent(this, AIHelpActivity::class.java))
        }

        findViewById<Button>(R.id.logout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Logout") { _, _ ->
                    getSharedPreferences(LoginActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().remove(LoginActivity.KEY_PHONE).apply()
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}