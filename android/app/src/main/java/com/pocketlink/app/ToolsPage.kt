package com.pocketlink.app

import android.app.AlertDialog
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Вкладка «ПК»: команды, программы, процессы, питание, включение по сети. */
class ToolsPage(act: MainActivity) : Page(act) {
    private val seg = Segmented(act, listOf("Команды", "Программы", "Процессы", "Питание")) { setMode(it) }
    private val body = android.widget.FrameLayout(act)
    private val views = List(4) { vbox(act) }
    private var mode = 0
    private var apps: List<JSONObject> = emptyList()
    private var procs: List<JSONObject> = emptyList()
    private var appsLoaded = false
    private var commandProfile = "Все"

    private val shortcuts = ShortcutPanel(act) { renderCommands() }

    override val root: View = vbox(act).also { r ->
        r.addView(seg.view, lp(l = 12, t = 4, r = 12, b = 6, ctx = act))
        for (v in views) body.addView(ScrollView(act).apply { isFillViewport = true; addView(v) })
        r.addView(body, lp(h = 0, weight = 1f, l = 12, r = 12, ctx = act))
        setMode(0)
    }

    private fun setMode(i: Int) {
        mode = i
        seg.select(i)
        for (c in 0 until body.childCount) body.getChildAt(c).visibility = if (c == i) View.VISIBLE else View.GONE
        render()
    }

    private fun render() {
        when (mode) {
            0 -> renderCommands()
            1 -> { if (!appsLoaded) Hub.send(JSONObject().put("t", "apps.get")); renderApps() }
            2 -> { Hub.send(JSONObject().put("t", "proc.get")); renderProcs() }
            3 -> renderPower()
        }
    }

    private fun offline(v: android.widget.LinearLayout, need: String?): Boolean {
        v.removeAllViews()
        if (Hub.state != Hub.State.ONLINE) {
            v.addView(emptyState(act, "Нет связи с ПК", "Подключись, чтобы запускать программы, смотреть процессы и управлять питанием.", "Подключить ПК") { act.showSettings() })
            return true
        }
        if (need != null && !Hub.can(need)) {
            v.addView(emptyState(act, "Запрещено на ПК", "Разреши этот доступ в окне PocketLink на компьютере (раздел «Разрешения»)."))
            return true
        }
        return false
    }

    private fun listRow(title: String, sub: String, onClick: () -> Unit): View {
        val r = vbox(act, 14).apply { background = ripple(roundBg(T.panel, 14, act, T.line, 1)); isClickable = true; setOnClickListener { onClick() } }
        r.addView(tv(act, title, 15f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) r.addView(muted(act, sub, 12f), lp(t = 2, ctx = act))
        r.layoutParams = lp(t = 6, ctx = act)
        return r
    }

    private fun renderCommands() {
        val v = views[0]
        v.removeAllViews()
        shortcuts.render(v)
        v.addView(sectionTitle(act, "Инструменты"))
        val tools = card(act, 4)
        tools.addView(actionRow(act, "YouTube", "Поиск и управление видео") { act.openUtility(YouTubePage(act)) })
        tools.addView(actionRow(act, "Микшер", "Звук приложений и аудиовыход") { act.openUtility(MixerPage(act)) }, lp(t = 4, ctx = act))
        tools.addView(actionRow(act, "Окна ПК", "Программы и состояние компьютера") { act.openUtility(DesktopPage(act)) }, lp(t = 4, ctx = act))
        v.addView(tools)
        v.addView(sectionTitle(act, "Команды с компьютера"))
        if (Hub.state != Hub.State.ONLINE) {
            v.addView(muted(act, "Сохранённые на ПК команды появятся после подключения.", 12.5f), lp(l = 4, b = 16, ctx = act))
            return
        }
        if (!Hub.can("commands")) { v.addView(muted(act, "Запуск команд запрещён в настройках ПК")); return }
        val cmds = Hub.commands
        val profiles = linkedSetOf("Все")
        for (i in 0 until cmds.length()) profiles += cmds.optJSONObject(i)?.optString("profile", "Основные") ?: "Основные"
        v.addView(btn(act, "Профиль: $commandProfile", 3) {
            AlertDialog.Builder(act).setTitle("Профиль кнопок").setItems(profiles.toTypedArray()) { _, index ->
                commandProfile = profiles.elementAt(index)
                renderCommands()
            }.show()
        })
        if (cmds.length() == 0) v.addView(muted(act, "Команд нет. Добавь их в окне PocketLink на ПК (вкладка «Команды»).", 12.5f).apply { setPadding(act.dp(4), act.dp(16), 0, 0) })
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            if (commandProfile != "Все" && c.optString("profile", "Основные") != commandProfile) continue
            v.addView(listRow(c.optString("name"), if (c.optString("type") == "keys") "сочетание клавиш" else "запуск") {
                val run = { Hub.send(JSONObject().put("t", "cmd.run").put("id", c.optString("id"))) }
                if (c.optBoolean("confirm")) {
                    AlertDialog.Builder(act).setTitle(c.optString("name")).setMessage("Выполнить на ПК?")
                        .setPositiveButton("Да") { _, _ -> run() }.setNegativeButton("Отмена", null).show()
                } else run()
            })
        }
    }

