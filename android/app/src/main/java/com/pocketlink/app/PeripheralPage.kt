package com.pocketlink.app

import android.view.View
import org.json.JSONArray
import org.json.JSONObject

class PeripheralPage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 12)
    override val root: View = android.widget.ScrollView(act).apply { addView(body) }
    override fun onShow() { render(Hub.status?.optJSONArray("peripherals")); Hub.send("peripherals.get") }
    private fun render(items: JSONArray?) {
        body.removeAllViews(); body.addView(pageHeading(act, "Мышь и наушники"))
        body.addView(btn(act, "Обновить заряд", 1) { Hub.send("peripherals.get"); Hub.toast("Проверяю приёмники на ПК…") })
        body.addView(muted(act, "Приёмники 2,4 ГГц должны быть подключены к ПК, устройства включены. Проценты показываются только при корректном ответе устройства."), lp(t = 8, ctx = act))
        if (items == null || items.length() == 0) body.addView(muted(act, "Нет поддерживаемых приёмников или ещё идёт первый опрос. Проверьте подключение ПК."), lp(t = 12, ctx = act))
        else for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            body.addView(card(act, 12).apply {
                addView(tv(act, item.optString("name"), 17f, T.text, true))
                addView(tv(act, if (item.isNull("battery")) "Заряд —" else "Заряд ${item.optInt("battery")}%", 23f, T.accent, true))
                addView(muted(act, item.optString("status")))
            }, lp(t = 10, ctx = act))
        }
    }
    override fun onHub(ev: String, data: Any?) {
        if (ev == "status" && data is JSONObject) render(data.optJSONArray("peripherals"))
        if (ev == "json" && data is JSONObject && data.optString("t") == "peripherals.state") render(data.optJSONArray("items"))
    }
}
