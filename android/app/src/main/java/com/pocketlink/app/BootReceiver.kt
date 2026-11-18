package com.pocketlink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки телефона поднимает связь с последним ПК, если включено автоподключение. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(context)
        if (Prefs.autoConnect && Prefs.lastPc() != null) LinkService.start(context)
    }
}