    private fun renderApps() {
        val v = views[1]
        if (offline(v, "commands")) return
        v.addView(muted(act, if (apps.isEmpty()) "Загружаю список программ…" else "Нажми, чтобы запустить на ПК").apply { setPadding(act.dp(4), act.dp(8), 0, act.dp(4)) })
        for (a in apps) v.addView(listRow(a.optString("n"), "") { Hub.send(JSONObject().put("t", "apps.run").put("id", a.optInt("id"))); Hub.toast("Запускаю: ${a.optString("n")}") })
    }

    private fun renderProcs() {
        val v = views[2]
        if (offline(v, "processes")) return
        v.addView(btn(act, "Обновить список", 3) { Hub.send(JSONObject().put("t", "proc.get")) }, lp(t = 4, b = 2, ctx = act))
        for (p in procs) {
            val title = p.optString("title").ifEmpty { p.optString("n") }
            v.addView(listRow(title, "${p.optString("n")} · ${fmtSize(p.optLong("mem"))}") {
                AlertDialog.Builder(act).setTitle(p.optString("n")).setMessage("Завершить процесс на ПК? Несохранённые данные пропадут.")
                    .setPositiveButton("Завершить") { _, _ ->
                        Hub.send(JSONObject().put("t", "proc.kill").put("pid", p.optInt("pid")))
                        Hub.main.postDelayed({ Hub.send(JSONObject().put("t", "proc.get")) }, 800)
                    }.setNegativeButton("Отмена", null).show()
            })
        }
    }

    private fun power(action: String, title: String, delay: Int = 0) {
        AlertDialog.Builder(act).setTitle(title).setMessage(if (delay > 0) "Через ${delay / 60} мин." else "Выполнить на ПК сейчас?")
            .setPositiveButton("Да") { _, _ -> Hub.send(JSONObject().put("t", "power").put("action", action).put("delay", delay)) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun renderPower() {
        val v = views[3]
        v.removeAllViews()
        // включение по сети работает, даже когда ПК выключен
        val pc = Prefs.lastPc()
        v.addView(sectionTitle(act, "Включить ПК"))
        v.addView(btn(act, "Включить по сети (Wake-on-LAN)", 1) {
            val p = Prefs.lastPc()
            if (p == null) Hub.toast("Сначала подключись к ПК один раз") else Hub.wake(p)
        })
        v.addView(muted(act, if (pc != null) "ПК: ${pc.name}. В BIOS/сетевой карте должно быть включено пробуждение по сети." else "ПК ещё не запомнен.", 12f).apply { setPadding(act.dp(4), act.dp(6), 0, 0) })
        if (Hub.state != Hub.State.ONLINE) return
        if (!Hub.can("power")) { v.addView(muted(act, "Управление питанием запрещено на ПК").apply { setPadding(0, act.dp(16), 0, 0) }); return }
        v.addView(sectionTitle(act, "Управление"))
        val items = listOf(
            Triple("lock", "Заблокировать", 0), Triple("sleep", "Спящий режим", 0), Triple("hibernate", "Гибернация", 0),
            Triple("logoff", "Выйти из системы", 0), Triple("restart", "Перезагрузить", 0), Triple("shutdown", "Выключить", 0)
        )
        items.chunked(2).forEach { pair ->
            val row = hbox(act)
            pair.forEachIndexed { n, (a, t, _) ->
                row.addView(btn(act, t, if (a == "shutdown" || a == "restart") 2 else 0) { power(a, t) }, lp(0, weight = 1f, r = if (n == 0) 8 else 0, ctx = act))
            }
            v.addView(row, lp(b = 8, ctx = act))
        }
        v.addView(sectionTitle(act, "Выключить по таймеру"))
        val row = hbox(act)
        for (min in intArrayOf(15, 30, 60, 120)) {
            row.addView(btn(act, "$min мин") { power("shutdown", "Выключить ПК", min * 60) }, lp(0, weight = 1f, r = if (min == 120) 0 else 6, ctx = act))
        }
        v.addView(row)
        v.addView(btn(act, "Отменить выключение", 3) { Hub.send(JSONObject().put("t", "power").put("action", "abort").put("delay", 0)) }, lp(t = 8, ctx = act))
    }

    override fun onShow() { render() }

    override fun onHub(ev: String, data: Any?) {
        when (ev) {
            "json" -> {
                val m = data as? JSONObject ?: return
                when (m.optString("t")) {
                    "apps.list" -> {
                        appsLoaded = true
                        apps = (0 until (m.optJSONArray("apps")?.length() ?: 0)).mapNotNull { m.optJSONArray("apps")?.optJSONObject(it) }
                        if (mode == 1) renderApps()
                    }
                    "proc.list" -> {
                        procs = (0 until (m.optJSONArray("procs")?.length() ?: 0)).mapNotNull { m.optJSONArray("procs")?.optJSONObject(it) }
                        if (mode == 2) renderProcs()
                    }
                }
            }
            "config", "state" -> render()
        }
    }
}
