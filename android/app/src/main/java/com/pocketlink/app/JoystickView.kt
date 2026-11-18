package com.pocketlink.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

@SuppressLint("ClickableViewAccessibility")
class JoystickView(context: Context, private val changed: (Int, Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x = 0f
    private var y = 0f
    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * 0.43f
        paint.color = T.panel
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.color = T.accent
        canvas.drawCircle(width / 2f + x * radius, height / 2f + y * radius, radius * 0.28f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val radius = (minOf(width, height) * 0.43f).coerceAtLeast(1f)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            x = 0f; y = 0f
        } else {
            x = (event.x - width / 2f) / radius
            y = (event.y - height / 2f) / radius
            val length = hypot(x, y)
            if (length > 1f) { x /= length; y /= length }
            if (length < 0.12f) { x = 0f; y = 0f }
        }
        changed((x * 127).toInt(), (y * 127).toInt())
        invalidate()
        return true
    }
}
