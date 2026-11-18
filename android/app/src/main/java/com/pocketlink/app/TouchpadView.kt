package com.pocketlink.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Тачпад. Плавность держится на трёх вещах:
 *  1) обрабатываем все исторические точки MotionEvent, а не только последнюю (touch-события приходят пачками);
 *  2) ускорение зависит от сглаженной скорости пальца, а не от одного кадра;
 *  3) дробные остатки движения копятся и не теряются (нет «ступенек» на медленном ведении).
 *
 * Жесты: тап = ЛКМ, тап двумя пальцами = ПКМ, два пальца = прокрутка,
 * тап и сразу прижать палец или удержать два пальца = перетаскивание.
 */
class TouchpadView(context: Context) : View(context) {

    interface Callbacks {
        fun onMove(dx: Int, dy: Int)
        fun onScroll(dx: Int, dy: Int)
        fun onButton(button: Int, down: Boolean)
    }

    var callbacks: Callbacks? = null

    /** Общая чувствительность, 1.0 = по умолчанию. */
    var sensitivity = 1.0f

    private val density = resources.displayMetrics.density
    private val slop = 6f * density
    private val tapMaxMs = 220L
    private val dragWindowMs = 260L

    private var tracking = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var speed = 0f // сглаженная скорость, dp/мс

    private var accX = 0f
    private var accY = 0f
    private var accScrollX = 0f
    private var accScrollY = 0f

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var maxPointers = 0
    private var scrolled = false
    private var scrollDist = 0f
    private var cursorLocked = false // после подъёма одного из двух пальцев курсор не дёргаем

    private var lastTapUpT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragging = false
    private var twoFingerDrag = false
    private val holdToDrag = Runnable {
        if (tracking && !moved && !scrolled && !dragging && maxPointers == 2) {
            dragging = true
            twoFingerDrag = true
            moved = true
            callbacks?.onButton(0, true)
        }
    }

    private val surface = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val rect = android.graphics.RectF()

