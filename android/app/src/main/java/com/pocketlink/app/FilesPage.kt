package com.pocketlink.app

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Вкладка «Файлы»: проводник по ПК, передачи, буфер обмена. */
class FilesPage(act: MainActivity) : Page(act) {
    private val seg = Segmented(act, listOf("Файлы ПК", "Передачи", "Буфер")) { setMode(it) }
    private val body = android.widget.FrameLayout(act)
    private val browser = vbox(act)
    private val transfers = vbox(act)
    private val clip = vbox(act)
    private var path = ""
    private var parent: String? = null
    private var items: List<JSONObject> = emptyList()
    private var roots: List<JSONObject> = emptyList()
    private var error = ""
    private var loaded = false
    private var lastClip = ""
    private var mode = 0
    fun openTransfers() { setMode(1) }

    override val root: View = vbox(act).also { r ->
        r.addView(pageHeading(act, "Файлы"), lp(l = 12, r = 12, ctx = act))
        r.addView(seg.view, lp(l = 12, r = 12, b = 8, ctx = act))
        for (v in listOf(browser, transfers, clip)) {
            val sv = ScrollView(act).apply { isFillViewport = true; addView(v) }
            body.addView(sv)
        }
        r.addView(body, lp(h = 0, weight = 1f, l = 10, r = 10, ctx = act))
        setMode(0)
    }

    private fun setMode(i: Int) {
        mode = i
        seg.select(i)
        for (c in 0 until body.childCount) body.getChildAt(c).visibility = if (c == i) View.VISIBLE else View.GONE
        when (i) {
            0 -> { renderBrowser(); if (!loaded && Hub.state == Hub.State.ONLINE && Hub.can("files")) list("") }
            1 -> renderTransfers()
            2 -> renderClip()
        }
    }

    private fun list(p: String) {
        Hub.send(JSONObject().put("t", "fs.list").put("path", p))
    }

    // ---------- проводник ----------

    private fun renderBrowser() {
        browser.removeAllViews()
        if (Hub.state != Hub.State.ONLINE) { browser.addView(emptyState(act, "Файлы твоего компьютера", "Подключи ПК, чтобы открыть папки и передавать файлы.", "Подключить") { act.showSettings() }); return }
        if (!Hub.can("files")) { browser.addView(muted(act, "На ПК запрещён доступ к файлам (Настройки → Разрешения)").apply { setPadding(0, act.dp(24), 0, 0) }); return }

        val bar = hbox(act)
        bar.addView(iconBtn(act, R.drawable.ic_upload, 44, Color.WHITE, T.accent) { act.pickFiles() }, lp(-2, -2, r = 8, ctx = act))
        bar.addView(tv(act, if (path.isEmpty()) "Этот компьютер" else path, 13f, T.muted).apply {
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.START
        }, lp(0, weight = 1f))
        bar.addView(iconBtn(act, R.drawable.ic_refresh, 40) { list(path) })
        browser.addView(bar, lp(t = 6, b = 6, ctx = act))
        if (error.isNotEmpty()) browser.addView(tv(act, error, 13f, T.danger))

        if (path.isNotEmpty()) {
            browser.addView(row(R.drawable.ic_folder, ".. (вверх)", "") { list(parent ?: "") })
        } else {
            for (r in roots) browser.addView(row(R.drawable.ic_folder, r.optString("n"), "быстрый доступ") { list(r.optString("p")) })
        }
        for (e in items) {
            val dir = e.optBoolean("d")
            val p = e.optString("p")
            val name = e.optString("n")
            browser.addView(
                row(if (dir) R.drawable.ic_folder else R.drawable.ic_files, name, if (dir) "" else fmtSize(e.optLong("s")),
                    onLong = if (dir) null else ({ fileMenu(name, p) })) {
                    if (dir) list(p) else confirmDownload(name, p, e.optLong("s"))
                }
            )
        }
    }

