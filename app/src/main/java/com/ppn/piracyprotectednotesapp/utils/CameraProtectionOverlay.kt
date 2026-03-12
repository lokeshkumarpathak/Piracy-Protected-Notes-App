package com.ppn.piracyprotectednotesapp.utils

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class CameraProtectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint()
    private val random = Random(System.currentTimeMillis())

    init {
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Subtle transparent noise layer
        for (i in 0 until 1000) {
            paint.color = Color.argb(
                3,  // VERY low alpha (invisible to eye)
                random.nextInt(256),
                random.nextInt(256),
                random.nextInt(256)
            )
            canvas.drawPoint(
                random.nextFloat() * width,
                random.nextFloat() * height,
                paint
            )
        }

        // Force continuous redraw
        postInvalidateOnAnimation()
    }
}