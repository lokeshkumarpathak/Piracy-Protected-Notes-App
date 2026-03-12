package com.ppn.piracyprotectednotesapp.utils

import android.content.Context

object SecretsManager {
    fun getServiceAccountJson(context: Context): String =
        context.assets.open("secrets.json")
            .bufferedReader()
            .use { it.readText() }
}