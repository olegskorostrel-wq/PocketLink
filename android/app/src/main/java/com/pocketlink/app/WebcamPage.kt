package com.pocketlink.app

import android.app.AlertDialog
import android.view.View
import org.json.JSONObject

class WebcamPage(act: MainActivity) : Page(act) {
    private var facing = Prefs.cameraFacing
    private var awaiting = false
    private val status = muted(act, "Камера передаётся самим PocketLink через защищённое подключение. На ПК один раз нужен компонент виртуальной камеры; отладка Android не требуется.")
    override val hasSettings = true
    private fun qualityLabel() = when(Prefs.cameraQuality) { "economy" -> "480p · до 10 кадров/с"; "quality" -> "1080p · до 30 кадров/с"; else -> "720p · до 15 кадров/с" }
    private val quality = muted(act, qualityLabel())
    override fun openSettings() {
        AlertDialog.Builder(act).setTitle("Настройки веб-камеры").setItems(arrayOf("Выбор камеры", "Качество: " + qualityLabel())) { _, item ->
            if(item==0) AlertDialog.Builder(act).setTitle("Камера телефона").setSingleChoiceItems(arrayOf("Задняя камера", "Передняя камера"), if (facing == "front") 1 else 0) { dialog, index ->
                facing = if (index == 1) "front" else "back"; Prefs.cameraFacing=facing; dialog.dismiss()
                if(PhoneCameraService.active) PhoneCameraService.start(act,facing)
            }.setNegativeButton("Отмена", null).show()
            else { val keys=listOf("economy","balanced","quality")
                AlertDialog.Builder(act).setTitle("Качество камеры").setSingleChoiceItems(arrayOf("Экономный · 480p / 10 кадров/с","Сбалансированный · 720p / 15 кадров/с","Чёткий · 1080p / 30 кадров/с"),keys.indexOf(Prefs.cameraQuality).coerceAtLeast(0)) { dialog,index ->
                    Prefs.cameraQuality=keys[index]; quality.text=qualityLabel();dialog.dismiss()
                    if(PhoneCameraService.active) PhoneCameraService.start(act,facing)
                }.setNegativeButton("Отмена",null).show()
            }
        }.setNegativeButton("Закрыть",null).show()
    }
    override val root: View = android.widget.ScrollView(act).apply { addView(vbox(act, 12).apply {
        addView(pageHeading(act, "Телефон как веб-камера")); addView(status); addView(quality,lp(t=8,ctx=act))
        addView(btn(act, "Использовать как веб-камеру", 1) {
            if (Hub.state != Hub.State.ONLINE) Hub.toast("Нет связи с ПК")
            else if (!Hub.can("camera")) status.text = "Разрешите передачу камеры в настройках PocketLink на ПК"
            else act.askPerms(arrayOf(android.Manifest.permission.CAMERA)) {
                if (Features.granted(android.Manifest.permission.CAMERA)) {
                    runCatching {
                        awaiting = true; Hub.send("camera.prepare"); status.text = "Проверяю готовность камеры на ПК…"
                        root.postDelayed({ if (awaiting) { awaiting = false; status.text = "ПК не ответил. Обновите PocketLink для Windows и повторите запуск камеры." } }, 15000)
                    }
                        .onFailure { status.text = "Не удалось запустить камеру. Откройте приложение и повторите." }
                } else status.text = "Разрешите PocketLink доступ к камере в настройках телефона."
            }
        }, lp(t = 12, ctx = act))
        addView(btn(act, "Остановить веб-камеру") { awaiting = false; PhoneCameraService.stop(act); Hub.send("camera.stop"); status.text = "Камера остановлена" }, lp(t = 8, ctx = act))
        addView(muted(act, "В Discord выберите PocketLink Camera. Звук выбирается отдельно. Пока камера работает, Android показывает постоянное уведомление; при потере подключения камера выключается."), lp(t = 12, ctx = act))
    }) }
    override fun onHub(ev: String, data: Any?) {
        if (ev == "state" && Hub.state != Hub.State.ONLINE) { awaiting = false; status.text = "Нет связи с ПК" }
        if (ev == "json" && data is JSONObject) {
            if (data.optString("t") == "webcam.state") {
                status.text = data.optString("message")
                val requested = awaiting; awaiting = false
                if (requested && data.optBoolean("ready") && Hub.state == Hub.State.ONLINE) {
                    runCatching { PhoneCameraService.start(act, facing) }.onFailure { status.text = "Откройте приложение и повторите запуск камеры" }
                }
            }
            if (data.optString("t") == "camera.state") status.text = data.optString("message")
        }
    }
    override fun onHide() { awaiting = false; super.onHide() }
}
