package com.ppn.piracyprotectednotesapp.utils

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotManager {

    const val SCREENSHOTS_DIR = "Screenshots"
    const val TEMP_DIR        = "temp"

    /**
     * Captures exactly what the user sees in the PhotoView — respecting their
     * current zoom level and pan position. Works entirely off-screen so
     * FLAG_SECURE is never lifted.
     *
     * [folderName] defaults to "Screenshots" for permanent saves.
     * Pass "temp" (TEMP_DIR) for one-off AI captures that get deleted after sending.
     */
    fun captureVisibleArea(
        activity:   Activity,
        photoView:  PhotoView,
        pageLabel:  String = "page",
        folderName: String = SCREENSHOTS_DIR
    ): File? {
        return try {
            val drawable = photoView.drawable ?: return null

            val viewWidth  = photoView.width
            val viewHeight = photoView.height
            if (viewWidth <= 0 || viewHeight <= 0) return null

            // Create off-screen bitmap matching the visible area exactly
            val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)

            val canvas = Canvas(bitmap)
            // Apply PhotoView's current display matrix — zoom + pan preserved
            canvas.save()
            canvas.concat(photoView.imageMatrix)
            drawable.draw(canvas)
            canvas.restore()

            val dir = File(activity.filesDir, folderName)
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outFile   = File(dir, "${pageLabel}_$timestamp.png")

            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            outFile

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Returns all permanent screenshots (filesDir/Screenshots/) sorted newest first. */
    fun getAllScreenshots(activity: Activity): List<File> {
        val dir = File(activity.filesDir, SCREENSHOTS_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Delete a single screenshot. */
    fun deleteScreenshot(file: File): Boolean = file.delete()

    /** Delete all permanent screenshots. */
    fun deleteAllScreenshots(activity: Activity): Boolean =
        File(activity.filesDir, SCREENSHOTS_DIR).deleteRecursively()

    /** Clean up any leftover temp files (call on app start if desired). */
    fun clearTempFiles(activity: Activity) {
        File(activity.filesDir, TEMP_DIR).deleteRecursively()
    }
}