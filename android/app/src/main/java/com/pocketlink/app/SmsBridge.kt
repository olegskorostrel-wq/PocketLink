package com.pocketlink.app

import android.Manifest
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** SMS: чтение через системный провайдер, отправка через SmsManager, новые сообщения через ContentObserver. */
object SmsBridge {
    private val ctx get() = Hub.app
    private val names = ConcurrentHashMap<String, String>()
    private var observer: ContentObserver? = null
    @Volatile private var lastId = 0L

    /** Отправленные с ПК сообщения: приложение не по умолчанию не может записать их в журнал SMS, храним сами. */
    private class Sent(val address: String, val body: String, val time: Long)
    private val sent = java.util.Collections.synchronizedList(ArrayList<Sent>())

    private fun norm(a: String) = a.filter { it.isDigit() }.takeLast(10)

    fun contactName(number: String): String {
        if (number.isEmpty()) return ""
        names[number]?.let { return it }
        var name = ""
        if (Features.granted(Manifest.permission.READ_CONTACTS)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0).orEmpty()
                }
            } catch (_: Exception) { }
        }
        names[number] = name
        return name
    }

    // ---------- наблюдатель за новыми SMS ----------

    fun start() {
        if (observer != null) return
        lastId = maxId()
        val o = object : ContentObserver(Hub.main) {
            override fun onChange(selfChange: Boolean) {
                Hub.work.execute { checkNew() }
            }
        }
        observer = o
        try { ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o) } catch (_: Exception) { observer = null }
    }

    fun stop() {
        observer?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) { } }
        observer = null
    }

    private fun maxId(): Long {
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, "_id DESC LIMIT 1")?.use {
                if (it.moveToFirst()) return it.getLong(0)
            }
        } catch (_: Exception) { }
        return 0
    }

    @Synchronized
    private fun checkNew() {
        if (!Prefs.shareSms) return
        try {
            val cols = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI, cols,
                "_id > ? AND type = ?", arrayOf(lastId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()), "_id ASC"
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id > lastId) lastId = id
                    val addr = it.getString(2).orEmpty()
                    Hub.send(
                        JSONObject().put("t", "sms.new").put("tid", it.getLong(1).toString()).put("address", addr)
                            .put("name", contactName(addr)).put("body", it.getString(3).orEmpty()).put("time", it.getLong(4) / 1000)
                    )
                }
            }
        } catch (_: Exception) { }
        val m = maxId()
        if (m > lastId) lastId = m
    }

    // ---------- запросы с ПК ----------

    fun handle(t: String, m: JSONObject) {
        when (t) {
            "sms.threads.get" -> threads()
            "sms.thread.get" -> thread(m.optString("tid"), m.optInt("limit", 100).coerceIn(1, 300))
            "sms.send" -> send(m.optString("to"), m.optString("body"))
        }
    }

    private fun threads() {
        val arr = JSONArray()
        if (Features.hasSms()) {
            try {
                val cols = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE)
                val seen = LinkedHashMap<Long, JSONObject>()
                val unread = HashMap<Long, Int>()
                ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, null, null, "date DESC")?.use {
                    var rows = 0
                    while (it.moveToNext() && rows < 1500) {
                        rows++
                        val tid = it.getLong(0)
                        if (it.getInt(4) == 0 && it.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX) unread[tid] = (unread[tid] ?: 0) + 1
                        if (seen.size >= 60 && !seen.containsKey(tid)) continue
                        if (!seen.containsKey(tid)) {
                            val addr = it.getString(1).orEmpty()
                            seen[tid] = JSONObject().put("tid", tid.toString()).put("address", addr)
                                .put("name", contactName(addr)).put("snippet", it.getString(2).orEmpty().take(120))
                                .put("time", it.getLong(3) / 1000)
                        }
                    }
                }
                for ((tid, o) in seen) arr.put(o.put("unread", unread[tid] ?: 0))
            } catch (_: Exception) { }
        }
        Hub.send("sms.threads", "threads" to arr)
    }

    private fun thread(tid: String, limit: Int) {
        val list = ArrayList<JSONObject>()
        var address = ""
        if (Features.hasSms() && tid.all { it.isDigit() } && tid.isNotEmpty()) {
            try {
                val cols = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS)
                ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, "thread_id = ?", arrayOf(tid), "date DESC")?.use {
                    while (it.moveToNext() && list.size < limit) {
                        if (address.isEmpty()) address = it.getString(4).orEmpty()
                        list += JSONObject().put("id", it.getLong(0)).put("body", it.getString(1).orEmpty())
                            .put("time", it.getLong(2) / 1000).put("out", it.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                }
            } catch (_: Exception) { }
        }
        list.reverse()
        // добавляем отправленные с ПК, которых нет в журнале
        if (address.isNotEmpty()) {
            val n = norm(address)
            synchronized(sent) {
                for (s in sent) if (norm(s.address) == n && list.none { it.optBoolean("out") && it.optString("body") == s.body && Math.abs(it.optLong("time") - s.time) < 120 }) {
                    list += JSONObject().put("id", -s.time).put("body", s.body).put("time", s.time).put("out", true)
                }
            }
            list.sortBy { it.optLong("time") }
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        Hub.send("sms.thread", "tid" to tid, "messages" to arr)
    }

    @Suppress("DEPRECATION")
    private fun send(to: String, body: String) {
        val addr = to.trim()
        if (addr.isEmpty() || body.isEmpty() || !Features.granted(Manifest.permission.SEND_SMS)) {
            Hub.send("sms.sent", "ok" to false, "to" to to)
            return
        }
        val ok = try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = sm.divideMessage(body)
            if (parts.size > 1) sm.sendMultipartTextMessage(addr, null, parts, null, null)
            else sm.sendTextMessage(addr, null, body, null, null)
            true
        } catch (_: Exception) { false }
        if (ok) {
            sent += Sent(addr, body, System.currentTimeMillis() / 1000)
            if (sent.size > 200) synchronized(sent) { while (sent.size > 200) sent.removeAt(0) }
        }
        Hub.send("sms.sent", "ok" to ok, "to" to to)
    }
}
