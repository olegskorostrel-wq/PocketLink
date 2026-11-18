package com.pocketlink.app

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Phone-owned keyboard buttons; sending still uses the paired PC's keyboard permission. */
class ShortcutPanel(private val act: MainActivity, private val refresh: () -> Unit) {
    private val store = act.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private data class Item(val name: String, val key: String, val mods: List<String>) {
        val combo get() = (mods + key).joinToString(" + ") { it.uppercase() }
        fun json() = JSONObject().put("name", name).put("key", key).put("mods", JSONArray(mods))
    }
    private val presets = listOf(
        Item("Копировать", "c", listOf("ctrl")), Item("Вставить", "v", listOf("ctrl")),
        Item("Отменить", "z", listOf("ctrl")), Item("Выделить всё", "a", listOf("ctrl")),
        Item("Сменить окно", "tab", listOf("alt")), Item("Рабочий стол", "d", listOf("win")),
        Item("Снимок области", "s", listOf("win", "shift")), Item("Проводник", "e", listOf("win")),
        Item("Диспетчер задач", "esc", listOf("ctrl", "shift")), Item("Поиск", "f", listOf("ctrl")),
        Item("Новая вкладка", "t", listOf("ctrl")), Item("Вернуть вкладку", "t", listOf("ctrl", "shift"))
    )
    private fun items(): MutableList<Item> {
        val data = runCatching { JSONArray(store.getString("items", null) ?: return presets.toMutableList()) }.getOrDefault(JSONArray())
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val mods = o.optJSONArray("mods") ?: JSONArray()
            Item(o.optString("name"), o.optString("key"), (0 until mods.length()).map { mods.optString(it) })
        }.toMutableList()
    }
    private fun save(items: List<Item>) {
        store.edit().putString("items", JSONArray().also { a -> items.forEach { a.put(it.json()) } }.toString()).apply()
        refresh()
    }
    fun render(container: LinearLayout) {
        val ready = Hub.state == Hub.State.ONLINE && Hub.can("keyboard")
        container.addView(tv(act, "Быстрые действия", 20f, T.text, true), lp(t = 12, b = 2, ctx = act))
        container.addView(muted(act, "Нажми, чтобы отправить сочетание на ПК. Удержи, чтобы изменить.", 12.5f), lp(b = 12, ctx = act))
        if (Hub.state != Hub.State.ONLINE) {
            container.addView(infoBanner(act, "ПК не подключён: кнопки можно настроить, а выполнять — после подключения.", "Подключить") { act.showSettings() }, lp(b = 12, ctx = act))
        }
        val actions = hbox(act)
        actions.addView(btn(act, "+  Своя кнопка", 1) { edit(null, null) }, lp(0, weight = 1f, r = 8, ctx = act))
        actions.addView(btn(act, "Справочник", 0) {
            act.openUtility(ShortcutCatalogPage(act, { items().map { it.json() } }) { o ->
                val mods = o.optJSONArray("mods") ?: JSONArray()
                val item = Item(o.optString("name"), o.optString("key"), (0 until mods.length()).map { mods.optString(it) })
                val all = items()
                if (all.any { it.key == item.key && it.mods.toSet() == item.mods.toSet() }) Hub.toast("Такая кнопка уже есть на панели")
                else { all.add(item); save(all); Hub.toast("Кнопка добавлена в ПК → Команды") }
            })
        }, lp(0, weight = 1f, ctx = act))
        container.addView(actions, lp(b = 12, ctx = act))
        val all = items()
        val columns = if (act.resources.configuration.screenWidthDp >= 600) 3 else 2
        all.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            val row = hbox(act)
            rowItems.forEachIndexed { col, item ->
                val index = rowIndex * columns + col
                row.addView(tile(item, ready, index), lp(0, -2, 1f, r = if (col < columns - 1) 8 else 0, ctx = act))
            }
            if (rowItems.size < columns) row.addView(View(act), lp(0, act.dp(78), (columns - rowItems.size).toFloat()))
            container.addView(row, lp(b = 8, ctx = act))
        }
    }

    /** Плитка сочетания: название сверху, само сочетание акцентной подписью снизу. */
    private fun tile(item: Item, ready: Boolean, index: Int): View = vbox(act, 10).apply {
        gravity = android.view.Gravity.CENTER
        background = ripple(roundBg(T.panel, 16, act, T.line, 1))
        alpha = if (ready) 1f else .6f
        isClickable = true
        addView(tv(act, item.name, 14f, T.text, true).apply {
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(tv(act, item.combo, 11.5f, T.accent).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
            setSingleLine()
            setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(2))
            background = roundBg(T.accentSoft, 8, act)
        }, lp(-2, -2, t = 6, ctx = act))
        setOnClickListener {
            when {
                Hub.state != Hub.State.ONLINE -> Hub.toast("Подключи ПК в настройках")
                !Hub.can("keyboard") -> Hub.toast("Разреши клавиатуру в PocketLink на ПК")
                else -> {
                    Hub.send(JSONObject().put("t", "key").put("k", item.key).put("m", JSONArray(item.mods)))
                    Hub.toast("Отправлено: ${item.combo}")
                }
            }
        }
        setOnLongClickListener { edit(index, item); true }
        contentDescription = item.name + ", " + item.combo + ". Удерживай для изменения"
    }

    private fun edit(index: Int?, old: Item?) {
        val box = vbox(act, 20)
        val name = input(act, "Название, например Сохранить").apply { setSingleLine(); setText(old?.name ?: "") }
        val key = input(act, "Клавиша: S, Enter, F5…").apply { setSingleLine(); setText(old?.key ?: "") }
        box.addView(name, lp(b = 12, ctx = act))
        box.addView(muted(act, "Зажать вместе с клавишей:"))
        val mods = listOf("ctrl", "alt", "shift", "win").map { modifier ->
            CheckBox(act).apply { text = modifier.uppercase(); setTextColor(T.text); isChecked = old?.mods?.contains(modifier) == true }
        }
        mods.chunked(2).forEach { pair ->
            val row = hbox(act)
            pair.forEach { row.addView(it, lp(0, weight = 1f)) }
            box.addView(row)
        }
        box.addView(key, lp(t = 8, b = 8, ctx = act))
        box.addView(muted(act, "Латинские A–Z, 0–9, F1–F24, Enter, Tab, Esc, Space, Delete, стрелки Left/Right/Up/Down. Например: CTRL + SHIFT + S."))
        val builder = AlertDialog.Builder(act).setTitle(if (old == null) "Новая кнопка" else "Изменить кнопку")
            .setView(android.widget.ScrollView(act).apply { addView(box) }).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
        if (index != null) builder.setNeutralButton("Удалить") { _, _ -> val list = items(); list.removeAt(index); save(list) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val k = key.text.toString().trim().lowercase(java.util.Locale.ROOT)
                val special = setOf("enter", "esc", "escape", "tab", "space", "backspace", "delete", "del", "insert", "home", "end", "pageup", "pagedown", "left", "up", "right", "down", "printscreen", "capslock", "numlock", "scrolllock", "pause", "menu", "win", "ctrl", "alt", "shift", "playpause", "next", "prev", "stop", "volup", "voldown", "mute", ",", ".", "-", "=", "/", ";", "'", "[", "]", "\\", "`")
                if (name.text.isBlank()) { name.error = "Укажи название"; return@setOnClickListener }
                if (!(k.matches(Regex("[a-z0-9]|f([1-9]|1[0-9]|2[0-4])|num[0-9]")) || k in special)) {
                    key.error = "Неизвестная клавиша. Например: s, enter или f5"; return@setOnClickListener
                }
                val item = Item(name.text.toString().trim().take(40), k, mods.filter { it.isChecked }.map { it.text.toString().lowercase(java.util.Locale.ROOT) })
                val list = items()
                if (index == null) list.add(item) else list[index] = item
                save(list); dialog.dismiss()
            }
        }
        dialog.show()
    }
}
