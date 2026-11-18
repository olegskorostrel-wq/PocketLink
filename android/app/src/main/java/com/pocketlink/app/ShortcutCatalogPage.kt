package com.pocketlink.app

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ShortcutCatalogPage(act: MainActivity, private val custom: () -> List<JSONObject>, private val pin: (JSONObject) -> Unit) : Page(act) {
    private val results = vbox(act)
    private val count = muted(act, "")
    private val search = input(act, "Найти: звук, устройство воспроизведения…").apply { setSingleLine() }
    private var category = "Все"
    private var query = ""
    private val entries: List<JSONObject> = runCatching {
        val array = JSONArray(act.assets.open("shortcuts.json").bufferedReader().use { it.readText() })
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrDefault(emptyList())
    override val root: View = vbox(act, 14).apply {
        addView(btn(act, "‹  К моим кнопкам") { act.showCommands() }, lp(b = 8, ctx = act))
        addView(tv(act, "Справочник сочетаний", 22f, T.text, true))
        addView(search, lp(t = 12, b = 8, ctx = act))
        val filters = hbox(act)
        listOf("Все", "Windows", "Текст", "Браузер", "YouTube", "Медиа", "Мои").forEach { name ->
            filters.addView(btn(act, name) { category = name; render() }, lp(-2, r = 6, ctx = act))
        }
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filters) })
        addView(count, lp(t = 10, b = 8, ctx = act))
        addView(ScrollView(act).apply { addView(results) }, lp(h = 0, weight = 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); render() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun normalized(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')
    private fun render() {
        results.removeAllViews()
        val words = normalized(query).split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotEmpty() }
        val mine = custom().map { JSONObject(it.toString()).put("category", "Мои").put("note", "Твоя кнопка. Выполняется в активном окне ПК.") }
        val list = (if (category == "Мои") mine else entries + mine).filter { o ->
            (category == "Все" || o.optString("category") == category) && words.all { word ->
                val hay = normalized(o.optString("name") + " " + o.optString("keywords") + " " + combo(o) + " " + o.optString("category"))
                hay.contains(if (word.length > 5) word.take(6) else word)
            }
        }.distinctBy { it.optString("name") + "|" + combo(it) }.sortedBy { if (normalized(it.optString("name")).contains(normalized(query))) 0 else 1 }
        count.text = "$category · найдено ${list.size} · справочник доступен без ПК"
        if (list.isEmpty()) results.addView(card(act).apply {
            addView(tv(act, "Не нашлось сочетания", 17f, T.text, true))
            addView(muted(act, "Попробуй короче: «звук», «вкладка», «снимок». Свою комбинацию можно создать на панели команд."))
        })
        list.forEach { o ->
            val row = card(act, 14).apply {
                background = ripple(roundBg(T.panel, 14, act))
                addView(tv(act, o.optString("name"), 16f, T.text, true))
                addView(tv(act, combo(o), 14f, T.accent, true), lp(t = 4, ctx = act))
                addView(muted(act, o.optString("category"), 12f), lp(t = 3, ctx = act))
                setOnClickListener { details(o) }
            }
            results.addView(row, lp(b = 8, ctx = act))
        }
    }
    private fun combo(o: JSONObject): String {
        val a = o.optJSONArray("mods") ?: JSONArray()
        return ((0 until a.length()).map { a.optString(it) } + o.optString("key")).joinToString(" + ") { it.uppercase(Locale.ROOT) }
    }
    private fun details(o: JSONObject) {
        AlertDialog.Builder(act).setTitle(o.optString("name"))
            .setMessage(combo(o) + "\n\n" + o.optString("note").ifBlank { "Windows 11. Действие зависит от активного окна и настроек ПК." })
            .setPositiveButton("Выполнить") { _, _ ->
                if (Hub.state != Hub.State.ONLINE) Hub.toast("Подключи ПК в настройках")
                else if (!Hub.can("keyboard")) Hub.toast("Разреши клавиатуру на ПК")
                else {
                    Hub.send(JSONObject().put("t", "key").put("k", o.optString("key")).put("m", o.optJSONArray("mods") ?: JSONArray()))
                    Hub.toast("Отправлено: ${combo(o)}")
                }
            }.setNeutralButton("На панель") { _, _ -> pin(o) }.setNegativeButton("Закрыть", null).show()
    }
    override fun onShow() { render() }
}