    private fun row(icon: Int, title: String, sub: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
        val r = hbox(act, 10).apply { background = ripple(roundBg(T.panel, 12, act)); isClickable = true }
        r.addView(ImageView(act).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(T.muted)
        }, lp(act.dp(24), act.dp(24), r = 12, ctx = act))
        val col = vbox(act)
        col.addView(tv(act, title, 15f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        if (sub.isNotEmpty()) col.addView(muted(act, sub, 12f))
        r.addView(col, lp(0, weight = 1f))
        r.setOnClickListener { onClick() }
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        r.layoutParams = lp(b = 4, ctx = act)
        return r
    }

    private fun confirmDownload(name: String, p: String, size: Long) {
        AlertDialog.Builder(act).setTitle(name).setMessage("Скачать на телефон (${fmtSize(size)})?")
            .setPositiveButton("Скачать") { _, _ -> download(p) }
            .setNeutralButton("Открыть на ПК") { _, _ -> Hub.send(JSONObject().put("t", "fs.open").put("path", p)) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun fileMenu(name: String, p: String) = confirmDownload(name, p, 0)

    private fun download(p: String) {
        Hub.send(JSONObject().put("t", "fs.get").put("path", p))
        Hub.toast("Скачивание началось: вкладка «Передачи»")
    }

    // ---------- передачи ----------

    private fun renderTransfers() {
        transfers.removeAllViews()
        transfers.addView(btn(act, "Отправить файлы на ПК", 1) { act.pickFiles() }, lp(t = 6, b = 8, ctx = act))
        transfers.addView(btn(act, "Отправить папку на ПК") { act.pickFolder() }, lp(b = 8, ctx = act))
        val list = Hub.transfers()
        if (list.isEmpty()) transfers.addView(muted(act, "Передач пока нет. Файлы с ПК сохраняются в Загрузки/PocketLink.").apply { setPadding(0, act.dp(12), 0, 0) })
        for (t in list) {
            val c = card(act, 12)
            val head = hbox(act)
            head.addView(tv(act, (if (t.incoming) "↓ " else "↑ ") + t.name, 14f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, lp(0, weight = 1f))
            val stText = when (t.state) { "queued" -> "ожидает подключения к своему ПК"; "cancelled" -> "отменено"; "done" -> "готово"; "failed" -> "ошибка · продолжится после подключения"; else -> "${if (t.size > 0) t.done * 100 / t.size else 0}%" }
            head.addView(tv(act, stText, 13f, if (t.state == "failed") T.danger else if (t.state == "done") T.ok else T.muted, true))
            c.addView(head)
            c.addView(muted(act, fmtSize(t.size), 12f))
            if (t.state == "active") {
                c.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = if (t.size > 0) (t.done * 1000 / t.size).toInt() else 0
                }, lp(t = 6, ctx = act))
            }
            if (t.id.startsWith("u")) {
                if (t.state == "active" || t.state == "queued" || t.state == "failed") c.addView(btn(act, "Отменить") { Hub.cancelUpload(t.id) })
                if (t.state == "failed" || t.state == "queued") c.addView(btn(act, "Продолжить передачу") { Hub.retryUpload(t.id) })
            }
            if (t.id.startsWith("d") && t.state == "active")
                c.addView(btn(act, "Отменить загрузку") { Hub.cancelUpload(t.id) })
            transfers.addView(c, lp(b = 8, ctx = act))
        }
    }

    // ---------- буфер ----------

    private fun renderClip() {
        clip.removeAllViews()
        clip.addView(btn(act, "Отправить буфер телефона на ПК", 1) {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = try { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() } catch (_: Exception) { null }
            Hub.sendClipboardManually(text.orEmpty())
        }, lp(t = 6, ctx = act))
        val c = card(act)
        c.addView(muted(act, "Последнее с ПК", 12f))
        c.addView(tv(act, lastClip.ifEmpty { "—" }, 14f).apply { setTextIsSelectable(true) }, lp(t = 4, ctx = act))
        clip.addView(c, lp(t = 10, ctx = act))
        clip.addView(muted(act, "ПК → телефон приходит автоматически. На ПК можно отправить буфер кнопкой или плиткой «Буфер на ПК». Автоотправка включается в настройках телефона.", 12f).apply { setPadding(act.dp(4), act.dp(10), 0, 0) })
    }

    override fun onShow() { setMode(mode) }

    override fun onHub(ev: String, data: Any?) {
        when (ev) {
            "json" -> {
                val m = data as? JSONObject ?: return
                if (m.optString("t") == "fs.list") {
                    path = m.optString("path")
                    parent = if (m.isNull("parent")) null else m.optString("parent")
                    error = m.optString("error")
                    items = (0 until (m.optJSONArray("items")?.length() ?: 0)).mapNotNull { m.optJSONArray("items")?.optJSONObject(it) }
                    roots = (0 until (m.optJSONArray("roots")?.length() ?: 0)).mapNotNull { m.optJSONArray("roots")?.optJSONObject(it) }
                    loaded = true
                    if (mode == 0) renderBrowser()
                }
            }
            "transfer" -> if (mode == 1) renderTransfers()
            "clip" -> { lastClip = data as? String ?: ""; if (mode == 2) renderClip() }
            "state", "config" -> if (mode == 0) { loaded = false; setMode(0) }
        }
    }
}
