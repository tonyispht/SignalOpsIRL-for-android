package com.example.signalopsirl

import android.content.Context

/** Reads assets/scanner_config.json into the classifier. Blocking (about 110 KB); call off the main thread. */
fun Classifier.loadAssets(ctx: Context) {
    if (isReady) return
    runCatching {
        val json = ctx.assets.open("scanner_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        loadFrom(json)
    }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
}
