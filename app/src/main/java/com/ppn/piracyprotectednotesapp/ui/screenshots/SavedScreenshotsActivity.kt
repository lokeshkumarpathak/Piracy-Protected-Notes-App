package com.ppn.piracyprotectednotesapp.ui.screenshots

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.utils.ScreenshotManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SavedScreenshotsActivity : BaseActivity() {

    private lateinit var viewPager:       ViewPager2
    private lateinit var tvCounter:       TextView
    private lateinit var tvInfo:          TextView
    private lateinit var btnDelete:       Button
    private lateinit var emptyText:       TextView

    private val screenshots = mutableListOf<File>()
    private lateinit var adapter: ScreenshotPagerAdapter

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_screenshots)

        findViewById<TextView>(R.id.tv_header_title).text = "Saved Screenshots"
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        supportActionBar?.title = "Saved Screenshots"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager  = findViewById(R.id.viewpager_screenshots)
        tvCounter  = findViewById(R.id.tv_counter)
        tvInfo     = findViewById(R.id.tv_screenshot_info)
        btnDelete  = findViewById(R.id.btn_delete_current)
        emptyText  = findViewById(R.id.empty_text)

        adapter = ScreenshotPagerAdapter(screenshots)
        viewPager.adapter = adapter

        // Update counter + info overlay as user swipes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateOverlay(position)
            }
        })

        btnDelete.setOnClickListener {
            val pos = viewPager.currentItem
            if (pos in screenshots.indices) confirmDelete(pos)
        }

        loadScreenshots()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load screenshots from internal storage
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadScreenshots() {
        screenshots.clear()
        screenshots.addAll(ScreenshotManager.getAllScreenshots(this))
        adapter.notifyDataSetChanged()

        val isEmpty = screenshots.isEmpty()
        emptyText.visibility  = if (isEmpty) View.VISIBLE else View.GONE
        viewPager.visibility  = if (isEmpty) View.GONE    else View.VISIBLE
        tvCounter.visibility  = if (isEmpty) View.GONE    else View.VISIBLE
        tvInfo.visibility     = if (isEmpty) View.GONE    else View.VISIBLE
        btnDelete.visibility  = if (isEmpty) View.GONE    else View.VISIBLE

        if (!isEmpty) updateOverlay(viewPager.currentItem.coerceIn(0, screenshots.lastIndex))
    }

    private fun updateOverlay(position: Int) {
        if (position !in screenshots.indices) return
        val file = screenshots[position]
        val fmt  = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        tvCounter.text = "${position + 1} / ${screenshots.size}"
        tvInfo.text    = "${file.nameWithoutExtension}\n${fmt.format(Date(file.lastModified()))}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete single screenshot
    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmDelete(position: Int) {
        val file = screenshots[position]
        AlertDialog.Builder(this)
            .setTitle("Delete Screenshot")
            .setMessage("Delete \"${file.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (ScreenshotManager.deleteScreenshot(file)) {
                    loadScreenshots()
                    Toast.makeText(this, "Screenshot deleted.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not delete file.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete All in action bar overflow
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, Menu.NONE, "Delete All")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                if (screenshots.isEmpty()) {
                    Toast.makeText(this, "No screenshots to delete.", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Delete All Screenshots")
                        .setMessage("All ${screenshots.size} screenshot(s) will be permanently deleted.")
                        .setPositiveButton("Delete All") { _, _ ->
                            ScreenshotManager.deleteAllScreenshots(this)
                            loadScreenshots()
                            Toast.makeText(this, "All screenshots deleted.", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
    override fun onResume() { super.onResume(); loadScreenshots() }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewPager2 Adapter — each page is a full-screen zoomable PhotoView
// ─────────────────────────────────────────────────────────────────────────────

class ScreenshotPagerAdapter(
    private val items: List<File>
) : RecyclerView.Adapter<ScreenshotPagerAdapter.ScreenshotViewHolder>() {

    inner class ScreenshotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.iv_screenshot_full)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScreenshotViewHolder =
        ScreenshotViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenshot, parent, false)
        )

    override fun onBindViewHolder(holder: ScreenshotViewHolder, position: Int) {
        val file = items[position]

        // Decode at reduced sample size first for fast display, then full res
        Thread {
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            val bitmap  = BitmapFactory.decodeFile(file.absolutePath, options)
            (holder.itemView.context as? android.app.Activity)?.runOnUiThread {
                holder.photoView.setImageBitmap(bitmap)
            }
        }.start()
    }

    override fun getItemCount() = items.size
}