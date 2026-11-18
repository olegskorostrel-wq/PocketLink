package com.pocketlink.app

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Вкладка «Пульт»: тачпад, клавиатура, медиа и громкость, показ презентаций. */
class RemotePage(act: MainActivity) : Page(act) {
    private val pad = TouchpadView(act)
    private val panel = FrameLayout(act)
    private val seg = Segmented(act, listOf("Клавиши", "Медиа", "Показ")) { setMode(it) }
    private var mode = 0
    private var seekTracking = false

    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var playBtn: android.widget.ImageView
    private lateinit var seek: SeekBar
    private lateinit var volText: TextView

    override val root: View = vbox(act).also { r ->
        r.addView(pageHeading(act, "Пульт"), lp(l = 12, r = 12, ctx = act))
        pad.callbacks = object : TouchpadView.Callbacks {
            override fun onMove(dx: Int, dy: Int) { Hub.conn?.move(dx, dy) }
            override fun onScroll(dx: Int, dy: Int) {
                val direction = if (Prefs.naturalScroll) 1 else -1
                Hub.conn?.scroll(dx * direction, dy * direction)
            }
            override fun onButton(button: Int, down: Boolean) { Hub.conn?.button(button, down) }
        }
        r.addView(pad, lp(h = 0, weight = 1f, l = 12, t = 4, r = 12, ctx = act))

        // кнопки мыши: крайние шире, чтобы попадать большим пальцем
        val mouse = hbox(act)
        mouse.addView(btn(act, "Левая") { click(0) }, lp(0, act.dp(50), 1.2f, r = 8, ctx = act))
        mouse.addView(btn(act, "Колесо") { click(2) }, lp(0, act.dp(50), 0.8f, r = 8, ctx = act))
        mouse.addView(btn(act, "Правая") { click(1) }, lp(0, act.dp(50), 1.2f, ctx = act))
        r.addView(mouse, lp(l = 12, t = 8, r = 12, ctx = act))

        // переключатель панелей
        r.addView(seg.view, lp(l = 12, t = 8, r = 12, ctx = act))
        r.addView(panel, lp(h = act.dp(184), l = 12, t = 4, r = 12, b = 8, ctx = act))
        buildPanels()
        setMode(1)
        if (act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            r.removeAllViews()
            r.orientation = LinearLayout.HORIZONTAL
            r.setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(8))
            r.addView(pad, lp(0, -1, 1f, r = 12, ctx = act))
            val controls = vbox(act)
            controls.addView(mouse, lp(b = 8, ctx = act))
            controls.addView(seg.view, lp(b = 8, ctx = act))
            controls.addView(panel, lp(h = act.dp(184)))
            r.addView(android.widget.ScrollView(act).apply { addView(controls) }, lp(0, -1, 1.2f))
        }
    }

    private fun click(b: Int) {
        val c = Hub.conn ?: return
        c.button(b, true); c.button(b, false)
    }

    private fun key(k: String, vararg mods: String) {
        val m = JSONArray(); mods.forEach { m.put(it) }
        Hub.send(JSONObject().put("t", "key").put("k", k).put("m", m))
    }

    private fun media(cmd: String, v: Int = 0) {
        Hub.send(JSONObject().put("t", "media").put("cmd", cmd).put("v", v))
    }

    private fun setMode(i: Int) {
        mode = i
        seg.select(i)
        for (c in 0 until panel.childCount) panel.getChildAt(c).visibility = if (c == i) View.VISIBLE else View.GONE
    }

    private fun chips(items: List<Triple<String, String, Array<String>>>): View {
        val sv = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
        val row = hbox(act)
        for ((label, k, mods) in items) {
            row.addView(btn(act, label) { key(k, *mods) }.apply {
                minWidth = act.dp(52); minHeight = act.dp(38); textSize = 13f
                setPadding(act.dp(14), act.dp(4), act.dp(14), act.dp(4))
            }, lp(-2, -2, l = 0, r = 6, ctx = act))
        }
        sv.addView(row)
        return sv
    }

    private fun buildPanels() {
        // --- клавиши ---
        val keys = vbox(act)
        val line = hbox(act)
        val et = input(act, "Текст для ввода на ПК").apply { imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine() }
        fun sendText() {
            val s = et.text.toString()
            if (s.isNotEmpty()) { Hub.conn?.sendText(s); et.setText("") }
        }
        et.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false }
        line.addView(et, lp(0, weight = 1f, r = 6, ctx = act))
        line.addView(iconBtn(act, R.drawable.ic_send, 44, android.graphics.Color.WHITE, T.accent) { sendText() })
        keys.addView(line, lp(t = 4, ctx = act))
        val none = arrayOf<String>()
        keys.addView(chips(listOf(
            Triple("Esc", "esc", none), Triple("Tab", "tab", none), Triple("Enter", "enter", none), Triple("⌫", "backspace", none),
            Triple("Del", "delete", none), Triple("Пробел", "space", none), Triple("Win", "win", none))), lp(t = 6, ctx = act))
        keys.addView(chips(listOf(
            Triple("←", "left", none), Triple("↑", "up", none), Triple("↓", "down", none), Triple("→", "right", none),
            Triple("Home", "home", none), Triple("End", "end", none), Triple("PgUp", "pageup", none), Triple("PgDn", "pagedown", none))), lp(t = 6, ctx = act))
        keys.addView(chips(listOf(
            Triple("Ctrl+C", "c", arrayOf("ctrl")), Triple("Ctrl+V", "v", arrayOf("ctrl")), Triple("Ctrl+X", "x", arrayOf("ctrl")),
            Triple("Ctrl+Z", "z", arrayOf("ctrl")), Triple("Ctrl+A", "a", arrayOf("ctrl")), Triple("Alt+Tab", "tab", arrayOf("alt")),
            Triple("Alt+F4", "f4", arrayOf("alt")), Triple("Win+D", "d", arrayOf("win")), Triple("F5", "f5", none), Triple("F11", "f11", none))), lp(t = 6, ctx = act))
        panel.addView(keys)

        // --- медиа ---
        val m = vbox(act)
        titleView = tv(act, "Ничего не играет", 16f, T.text, true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        subView = muted(act, "").apply { gravity = Gravity.CENTER; setSingleLine() }
        m.addView(titleView, lp(t = 6, ctx = act))
        m.addView(subView)
        val ctl = hbox(act).apply { gravity = Gravity.CENTER }
        ctl.addView(iconBtn(act, R.drawable.ic_prev, 48) { media("prev") }, lp(-2, -2, r = 14, ctx = act))
        playBtn = iconBtn(act, R.drawable.ic_play, 56, android.graphics.Color.WHITE, T.accent) { media("play") }
        ctl.addView(playBtn, lp(-2, -2, r = 14, ctx = act))
        ctl.addView(iconBtn(act, R.drawable.ic_next, 48) { media("next") })
        m.addView(ctl, lp(t = 8, ctx = act))
        val vol = hbox(act)
        vol.addView(iconBtn(act, R.drawable.ic_mute, 40) { media("mute") }, lp(-2, -2, r = 6, ctx = act))
        seek = SeekBar(act).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    volText.text = "$p%"
                    if (fromUser) media("vol", p)
                }
                override fun onStartTrackingTouch(s: SeekBar) { seekTracking = true }
                override fun onStopTrackingTouch(s: SeekBar) { seekTracking = false }
            })
        }
        vol.addView(seek, lp(0, weight = 1f))
        volText = muted(act, "0%").apply { minWidth = act.dp(40); gravity = Gravity.END }
        vol.addView(volText)
        m.addView(vol, lp(t = 8, ctx = act))
        panel.addView(m)

        // --- презентация ---
        val p = vbox(act)
        val row = hbox(act)
        row.addView(btn(act, "◀  Назад") { key("left") }, lp(0, h = act.dp(76), weight = 1f, r = 8, ctx = act))
        row.addView(btn(act, "Вперёд  ▶", 1) { key("right") }, lp(0, h = act.dp(76), weight = 1f, ctx = act))
        p.addView(row, lp(t = 6, ctx = act))
        val row2 = hbox(act)
        row2.addView(btn(act, "Показ (F5)") { key("f5") }, lp(0, weight = 1f, r = 8, ctx = act))
        row2.addView(btn(act, "С текущего") { key("f5", "shift") }, lp(0, weight = 1f, r = 8, ctx = act))
        row2.addView(btn(act, "Выход") { key("esc") }, lp(0, weight = 1f, ctx = act))
        p.addView(row2, lp(t = 8, ctx = act))
        panel.addView(p)
    }

    private fun applyStatus() {
        val st = Hub.status ?: return
        if (!::seek.isInitialized) return
        if (!seekTracking) { seek.progress = st.optInt("volume", 0); volText.text = "${seek.progress}%" }
        val md = st.optJSONObject("media")
        if (md != null) {
            titleView.text = md.optString("title").ifEmpty { "Играет" }
            subView.text = md.optString("artist").ifEmpty { md.optString("app") }
            playBtn.setImageResource(if (md.optBoolean("playing")) R.drawable.ic_pause else R.drawable.ic_play)
        } else {
            titleView.text = "Ничего не играет"; subView.text = ""
            playBtn.setImageResource(R.drawable.ic_play)
        }
    }

    override fun onShow() { pad.sensitivity = Prefs.pointerSensitivity; applyStatus(); pad.invalidate() }
    override fun onHub(ev: String, data: Any?) {
        if (ev == "status" || ev == "state") applyStatus()
        if (ev == "state") pad.invalidate()
    }
}
