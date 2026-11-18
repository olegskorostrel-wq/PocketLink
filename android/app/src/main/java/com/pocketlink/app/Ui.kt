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
    var accentSoft = 0; var dangerSoft = 0; var okSoft = 0
    var accent = Color.rgb(173, 143, 250)
    var primary = Color.rgb(116, 70, 216)
    val danger = Color.rgb(228, 84, 84)
    val ok = Color.rgb(46, 184, 114)

    private fun mix(a: Int, b: Int, fraction: Float) = Color.rgb(
        (Color.red(a) * (1 - fraction) + Color.red(b) * fraction).toInt(),
        (Color.green(a) * (1 - fraction) + Color.green(b) * fraction).toInt(),
        (Color.blue(a) * (1 - fraction) + Color.blue(b) * fraction).toInt())

    fun update(ctx: Context) {
        dark = when (Prefs.themeMode) {
            "dark" -> true
            "light" -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (dark) {
            bg = Color.rgb(16, 15, 22); panel = Color.rgb(28, 25, 37); panel2 = Color.rgb(39, 35, 51); line = Color.rgb(60, 53, 76)
            text = Color.rgb(241, 236, 248); muted = Color.rgb(172, 162, 189)
            accent = Color.rgb(173, 143, 250)
            accentSoft = Color.argb(42, 173, 143, 250)
        } else {
            bg = Color.rgb(247, 244, 252); panel = Color.WHITE; panel2 = Color.rgb(239, 233, 249); line = Color.rgb(223, 213, 238)
            text = Color.rgb(35, 27, 50); muted = Color.rgb(111, 98, 132)
            accent = Color.rgb(112, 65, 200)
            accentSoft = Color.argb(22, 112, 65, 200)
        }
        primary = Color.parseColor(when (Prefs.themeAccent) {
            "blue" -> "#3268D9"
            "green" -> "#187C61"
            "orange" -> "#B75B19"
            "pink" -> "#B63D76"
            "graphite" -> "#536173"
            else -> "#7847D8"
        })
        accent = if (dark) mix(primary, Color.WHITE, .40f) else primary
        accentSoft = Color.argb(if (dark) 38 else 22, Color.red(accent), Color.green(accent), Color.blue(accent))
        bg = mix(if (dark) Color.rgb(16, 17, 22) else Color.rgb(248, 248, 251), primary, .025f)
        panel = mix(if (dark) Color.rgb(26, 27, 34) else Color.WHITE, primary, .035f)
        panel2 = mix(if (dark) Color.rgb(37, 38, 47) else Color.rgb(238, 239, 244), primary, .06f)
        line = mix(if (dark) Color.rgb(56, 57, 68) else Color.rgb(219, 221, 229), primary, .07f)
        dangerSoft = Color.argb(if (dark) 40 else 28, 228, 84, 84)
        okSoft = Color.argb(if (dark) 40 else 30, 46, 184, 114)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(ctx.dp(strokeDp), strokeColor)
    }

/** Шрифт для заголовков и подписей: средняя насыщенность выглядит аккуратнее жирного. */
val UI_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
        if (bold) typeface = UI_MEDIUM
    }

fun muted(ctx: Context, text: CharSequence, sizeSp: Float = 13f) = tv(ctx, text, sizeSp, T.muted)

fun card(ctx: Context, padDp: Int = 14): LinearLayout = vbox(ctx, padDp).apply {
    background = roundBg(T.panel, 20, ctx, T.line, 1)
}

/** kind: 0 обычная, 1 основная (акцент), 2 опасная, 3 контурная. */
fun btn(ctx: Context, text: String, kind: Int = 0, onClick: () -> Unit): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    typeface = UI_MEDIUM
    gravity = Gravity.CENTER
    val fg = when (kind) { 1 -> Color.WHITE; 2 -> T.danger; else -> T.text }
    setTextColor(fg)
    val shape = when (kind) {
        1 -> roundBg(T.primary, 15, ctx)
        2 -> roundBg(T.dangerSoft, 14, ctx)
        3 -> roundBg(0, 14, ctx, T.line, 1)
        else -> roundBg(T.panel2, 14, ctx)
    }
    background = ripple(shape)
    minHeight = ctx.dp(46)
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
    background = roundBg(T.panel2, 14, ctx, T.line, 1)
    minHeight = ctx.dp(48)
    setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
    inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    else InputType.TYPE_CLASS_TEXT
}

