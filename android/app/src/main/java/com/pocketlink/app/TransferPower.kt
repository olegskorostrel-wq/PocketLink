package com.pocketlink.app

import android.content.Context
import android.os.PowerManager

/** Keep CPU awake only for actual copying/transmission, never while waiting for an offline PC. */
object TransferPower {
    fun acquire(): PowerManager.WakeLock? = runCatching {
        (Hub.app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketLink:transfer").apply { acquire(20 * 60 * 1000L) }
    }.getOrNull()
    fun release(lock: PowerManager.WakeLock?) { runCatching { if (lock?.isHeld == true) lock.release() } }
}
