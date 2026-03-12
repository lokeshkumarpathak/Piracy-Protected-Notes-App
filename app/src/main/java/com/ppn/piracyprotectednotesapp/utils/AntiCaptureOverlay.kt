package com.ppn.piracyprotectednotesapp.ss_screen_rec

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

class AntiCaptureOverlay(context: Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var frameCount = 0L

    // ---- Configuration ----

    // Block size — large enough for brain to recognise features
    // Small enough that half the blocks missing = unreadable to camera
    private val BLOCK_SIZE = 20

    // Frame interval — 16ms = 60fps matches screen refresh
    // Human brain integrates 60-100ms = 4-6 frames = sees complete image
    // Camera captures individual frames = sees only half image per frame
    private val FRAME_INTERVAL_MS = 16L

    // Session unique shuffle — every user session has different block pattern
    // Camera recording from one session cannot be used to reconstruct another
    private val sessionSeed = Random.nextLong()
    private val rng = Random(sessionSeed)

    // Stores which blocks are shown in odd frames vs even frames
    // Computed once when size is known
    private val oddFrameBlocks = mutableListOf<RectF>()   // shown on frame 1,3,5...
    private val evenFrameBlocks = mutableListOf<RectF>()  // shown on frame 2,4,6...

    // Paint for the masking layer
    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // Background fill for hidden blocks
    // Must match the app's background color so hidden blocks look natural
    // White assumed — change if your PDF viewer background differs
    private val hidePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var gridInitialized = false

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                frameCount++
                invalidate()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            buildBlockGrid(w, h)
        }
    }

    private fun buildBlockGrid(w: Int, h: Int) {
        oddFrameBlocks.clear()
        evenFrameBlocks.clear()

        val cols = (w / BLOCK_SIZE) + 1
        val rows = (h / BLOCK_SIZE) + 1

        // Build all block positions
        val allBlocks = mutableListOf<RectF>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = (col * BLOCK_SIZE).toFloat()
                val top = (row * BLOCK_SIZE).toFloat()
                val right = left + BLOCK_SIZE
                val bottom = top + BLOCK_SIZE
                allBlocks.add(RectF(left, top, right, bottom))
            }
        }

        // Session-unique shuffle of block order
        // This means the odd/even distribution is unique per session
        // Two users watching same content have different block distributions
        // A recording from one session is useless for reconstructing another
        allBlocks.shuffle(rng)

        // Split shuffled blocks into two alternating sets
        // Odd frames show first half — even frames show second half
        // Together they cover 100% of screen
        // Individually they cover 50% — unreadable snapshot
        allBlocks.forEachIndexed { index, block ->
            if (index % 2 == 0) {
                oddFrameBlocks.add(block)
            } else {
                evenFrameBlocks.add(block)
            }
        }

        gridInitialized = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!gridInitialized) return

        // ================================================
        // TEMPORAL BLOCK SHUFFLING
        // ================================================
        //
        // WHAT HUMAN BRAIN PERCEIVES:
        // Frame N:   50% of blocks visible (odd set)
        // Frame N+1: other 50% visible (even set)
        // Brain integrates across ~100ms = sees 100% of content
        // Reading experience: completely normal
        //
        // WHAT CAMERA CAPTURES:
        // Frame N:   50% of content + 50% white = half image
        // Frame N+1: other 50% + 50% white = other half image
        // No single frame contains full readable content
        // Merging frames requires knowing exact block positions
        // Block positions are session-unique — unknown to attacker
        //
        // ================================================

        val isOddFrame = frameCount % 2 == 0L

        // Blocks to HIDE in this frame
        // We hide the opposite set — revealing the current set through
        val blocksToHide = if (isOddFrame) evenFrameBlocks else oddFrameBlocks

        // Draw white rectangle over blocks that should not be visible this frame
        // This effectively masks 50% of the underlying content each frame
        for (block in blocksToHide) {
            canvas.drawRect(block, hidePaint)
        }

        // ================================================
        // ADDITIONAL LAYER: Block-level contrast pulse
        // ================================================
        // On visible blocks, add very slight contrast oscillation
        // Human: brain averages this out as stable
        // Camera: records as micro-flicker within visible blocks
        // Makes OCR and AI reconstruction even harder

        val visibleBlocks = if (isOddFrame) oddFrameBlocks else evenFrameBlocks
        val pulseAlpha = if (frameCount % 4 < 2) 6 else 0

        if (pulseAlpha > 0) {
            maskPaint.color = Color.argb(pulseAlpha, 0, 0, 0)
            for (block in visibleBlocks) {
                canvas.drawRect(block, maskPaint)
            }
        }
    }

    fun start() {
        isRunning = true
        handler.post(frameRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(frameRunnable)
    }
}