    override fun onDraw(canvas: Canvas) {
        surface.color = T.panel
        border.color = T.line
        val radius = 22f * density
        val inset = border.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, radius, radius, surface)
        canvas.drawRoundRect(rect, radius, radius, border)

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.line; alpha = 130 }
        val step = 22f * density
        var x = step
        while (x < width) { var y = step; while (y < height) { canvas.drawCircle(x, y, density, grid); y += step }; x += step }
        val cy = height / 2f
        val pointer = context.getDrawable(R.drawable.ic_pointer)?.mutate()
        pointer?.setTint(T.accent)
        pointer?.setBounds((width / 2f - 16 * density).toInt(), (cy - 46 * density).toInt(), (width / 2f + 16 * density).toInt(), (cy - 14 * density).toInt())
        pointer?.draw(canvas)
        hint.color = T.muted
        hint.typeface = UI_MEDIUM
        canvas.drawText("Сенсорная панель", width / 2f, cy + 20 * density, hint)
        hint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText("Два пальца — прокрутка", width / 2f, cy + 42 * density, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; downT = e.eventTime
                moved = false; scrolled = false; cursorLocked = false; scrollDist = 0f
                maxPointers = 1
                resetTracking(e.x, e.y, e.eventTime)
                val nearLastTap = e.eventTime - lastTapUpT < dragWindowMs &&
                    hypot(e.x - lastTapX, e.y - lastTapY) < slop * 4
                if (nearLastTap) {
                    dragging = true
                    callbacks?.onButton(0, true)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) {
                    // перешли в режим прокрутки: точка отсчёта — центр двух пальцев
                    resetTracking(centerX(e), centerY(e), e.eventTime)
                    accScrollX = 0f; accScrollY = 0f
                    if (!dragging && !moved) postDelayed(holdToDrag, 380L)
                } else {
                    removeCallbacks(holdToDrag)
                    if (twoFingerDrag) stopDrag()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !cursorLocked) {
                    for (i in 0 until e.historySize) {
                        moveSample(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                    }
                    moveSample(e.x, e.y, e.eventTime)
                } else if (e.pointerCount == 2) {
                    if (twoFingerDrag) moveSample(centerX(e), centerY(e), e.eventTime)
                    else if (!cursorLocked) scrollSample(centerX(e), centerY(e), e.eventTime)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(holdToDrag)
                if (twoFingerDrag) stopDrag()
                if (e.pointerCount == 2) cursorLocked = true // остался один палец, пока не отпустит, курсор стоит
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdToDrag)
                if (dragging) {
                    stopDrag()
                    lastTapUpT = 0L
                } else if (!moved && !scrolled && e.eventTime - downT < tapMaxMs) {
                    if (maxPointers == 1) {
                        callbacks?.onButton(0, true)
                        callbacks?.onButton(0, false)
                        lastTapUpT = e.eventTime
                        lastTapX = e.x; lastTapY = e.y
                    } else if (maxPointers == 2) {
                        callbacks?.onButton(1, true)
                        callbacks?.onButton(1, false)
                    }
                }
                tracking = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdToDrag)
                stopDrag()
                tracking = false
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        // если экран закрыли во время перетаскивания, ЛКМ на ПК не должна остаться зажатой
        removeCallbacks(holdToDrag)
        stopDrag()
        tracking = false
        super.onDetachedFromWindow()
    }

    private fun stopDrag() {
        if (dragging) callbacks?.onButton(0, false)
        dragging = false
        twoFingerDrag = false
    }

    private fun centerX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun centerY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    private fun resetTracking(x: Float, y: Float, t: Long) {
        tracking = true
        lastX = x; lastY = y; lastT = t
        speed = 0f
    }

    private fun moveSample(x: Float, y: Float, t: Long) {
        if (!tracking) return
        if (!moved) {
            // небольшая «мёртвая зона», чтобы дрожание пальца при тапе не сдвигало курсор
            if (hypot(x - downX, y - downY) < slop && !dragging) return
            moved = true
            lastX = x; lastY = y; lastT = t
            return
        }
        val dtMs = (t - lastT).coerceAtLeast(1L)
        val dxDp = (x - lastX) / density
        val dyDp = (y - lastY) / density
        lastX = x; lastY = y; lastT = t

        val inst = hypot(dxDp, dyDp) / dtMs
        // сглаживание привязано ко времени, а не к числу точек: на 60 и 120 Гц ощущается одинаково
        val k = (dtMs / 40f).coerceIn(0.05f, 1f)
        speed += (inst - speed) * k
        val gain = gain(speed) * sensitivity * BASE_SCALE

        accX += dxDp * gain
        accY += dyDp * gain
        val sx = accX.toInt()
        val sy = accY.toInt()
        if (sx != 0 || sy != 0) {
            accX -= sx; accY -= sy
            callbacks?.onMove(sx, sy)
        }
    }

    private fun scrollSample(x: Float, y: Float, t: Long) {
        if (!tracking) return
        val dxDp = (x - lastX) / density
        val dyDp = (y - lastY) / density
        lastX = x; lastY = y; lastT = t
        scrollDist += hypot(dxDp, dyDp)
        if (scrollDist > 4f) {
            scrolled = true
            removeCallbacks(holdToDrag)
        }
        if (!scrolled) return
        // как на телефоне: палец вниз = содержимое едет вниз (колесо «от себя»)
        accScrollY += dyDp * SCROLL_PER_DP
        accScrollX -= dxDp * SCROLL_PER_DP
        val sy = accScrollY.toInt()
        val sx = accScrollX.toInt()
        if (sx != 0 || sy != 0) {
            accScrollX -= sx; accScrollY -= sy
            callbacks?.onScroll(sx, sy)
        }
    }

    /** Кривая ускорения: на медленном ведении почти 1:1 (точность), на быстром до ~3.5x. */
    private fun gain(v: Float): Float {
        val x = min(v / 2.2f, 1f)
        val smooth = x * x * (3f - 2f * x)
        return 0.9f + 2.6f * smooth
    }

    private companion object {
        const val BASE_SCALE = 1.7f      // пикселей ПК на dp пальца при gain = 1
        const val SCROLL_PER_DP = 6.0f   // единиц колеса (120 = щелчок) на dp, 20 dp пальца = один щелчок
    }
}
