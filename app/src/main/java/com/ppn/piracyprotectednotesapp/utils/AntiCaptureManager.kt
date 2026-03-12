package com.ppn.piracyprotectednotesapp.utils

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ppn.piracyprotectednotesapp.ss_screen_rec.AntiCaptureOverlay

object AntiCaptureManager {

    private var overlay: AntiCaptureOverlay? = null

    fun attach(activity: Activity) {
        // Detach any existing overlay first — prevents stacking
        detach()

        val rootView = activity.window.decorView.findViewById<ViewGroup>(
            android.R.id.content
        )

        val newOverlay = AntiCaptureOverlay(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootView.addView(newOverlay)
        newOverlay.start()
        overlay = newOverlay
    }

    fun detach() {
        overlay?.stop()
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
    }
}