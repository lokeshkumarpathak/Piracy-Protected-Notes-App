package com.ppn.piracyprotectednotesapp.ui.notes

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.ui.viewer.PdfViewerActivity
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class AllNotesActivity : BaseActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView

    private val pdfs = mutableListOf<NoteItem>()
    private lateinit var adapter: AllNotesAdapter
    private var listenerReg: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_all_notes)

        findViewById<TextView>(R.id.tv_header_title).text = "All Notes"
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        supportActionBar?.title = "All Notes"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db           = FirebaseFirestore.getInstance()
        recyclerView = findViewById(R.id.recycler_all_notes)
        progressBar  = findViewById(R.id.progress_bar)
        emptyText    = findViewById(R.id.empty_text)

        adapter = AllNotesAdapter(
            pdfs,
            onDownload = { item, holder -> downloadPdf(item, holder) },
            onOpen     = { item -> openPdf(item) },
            filesDir   = filesDir
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        listenForNotes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firestore real-time listener
    // ─────────────────────────────────────────────────────────────────────────

    private fun listenForNotes() {
        progressBar.visibility = View.VISIBLE

        listenerReg = db.collection("uploaded_pdfs")
            .orderBy("uploadedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                progressBar.visibility = View.GONE

                if (error != null) {
                    Toast.makeText(this, "Error loading notes: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                pdfs.clear()
                snapshot?.documents?.forEach { doc ->
                    pdfs.add(
                        NoteItem(
                            docId         = doc.id,
                            title         = doc.getString("title")         ?: "Untitled",
                            fileName      = doc.getString("fileName")      ?: "",
                            cloudinaryUrl = doc.getString("cloudinaryUrl") ?: "",
                            uploadedAt    = doc.getTimestamp("uploadedAt")?.toDate() ?: Date(),
                            fileSizeBytes = doc.getLong("fileSizeBytes")   ?: 0L
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                emptyText.visibility    = if (pdfs.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (pdfs.isEmpty()) View.GONE   else View.VISIBLE
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download PDF into filesDir/pdfs/
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadPdf(item: NoteItem, holder: AllNotesAdapter.NoteViewHolder) {
        val pdfsDir   = File(filesDir, "pdfs").also { it.mkdirs() }
        val localFile = File(pdfsDir, item.fileName)

        // Show per-card progress
        holder.progressBar.visibility = View.VISIBLE
        holder.progressBar.isIndeterminate = true
        holder.btnAction.isEnabled = false

        val client  = OkHttpClient()
        val request = Request.Builder().url(item.cloudinaryUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    holder.progressBar.visibility = View.GONE
                    holder.btnAction.isEnabled    = true
                    Toast.makeText(this@AllNotesActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body        = response.body ?: run { onFailure(call, IOException("Empty response")); return }
                val totalBytes  = body.contentLength()
                var bytesRead   = 0L

                try {
                    body.byteStream().use { input ->
                        FileOutputStream(localFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                if (totalBytes > 0) {
                                    val pct = (bytesRead * 100 / totalBytes).toInt()
                                    runOnUiThread {
                                        holder.progressBar.isIndeterminate = false
                                        holder.progressBar.progress        = pct
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        holder.progressBar.visibility = View.GONE
                        holder.btnAction.isEnabled    = true
                        // Flip card to Open state
                        adapter.notifyItemChanged(pdfs.indexOf(item))
                        Toast.makeText(this@AllNotesActivity, "\"${item.title}\" downloaded.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    localFile.delete()
                    runOnUiThread {
                        holder.progressBar.visibility = View.GONE
                        holder.btnAction.isEnabled    = true
                        Toast.makeText(this@AllNotesActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Open PDF in custom in-app viewer
    // ─────────────────────────────────────────────────────────────────────────

    private fun openPdf(item: NoteItem) {
        val localFile = File(File(filesDir, "pdfs"), item.fileName)
        if (!localFile.exists()) {
            Toast.makeText(this, "File not found. Please download again.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_PATH,  localFile.absolutePath)
                putExtra(PdfViewerActivity.EXTRA_PDF_TITLE, item.title)
            }
        )
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onDestroy() { super.onDestroy(); listenerReg?.remove() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

data class NoteItem(
    val docId:         String,
    val title:         String,
    val fileName:      String,
    val cloudinaryUrl: String,
    val uploadedAt:    Date,
    val fileSizeBytes: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

class AllNotesAdapter(
    private val items: List<NoteItem>,
    private val onDownload: (NoteItem, AllNotesAdapter.NoteViewHolder) -> Unit,
    private val onOpen:     (NoteItem) -> Unit,
    private val filesDir:   File
) : RecyclerView.Adapter<AllNotesAdapter.NoteViewHolder>() {

    inner class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle:       TextView    = view.findViewById(R.id.tv_pdf_title)
        val tvDateSize:    TextView    = view.findViewById(R.id.tv_date_size)
        val tvBadge:       TextView    = view.findViewById(R.id.tv_downloaded_badge)
        val btnAction:     Button      = view.findViewById(R.id.btn_action)
        val progressBar:   ProgressBar = view.findViewById(R.id.progress_download)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder =
        NoteViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_user, parent, false)
        )

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val item      = items[position]
        val fmt       = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val localFile = File(File(filesDir, "pdfs"), item.fileName)
        val cached    = localFile.exists()

        holder.tvTitle.text    = item.title
        holder.tvDateSize.text = "${fmt.format(item.uploadedAt)}  •  ${formatSize(item.fileSizeBytes)}"

        if (cached) {
            holder.tvBadge.visibility  = View.VISIBLE
            holder.btnAction.text      = "Open"
            holder.btnAction.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
            holder.btnAction.setOnClickListener { onOpen(item) }
        } else {
            holder.tvBadge.visibility  = View.GONE
            holder.btnAction.text      = "Download"
            holder.btnAction.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))
            holder.btnAction.setOnClickListener { onDownload(item, holder) }
        }

        holder.progressBar.visibility = View.GONE
        holder.progressBar.progress   = 0
    }

    override fun getItemCount() = items.size

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.0f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }
}