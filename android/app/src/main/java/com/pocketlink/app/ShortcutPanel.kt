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
        container.addView(tv(act, "Быстрые действия", 23f, T.text, true), lp(t = 16, b = 4, ctx = act))
        container.addView(muted(act, "Одно нажатие — сочетание на ПК. Удерживай кнопку, чтобы изменить её."), lp(b = 12, ctx = act))
        if (Hub.state != Hub.State.ONLINE) container.addView(card(act).apply {
            addView(tv(act, "Подключи компьютер", 15f, T.text, true))
            addView(muted(act, "Кнопки уже можно настроить. Выполнение станет доступно после подключения."))
        }, lp(b = 8, ctx = act))
        container.addView(btn(act, "+  Своя кнопка", 1) { edit(null, null) }, lp(t = 4, b = 8, ctx = act))
        container.addView(btn(act, "Найти сочетание в справочнике", 1) {
            act.openUtility(ShortcutCatalogPage(act, { items().map { it.json() } }) { o ->
                val mods = o.optJSONArray("mods") ?: JSONArray()
                val item = Item(o.optString("name"), o.optString("key"), (0 until mods.length()).map { mods.optString(it) })
                val all = items()
                if (all.any { it.key == item.key && it.mods.toSet() == item.mods.toSet() }) Hub.toast("Такая кнопка уже есть на панели")
                else { all.add(item); save(all); Hub.toast("Кнопка добавлена в ПК → Команды") }
            })
        }, lp(t = 8, ctx = act))
        container.addView(spacer(act, 12))
        val all = items()
        val columns = if (act.resources.configuration.screenWidthDp >= 600) 3 else 2
        all.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            val row = hbox(act)
            rowItems.forEachIndexed { col, item ->
                val index = rowIndex * columns + col
                val button = btn(act, item.name + "\n" + item.combo) {
                    when {
                        Hub.state != Hub.State.ONLINE -> Hub.toast("Подключи ПК в настройках")
                        !Hub.can("keyboard") -> Hub.toast("Разреши клавиатуру в PocketLink на ПК")
                        else -> {
                            Hub.send(JSONObject().put("t", "key").put("k", item.key).put("m", JSONArray(item.mods)))
                            Hub.toast("Отправлено: ${item.combo}")
                        }
                    }
                }.apply {
                    minHeight = act.dp(80)
                    alpha = if (Hub.state == Hub.State.ONLINE && Hub.can("keyboard")) 1f else .65f
                    setOnLongClickListener { edit(index, item); true }
                    contentDescription = item.name + ", " + item.combo + ". Удерживай для изменения"
                }
                row.addView(button, lp(0, weight = 1f, r = if (col < columns - 1) 8 else 0, ctx = act))
            }
            if (rowItems.size < columns) row.addView(View(act), lp(0, weight = (columns - rowItems.size).toFloat()))
            container.addView(row, lp(b = 8, ctx = act))
        }
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
