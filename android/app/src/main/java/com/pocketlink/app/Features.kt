package com.pocketlink.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** Функции телефона, доступные ПК: сборка и раздача по сообщениям протокола. */
object Features {
    private var started = false
    private var lastBattery = ""

    private val ctx get() = Hub.app

    /** Регистрация приёмника системных рассылок (на Android 13+ нужен явный флаг). */
    fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
    }

    fun granted(perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun hasNotifAccess(): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    fun hasSms() = granted(Manifest.permission.READ_SMS)
    fun hasCalls() = granted(Manifest.permission.READ_PHONE_STATE)
    fun hasPhotos() =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) || Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasVideos() = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) || Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun permsJson() = JSONObject()
        .put("notif", hasNotifAccess()).put("sms", hasSms()).put("calls", hasCalls()).put("photos", hasPhotos() || hasVideos())

    // ---------- батарея ----------

    fun batteryJson(): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return JSONObject().put("level", pct).put("charging", charging)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val b = batteryJson()
            val key = "${b.optInt("level")}/${b.optBoolean("charging")}"
            if (key == lastBattery) return
            lastBattery = key
            Hub.send(b.put("t", "battery"))
        }
    }

    // ---------- жизненный цикл ----------

    fun start() {
        if (started) return
        started = true
        lastBattery = ""
        try { register(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { }
        NotifMirror.syncAll()
        if (Prefs.shareSms && hasSms()) SmsBridge.start()
        if (Prefs.shareCalls && hasCalls()) CallBridge.start()
    }

    fun stop() {
        if (!started) return
        started = false
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        SmsBridge.stop()
        CallBridge.stop()
        FindPhone.stop()
    }

    /** Возвращает true, если сообщение обработано как функция телефона. Тяжёлое делается вне сетевого потока. */
    fun handle(m: JSONObject): Boolean {
        val t = m.optString("t")
        when {
            t.startsWith("notif.") -> Hub.work.execute { NotifMirror.handle(t, m) }
            t == "sms.threads.get" || t == "sms.thread.get" || t == "sms.send" ->
                Hub.work.execute { if (Prefs.shareSms) SmsBridge.handle(t, m) else Hub.send("sms.threads", "threads" to org.json.JSONArray()) }
            t == "call.answer" || t == "call.hangup" || t == "call.dial" ->
                Hub.work.execute { if (Prefs.shareCalls) CallBridge.handle(t, m) }
            t == "photos.get" || t == "photo.fetch" ->
                Hub.work.execute { if (Prefs.sharePhotos) PhotoBridge.handle(t, m) else Hub.send("photos.list", "items" to org.json.JSONArray(), "more" to false) }
            t == "find.start" -> FindPhone.start()
            t == "find.stop" -> FindPhone.stop()
            t == "share.text" -> shareText(m.optString("text"))
            else -> return false
        }
        return true
    }

    private fun shareText(text: String) {
        if (text.isEmpty()) return
        Hub.main.post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PocketLink", text))
            } catch (_: Exception) { }
            Notifier.info(ctx, "С ПК (скопировано в буфер)", text)
        }
    }
}
