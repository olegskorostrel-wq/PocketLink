package com.pocketlink.app

import android.view.View
import android.widget.ScrollView
import android.widget.SeekBar
import org.json.JSONObject

class MixerPage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 12)
    override val root: View = ScrollView(act).apply { addView(body) }
    override fun onShow() { Hub.send("mixer.get") }

    override fun onHub(ev: String, data: Any?) {
        if (ev != "json") return
        val message = data as? JSONObject ?: return
        if (message.optString("t") != "mixer.list") return
        body.removeAllViews()
        body.addView(btn(act, "Обновить") { Hub.send("mixer.get") })
        val outputs = message.optJSONArray("outputs")
        if (outputs != null) body.addView(btn(act, "Выбрать колонки или наушники") {
            android.app.AlertDialog.Builder(act).setTitle("Выход звука ПК").setItems(
                Array(outputs.length()) { i -> outputs.getJSONObject(i).optString("name") + if (outputs.getJSONObject(i).optBoolean("default")) " ✓" else "" }
            ) { _, index -> Hub.send("mixer.output", "id" to outputs.getJSONObject(index).optString("id")) }.show()
        })
        body.addView(btn(act, "Выключить микрофон ПК") { Hub.send("mixer.microphone", "muted" to true) })
        body.addView(btn(act, "Включить микрофон ПК") { Hub.send("mixer.microphone", "muted" to false) })
        val items = message.optJSONArray("items") ?: return
        if (items.length() == 0) body.addView(muted(act, "Запусти звук в нужном приложении на ПК"))
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val panel = card(act, 12)
            panel.addView(tv(act, item.optString("name"), 16f, T.text, true))
            val label = tv(act, item.optInt("volume").toString() + "%", 13f)
            val bar = SeekBar(act).apply {
                max = 100
                progress = item.optInt("volume")
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { label.text = "$p%" }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        Hub.send("mixer.set", "device" to item.optString("device"), "id" to item.optString("id"), "volume" to s.progress, "muted" to false)
                    }
                })
            }
            panel.addView(label)
            panel.addView(bar)
            panel.addView(switchRow(act, "Без звука", "", item.optBoolean("muted")) {
                Hub.send("mixer.set", "device" to item.optString("device"), "id" to item.optString("id"), "volume" to bar.progress, "muted" to it)
            })
            body.addView(panel, lp(t = 8, ctx = act))
        }
    }
}
