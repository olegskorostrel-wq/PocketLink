package com.pocketlink.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Цвета темы (светлая/тёмная по настройке телефона). */
object T {
    var dark = true
    var bg = 0; var panel = 0; var panel2 = 0; var line = 0
    var text = 0; var muted = 0
    val accent = Color.rgb(74, 128, 240)
    val danger = Color.rgb(225, 80, 80)
    val ok = Color.rgb(40, 180, 110)

    fun update(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            bg = Color.rgb(16, 18, 24); panel = Color.rgb(28, 31, 40); panel2 = Color.rgb(40, 44, 56); line = Color.rgb(52, 57, 70)
            text = Color.rgb(236, 238, 244); muted = Color.rgb(140, 148, 165)
        } else {
            bg = Color.rgb(243, 245, 250); panel = Color.WHITE; panel2 = Color.rgb(232, 236, 245); line = Color.rgb(220, 225, 236)
            text = Color.rgb(24, 28, 40); muted = Color.rgb(104, 112, 130)
        }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = ctx.dp(radiusDp).toFloat() }

fun ripple(base: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
    RippleDrawable(ColorStateList.valueOf(Color.argb(50, 128, 128, 128)), base, null)

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
       l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, ctx: Context? = null): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    if (ctx != null) p.setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
    return p
}

fun vbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
}

fun hbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
}

fun tv(ctx: Context, text: CharSequence, sizeSp: Float = 15f, color: Int = T.text, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun muted(ctx: Context, text: CharSequence, sizeSp: Float = 13f) = tv(ctx, text, sizeSp, T.muted)

fun card(ctx: Context, padDp: Int = 14): LinearLayout = vbox(ctx, padDp).apply {
    background = roundBg(T.panel, 16, ctx)
}

/** kind: 0 обычная, 1 основная (акцент), 2 опасная. */
fun btn(ctx: Context, text: String, kind: Int = 0, onClick: () -> Unit): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    val fg = when (kind) { 1 -> Color.WHITE; 2 -> T.danger; else -> T.text }
    setTextColor(fg)
    val bgc = when (kind) { 1 -> T.accent; else -> T.panel2 }
    background = ripple(roundBg(bgc, 12, ctx))
    minHeight = ctx.dp(44)
    setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
    setOnClickListener { onClick() }
}

fun iconBtn(ctx: Context, res: Int, sizeDp: Int = 44, tint: Int = T.text, bgColor: Int = T.panel2, onClick: () -> Unit): ImageView =
    ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = ctx.dp(10)
        setPadding(pad, pad, pad, pad)
        background = ripple(roundBg(bgColor, sizeDp / 2, ctx))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        setOnClickListener { onClick() }
    }

fun input(ctx: Context, hint: String, multiline: Boolean = false): EditText = EditText(ctx).apply {
    this.hint = hint
    setHintTextColor(T.muted)
    setTextColor(T.text)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    background = roundBg(T.panel2, 12, ctx)
    setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
    inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    else InputType.TYPE_CLASS_TEXT
}

/** Строка с переключателем. */
fun switchRow(ctx: Context, title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val row = hbox(ctx).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
    val col = vbox(ctx)
    col.addView(tv(ctx, title, 15f))
    if (desc != null) col.addView(muted(ctx, desc, 12f))
    row.addView(col, lp(0, weight = 1f))
    val sw = Switch(ctx).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    row.addView(sw)
    return row
}

fun sectionTitle(ctx: Context, text: String): TextView =
    tv(ctx, text.uppercase(), 12f, T.muted, true).apply { setPadding(ctx.dp(4), ctx.dp(14), 0, ctx.dp(6)); letterSpacing = 0.06f }

fun spacer(ctx: Context, hDp: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, ctx.dp(hDp)) }

fun fmtSize(n: Long): String = when {
    n < 1024 -> "$n Б"
    n < 1024 * 1024 -> "${n / 1024} КБ"
    n < 1024L * 1024 * 1024 -> String.format("%.1f МБ", n / 1048576.0)
    else -> String.format("%.2f ГБ", n / 1073741824.0)
}

/** Базовый класс вкладки. */
abstract class Page(val act: MainActivity) {
    abstract val root: View
    open fun onShow() {}
    open fun onHide() {}
    open fun onHub(ev: String, data: Any?) {}
    protected val ctx: Context get() = act
}

fun FrameLayout.fill(v: View) = addView(v, FrameLayout.LayoutParams(-1, -1))

/** Compact navigable settings row instead of an oversized button stack. */
fun actionRow(ctx: Context, title: String, subtitle: String, onClick: () -> Unit): View = hbox(ctx, 12).apply {
    minimumHeight = ctx.dp(60)
    background = ripple(roundBg(T.panel2, 12, ctx))
    val col = vbox(ctx)
    col.addView(tv(ctx, title, 15f, T.text, true))
    col.addView(muted(ctx, subtitle, 12f), lp(t = 3, ctx = ctx))
    addView(col, lp(0, weight = 1f, r = 8, ctx = ctx))
    addView(tv(ctx, "›", 25f, T.muted))
    setOnClickListener { onClick() }
}
