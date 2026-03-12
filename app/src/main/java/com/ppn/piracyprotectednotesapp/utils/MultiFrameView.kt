package com.ppn.piracyprotectednotesapp.utils

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.util.AttributeSet
import android.view.View

class MultiFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null

) : View(context, attrs), Choreographer.FrameCallback {

    private var originalBitmap: Bitmap? = null
    private var frameToggle = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var protectedBitmap: Bitmap? = null

    fun setProtectedBitmap(bitmap: Bitmap) {
        protectedBitmap = bitmap
        invalidate()
    }

    fun setImage(bitmap: Bitmap) {
        originalBitmap = bitmap
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = originalBitmap ?: return

        val width = width
        val height = height

        protectedBitmap?.let { bmp ->
            val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)

        val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(tempBitmap)

        val maskPaint = Paint()
        maskPaint.isFilterBitmap = false

        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                if ((x + y) % 4 == if (frameToggle) 0 else 2) {
                    val pixel = scaledBitmap.getPixel(x, y)
                    tempBitmap.setPixel(x, y, pixel)
                }
            }
        }

        canvas.drawBitmap(tempBitmap, 0f, 0f, paint)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameToggle = !frameToggle
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }
}