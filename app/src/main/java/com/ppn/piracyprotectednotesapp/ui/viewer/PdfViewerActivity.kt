package com.ppn.piracyprotectednotesapp.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.ui.ai.AIHelpActivity
import com.ppn.piracyprotectednotesapp.utils.ScreenshotManager
import java.io.File
import kotlin.jvm.java

class PdfViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_PDF_PATH  = "pdf_path"
        const val EXTRA_PDF_TITLE = "pdf_title"
    }

    private lateinit var recyclerView:    RecyclerView
    private lateinit var progressBar:     ProgressBar
    private lateinit var tvPageIndicator: TextView
    private lateinit var fabScreenshot:   FloatingActionButton
    private lateinit var fabAiHelp:       FloatingActionButton

    private var pdfRenderer:          PdfRenderer?          = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private lateinit var pdfFile:  File
    private lateinit var pdfTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        val pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        pdfTitle    = intent.getStringExtra(EXTRA_PDF_TITLE) ?: "Document"

        if (pdfPath.isNullOrEmpty()) {
            Toast.makeText(this, "Could not open PDF.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        pdfFile = File(pdfPath)
        if (!pdfFile.exists()) {
            Toast.makeText(this, "PDF file not found.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        supportActionBar?.title = pdfTitle
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView    = findViewById(R.id.recycler_pages)
        progressBar     = findViewById(R.id.progress_bar)
        tvPageIndicator = findViewById(R.id.tv_page_indicator)
        fabScreenshot   = findViewById(R.id.fab_screenshot)
        fabAiHelp       = findViewById(R.id.fab_ai_help)

        openRenderer()
        setupRecyclerView()
        setupScreenshotButton()
        setupAiHelpButton()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PdfRenderer
    // ─────────────────────────────────────────────────────────────────────────

    private fun openRenderer() {
        parcelFileDescriptor = ParcelFileDescriptor.open(
            pdfFile, ParcelFileDescriptor.MODE_READ_ONLY
        )
        pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
    }

    private fun closeRenderer() {
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
        pdfRenderer = null
        parcelFileDescriptor = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView — one PhotoView per PDF page
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        val pageCount     = pdfRenderer?.pageCount ?: 0
        tvPageIndicator.text = "Page 1 / $pageCount"

        val layoutManager = LinearLayoutManager(this)
        val adapter = PdfPagesAdapter(pdfRenderer!!, pageCount) {
            progressBar.visibility = View.GONE
        }

        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = layoutManager.findFirstVisibleItemPosition()
                if (first >= 0) tvPageIndicator.text = "Page ${first + 1} / $pageCount"
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screenshot FAB — captures visible area including zoom/pan
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupScreenshotButton() {
        fabScreenshot.setOnClickListener {
            captureCurrentPage { savedFile ->
                if (savedFile != null) {
                    Toast.makeText(this, "Screenshot saved (Page ${getCurrentPageNumber()})", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Screenshot failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AI Help FAB — dialog to choose text-only or screenshot+text
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupAiHelpButton() {
        fabAiHelp.setOnClickListener {
            val options = arrayOf("Ask with Screenshot", "Ask with Text Only")
            android.app.AlertDialog.Builder(this)
                .setTitle("AI Help")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> captureAndLaunchAi()   // silently capture, pass to AI Help
                        1 -> launchAiHelp(null)     // no screenshot
                    }
                }
                .show()
        }
    }

    /**
     * Silently captures the current visible page as a TEMPORARY file
     * (saved to filesDir/temp/ NOT filesDir/Screenshots/).
     * Passed to AiHelpActivity which deletes it after the message is sent.
     */
    private fun captureAndLaunchAi() {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val visiblePos    = layoutManager.findFirstVisibleItemPosition()

        if (visiblePos < 0) {
            Toast.makeText(this, "No page visible.", Toast.LENGTH_SHORT).show()
            return
        }

        val viewHolder = recyclerView.findViewHolderForAdapterPosition(visiblePos)
        val photoView  = viewHolder?.itemView?.findViewById<PhotoView>(R.id.iv_page)

        if (photoView == null || photoView.drawable == null) {
            Toast.makeText(this, "Page not ready yet. Try again.", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            // Save to temp dir, NOT Screenshots dir
            val tempFile = ScreenshotManager.captureVisibleArea(
                activity   = this,
                photoView  = photoView,
                pageLabel  = "temp_page_${visiblePos + 1}",
                folderName = "temp"           // separate temp folder
            )
            runOnUiThread {
                if (tempFile != null) {
                    launchAiHelp(tempFile.absolutePath)
                } else {
                    Toast.makeText(this, "Could not capture screenshot. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun launchAiHelp(tempScreenshotPath: String?) {
        startActivity(
            Intent(this, AIHelpActivity::class.java).apply {
                if (tempScreenshotPath != null) {
                    putExtra(AIHelpActivity.EXTRA_TEMP_SCREENSHOT_PATH, tempScreenshotPath)
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared screenshot helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun captureCurrentPage(onDone: (File?) -> Unit) {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val visiblePos    = layoutManager.findFirstVisibleItemPosition()

        if (visiblePos < 0) { onDone(null); return }

        val viewHolder = recyclerView.findViewHolderForAdapterPosition(visiblePos)
        val photoView  = viewHolder?.itemView?.findViewById<PhotoView>(R.id.iv_page)

        if (photoView == null || photoView.drawable == null) {
            Toast.makeText(this, "Page not ready yet.", Toast.LENGTH_SHORT).show()
            onDone(null); return
        }

        Thread {
            val saved = ScreenshotManager.captureVisibleArea(
                activity   = this,
                photoView  = photoView,
                pageLabel  = "page_${visiblePos + 1}"
                // default folderName = "Screenshots"
            )
            runOnUiThread { onDone(saved) }
        }.start()
    }

    private fun getCurrentPageNumber(): Int {
        val lm = recyclerView.layoutManager as LinearLayoutManager
        return (lm.findFirstVisibleItemPosition() + 1).coerceAtLeast(1)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    override fun onDestroy() {
        super.onDestroy()
        closeRenderer()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

class PdfPagesAdapter(
    private val renderer: PdfRenderer,
    private val pageCount: Int,
    private val onFirstPageReady: () -> Unit
) : RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder>() {

    private var firstPageRendered = false

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.iv_page)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder =
        PageViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pdf_page, parent, false)
        )

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.photoView.setImageBitmap(null)
        Thread {
            val bitmap = renderPage(position)
            (holder.itemView.context as? android.app.Activity)?.runOnUiThread {
                holder.photoView.setImageBitmap(bitmap)
                if (!firstPageRendered && position == 0) {
                    firstPageRendered = true
                    onFirstPageReady()
                }
            }
        }.start()
    }

    override fun getItemCount() = pageCount

    @Synchronized
    private fun renderPage(pageIndex: Int): Bitmap? {
        return try {
            val page   = renderer.openPage(pageIndex)
            val width  = page.width  * 2
            val height = page.height * 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}