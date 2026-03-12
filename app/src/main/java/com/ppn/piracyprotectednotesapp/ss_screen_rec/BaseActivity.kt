package com.ppn.piracyprotectednotesapp.ss_screen_rec

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ppn.piracyprotectednotesapp.utils.FontObfuscationEngine

open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FontObfuscationEngine.init(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun onResume() {
        super.onResume()
        // Apply obfuscated font to every TextView in the entire view hierarchy
        applyObfuscatedFontToAll(window.decorView)
    }

    private fun applyObfuscatedFontToAll(view: View) {
        if (!FontObfuscationEngine.isInitialized()) return

        when (view) {
            is TextView -> {
                // Covers TextView, Button, EditText, CheckBox etc
                // since they all extend TextView
                view.typeface = FontObfuscationEngine.getTypeface()
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyObfuscatedFontToAll(view.getChildAt(i))
            }
        }
    }
}



//package com.ppn.piracyprotectednotesapp.ss_screen_rec
//
//import android.os.Bundle
//import android.view.WindowManager
//import android.widget.FrameLayout
//import androidx.appcompat.app.AppCompatActivity
//import com.ppn.piracyprotectednotesapp.utils.CameraProtectionOverlay
//
//open class BaseActivity : AppCompatActivity() {
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        // Block OS-level screenshots and recording
//        window.setFlags(
//            WindowManager.LayoutParams.FLAG_SECURE,
//            WindowManager.LayoutParams.FLAG_SECURE
//        )
//    }
//
//    override fun onPostCreate(savedInstanceState: Bundle?) {
//        super.onPostCreate(savedInstanceState)
//
//        addCameraProtectionLayer()
//    }
//
//    private fun addCameraProtectionLayer() {
//        val overlay = CameraProtectionOverlay(this)
//
//        val rootView = window.decorView.findViewById<FrameLayout>(android.R.id.content)
//        rootView.addView(
//            overlay,
//            FrameLayout.LayoutParams(
//                FrameLayout.LayoutParams.MATCH_PARENT,
//                FrameLayout.LayoutParams.MATCH_PARENT
//            )
//        )
//    }
//}
//
////package com.ppn.piracyprotectednotesapp.ss_screen_rec
////
////import android.os.Bundle
////import android.view.WindowManager
////import androidx.appcompat.app.AppCompatActivity
////import com.ppn.piracyprotectednotesapp.utils.AntiCaptureManager
////
////open class BaseActivity : AppCompatActivity() {
////
////    override fun onCreate(savedInstanceState: Bundle?) {
////        super.onCreate(savedInstanceState)
////
////        // Block OS-level screenshots and screen recording
////        window.setFlags(
////            WindowManager.LayoutParams.FLAG_SECURE,
////            WindowManager.LayoutParams.FLAG_SECURE
////        )
////    }
////
////    override fun onResume() {
////        super.onResume()
////        // Start anti-capture overlay whenever screen is visible
////        AntiCaptureManager.attach(this)
////    }
////
////    override fun onPause() {
////        super.onPause()
////        // Stop when screen is not visible — saves battery
////        AntiCaptureManager.detach()
////    }
////}
////
