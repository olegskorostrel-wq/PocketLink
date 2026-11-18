package com.pocketlink.app

import android.app.AlertDialog
import android.view.View
import android.widget.ScrollView
import org.json.JSONObject

class DesktopPage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 12)
    override val root: View = ScrollView(act).apply { addView(body) }
    private var shown = false
    private var waiting = false
    private val timeout = Runnable {
        if (shown && waiting) { waiting = false; showMessage("ПК не ответил за 5 секунд. Обновите Windows-часть PocketLink и повторите.") }
    }
    private fun showMessage(text: String) {
        body.removeAllViews()
        body.addView(pageHeading(act, "Окна и состояние ПК"))
        body.addView(muted(act, text))
        body.addView(btn(act, "Повторить") { request() }, lp(t = 12, ctx = act))
        body.addView(btn(act, "Подключение и разрешения") { act.showSettings() }, lp(t = 8, ctx = act))
    }
    private fun request() {
        if (!shown || waiting) return
        if (Hub.state != Hub.State.ONLINE) { showMessage("Нет связи с ПК. Подключите компьютер, затем повторите."); return }
        if (!Hub.can("processes")) { showMessage("На ПК запрещён доступ к окнам и процессам. Разрешите его в настройках PocketLink на ПК."); return }
        waiting = true
        Hub.send("desktop.get")
        Hub.main.postDelayed(timeout, 5000)
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!shown) return
            request()
            Hub.main.postDelayed(this, 3000)
        }
    }
    override fun onShow() { if (shown) return; shown = true; showMessage("Получаю окна и состояние ПК…"); refresh.run() }
    override fun onHide() { shown = false; waiting = false; Hub.main.removeCallbacks(refresh); Hub.main.removeCallbacks(timeout) }
    private fun gib(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f ГБ", bytes / 1073741824.0)
    override fun onHub(ev: String, data: Any?) {
        if (shown && (ev == "state" || ev == "config")) {
            waiting = false; Hub.main.removeCallbacks(timeout); request(); return
        }
        val state = data as? JSONObject ?: return
        if (shown && ev == "json" && (state.optString("t") == "desktop.error" || state.optString("t") == "denied" && state.optString("what") == "processes")) {
            waiting = false; Hub.main.removeCallbacks(timeout)
            showMessage(state.optString("message", "Доступ к окнам и процессам запрещён на ПК")); return
        }
        if (shown && ev == "json" && state.optString("t") == "monitor.brightness") {
            val monitors = state.optJSONArray("items") ?: return
            if (monitors.length() == 0) { Hub.toast("Монитор не предоставляет управление яркостью DDC/CI"); return }
            val panel = vbox(act, 12)
            for (index in 0 until monitors.length()) {
                val monitor = monitors.getJSONObject(index)
                panel.addView(tv(act, monitor.optString("name"), 14f))
                panel.addView(android.widget.SeekBar(act).apply {
                    max = 100; progress = monitor.optInt("value")
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {}
                        override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                        override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                            Hub.send("monitor.brightness.set", "id" to monitor.optInt("id"), "value" to bar.progress)
                        }
                    })
                })
            }
            AlertDialog.Builder(act).setTitle("Яркость мониторов").setView(panel).setPositiveButton("Готово", null).show()
            return
        }
        if (!shown || ev != "json" || state.optString("t") != "desktop.state") return
        waiting = false; Hub.main.removeCallbacks(timeout)
        body.removeAllViews()
        val metrics = state.optJSONObject("metrics") ?: JSONObject()
        body.addView(tv(act, "Состояние ПК", 18f, T.text, true))
        val sensors = state.optJSONArray("sensors")
        if (sensors != null && sensors.length() > 0) {
            for (index in 0 until sensors.length()) {
                val sensor = sensors.getJSONObject(index)
                body.addView(muted(act, sensor.optString("device") + " · " + sensor.optString("name") + " — " + sensor.optDouble("value") + sensor.optString("unit"), 12f))
            }
        } else body.addView(muted(act, "Температуры и датчики GPU недоступны для этого оборудования или прав Windows", 12f))
        body.addView(btn(act, "Яркость мониторов") { Hub.send("monitor.brightness.get") })
        body.addView(tv(act, if (metrics.isNull("cpu")) "Процессор — измеряю…" else "Процессор — " + metrics.optInt("cpu") + "%"))
        body.addView(tv(act, "Память — " + gib(metrics.optLong("memoryTotal") - metrics.optLong("memoryFree")) + " из " + gib(metrics.optLong("memoryTotal"))))
        val disks = metrics.optJSONArray("disks")
        if (disks != null) for (i in 0 until disks.length()) {
            val disk = disks.getJSONObject(i)
            body.addView(muted(act, disk.optString("name") + " свободно " + gib(disk.optLong("free"))))
        }
        body.addView(btn(act, "Скриншот ПК в загрузки телефона") { Hub.send("screen.capture", "mon" to Prefs.screenMonitor) })
        body.addView(btn(act, "Записать экран ПК · до 2 минут") { Hub.send("screen.record.start", "mon" to Prefs.screenMonitor) })
        body.addView(btn(act, "Завершить запись и получить AVI") { Hub.send("screen.record.stop") })
        body.addView(sectionTitle(act, "Окна"))
        val windows = state.optJSONArray("windows") ?: return
        for (i in 0 until windows.length()) {
            val window = windows.getJSONObject(i)
            body.addView(btn(act, window.optString("title")) {
                val actions = arrayOf("Открыть", "Свернуть", "Развернуть", "Восстановить", "Левая половина", "Правая половина", "Закрыть")
                AlertDialog.Builder(act).setTitle(window.optString("title")).setItems(actions) { _, choice ->
                    val action = arrayOf("activate", "minimize", "maximize", "restore", "left", "right", "close")[choice]
                    val perform = { Hub.send("desktop.act", "id" to window.optString("id"), "action" to action) }
                    if (action == "close") AlertDialog.Builder(act).setMessage("Закрыть окно на ПК?")
                        .setPositiveButton("Закрыть") { _, _ -> perform() }.setNegativeButton("Отмена", null).show()
                    else perform()
                }.show()
            }, lp(t = 6, ctx = act))
        }
    }
}
