package com.example.signalopsirl

import android.content.Context

/**
 * Reads the bundled Bluetooth database (assets/btdb/) and assets/scanner_config.json into the classifier.
 * Blocking; call off the main thread. Safe to call from both the activity and the scan service.
 */
fun Classifier.loadAssets(ctx: Context): Unit = synchronized(BtDb) {
    if (!BtDb.isLoaded) {
        runCatching {
            val a = ctx.assets
            fun open(n: String) = runCatching { a.open("btdb/$n").bufferedReader(Charsets.UTF_8) }.getOrNull()
            val ver = runCatching { a.open("btdb/VERSION").bufferedReader().use { it.readText() } }.getOrNull()
            BtDb.load(open("companies.tsv"), open("uuid16.tsv"), open("appearance.tsv"), ver)
        }
    }
    if (isReady) return@synchronized
    runCatching {
        val json = ctx.assets.open("scanner_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        loadFrom(json)
    }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
}
