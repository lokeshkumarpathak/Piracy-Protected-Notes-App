package com.ppn.piracyprotectednotesapp.ui.ai

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ppn.piracyprotectednotesapp.R
import com.ppn.piracyprotectednotesapp.ss_screen_rec.BaseActivity
import com.ppn.piracyprotectednotesapp.utils.ScreenshotManager
import java.io.File

class ScreenshotPickerActivity : BaseActivity() {

    companion object {
        const val RESULT_SCREENSHOT_PATH = "selected_screenshot_path"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText:    TextView
    private lateinit var btnAttach:    Button

    private val screenshots  = mutableListOf<File>()
    private var selectedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_picker)

        supportActionBar?.title = "Choose Screenshot"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recycler_pick)
        emptyText    = findViewById(R.id.empty_text)
        btnAttach    = findViewById(R.id.btn_attach_selected)

        screenshots.addAll(ScreenshotManager.getAllScreenshots(this))

        if (screenshots.isEmpty()) {
            emptyText.visibility    = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility    = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.layoutManager = GridLayoutManager(this, 2)
            recyclerView.adapter = PickerAdapter(screenshots) { file ->
                selectedFile = file
                btnAttach.isEnabled = true
            }
        }

        btnAttach.setOnClickListener {
            selectedFile?.let { file ->
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(RESULT_SCREENSHOT_PATH, file.absolutePath)
                )
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(Activity.RESULT_CANCELED)
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

class PickerAdapter(
    private val items: List<File>,
    private val onSelect: (File) -> Unit
) : RecyclerView.Adapter<PickerAdapter.PickViewHolder>() {

    private var selectedIndex = -1

    inner class PickViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb:   ImageView  = view.findViewById(R.id.iv_pick_thumb)
        val tvName:    TextView   = view.findViewById(R.id.tv_pick_name)
        val overlay:   FrameLayout = view.findViewById(R.id.selection_overlay)
        val tvCheck:   TextView   = view.findViewById(R.id.tv_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickViewHolder =
        PickViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenshot_pick, parent, false)
        )

    override fun onBindViewHolder(holder: PickViewHolder, position: Int) {
        val file     = items[position]
        val selected = position == selectedIndex

        // Load bitmap thumbnail in background
        Thread {
            val opts   = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            (holder.itemView.context as? Activity)?.runOnUiThread {
                holder.ivThumb.setImageBitmap(bitmap)
            }
        }.start()

        holder.tvName.text        = file.nameWithoutExtension
        holder.overlay.visibility = if (selected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility = if (selected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current == RecyclerView.NO_POSITION) return@setOnClickListener
            val prev = selectedIndex
            selectedIndex = current
            if (prev >= 0) notifyItemChanged(prev)
            notifyItemChanged(current)
            onSelect(items[current])
        }
    }

    override fun getItemCount() = items.size
}