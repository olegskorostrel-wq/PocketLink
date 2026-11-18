package com.pocketlink.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

/** Невидимая Activity: в фокусе читает буфер обмена телефона и отправляет на ПК. */
class ClipSendActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Hub.init(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        Hub.sendClipboardManually(text.orEmpty())
        finish()
    }
}
