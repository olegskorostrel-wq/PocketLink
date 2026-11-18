package com.pocketlink.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView

/** Вкладка «Телефон»: подключение, сопряжение, что делиться с ПК, права. */
class DevicePage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 12)
    private var found: List<Discovery.Found> = emptyList()
    private var scanning = false
    private val expanded = mutableSetOf<String>()
    private var manualIp = ""
    private var manualPin = ""

    override val root: View = ScrollView(act).apply { isFillViewport = true; addView(body) }

    override fun onShow() { render() }
    override fun onHub(ev: String, data: Any?) { if (ev == "state" || ev == "config") render() }

    private fun scan() {
        if (scanning) return
        scanning = true
        render()
        Hub.work.execute {
            val f = Discovery.scan(1800)
            Hub.main.post { found = f; scanning = false; render() }
        }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(pageHeading(act, "Настройки"))
        // --- состояние ---
        val online = Hub.state == Hub.State.ONLINE
        val st = card(act, 16)
        val head = hbox(act)
        val stColor = when (Hub.state) { Hub.State.ONLINE -> T.ok; Hub.State.CONNECTING -> T.accent; Hub.State.OFF -> T.muted }
        head.addView(View(act).apply { background = roundBg(stColor, 6, act) }, lp(act.dp(12), act.dp(12), r = 12, ctx = act))
        val titleCol = vbox(act)
        titleCol.addView(tv(act, when (Hub.state) {
            Hub.State.ONLINE -> Hub.pcName.ifEmpty { "Подключено" }
            Hub.State.CONNECTING -> "Подключаюсь…"
            Hub.State.OFF -> "Нет связи с ПК"
        }, 17f, T.text, true))
        if (online) titleCol.addView(muted(act, "${Hub.pcHost} · ${Hub.pcOs}", 12f), lp(t = 2, ctx = act))
        else if (Hub.lastError.isNotEmpty()) titleCol.addView(tv(act, Hub.lastError, 12.5f, T.danger), lp(t = 2, ctx = act))
        else titleCol.addView(muted(act, "Подключи компьютер ниже, чтобы управлять им", 12f), lp(t = 2, ctx = act))
        head.addView(titleCol, lp(0, weight = 1f))
        if (Hub.state != Hub.State.OFF) head.addView(btn(act, "Отключить", 3) { Hub.disconnect() }.apply {
            minHeight = act.dp(38); setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(4))
        }, lp(-2, -2, l = 8, ctx = act))
        st.addView(head)
        body.addView(st)

        val colors = arrayOf("Фиолетовый", "Синий", "Зелёный", "Оранжевый", "Розовый", "Графитовый")
        val colorKeys = arrayOf("purple", "blue", "green", "orange", "pink", "graphite")
        val modes = arrayOf("Как в системе", "Светлая", "Тёмная")
        val modeKeys = arrayOf("system", "light", "dark")
        val appearance = card(act, 4)
        appearance.addView(actionRow(act, "Цвет оформления", colors[colorKeys.indexOf(Prefs.themeAccent).coerceAtLeast(0)]) {
            AlertDialog.Builder(act).setTitle("Цвет оформления").setSingleChoiceItems(colors, colorKeys.indexOf(Prefs.themeAccent)) { dialog, index ->
                Prefs.themeAccent = colorKeys[index]; Hub.sendAppearance(); dialog.dismiss(); act.recreate()
            }.setNegativeButton("Отмена", null).show()
        })
        appearance.addView(actionRow(act, "Тема", modes[modeKeys.indexOf(Prefs.themeMode).coerceAtLeast(0)]) {
            AlertDialog.Builder(act).setTitle("Тема").setSingleChoiceItems(modes, modeKeys.indexOf(Prefs.themeMode)) { dialog, index ->
                Prefs.themeMode = modeKeys[index]; Hub.sendAppearance(); dialog.dismiss(); act.recreate()
            }.setNegativeButton("Отмена", null).show()
        }, lp(t = 4, ctx = act))
        appearance.addView(switchRow(act, "Передавать оформление на ПК", "На ПК включите «Следовать теме телефона». Можно отключить в любой момент.", Prefs.syncAppearanceToPc) { Prefs.syncAppearanceToPc = it; Hub.sendAppearance() }, lp(t = 8, ctx = act))
        body.addView(sectionTitle(act, "Оформление"))
        body.addView(appearance)
        body.addView(actionRow(act, "Обновления PocketLink", "Проверка и установка новых версий") { act.openUtility(UpdatesPage(act)) }, lp(t = 8, ctx = act))

        // --- запомненные ПК ---
        val pcs = Prefs.pcs()
        if (pcs.isNotEmpty()) {
            body.addView(sectionTitle(act, "Мои компьютеры"))
            for (pc in pcs) {
                val row = hbox(act, 12).apply { background = ripple(roundBg(T.panel, 14, act, T.line, 1)); isClickable = true }
                val col = vbox(act)
                col.addView(tv(act, pc.name, 15f, T.text, true))
                col.addView(muted(act, pc.host + if (pc.key == null) " · нужен код" else "", 12f))
                row.addView(col, lp(0, weight = 1f))
                row.addView(btn(act, "Включить") { Hub.wake(pc) }.apply {
                    minHeight = act.dp(38); setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(4))
                }, lp(-2, -2, ctx = act))
                row.setOnClickListener { if (pc.key == null) askPin(pc.host) else Hub.connect(pc.host, null) }
                row.setOnLongClickListener {
                    AlertDialog.Builder(act).setTitle(pc.name).setMessage("Забыть этот компьютер на телефоне?")
                        .setPositiveButton("Забыть") { _, _ -> Prefs.removePc(pc.name); render() }
                        .setNegativeButton("Отмена", null).show()
                    true
                }
                row.layoutParams = lp(b = 6, ctx = act)
                body.addView(row)
            }
        }

        // --- поиск и ручное подключение ---
        val c = card(act)
        c.addView(btn(act, "Сканировать QR с ПК", 1) { act.scanPairing() }, lp(b = 8, ctx = act))
        c.addView(btn(act, if (scanning) "Ищу…" else "Найти ПК в сети") { scan() })
        for (f in found) {
            c.addView(btn(act, "${f.name}  ·  ${f.host}", 1) { askPin(f.host) }, lp(t = 8, ctx = act))
        }
        if (found.isEmpty() && !scanning) c.addView(muted(act, "ПК должен быть включён, PocketLink запущен, телефон в той же Wi‑Fi сети.", 12f).apply { setPadding(0, act.dp(8), 0, 0) })
        c.addView(muted(act, "Или вручную", 12f).apply { setPadding(0, act.dp(14), 0, act.dp(4)) })
        val ip = input(act, "IP-адрес ПК (например 192.168.1.42)").apply { setText(manualIp); setSingleLine() }
        val pin = input(act, "Полный код сопряжения с ПК").apply { setText(manualPin); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        c.addView(ip, lp(b = 6, ctx = act))
        c.addView(pin, lp(b = 8, ctx = act))
        c.addView(btn(act, "Подключиться", 1) {
            manualIp = ip.text.toString().trim(); manualPin = pin.text.toString().trim()
            if (manualIp.isEmpty()) { Hub.toast("Введи IP-адрес ПК") }
            else if (manualPin.isEmpty()) {
                if (Prefs.pcByHost(manualIp)?.key != null) Hub.connect(manualIp, null) else Hub.toast("Введи код сопряжения (он на ПК в окне PocketLink)")
            } else { Hub.connect(manualIp, manualPin); manualPin = "" }
        })
        body.addView(fold("Подключить новый ПК", c, Prefs.pcs().isEmpty(), "QR-код, поиск в сети, вручную"), lp(t = 8, ctx = act))

        // --- что делиться ---
        val s = card(act, 12)
        s.addView(switchRow(act, "Уведомления", "Показывать на ПК, отвечать и закрывать оттуда", Prefs.shareNotifs) { Prefs.shareNotifs = it; if (it && Hub.state == Hub.State.ONLINE) NotifMirror.syncAll() })
        s.addView(switchRow(act, "SMS", "Читать и отправлять с ПК", Prefs.shareSms) { Prefs.shareSms = it; resetFeatures() })
        s.addView(switchRow(act, "Звонки", "Принимать, сбрасывать и набирать с ПК", Prefs.shareCalls) { Prefs.shareCalls = it; resetFeatures() })
        s.addView(switchRow(act, "Фото", "Просмотр и скачивание на ПК", Prefs.sharePhotos) { Prefs.sharePhotos = it })
        s.addView(switchRow(act, "Буфер обмена", "Слать на ПК само, пока приложение открыто", Prefs.clipAuto) { Prefs.clipAuto = it })
        s.addView(switchRow(act, "Автоподключение", "Держать связь с последним ПК в фоне и после перезагрузки", Prefs.autoConnect) { Prefs.autoConnect = it })
        body.addView(fold("Обмен с телефоном", s, false, "Уведомления, SMS, звонки, фото, буфер"), lp(t = 8, ctx = act))

        val controls = card(act, 12)
        controls.addView(switchRow(act, "Кнопки громкости", null, Prefs.pcVolumeKeys) { Prefs.pcVolumeKeys = it })
        controls.addView(actionRow(act, "YouTube — поиск и пульт", "Обложки, поиск и управление видео") { act.openUtility(YouTubePage(act)) })
        controls.addView(actionRow(act, "Микшер приложений ПК", "Громкость программ и вывод звука") { act.openUtility(MixerPage(act)) }, lp(t = 8, ctx = act))
        controls.addView(actionRow(act, "Окна и состояние ПК", "Активные окна, нагрузка и яркость") { act.openUtility(DesktopPage(act)) }, lp(t = 8, ctx = act))
        controls.addView(actionRow(act, "Bluetooth-пульт и геймпад", "Подключение по Bluetooth") { act.openUtility(BluetoothPage(act)) }, lp(t = 8, ctx = act))
        controls.addView(actionRow(act, "Телефон как микрофон ПК", "Передача голоса на компьютер") { act.openUtility(MicrophonePage(act)) }, lp(t = 8, ctx = act))
        controls.addView(actionRow(act, "Настройки тачпада", "Чувствительность и прокрутка") {
            val box = vbox(act, 14)
            val label = tv(act, "Чувствительность", 15f)
            val slider = android.widget.SeekBar(act).apply {
                max = 275
                progress = ((Prefs.pointerSensitivity - 0.25f) * 100).toInt()
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                        Prefs.pointerSensitivity = 0.25f + p / 100f
                        label.text = "Чувствительность " + String.format(java.util.Locale.getDefault(), "%.2f", Prefs.pointerSensitivity)
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
                })
            }
            box.addView(label)
            box.addView(slider)
            box.addView(switchRow(act, "Естественная прокрутка", "Палец вниз — содержимое вниз", Prefs.naturalScroll) { Prefs.naturalScroll = it })
            AlertDialog.Builder(act).setTitle("Тачпад").setView(box).setPositiveButton("Готово", null).show()
        }, lp(t = 8, ctx = act))
        controls.addView(actionRow(act, "Качество экрана ПК", "Чёткость и скорость трансляции") {
            val options = arrayOf("Экономный · 1280 px, 8 кадров/с", "Чёткий текст · 1920 px, 12 кадров/с", "Максимальная детализация · 2560 px, 15 кадров/с")
            AlertDialog.Builder(act).setTitle("Качество экрана")
                .setItems(options) { _, index ->
                    Prefs.screenWidth = intArrayOf(1280, 1920, 2560)[index]
                    Prefs.screenQuality = intArrayOf(60, 80, 88)[index]
                    Prefs.screenFps = intArrayOf(8, 12, 15)[index]
                    Hub.toast("Применится при следующем запуске экрана")
                }.show()
        }, lp(t = 8, ctx = act))
        controls.addView(switchRow(act, "Касания управляют ПК", "Выключи для просмотра без случайных кликов", Prefs.screenControl) { Prefs.screenControl = it })
        body.addView(fold("Пульт и экран", controls, false, "YouTube, микшер, тачпад, качество экрана"), lp(t = 8, ctx = act))

        // --- права ---
        val p = card(act, 12)
        p.addView(permRow("Доступ к уведомлениям", Features.hasNotifAccess()) {
            act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        p.addView(permRow("SMS и контакты", Features.hasSms() && Features.granted(Manifest.permission.SEND_SMS)) {
            act.askPerms(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)) { resetFeatures() }
        })
        p.addView(permRow("Звонки", Features.hasCalls() && Features.granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            act.askPerms(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE)) { resetFeatures() }
        })
        p.addView(permRow("Фото", Features.hasPhotos()) {
            act.askPerms(arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)) { render() }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            p.addView(permRow("Показ уведомлений приложения", Features.granted(Manifest.permission.POST_NOTIFICATIONS)) {
                act.askPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { render() }
            })
        }
        val pm = act.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        p.addView(permRow("Работа в фоне без ограничений", pm.isIgnoringBatteryOptimizations(act.packageName)) {
            try {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
            } catch (_: Exception) { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        body.addView(fold("Разрешения Android", p, false, "Доступы и работа в фоне"), lp(t = 8, ctx = act))

        // --- прочее ---
        val o = card(act)
        o.addView(tv(act, "Имя: ${Prefs.deviceName}", 15f))
        o.addView(btn(act, "Изменить имя") {
            val e = EditText(act).apply { setText(Prefs.deviceName); setSingleLine() }
            AlertDialog.Builder(act).setTitle("Имя телефона").setView(e)
                .setPositiveButton("Сохранить") { _, _ -> if (e.text.isNotBlank()) { Prefs.deviceName = e.text.toString(); render() } }
                .setNegativeButton("Отмена", null).show()
        }, lp(t = 8, ctx = act))
        o.addView(muted(act, "Плитка «Буфер на ПК» добавляется в шторку быстрых настроек (изменить → найти PocketLink).", 12f).apply { setPadding(0, act.dp(10), 0, 0) })
        body.addView(fold("Этот телефон", o, false, "Имя и плитка быстрых настроек"), lp(t = 8, ctx = act))
    }

    private fun fold(title: String, content: View, defaultOpen: Boolean = false, subtitle: String? = null): View {
        val box = vbox(act)
        val open = if (expanded.contains("!" + title)) false else defaultOpen || expanded.contains(title)
        content.visibility = if (open) View.VISIBLE else View.GONE
        val chevron = tv(act, "›", 24f, T.muted).apply { rotation = if (open) 90f else 0f }
        val header = hbox(act, 14).apply {
            minimumHeight = act.dp(58)
            background = ripple(roundBg(T.panel, 16, act, T.line, 1))
            isClickable = true
            contentDescription = title
            val col = vbox(act)
            col.addView(tv(act, title, 15f, T.text, true))
            if (subtitle != null) col.addView(muted(act, subtitle, 12f), lp(t = 2, ctx = act))
            addView(col, lp(0, weight = 1f, r = 8, ctx = act))
            addView(chevron)
            setOnClickListener {
                val next = content.visibility != View.VISIBLE
                content.visibility = if (next) View.VISIBLE else View.GONE
                if (next) { expanded.add(title); expanded.remove("!" + title) }
                else { expanded.remove(title); expanded.add("!" + title) }
                chevron.rotation = if (next) 90f else 0f
            }
        }
        box.addView(header)
        box.addView(content, lp(t = 8, ctx = act))
        return box
    }

    private fun permRow(title: String, ok: Boolean, onClick: () -> Unit): View {
        val r = hbox(act).apply { setPadding(0, act.dp(6), 0, act.dp(6)) }
        r.addView(tv(act, title, 14f), lp(0, weight = 1f))
        if (ok) r.addView(tv(act, "✓ выдано", 13f, T.ok, true))
        else r.addView(btn(act, "Выдать", 1) { onClick() }.apply {
            minHeight = act.dp(36); setPadding(act.dp(14), act.dp(4), act.dp(14), act.dp(4))
        })
        return r
    }

    private fun resetFeatures() {
        if (Hub.state == Hub.State.ONLINE) { Features.stop(); Features.start() }
        render()
    }

    private fun askPin(host: String) {
        val e = EditText(act).apply { hint = "Полный код с ПК"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD; setSingleLine() }
        AlertDialog.Builder(act).setTitle("Сопряжение с $host").setMessage("На ПК нажми «Новый код» и вставь сюда полный код. Он действует пять минут.").setView(e)
            .setPositiveButton("Подключить") { _, _ -> val c = e.text.toString().trim(); if (c.isNotEmpty()) Hub.connect(host, c) }
            .setNegativeButton("Отмена", null).show()
    }
}
