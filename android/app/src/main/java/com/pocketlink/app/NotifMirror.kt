package com.pocketlink.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Системная служба доступа к уведомлениям: включается пользователем вручную в настройках Android. */
class PlNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Prefs.init(this)
        if (Hub.state == Hub.State.ONLINE) Hub.work.execute { NotifMirror.syncAll() }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (Hub.state == Hub.State.ONLINE) Hub.work.execute { NotifMirror.posted(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (Hub.state == Hub.State.ONLINE) NotifMirror.removed(sbn)
    }

    companion object {
        @Volatile var instance: PlNotificationListener? = null
    }
}

object NotifMirror {
    private val iconCache = ConcurrentHashMap<String, String>()
    private val labelCache = ConcurrentHashMap<String, String>()

    private fun skip(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == Hub.app.packageName) return true
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return true
        return false
    }

    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = Hub.app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun icon(pkg: String): String = iconCache.getOrPut(pkg) {
        try {
            val d: Drawable = Hub.app.packageManager.getApplicationIcon(pkg)
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun plainActions(n: Notification): List<Notification.Action> =
        n.actions?.filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrEmpty() } ?: emptyList()

    fun build(sbn: StatusBarNotification): JSONObject? {
        if (!Prefs.shareNotifs || skip(sbn)) return null
        val n = sbn.notification
        val ex: Bundle = n.extras ?: Bundle()
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val acts = JSONArray()
        plainActions(n).take(3).forEach { acts.put(it.title.toString()) }
        val o = JSONObject()
            .put("t", "notif.post")
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", label(sbn.packageName))
            .put("title", title)
            .put("text", text.take(2000))
            .put("time", sbn.postTime / 1000)
            .put("reply", replyAction(n) != null)
            .put("actions", acts)
        val ic = icon(sbn.packageName)
        if (ic.isNotEmpty()) o.put("icon", ic)
        return o
    }

    fun posted(sbn: StatusBarNotification) {
        build(sbn)?.let { Hub.send(it) }
    }

    fun removed(sbn: StatusBarNotification) {
        if (!Prefs.shareNotifs) return
        Hub.send("notif.remove", "key" to sbn.key)
    }

    fun syncAll() {
        val l = PlNotificationListener.instance ?: return
        if (!Prefs.shareNotifs) return
        Hub.work.execute {
            try {
                val arr = JSONArray()
                for (sbn in l.activeNotifications ?: emptyArray()) build(sbn)?.let { arr.put(it) }
                // одним сообщением не больше ~700 КБ: иконки по 3-4 КБ, поэтому режем на пачки
                var i = 0
                while (i < arr.length() || i == 0) {
                    val part = JSONArray()
                    var bytes = 0
                    while (i < arr.length() && bytes < 500_000) {
                        val o = arr.getJSONObject(i++)
                        bytes += o.toString().length
                        part.put(o)
                    }
                    Hub.send("notif.sync", "list" to part)
                    if (arr.length() == 0) break
                }
            } catch (_: Exception) { }
        }
    }

    private fun find(key: String): StatusBarNotification? =
        try { PlNotificationListener.instance?.getActiveNotifications(arrayOf(key))?.firstOrNull() } catch (_: Exception) { null }

    fun handle(t: String, m: JSONObject) {
        val l = PlNotificationListener.instance ?: return
        val key = m.optString("key")
        when (t) {
            "notif.dismiss" -> try { l.cancelNotification(key) } catch (_: Exception) { }
            "notif.reply" -> {
                val sbn = find(key) ?: return
                val a = replyAction(sbn.notification) ?: return
                val inputs = a.remoteInputs ?: return
                val text = m.optString("text")
                if (text.isEmpty()) return
                try {
                    val intent = Intent()
                    val results = Bundle()
                    for (ri in inputs) results.putCharSequence(ri.resultKey, text)
                    RemoteInput.addResultsToIntent(inputs, intent, results)
                    a.actionIntent.send(Hub.app, 0, intent)
                } catch (_: Exception) { }
            }
            "notif.action" -> {
                val sbn = find(key) ?: return
                val a = plainActions(sbn.notification).getOrNull(m.optInt("idx", -1)) ?: return
                try { a.actionIntent.send() } catch (_: Exception) { }
            }
        }
    }
}
