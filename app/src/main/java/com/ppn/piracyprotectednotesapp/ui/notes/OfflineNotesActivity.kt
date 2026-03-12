package com.ppn.piracyprotectednotesapp.ui.notes

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.ui.viewer.PdfViewerActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class OfflineNotesActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_notes)

        findViewById<TextView>(R.id.tv_header_title).text = "Offline Notes"
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        supportActionBar?.title = "Offline Notes"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recycler_offline_notes)
        emptyText    = findViewById(R.id.empty_text)

        loadOfflineFiles()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scan filesDir/pdfs/ — no network call needed
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadOfflineFiles() {
        val pdfsDir = File(filesDir, "pdfs")
        val files   = pdfsDir.listFiles()
            ?.filter { it.extension == "pdf" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            emptyText.visibility    = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        emptyText.visibility    = View.GONE
        recyclerView.visibility = View.VISIBLE

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = OfflineNotesAdapter(files) { file ->
            startActivity(
                Intent(this, PdfViewerActivity::class.java).apply {
                    putExtra(PdfViewerActivity.EXTRA_PDF_PATH,  file.absolutePath)
                    // Use filename without extension as display title
                    putExtra(PdfViewerActivity.EXTRA_PDF_TITLE,
                        file.nameWithoutExtension.replace("_", " "))
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    // Resume re-scans in case user downloaded something in AllNotes and came back
    override fun onResume() { super.onResume(); loadOfflineFiles() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter — binds item_pdf_user.xml, always in "Open" (cached) state
// ─────────────────────────────────────────────────────────────────────────────

class OfflineNotesAdapter(
    private val files: List<File>,
    private val onOpen: (File) -> Unit
) : RecyclerView.Adapter<OfflineNotesAdapter.OfflineViewHolder>() {

    inner class OfflineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle:    TextView = view.findViewById(R.id.tv_pdf_title)
        val tvDateSize: TextView = view.findViewById(R.id.tv_date_size)
        val tvBadge:    TextView = view.findViewById(R.id.tv_downloaded_badge)
        val btnAction:  android.widget.Button = view.findViewById(R.id.btn_action)
        val progress:   android.widget.ProgressBar = view.findViewById(R.id.progress_download)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OfflineViewHolder =
        OfflineViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_user, parent, false)
        )

    override fun onBindViewHolder(holder: OfflineViewHolder, position: Int) {
        val file = files[position]
        val fmt  = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        holder.tvTitle.text    = file.nameWithoutExtension.replace("_", " ")
        holder.tvDateSize.text = "${fmt.format(Date(file.lastModified()))}  •  ${formatSize(file.length())}"

        // All files here are cached — always show Downloaded badge + Open button
        holder.tvBadge.visibility = View.VISIBLE
        holder.btnAction.text     = "Open"
        holder.btnAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
        holder.btnAction.setOnClickListener { onOpen(file) }
        holder.progress.visibility = View.GONE
    }

    override fun getItemCount() = files.size

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.0f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }
}