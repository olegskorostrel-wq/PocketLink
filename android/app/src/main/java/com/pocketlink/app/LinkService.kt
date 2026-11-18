package com.pocketlink.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Держит процесс живым, пока есть связь с ПК (или идёт переподключение). Вся логика в [Hub]. */
class LinkService : Service() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Hub.init(this)
        Notifier.createChannels(this)
        val n = Notifier.linkNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_LINK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Notifier.ID_LINK, n)
        } catch (_: Exception) {
            // нет права показывать уведомления / запуск службы запрещён системой: работаем без значка
        }
        Hub.serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.autoConnectIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        Hub.serviceAlive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LinkService::class.java))
        }
    }
}