/** Строка с переключателем. */
fun switchRow(ctx: Context, title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val row = hbox(ctx).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
    val col = vbox(ctx)
    col.addView(tv(ctx, title, 15f))
    if (desc != null) col.addView(muted(ctx, desc, 12f), lp(t = 2, ctx = ctx))
    row.addView(col, lp(0, weight = 1f, r = 12, ctx = ctx))
    val sw = Switch(ctx).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, if (T.dark) Color.rgb(176, 182, 196) else Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(T.accent, if (T.dark) Color.rgb(62, 68, 86) else Color.rgb(196, 202, 216)))
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    row.addView(sw)
    return row
}

fun sectionTitle(ctx: Context, text: String): TextView =
    tv(ctx, text.uppercase(), 11.5f, T.muted, true).apply { setPadding(ctx.dp(4), ctx.dp(18), 0, ctx.dp(8)); letterSpacing = 0.08f }

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
    open val hasSettings: Boolean = false
    open fun openSettings() {}
    protected val ctx: Context get() = act
}

fun FrameLayout.fill(v: View) = addView(v, FrameLayout.LayoutParams(-1, -1))

/** Compact navigable settings row instead of an oversized button stack. */
fun actionRow(ctx: Context, title: String, subtitle: String, onClick: () -> Unit): View = hbox(ctx, 12).apply {
    minimumHeight = ctx.dp(58)
    background = ripple(roundBg(T.panel2, 14, ctx))
    val col = vbox(ctx)
    col.addView(tv(ctx, title, 15f, T.text, true))
    col.addView(muted(ctx, subtitle, 12f), lp(t = 2, ctx = ctx))
    addView(col, lp(0, weight = 1f, r = 8, ctx = ctx))
    addView(tv(ctx, "›", 24f, T.muted))
    setOnClickListener { onClick() }
}

/** Переключатель режимов в одну линию (сегменты в общей подложке). */
class Segmented(private val ctx: Context, names: List<String>, private val onPick: (Int) -> Unit) {
    val view: LinearLayout = hbox(ctx).apply {
        background = roundBg(T.panel2, 14, ctx)
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
    }
    private val items: List<TextView> = names.mapIndexed { i, n ->
        tv(ctx, n, 13f, T.muted, true).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(38)
            setSingleLine()
            setOnClickListener { onPick(i) }
            view.addView(this, lp(0, weight = 1f))
        }
    }

    fun select(sel: Int) {
        items.forEachIndexed { i, t ->
            val on = i == sel
            t.setTextColor(if (on) Color.WHITE else T.muted)
            t.background = if (on) roundBg(T.primary, 11, ctx) else null
        }
    }
}

/** Плашка-подсказка вместо пустого экрана: коротко, с необязательной кнопкой действия. */
fun infoBanner(ctx: Context, text: String, actionText: String? = null, onAction: (() -> Unit)? = null): View = hbox(ctx, 12).apply {
    background = roundBg(T.panel2, 14, ctx)
    val dot = View(ctx).apply { background = roundBg(T.muted, 4, ctx) }
    addView(dot, lp(ctx.dp(8), ctx.dp(8), r = 10, ctx = ctx))
    addView(muted(ctx, text, 12.5f), lp(0, weight = 1f))
    if (actionText != null && onAction != null) {
        addView(tv(ctx, actionText, 13f, T.accent, true).apply {
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(4), ctx.dp(8))
            setOnClickListener { onAction() }
        })
    }
}

/** Пустое состояние по центру экрана с необязательной кнопкой. */
fun emptyState(ctx: Context, title: String, sub: String, actionText: String? = null, onAction: (() -> Unit)? = null): View =
    vbox(ctx, 24).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(24), ctx.dp(56), ctx.dp(24), ctx.dp(24))
        val badge = FrameLayout(ctx).apply { background = roundBg(T.accentSoft, 28, ctx) }
        badge.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_remote)
            imageTintList = ColorStateList.valueOf(T.accent)
        }, FrameLayout.LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.CENTER))
        addView(badge, lp(ctx.dp(56), ctx.dp(56)))
        addView(tv(ctx, title, 17f, T.text, true).apply { gravity = Gravity.CENTER }, lp(t = 14, ctx = ctx))
        addView(muted(ctx, sub, 13f).apply { gravity = Gravity.CENTER }, lp(t = 6, ctx = ctx))
        if (actionText != null && onAction != null)
            addView(btn(ctx, actionText, 1) { onAction() }, lp(-2, -2, t = 16, ctx = ctx))
    }

fun pageHeading(ctx: Context, title: String, subtitle: String? = null): View = vbox(ctx).apply {
    addView(tv(ctx, title, 26f, T.text, true))
    if (subtitle != null) addView(muted(ctx, subtitle, 13f), lp(t = 4, ctx = ctx))
    setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(14))
}

fun pageHeading(ctx: Context, title: String): View = tv(ctx, title, 25f, T.text, true).apply {
    setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(12))
}
