package com.pocketlink.app

import android.app.AlertDialog
import android.view.View
import org.json.JSONObject

class WebcamPage(act: MainActivity) : Page(act) {
    private var facing = "back"
    private val status = muted(act, "Для Discord нужна OBS Virtual Camera на ПК. Первый раз установите OBS Studio через раздел «Экран телефона» в PocketLink на ПК и подтвердите ADB на телефоне. Затем можно запускать отсюда по USB или Wi-Fi.")
    override val hasSettings = true
    override fun openSettings() {
        AlertDialog.Builder(act).setTitle("Камера телефона").setSingleChoiceItems(arrayOf("Задняя камера", "Передняя камера"), if (facing == "front") 1 else 0) { dialog, index -> facing = if (index == 1) "front" else "back"; dialog.dismiss() }.setNegativeButton("Отмена", null).show()
    }
    override val root: View = android.widget.ScrollView(act).apply { addView(vbox(act, 12).apply {
        addView(pageHeading(act, "Телефон как веб-камера")); addView(status)
        addView(btn(act, "Использовать как веб-камеру", 1) {
            if (Hub.state != Hub.State.ONLINE) Hub.toast("Нет связи с ПК")
            else { status.text = "Подготавливаю камеру…"; Hub.send("webcam.start", "facing" to facing) }
        }, lp(t = 12, ctx = act))
        addView(btn(act, "Остановить веб-камеру") { Hub.send("webcam.stop") }, lp(t = 8, ctx = act))
        addView(muted(act, "В Discord выберите OBS Virtual Camera. Звук выбирается отдельно. Для камеры нужен Android 12+; отладка ADB подтверждается отдельно от сопряжения PocketLink."), lp(t = 12, ctx = act))
    }) }
    override fun onHub(ev: String, data: Any?) { if (ev == "json" && data is JSONObject && data.optString("t") == "webcam.state") status.text = data.optString("message") }
}
