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
    private var filter = 0
    private var searchText = ""
    private fun signature(item: Item) = (item.mods.sorted() + item.key).joinToString("+")
    private fun favorites() = store.getStringSet("favorites", setOf("ctrl+c", "ctrl+v", "win+d"))?.toSet() ?: emptySet()

    private fun save(items: List<Item>) {
        store.edit().putString("items", JSONArray().also { a -> items.forEach { a.put(it.json()) } }.toString()).apply()
        refresh()
    }
    fun render(container: LinearLayout) {
        val ready = Hub.state == Hub.State.ONLINE && Hub.can("keyboard")
        container.addView(pageHeading(act, "Команды"), lp(t = 8, b = 8, ctx = act))
        val search = input(act, "Найти свою команду").apply { setSingleLine(); setText(searchText) }
        container.addView(search, lp(b = 10, ctx = act))
        val tiles = vbox(act)
        fun drawTiles() {
            tiles.removeAllViews()
            val all = items()
            val selected = all.mapIndexed { index, item -> index to item }.filter { (_, item) ->
                val category = when (filter) {
                    1 -> signature(item) in favorites()
                    2 -> presets.none { it.name == item.name && signature(it) == signature(item) }
                    else -> true
                }
                category && (item.name + " " + item.combo).contains(searchText, ignoreCase = true)
            }
            val columns = if (act.resources.configuration.screenWidthDp >= 600) 3 else 2
            selected.chunked(columns).forEach { group ->
                val row = hbox(act)
                group.forEachIndexed { col, (index, item) ->
                    row.addView(tile(item, ready, index), lp(0, weight = 1f, r = if (col < columns - 1) 8 else 0, ctx = act))
                }
                if (group.size < columns) row.addView(View(act), lp(0, weight = (columns - group.size).toFloat()))
                tiles.addView(row, lp(b = 8, ctx = act))
            }
            if (selected.isEmpty()) tiles.addView(infoBanner(act, if (searchText.isNotBlank()) "Команда не найдена" else if (filter == 1) "Удержи команду, чтобы добавить её в избранное" else "Создай свою кнопку или добавь её из справочника"))
        }
        val categories = Segmented(act, listOf("Все", "Избранное", "Свои")) { filter = it; refresh() }
        categories.select(filter)
        container.addView(categories.view, lp(b = 12, ctx = act))
        if (Hub.state != Hub.State.ONLINE) container.addView(infoBanner(act, "Нет подключения к ПК", "Подключить") { act.showSettings() }, lp(b = 12, ctx = act))
        container.addView(tiles)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchText = s.toString(); drawTiles() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        drawTiles()
        val actions = hbox(act)
        actions.addView(btn(act, "+  Своя кнопка", 1) { edit(null, null) }, lp(0, weight = 1f, r = 8, ctx = act))
        actions.addView(btn(act, "Справочник", 3) {
            act.openUtility(ShortcutCatalogPage(act, { items().map { it.json() } }) { o ->
                val mods = o.optJSONArray("mods") ?: JSONArray()
                val item = Item(o.optString("name"), o.optString("key"), (0 until mods.length()).map { mods.optString(it) })
                val all = items()
                if (all.any { signature(it) == signature(item) }) Hub.toast("Такая кнопка уже есть на панели")
                else { all.add(item); save(all); Hub.toast("Кнопка добавлена") }
            })
        }, lp(0, weight = 1f, ctx = act))
        container.addView(actions, lp(t = 4, b = 6, ctx = act))
    }

    private fun tile(item: Item, ready: Boolean, index: Int): View = vbox(act, 14).apply {
        minimumHeight = act.dp(106)
        background = ripple(roundBg(T.panel, 18, act, T.line, 1))
        isClickable = true
        val top = hbox(act)
        val icon = when (item.key) { "c", "v" -> R.drawable.ic_copy; "e" -> R.drawable.ic_folder; "d", "s" -> R.drawable.ic_display; else -> R.drawable.ic_tools }
        top.addView(android.widget.ImageView(act).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(T.accent)
        }, lp(act.dp(22), act.dp(22)))
        top.addView(View(act), lp(0, 1, 1f))
        if (signature(item) in favorites()) top.addView(tv(act, "★", 13f, T.accent))
        addView(top, lp(b = 8, ctx = act))
        addView(tv(act, item.name, 14f, T.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(tv(act, item.combo, 11f, T.accent).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(t = 4, ctx = act))
        setOnClickListener {
            when {
                Hub.state != Hub.State.ONLINE -> Hub.toast("Подключи ПК в настройках")
                !Hub.can("keyboard") -> Hub.toast("Разреши клавиатуру в PocketLink на ПК")
                else -> Hub.send(JSONObject().put("t", "key").put("k", item.key).put("m", JSONArray(item.mods)))
            }
        }
        setOnLongClickListener {
            val starred = signature(item) in favorites()
            AlertDialog.Builder(act).setTitle(item.name).setItems(arrayOf("Изменить", if (starred) "Убрать из избранного" else "В избранное", "Удалить")) { _, choice ->
                when (choice) {
                    0 -> edit(index, item)
                    1 -> { val next = favorites().toMutableSet(); if (starred) next.remove(signature(item)) else next.add(signature(item)); store.edit().putStringSet("favorites", next).apply(); refresh() }
                    2 -> { val next = items(); next.removeAt(index); save(next) }
                }
            }.show()
            true
        }
        contentDescription = item.name + ", " + item.combo
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
