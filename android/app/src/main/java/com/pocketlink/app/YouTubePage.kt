package com.pocketlink.app

import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class YouTubePage(act: MainActivity) : Page(act) {
    private val title = tv(act, "YouTube на ПК", 17f, T.text, true)
    private val stateText = muted(act, "Подключаю расширение…")
    private val items = vbox(act)
    private var visible = false
    private var resultKey = ""
    private var duration = 0
    private var position = 0
    private val scrubber = SeekBar(act)
    private var dragging = false

    override val root: View = ScrollView(act).apply {
        val body = vbox(act, 12)
        body.addView(title)
        body.addView(stateText)
        val query = input(act, "Найти видео").apply { setSingleLine() }
        body.addView(query)
        body.addView(btn(act, "Найти", 1) { command("search", "query", query.text.toString().trim()) })
        val playback = hbox(act)
        playback.addView(btn(act, "−10 с") { command("seek", "value", maxOf(0, position - 10)) }, lp(0, weight = 1f))
        playback.addView(btn(act, "▶ / Ⅱ") { command("toggle") }, lp(0, weight = 1f))
        playback.addView(btn(act, "+10 с") { command("seek", "value", minOf(duration, position + 10)) }, lp(0, weight = 1f))
        body.addView(playback)
        scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                command("seek", "value", bar.progress)
                dragging = false
            }
        })
        body.addView(scrubber)
        val buttons = hbox(act)
        buttons.addView(btn(act, "Субтитры") { command("captions") }, lp(0, weight = 1f))
        buttons.addView(btn(act, "Театр") { command("theater") }, lp(0, weight = 1f))
        buttons.addView(btn(act, "Далее") { command("next") }, lp(0, weight = 1f))
        body.addView(buttons)
        body.addView(btn(act, "На весь экран ПК") { command("fullscreen") })
        val speed = hbox(act)
        for (rate in listOf(0.5, 1.0, 1.5, 2.0))
            speed.addView(btn(act, rate.toString() + "×") { command("rate", "value", rate) }, lp(0, weight = 1f))
        body.addView(speed)
        body.addView(tv(act, "Громкость YouTube", 14f))
        body.addView(SeekBar(act).apply {
            max = 100
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { command("volume", "value", bar.progress) }
            })
        })
        body.addView(items)
        addView(body)
    }

    private fun command(action: String, key: String? = null, value: Any? = null) {
        if (Hub.state != Hub.State.ONLINE) { Hub.toast("Нет связи с ПК"); return }
        val message = JSONObject().put("t", "youtube.cmd").put("action", action)
        if (key != null) message.put(key, value)
        Hub.send(message)
    }

    override fun onShow() { visible = true; Hub.send("youtube.get") }
    override fun onHide() { visible = false }

    override fun onHub(ev: String, data: Any?) {
        if (!visible || ev != "json") return
        val state = data as? JSONObject ?: return
        if (state.optString("t") != "youtube.state") return
        stateText.text = state.optString("error").ifEmpty {
            if (state.optBoolean("connected")) state.optString("channel") else "Подключи расширение PocketLink в Firefox на ПК"
        }
        title.text = state.optString("title").ifEmpty { "YouTube на ПК" }
        duration = state.optDouble("duration", 0.0).toInt().coerceAtLeast(0)
        position = state.optDouble("position", 0.0).toInt().coerceAtLeast(0)
        if (!dragging) { scrubber.max = duration; scrubber.progress = position }
        val results = state.optJSONArray("results") ?: return
        val nextKey = results.toString()
        if (resultKey == nextKey) return
        resultKey = nextKey
        items.removeAllViews()
        for (index in 0 until minOf(results.length(), 24)) {
            val result = results.optJSONObject(index) ?: continue
            val id = result.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) continue
            val card = card(act, 10)
            val image = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(image, lp(h = act.dp(170)))
            card.addView(tv(act, result.optString("title"), 15f, T.text, true))
            card.addView(muted(act, result.optString("channel"), 12f))
            card.setOnClickListener { command("play", "id", id) }
            items.addView(card, lp(t = 8, ctx = act))
            Hub.work.execute {
                var connection: HttpsURLConnection? = null
                try {
                    connection = URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpsURLConnection
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.instanceFollowRedirects = false
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    Hub.main.post { if (resultKey == nextKey) image.setImageBitmap(bitmap) }
                } catch (_: Exception) {
                } finally { connection?.disconnect() }
            }
        }
    }
}
