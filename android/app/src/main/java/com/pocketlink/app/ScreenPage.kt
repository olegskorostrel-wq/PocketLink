package com.pocketlink.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Показывает кадры экрана ПК; тап = клик, долгий тап = правый клик. */
@SuppressLint("ClickableViewAccessibility")
class PcScreenView(context: Context) : View(context) {
    var onTap: ((x: Float, y: Float, button: Int) -> Unit)? = null
    private var bmp: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 148, 165); textSize = 14f * resources.displayMetrics.density; textAlign = Paint.Align.CENTER
    }
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-decode").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    var hint = "Экран ПК"

    private var generation = 0
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var multipleTouch = false
    private var previousX = 0f
    private var previousY = 0f

    fun clear() {
        generation++
        bmp = null
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    /** Кадры, пришедшие пока декодируется предыдущий, пропускаются: на экране всегда самое свежее. */
    fun push(jpeg: ByteArray) {
        if (!busy.compareAndSet(false, true)) return
        val frameGeneration = generation
        decoder.execute {
            try {
                val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (b != null) post {
                    if (generation == frameGeneration) { bmp = b; invalidate() }
                    else b.recycle()
                }
            } catch (_: Throwable) {
            } finally { busy.set(false) }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(if (bmp == null) T.panel else Color.BLACK)
        val b = bmp
        if (b == null) { c.drawText(hint, width / 2f, height / 2f, textPaint); return }
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        val w = b.width * s; val h = b.height * s
        offsetX = offsetX.coerceIn(-maxOf(0f, (w - width) / 2), maxOf(0f, (w - width) / 2))
        offsetY = offsetY.coerceIn(-maxOf(0f, (h - height) / 2), maxOf(0f, (h - height) / 2))
        dst.set((width - w) / 2 + offsetX, (height - h) / 2 + offsetY, (width + w) / 2 + offsetX, (height + h) / 2 + offsetY)
        c.drawBitmap(b, null, dst, paint)
    }

    private fun norm(e: MotionEvent): Pair<Float, Float>? {
        if (bmp == null || dst.width() <= 0) return null
        val x = (e.x - dst.left) / dst.width()
        val y = (e.y - dst.top) / dst.height()
        if (x < 0 || x > 1 || y < 0 || y > 1) return null
        return x to y
    }

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { norm(e)?.let { onTap?.invoke(it.first, it.second, 0) }; return true }
        override fun onLongPress(e: MotionEvent) { norm(e)?.let { onTap?.invoke(it.first, it.second, 1) } }
        override fun onDoubleTap(e: MotionEvent): Boolean { norm(e)?.let { onTap?.invoke(it.first, it.second, 0); onTap?.invoke(it.first, it.second, 0) }; return true }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) multipleTouch = false
        if (e.pointerCount > 1) {
            if (!multipleTouch) {
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                gd.onTouchEvent(cancel)
                cancel.recycle()
            }
            multipleTouch = true
            val x = (e.getX(0) + e.getX(1)) / 2
            val y = (e.getY(0) + e.getY(1)) / 2
            if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                offsetX += x - previousX
                offsetY += y - previousY
                invalidate()
            }
            previousX = x
            previousY = y
        } else if (!multipleTouch) gd.onTouchEvent(e)
        return true
    }
}

/** Вкладка «Экран»: трансляция экрана ПК с касаниями. */
class ScreenPage(act: MainActivity) : Page(act) {
    private val view = PcScreenView(act)
    private val status = muted(act, "")
    private var running = false
    private var shown = false
    private lateinit var heading: View
    private lateinit var controls: View
    override val hasSettings = true
    override fun openSettings() = FunctionSettings.screen(act) { if (running) { stop(); start() } }
    fun orientationChanged() = layoutMode()
    private fun layoutMode() {
        val full = shown && running && act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        act.setScreenImmersive(full)
        if (::heading.isInitialized) heading.visibility = if (full) View.GONE else View.VISIBLE
        if (::controls.isInitialized) controls.visibility = if (full) View.GONE else View.VISIBLE
        (view.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply { val pad = if (full) 0 else act.dp(12); setMargins(pad, 0, pad, pad); view.layoutParams = this }
        view.clipToOutline = !full
    }
    private lateinit var toggle: TextView

    override val root: View = android.widget.LinearLayout(act).also { r ->
        r.orientation = android.widget.LinearLayout.VERTICAL
        heading = pageHeading(act, "Экран ПК")
        r.addView(heading, lp(l = 12, r = 12, ctx = act))
        val bar = hbox(act, 8)
        toggle = btn(act, "Старт", 1) { if (running) stop() else start() }
        bar.addView(toggle, lp(-2, -2, r = 8, ctx = act))
        bar.addView(btn(act, "Монитор") { Hub.send("screen.monitors") }, lp(-2, -2, r = 8, ctx = act))
        bar.addView(status, lp(0, weight = 1f))
        controls = bar
        r.addView(bar)
        view.background = roundBg(T.panel, 20, act, T.line, 1)
        view.clipToOutline = true
        r.addView(view, lp(h = 0, weight = 1f, l = 12, r = 12, b = 12, ctx = act))
        view.onTap = { x, y, b ->
            if (running && Prefs.screenControl) Hub.send(JSONObject().put("t", "screen.tap").put("x", x.toDouble()).put("y", y.toDouble()).put("btn", b))
        }
    }

    private fun start() {
        if (Hub.state != Hub.State.ONLINE) { Hub.toast("Нет связи с ПК"); return }
        if (!Hub.can("screen")) { Hub.toast("Просмотр экрана запрещён на ПК"); return }
        running = true
        toggle.text = "Стоп"
        status.text = "Подключаюсь к экрану…"
        view.hint = "Жду кадр…"
        view.clear()
        Hub.send(JSONObject().put("t", "screen.start").put("w", Prefs.screenWidth).put("q", Prefs.screenQuality).put("fps", Prefs.screenFps).put("mon", Prefs.screenMonitor))
        view.invalidate()
        layoutMode()
    }

    private fun stop() {
        if (running) Hub.send(JSONObject().put("t", "screen.stop"))
        running = false
        toggle.text = "Старт"
        status.text = ""
        view.hint = "Экран ПК"
        view.clear()
        layoutMode()
    }

    override fun onShow() { shown = true; layoutMode(); if (!running) status.text = "Поверните телефон боком для полного экрана. Настройки — ⚙." }
    override fun onHide() { shown = false; if (running) stop(); act.setScreenImmersive(false) }

    override fun onHub(ev: String, data: Any?) {
        when (ev) {
            "json" -> {
                val message = data as? JSONObject ?: return
                if (message.optString("t") == "screen.monitors") {
                    val list = message.optJSONArray("list") ?: return
                    val labels = Array(list.length()) { i -> list.getJSONObject(i).optString("name") }
                    android.app.AlertDialog.Builder(act).setTitle("Экран ПК").setItems(labels) { _, i ->
                        Prefs.screenMonitor = list.getJSONObject(i).optInt("id")
                        if (running) { stop(); start() }
                    }.show()
                }
            }
            "screen" -> if (running) { (data as? ByteArray)?.let { view.push(it) }; status.text = "" }
            "state" -> if (Hub.state != Hub.State.ONLINE && running) stop()
        }
    }
}
