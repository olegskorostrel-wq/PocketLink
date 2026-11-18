package com.pocketlink.app

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ScrollView

class HomePage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 12)
    override val root: View = ScrollView(act).apply { isFillViewport = true; addView(body) }
    override fun onShow() = render()
    override fun onHub(ev: String, data: Any?) {
        if (ev == "state" || ev == "config") render()
        if (ev == "json" && data is org.json.JSONObject && data.optString("t") == "mirror.state")
            AlertDialog.Builder(act).setTitle("Экран телефона на ПК").setMessage(data.optString("message")).setPositiveButton("Понятно", null).show()
    }
    private fun online(permission: String, run: () -> Unit) {
        if (Hub.state != Hub.State.ONLINE) { Hub.toast("Нет связи с ПК"); return }
        if (!Hub.can(permission)) { Hub.toast("Действие запрещено в разрешениях ПК"); return }
        run()
    }
    private fun power(action: String, title: String) = online("power") {
        AlertDialog.Builder(act).setTitle(title).setMessage("Выполнить на ПК? Несохранённые данные могут потеряться.")
            .setNegativeButton("Отмена", null).setPositiveButton("Выполнить") { _, _ -> Hub.send("power", "action" to action, "delay" to 0) }.show()
    }
    private fun render() {
        body.removeAllViews()
        body.addView(pageHeading(act, "Быстрые действия"))
        val row = hbox(act)
        row.addView(btn(act, "Выключить ПК", 2) { power("shutdown", "Выключить ПК?") }, lp(0, weight = 1f, r = 8, ctx = act))
        row.addView(btn(act, "Включить ПК", 1) {
            val pc = Prefs.lastPc()
            if (pc == null) Hub.toast("Сначала подключитесь к ПК один раз") else Hub.wake(pc)
        }, lp(0, weight = 1f, ctx = act))
        body.addView(row)
        body.addView(muted(act, "Включение по сети требует настройки Wake-on-LAN в BIOS и сетевой карте ПК.", 12f), lp(t = 6, ctx = act))
        body.addView(sectionTitle(act, "Буфер обмена"))
        body.addView(btn(act, "Буфер телефона → на ПК", 1) { online("clipboard") {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() }.getOrNull()
            if (text.isNullOrEmpty()) Hub.toast("В буфере телефона нет текста") else Hub.sendClipboardManually(text)
        } })
        body.addView(btn(act, "Буфер ПК → на телефон") { online("clipboard") { Hub.send("clip.pc.request") } }, lp(t = 8, ctx = act))
        body.addView(sectionTitle(act, "Часто нужно"))
        body.addView(btn(act, "Файлы и передача") { act.openUtility(FilesPage(act)) })
        body.addView(btn(act, "Тачпад, клавиатура и мультимедиа") { act.openUtility(RemotePage(act)) })
        body.addView(btn(act, "YouTube — поиск и пульт") { act.openUtility(YouTubePage(act)) }, lp(t = 8, ctx = act))
        body.addView(btn(act, "Окна и состояние ПК") { act.openUtility(DesktopPage(act)) }, lp(t = 8, ctx = act))
        body.addView(btn(act, "Заблокировать ПК") { online("power") { Hub.send("power", "action" to "lock", "delay" to 0) } }, lp(t = 8, ctx = act))
        body.addView(btn(act, "Спящий режим") { power("sleep", "Перевести ПК в спящий режим?") }, lp(t = 8, ctx = act))
        body.addView(sectionTitle(act, "Телефон и устройства"))
        body.addView(btn(act, "Экран телефона и управление", 1) { online("commands") {
            Hub.send("mirror.quick"); Hub.toast("Запускаю экран телефона на ПК…")
        } }, lp(b = 8, ctx = act))
        body.addView(btn(act, "Телефон как веб-камера для Discord") { act.openUtility(WebcamPage(act)) }, lp(b = 8, ctx = act))
        body.addView(btn(act, "Bluetooth-пульт и геймпад") { act.openUtility(BluetoothPage(act)) })
        body.addView(btn(act, "Телефон как микрофон ПК") { act.openUtility(MicrophonePage(act)) }, lp(t = 8, ctx = act))
        body.addView(btn(act, "Выход звука ПК и микшер") { act.openUtility(MixerPage(act)) }, lp(t = 8, ctx = act))
        if (Hub.state != Hub.State.ONLINE) body.addView(btn(act, "Подключить ПК", 1) { act.showSettings() }, lp(t = 12, ctx = act))
    }
}
