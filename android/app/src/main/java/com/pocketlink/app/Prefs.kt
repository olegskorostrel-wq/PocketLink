package com.pocketlink.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Запомненный ПК: адрес, имя, ключ устройства (после сопряжения) и MAC-адреса для Wake-on-LAN. */
class SavedPc(var host: String, var name: String, var key: String?, var macs: List<String>, val fingerprint: String) {
    fun toJson() = JSONObject()
        .put("host", host).put("name", name).put("key", key?.let { SecretStore.protect(it) } ?: "")
        .put("macs", JSONArray(macs)).put("fingerprint", fingerprint)

    companion object {
        fun from(o: JSONObject): SavedPc {
            val macs = mutableListOf<String>()
            o.optJSONArray("macs")?.let { for (i in 0 until it.length()) macs += it.optString(i) }
            val fingerprint = o.optString("fingerprint")
            val key = o.optString("key").takeIf { it.isNotEmpty() && fingerprint.length == 64 }?.let { SecretStore.read(it) }
            return SavedPc(o.optString("host"), o.optString("name"), key, macs, fingerprint)
        }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("pocketlink", Context.MODE_PRIVATE)
    }

    /** Постоянный идентификатор этого телефона для ПК. */
    val deviceId: String
        get() {
            var id = sp.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString("device_id", id).apply()
            }
            return id
        }

    var deviceName: String
        get() = sp.getString("device_name", null) ?: (Build.MODEL ?: "Android")
        set(v) = sp.edit().putString("device_name", v.trim().take(30)).apply()

    var lastHost: String
        get() = sp.getString("last_host", "") ?: ""
        set(v) = sp.edit().putString("last_host", v).apply()

    /** Автоподключение к последнему ПК (служба поднимается при запуске приложения). */
    /** Hardware volume keys control the PC while the PocketLink window is active. */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    var themeAccent: String
        get() = sp.getString("theme_accent", "purple") ?: "purple"
        set(v) = sp.edit().putString("theme_accent", v).apply()

    var pcVolumeKeys: Boolean
        get() = sp.getBoolean("pc_volume_keys", true)
        set(v) = sp.edit().putBoolean("pc_volume_keys", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var shareNotifs: Boolean
        get() = sp.getBoolean("share_notifs", true)
        set(v) = sp.edit().putBoolean("share_notifs", v).apply()

    var shareSms: Boolean
        get() = sp.getBoolean("share_sms", true)
        set(v) = sp.edit().putBoolean("share_sms", v).apply()

    var shareCalls: Boolean
        get() = sp.getBoolean("share_calls", true)
        set(v) = sp.edit().putBoolean("share_calls", v).apply()

    var sharePhotos: Boolean
        get() = sp.getBoolean("share_photos", true)
        set(v) = sp.edit().putBoolean("share_photos", v).apply()

    /** Автоматически слать буфер обмена на ПК, пока приложение открыто. */
    var clipAuto: Boolean
        get() = sp.getBoolean("clip_auto", false)
        set(v) = sp.edit().putBoolean("clip_auto", v).apply()

    var haveAsked: Boolean
        get() = sp.getBoolean("asked", false)
        set(v) = sp.edit().putBoolean("asked", v).apply()

    fun gamepadButton(index: Int): Int = sp.getInt("gamepad_button_$index", index).coerceIn(0, 15)
    fun setGamepadButton(index: Int, button: Int) { sp.edit().putInt("gamepad_button_$index", button.coerceIn(0, 15)).apply() }

    var useRelay: Boolean
        get() = sp.getBoolean("use_relay", false)
        set(v) = sp.edit().putBoolean("use_relay", v).apply()

    var relayUrl: String
        get() = sp.getString("relay_url", "") ?: ""
        set(v) = sp.edit().putString("relay_url", v).apply()

    var relayRoom: String
        get() = sp.getString("relay_room", "") ?: ""
        set(v) = sp.edit().putString("relay_room", v).apply()

    var relayToken: String
        get() = SecretStore.read(sp.getString("relay_token", "") ?: "") ?: ""
        set(v) = sp.edit().putString("relay_token", SecretStore.protect(v)).apply()

    var pointerSensitivity: Float
        get() = sp.getFloat("pointer_sensitivity", 1f).coerceIn(0.25f, 3f)
        set(v) = sp.edit().putFloat("pointer_sensitivity", v.coerceIn(0.25f, 3f)).apply()

    var naturalScroll: Boolean
        get() = sp.getBoolean("natural_scroll", true)
        set(v) = sp.edit().putBoolean("natural_scroll", v).apply()

    var screenWidth: Int
        get() = sp.getInt("screen_width", 1920)
        set(v) = sp.edit().putInt("screen_width", v).apply()

    var screenQuality: Int
        get() = sp.getInt("screen_quality", 80).coerceIn(30, 90)
        set(v) = sp.edit().putInt("screen_quality", v.coerceIn(30, 90)).apply()

    var screenFps: Int
        get() = sp.getInt("screen_fps", 12).coerceIn(1, 20)
        set(v) = sp.edit().putInt("screen_fps", v.coerceIn(1, 20)).apply()

    var screenMonitor: Int
        get() = sp.getInt("screen_monitor", 0)
        set(v) = sp.edit().putInt("screen_monitor", v.coerceAtLeast(0)).apply()

    var screenControl: Boolean
        get() = sp.getBoolean("screen_control", false)
        set(v) = sp.edit().putBoolean("screen_control", v).apply()

    fun pcs(): MutableList<SavedPc> {
        val res = mutableListOf<SavedPc>()
        try {
            val a = JSONArray(sp.getString("pcs", "[]"))
            for (i in 0 until a.length()) res += SavedPc.from(a.getJSONObject(i))
        } catch (_: Exception) {
        }
        return res
    }

    private fun writePcs(list: List<SavedPc>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp.edit().putString("pcs", a.toString()).apply()
    }

    @Synchronized
    fun savePc(pc: SavedPc) {
        val list = pcs()
        // один ПК = одна запись; ищем по имени, чтобы смена IP не плодила дубликаты
        val i = list.indexOfFirst { it.name == pc.name }
        if (i >= 0) list[i] = pc else list += pc
        writePcs(list)
        lastHost = pc.host
    }

    @Synchronized
    fun removePc(name: String) = writePcs(pcs().filter { it.name != name })

    fun pcByHost(host: String): SavedPc? = pcs().firstOrNull { it.host == host }
    fun lastPc(): SavedPc? = pcByHost(lastHost) ?: pcs().firstOrNull()
}
