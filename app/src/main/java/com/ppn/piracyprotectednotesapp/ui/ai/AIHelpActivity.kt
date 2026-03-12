package com.ppn.piracyprotectednotesapp.ui.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.data.ChatDatabase
import com.ppn.piracyprotectednotesapp.data.ChatMessage
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.utils.OpenRouterApiClient
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AIHelpActivity : BaseActivity() {

    companion object {
        const val EXTRA_TEMP_SCREENSHOT_PATH = "temp_screenshot_path"
    }

    private lateinit var recyclerView:           RecyclerView
    private lateinit var etMessage:              EditText
    private lateinit var btnSend:                ImageButton
    private lateinit var btnAttach:              ImageButton
    private lateinit var btnClearChat:           ImageButton
    private lateinit var attachmentContainer:    View
    private lateinit var ivAttachThumb:          ImageView
    private lateinit var tvAttachName:           TextView
    private lateinit var btnRemoveAttachment:    ImageButton
    private lateinit var tvThinking:             TextView

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter

    private var pendingScreenshot: File? = null
    private var isTempFile = false

    private val db by lazy { ChatDatabase.getInstance(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Screenshot picker launcher ─────────────────────────────────────────
    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val path = result.data?.getStringExtra(ScreenshotPickerActivity.RESULT_SCREENSHOT_PATH)
                if (!path.isNullOrEmpty()) attachScreenshot(File(path), isTemp = false)
            }
        }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aihelp)

        findViewById<TextView>(R.id.tv_header_title).text = "AI Help"
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        recyclerView        = findViewById(R.id.recycler_chat)
        etMessage           = findViewById(R.id.et_message)
        btnSend             = findViewById(R.id.btn_send)
        btnAttach           = findViewById(R.id.btn_attach)
        btnClearChat        = findViewById(R.id.btn_clear_chat)
        attachmentContainer = findViewById(R.id.attachment_preview_container)
        ivAttachThumb       = findViewById(R.id.iv_attachment_thumb)
        tvAttachName        = findViewById(R.id.tv_attachment_name)
        btnRemoveAttachment = findViewById(R.id.btn_remove_attachment)
        tvThinking          = findViewById(R.id.tv_thinking)

        adapter = ChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        // ── Check if PdfViewer passed a temp screenshot ────────────────────
        val tempPath = intent.getStringExtra(EXTRA_TEMP_SCREENSHOT_PATH)
        if (!tempPath.isNullOrEmpty()) {
            val tempFile = File(tempPath)
            if (tempFile.exists()) attachScreenshot(tempFile, isTemp = true)
        }

        // ── Load Room history ──────────────────────────────────────────────
        scope.launch {
            val history = withContext(Dispatchers.IO) { db.chatDao().getAll() }
            messages.addAll(history)
            adapter.notifyDataSetChanged()
            scrollToBottom()
        }

        // ── Button listeners ───────────────────────────────────────────────
        btnSend.setOnClickListener { sendMessage() }

        btnAttach.setOnClickListener {
            pickerLauncher.launch(Intent(this, ScreenshotPickerActivity::class.java))
        }

        btnRemoveAttachment.setOnClickListener { clearAttachment() }

        btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Chat History")
                .setMessage("All messages will be permanently deleted from this device.")
                .setPositiveButton("Clear") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) { db.chatDao().deleteAll() }
                        messages.clear()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this@AIHelpActivity, "Chat history cleared.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attach screenshot
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachScreenshot(file: File, isTemp: Boolean) {
        pendingScreenshot              = file
        isTempFile                     = isTemp
        val bitmap                     = BitmapFactory.decodeFile(file.absolutePath)
        ivAttachThumb.setImageBitmap(bitmap)
        tvAttachName.text              = file.name
        attachmentContainer.visibility = View.VISIBLE
    }

    private fun clearAttachment() {
        // Only delete the file here if user manually removes it — NOT during send
        if (isTempFile) pendingScreenshot?.delete()
        pendingScreenshot              = null
        isTempFile                     = false
        attachmentContainer.visibility = View.GONE
        ivAttachThumb.setImageBitmap(null)
    }

    // Same as clearAttachment but never deletes the file —
    // called after send so the file survives long enough to be encoded
    private fun clearAttachmentUi() {
        pendingScreenshot              = null
        isTempFile                     = false
        attachmentContainer.visibility = View.GONE
        ivAttachThumb.setImageBitmap(null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send message
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text       = etMessage.text.toString().trim()
        val screenshot = pendingScreenshot   // snapshot before any clearing
        val wasTemp    = isTempFile          // snapshot before any clearing

        if (text.isEmpty() && screenshot == null) {
            Toast.makeText(this, "Type a message or attach a screenshot.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isOnline()) {
            Toast.makeText(this, "No internet connection. Cannot send.", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Snapshot history BEFORE adding new user message
        val historySnapshot = messages.toList()

        val userMsg = ChatMessage(
            role           = "user",
            text           = text,
            screenshotPath = screenshot?.absolutePath
        )
        scope.launch {
            withContext(Dispatchers.IO) { db.chatDao().insert(userMsg) }
            messages.add(userMsg)
            adapter.notifyItemInserted(messages.lastIndex)
            scrollToBottom()
        }

        // Clear input field
        etMessage.setText("")

        // ✅ Clear the UI preview WITHOUT deleting the file —
        // the file must still exist when GeminiApiClient reads and encodes it
        clearAttachmentUi()

        tvThinking.visibility = View.VISIBLE
        btnSend.isEnabled     = false

        OpenRouterApiClient.sendMessage(
            history        = historySnapshot,
            userText       = text,
            tempScreenshot = screenshot,        // file still exists on disk here
            onSuccess      = { replyText ->
                // ✅ Now safe to delete the temp file — API has already read it
                if (wasTemp) screenshot?.delete()

                val aiMsg = ChatMessage(role = "model", text = replyText)
                scope.launch {
                    withContext(Dispatchers.IO) { db.chatDao().insert(aiMsg) }
                    messages.add(aiMsg)
                    adapter.notifyItemInserted(messages.lastIndex)
                    tvThinking.visibility = View.GONE
                    btnSend.isEnabled     = true
                    scrollToBottom()
                }
            },
            onFailure = { errorMsg ->
                scope.launch {
                    tvThinking.visibility = View.GONE
                    btnSend.isEnabled     = true
                    AlertDialog.Builder(this@AIHelpActivity)
                        .setTitle("Could not get response")
                        .setMessage(errorMsg)
                        .setPositiveButton("Resend") { _, _ ->
                            // Re-stage the screenshot so user can retry with image
                            if (screenshot != null && screenshot.exists()) {
                                attachScreenshot(screenshot, isTemp = wasTemp)
                            }
                            etMessage.setText(text)
                        }
                        .setNegativeButton("Dismiss") { _, _ ->
                            // Only now delete the temp file on explicit dismiss
                            if (wasTemp) screenshot?.delete()
                        }
                        .show()
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) recyclerView.scrollToPosition(messages.lastIndex)
    }

    private fun isOnline(): Boolean {
        val cm  = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat RecyclerView Adapter
// ─────────────────────────────────────────────────────────────────────────────

class ChatAdapter(
    private val items: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_USER = 0
    private val VIEW_AI   = 1

    inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText:       TextView  = view.findViewById(R.id.tv_message_text)
        val tvTimestamp:  TextView  = view.findViewById(R.id.tv_timestamp)
        val ivScreenshot: ImageView = view.findViewById(R.id.iv_message_screenshot)
    }

    inner class AiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText:      TextView = view.findViewById(R.id.tv_message_text)
        val tvTimestamp: TextView = view.findViewById(R.id.tv_timestamp)
    }

    override fun getItemViewType(position: Int) =
        if (items[position].role == "user") VIEW_USER else VIEW_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_USER)
            UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
        else
            AiViewHolder(inflater.inflate(R.layout.item_chat_ai, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg  = items[position]
        val fmt  = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val time = fmt.format(Date(msg.timestamp))

        when (holder) {
            is UserViewHolder -> {
                holder.tvText.text      = msg.text.ifEmpty { "" }
                holder.tvTimestamp.text = time

                val screenshotPath = msg.screenshotPath
                if (!screenshotPath.isNullOrEmpty()) {
                    val file = File(screenshotPath)
                    if (file.exists()) {
                        holder.ivScreenshot.visibility = View.VISIBLE
                        Thread {
                            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                            (holder.itemView.context as? Activity)?.runOnUiThread {
                                holder.ivScreenshot.setImageBitmap(bmp)
                            }
                        }.start()
                    } else {
                        holder.ivScreenshot.visibility = View.GONE
                        if (msg.text.isEmpty()) holder.tvText.text = "(screenshot sent)"
                    }
                } else {
                    holder.ivScreenshot.visibility = View.GONE
                }
            }
            is AiViewHolder -> {
                holder.tvText.text      = msg.text
                holder.tvTimestamp.text = time
            }
        }
    }

    override fun getItemCount() = items.size
}