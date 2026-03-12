package com.ppn.piracyprotectednotesapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val role: String,           // "user" or "model"
    val text: String,
    val screenshotPath: String? = null,   // absolute path, null if text-only
    val timestamp: Long = System.currentTimeMillis()
)