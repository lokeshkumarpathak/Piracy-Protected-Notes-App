package com.ppn.piracyprotectednotesapp.utils

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

object FontObfuscationEngine {

    private var obfuscatedTypeface: Typeface? = null

    /**
     * Call once in Application.onCreate() or MainActivity.onCreate()
     * Loads the obfuscated font from assets
     */
    fun init(context: Context) {
        obfuscatedTypeface = Typeface.createFromAsset(
            context.assets,
            "fonts/ppn_obfuscated.ttf"
        )
    }

    /**
     * Encodes real text into PUA codepoints
     * The obfuscated font renders PUA codepoints as the original glyphs
     * Camera captures PUA codepoints = meaningless without FontMap
     *
     * Example:
     * Input:  "Hello World"
     * Output: "ꀕꀎꀙꀙꀛ ꏗꀛꀠꀙꀒ" (PUA codepoints rendered as Hello World)
     */
    fun encode(text: String): String {
        return text.map { char ->
            val puaCodepoint = FontMap.charToPua[char]
            if (puaCodepoint != null) {
                String(Character.toChars(puaCodepoint))
            } else {
                char.toString()  // unmapped chars pass through unchanged
            }
        }.joinToString("")
    }

    /**
     * Decodes PUA codepoints back to real text
     * Use in admin tool to decode captured recordings
     */
    fun decode(encodedText: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < encodedText.length) {
            val codepoint = encodedText.codePointAt(i)
            val realChar = FontMap.puaToChar[codepoint]
            if (realChar != null) {
                sb.append(realChar)
            } else {
                sb.appendCodePoint(codepoint)
            }
            i += Character.charCount(codepoint)
        }
        return sb.toString()
    }

    /**
     * Applies obfuscated font and encoded text to a TextView
     * Drop-in replacement for textView.text = "something"
     *
     * Usage:
     * FontObfuscationEngine.applyTo(textView, "Hello World")
     */
    fun applyTo(textView: TextView, text: String) {
        val typeface = obfuscatedTypeface
            ?: throw IllegalStateException(
                "FontObfuscationEngine not initialized. Call init() first."
            )
        textView.typeface = typeface
        textView.text = encode(text)
    }

    /**
     * Returns the obfuscated typeface for use with
     * custom views or PDF text rendering
     */
    fun getTypeface(): Typeface {
        return obfuscatedTypeface
            ?: throw IllegalStateException(
                "FontObfuscationEngine not initialized. Call init() first."
            )
    }

    /**
     * Checks if engine is ready to use
     */
    fun isInitialized(): Boolean = obfuscatedTypeface != null
}