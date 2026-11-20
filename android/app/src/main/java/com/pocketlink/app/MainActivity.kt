package com.pocketlink.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity(), Hub.Observer {

    private lateinit var pages: List<Page>
    private lateinit var content: FrameLayout
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private class NavItem(val pill: FrameLayout, val img: ImageView, val label: TextView)
    private val navItems = mutableListOf<NavItem>()
    private lateinit var topBar: View
    private lateinit var navigationBar: View
    private lateinit var functionSettings: View
    private lateinit var statusPill: View
    private var current = -1
    private var utility: Page? = null
    private var utilityOrigin = 4
    private var lastOrientation = 0
    private var lastFontScale = 0f
    private val capturedVolumeKeys = mutableSetOf<Int>()
    private var lastVolumeCommandAt = Long.MIN_VALUE / 2

    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private var lastSentClip: String? = null
    private val pendingUris = mutableListOf<Uri>()
    private val pendingTexts = mutableListOf<String>()
    private var permCallback: (() -> Unit)? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { pushLocalClipboard() }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { if (Hub.state == Hub.State.ONLINE) Hub.sendUri(it) else pendingUris += it }
        if (uris.isNotEmpty() && Hub.state != Hub.State.ONLINE) Hub.toast("Нет связи с ПК: отправлю после подключения")
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cb = permCallback
        permCallback = null
        cb?.invoke()
    }

    fun pickFiles() = pickFilesLauncher.launch(arrayOf("*/*"))

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Hub.sendFolder(uri)
        }
    }

    fun pickFolder() = folderPicker.launch(null)

    private val pairingScanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        try {
            val payload = org.json.JSONObject(contents)
            val host = payload.getString("host")
            val code = payload.getString("code")
            if (host.isBlank() || host.length > 255 || !code.matches(Regex("[0-9]{6}\\.[0-9a-fA-F]{64}"))) {
                Hub.toast("Это не код PocketLink")
            } else Hub.connect(host, code)
        } catch (_: Exception) { Hub.toast("Это не код PocketLink") }
    }

    fun scanPairing() {
        pairingScanner.launch(com.journeyapps.barcodescanner.ScanOptions()
            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setPrompt("Наведите камеру на QR в окне PocketLink на ПК")
            .setBeepEnabled(false).setOrientationLocked(false))
    }

    fun askPerms(perms: Array<String>, then: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { then(); return }
        permCallback = then
        permLauncher.launch(missing.toTypedArray())
    }

    // ---------- жизненный цикл ----------

    override fun attachBaseContext(newBase: Context) {
        Prefs.init(newBase)
        if (Prefs.themeMode == "system") { super.attachBaseContext(newBase); return }
        val config = android.content.res.Configuration()
        when (Prefs.themeMode) {
            "dark" -> config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
            "light" -> config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val systemDark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val activeScreen = utility ?: if (::pages.isInitialized) pages.getOrNull(current) else null
        if (activeScreen is ScreenPage && newConfig.fontScale == lastFontScale && !(Prefs.themeMode == "system" && systemDark != T.dark)) {
            lastOrientation = newConfig.orientation; activeScreen.orientationChanged(); return
        }
        if ((Prefs.themeMode == "system" && systemDark != T.dark) || newConfig.orientation != lastOrientation || newConfig.fontScale != lastFontScale) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(when (Prefs.themeAccent) {
            "blue" -> R.style.Theme_PocketLink_Blue
            "green" -> R.style.Theme_PocketLink_Green
            "orange" -> R.style.Theme_PocketLink_Orange
            "pink" -> R.style.Theme_PocketLink_Pink
            "graphite" -> R.style.Theme_PocketLink_Graphite
            else -> R.style.Theme_PocketLink
        })
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Hub.init(this)
        T.update(this)
        lastOrientation = resources.configuration.orientation
        lastFontScale = resources.configuration.fontScale
        Notifier.createChannels(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.bg))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !T.dark
            isAppearanceLightNavigationBars = !T.dark
        }

        pages = listOf(HomePage(this), RemotePage(this), ToolsPage(this), ScreenPage(this), DevicePage(this))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildLayout())

        // служба держит связь в фоне; без сохранённого ПК запускать её незачем
        if (Prefs.lastPc() != null && Prefs.autoConnect) {
            LinkService.start(this)
            Hub.autoConnectIfNeeded()
        }
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.haveAsked) {
            Prefs.haveAsked = true
            askPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        }

        // при пересоздании этот intent уже обработан прежней копией
        if (savedInstanceState == null) collectShare(intent)
        else {
            savedInstanceState.getStringArrayList("queued_uris")?.mapTo(pendingUris) { Uri.parse(it) }
            savedInstanceState.getStringArrayList("queued_texts")?.let { pendingTexts.addAll(it) }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (utility != null) select(utilityOrigin)
                else if (current != 0) select(0)
                else finish()
            }
        })
        AppUpdates.schedule(this)
        select(if (savedInstanceState != null) savedInstanceState.getInt("tab", 0).coerceIn(0, 4) else 0)
        if (intent.getBooleanExtra("open_updates", false)) { intent.removeExtra("open_updates"); openUtility(UpdatesPage(this)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current.coerceAtLeast(0))
        outState.putStringArrayList("queued_uris", ArrayList(pendingUris.map(Uri::toString)))
        outState.putStringArrayList("queued_texts", ArrayList(pendingTexts))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        collectShare(intent)
        if (intent.getBooleanExtra("open_updates", false)) { intent.removeExtra("open_updates"); openUtility(UpdatesPage(this)) }
        flushShares()
    }

    override fun onStart() {
        super.onStart()
        Hub.uiVisible = true
        Hub.foreground()
        Hub.observe(this)
        flushShares()
        clipboard.addPrimaryClipChangedListener(clipListener)
        updateStatus()
        val updatePrefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - updatePrefs.getLong("checked", 0) > 24L * 60 * 60 * 1000) {
            Hub.work.execute { runCatching { AppUpdates.check(this)?.let { AppUpdates.notify(this, it) } } }
        }
        pages.getOrNull(current)?.onShow()
        utility?.onShow()
        Hub.sendAppearance()
        // служба могла быть остановлена системой
        if (Hub.state == Hub.State.OFF && Prefs.autoConnect && Prefs.lastPc() != null) {
            LinkService.start(this)
            Hub.autoConnectIfNeeded()
        }
    }

    override fun onStop() {
        capturedVolumeKeys.clear()
        Hub.uiVisible = false
        Hub.unobserve(this)
        clipboard.removePrimaryClipChangedListener(clipListener)
        pages.getOrNull(current)?.onHide()
        utility?.onHide()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleVolumeKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleVolumeKey(event) || super.onKeyUp(keyCode, event)

    private fun handleVolumeKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!Prefs.pcVolumeKeys && key !in capturedVolumeKeys) return false
        // Consume both halves of a press: a failed PC connection must never change phone volume.
        if (event.action == KeyEvent.ACTION_UP) {
            capturedVolumeKeys.remove(key)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            capturedVolumeKeys.add(key)
            if (!Prefs.pcVolumeKeys || event.isCanceled) return true
            when {
                Hub.state != Hub.State.ONLINE -> if (event.repeatCount == 0) Hub.toast("Нет связи с ПК")
                !Hub.can("audio") -> if (event.repeatCount == 0) Hub.toast("Разреши управление звуком в PocketLink на ПК")
                event.repeatCount == 0 || event.eventTime - lastVolumeCommandAt >= 150 -> {
                    lastVolumeCommandAt = event.eventTime
                    Hub.send(org.json.JSONObject().put("t", "media").put("cmd", if (key == KeyEvent.KEYCODE_VOLUME_UP) "volup" else "voldown"))
                }
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ отдаёт буфер обмена только приложению в фокусе: проверяем при возврате
        if (hasFocus) pushLocalClipboard()
    }

    // ---------- интерфейс ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(T.bg) }

        // нижняя навигация собирается первой: системный отступ снизу отдаётся ей, чтобы фон доходил до края экрана
        val navWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.panel)
        }
        navWrap.addView(View(this).apply { setBackgroundColor(T.line) }, LinearLayout.LayoutParams(-1, 1))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val items = listOf("Главная" to R.drawable.ic_home, "Пульт" to R.drawable.ic_pointer, "ПК" to R.drawable.ic_tools,
            "Экран" to R.drawable.ic_display, "Настройки" to R.drawable.ic_device)
        items.forEachIndexed { i, (name, icon) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                minimumHeight = dp(56)
                background = ripple(roundBg(0, 14, this@MainActivity))
                contentDescription = name
                setOnClickListener { select(i) }
            }
            val pill = FrameLayout(this)
            val img = ImageView(this).apply { setImageResource(icon) }
            pill.addView(img, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            val label = tv(this, name, 11f, T.muted).apply {
                gravity = Gravity.CENTER
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(3), dp(2), 0)
                includeFontPadding = false
            }
            col.addView(pill, LinearLayout.LayoutParams(dp(54), dp(34)))
            col.addView(label, LinearLayout.LayoutParams(-1, -2))
            navItems += NavItem(pill, img, label)
            nav.addView(col, lp(0, weight = 1f))
        }
        navWrap.addView(nav)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardUp = keyboard.bottom > bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, if (keyboardUp) keyboard.bottom else 0)
            navWrap.setPadding(0, 0, 0, if (keyboardUp) 0 else bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        // верхняя строка: название слева, состояние связи плашкой справа
        val top = hbox(this).apply { setPadding(dp(16), dp(10), dp(16), dp(8)) }
        top.addView(tv(this, "PocketLink", 18f, T.text, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, weight = 1f, r = 8, ctx = this))
        val pill = hbox(this).apply {
            setPadding(dp(12), dp(7), dp(14), dp(7))
            background = ripple(roundBg(T.panel, 18, this@MainActivity, T.line, 1))
            setOnClickListener { select(4) }
        }
        statusPill = pill
        dot = View(this).apply { background = roundBg(T.muted, 6, this@MainActivity) }
        pill.addView(dot, lp(dp(8), dp(8), r = 8, ctx = this))
        statusText = tv(this, "Нет связи", 13f, T.text, true).apply {
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(200)
        }
        pill.addView(statusText)
        top.addView(pill, lp(0, weight = 1.2f))
        topBar = top
        val gear = TextView(this).apply { text = "⚙"; textSize = 24f; gravity = Gravity.CENTER; contentDescription = "Настройки текущей функции"; setTextColor(T.accent); setOnClickListener { (utility ?: pages.getOrNull(current))?.openSettings() } }
        functionSettings = gear
        top.addView(gear, lp(dp(48), dp(48), l = 8, ctx = this))
        root.addView(top)

        content = FrameLayout(this)
        for (p in pages) content.addView(p.root, FrameLayout.LayoutParams(-1, -1).also { p.root.visibility = View.GONE })
        root.addView(content, lp(h = 0, weight = 1f))
        navigationBar = navWrap
        root.addView(navWrap)
        return root
    }

    private fun select(i: Int) {
        utility?.let { it.onHide(); content.removeView(it.root) }
        utility = null
        if (i == current) return
        pages.getOrNull(current)?.let { it.onHide(); it.root.visibility = View.GONE }
        current = i
        pages[i].root.visibility = View.VISIBLE
        pages[i].onShow()
        updateFunctionSettings()
        if (pages[i] !is ScreenPage) setScreenImmersive(false)
        navItems.forEachIndexed { idx, item ->
            val on = idx == i
            val c = if (on) T.accent else T.muted
            item.img.imageTintList = ColorStateList.valueOf(c)
            item.label.setTextColor(c)
            item.label.typeface = if (on) UI_MEDIUM else android.graphics.Typeface.DEFAULT
            item.pill.background = if (on) roundBg(T.accentSoft, 16, this) else null
        }
    }

    fun showCommands() = select(2)
    fun showSettings() = select(4)

    fun openUtility(page: Page) {
        if (current >= 0) utilityOrigin = current
        pages.getOrNull(current)?.let { it.onHide(); it.root.visibility = View.GONE }
        utility?.let { it.onHide(); content.removeView(it.root) }
        utility = page
        current = -1
        content.addView(page.root, FrameLayout.LayoutParams(-1, -1))
        page.onShow()
        updateFunctionSettings()
        if (page !is ScreenPage) setScreenImmersive(false)
    }

    private fun updateFunctionSettings() { if (::functionSettings.isInitialized) functionSettings.visibility = if ((utility ?: pages.getOrNull(current))?.hasSettings == true) View.VISIBLE else View.GONE }
    fun setScreenImmersive(enabled: Boolean) {
        if (!::topBar.isInitialized || !::navigationBar.isInitialized) return
        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        navigationBar.visibility = if (enabled) View.GONE else View.VISIBLE
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val (color, text) = when (Hub.state) {
            Hub.State.ONLINE -> T.ok to Hub.pcName.ifEmpty { "Подключено" }
            Hub.State.CONNECTING -> T.accent to "Подключаюсь…"
            Hub.State.OFF -> T.muted to "Нет связи"
        }
        (dot.background as? GradientDrawable)?.setColor(color)
        statusText.text = text
        statusText.setTextColor(if (Hub.state == Hub.State.OFF) T.muted else T.text)
        statusPill.contentDescription = "PocketLink: " + when (Hub.state) {
            Hub.State.ONLINE -> "подключено, " + Hub.pcName
            Hub.State.CONNECTING -> "подключаюсь"
            Hub.State.OFF -> "нет связи с ПК"
        }
    }

    override fun onHub(ev: String, data: Any?) {
        when (ev) {
            "toast" -> Toast.makeText(this, data as? String ?: "", Toast.LENGTH_SHORT).show()
            "state" -> {
                updateStatus()
                if (Hub.state == Hub.State.ONLINE) { flushShares(); pushLocalClipboard() }
            }
            "config" -> updateStatus()
        }
        for (p in pages) p.onHub(ev, data)
        utility?.onHub(ev, data)
    }

    // ---------- буфер обмена ----------

    private fun pushLocalClipboard() {
        if (!Prefs.clipAuto || Hub.state != Hub.State.ONLINE) return
        val text = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: SecurityException) { null }
        if (text.isNullOrEmpty() || text == lastSentClip) return
        lastSentClip = text
        Hub.sendClipboard(text)
    }

    // ---------- «Поделиться» ----------

    private fun collectShare(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                streamOf(i)?.let { pendingUris += it }
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { if (streamOf(i) == null) pendingTexts += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> pendingUris += streamsOf(i)
        }
        // intent разобран: убираем, чтобы не отправить повторно при пересоздании
        i.action = null
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsOf(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun flushShares() {
        val uris = pendingUris.toList().filter { Hub.sendUri(it) }
        pendingUris.removeAll(uris.toSet())
        if (uris.isNotEmpty()) { Hub.connectForShare(); openUtility(FilesPage(this).apply { openTransfers() }) }
        if (Hub.state != Hub.State.ONLINE) {
            if (uris.isNotEmpty() || pendingTexts.isNotEmpty()) { Hub.connectForShare(); Hub.toast("Подключаюсь к ПК. Файлы отправятся автоматически") }
            return
        }
        val texts = pendingTexts.toList(); pendingTexts.clear()
        texts.forEach { Hub.send("share.text", "text" to it) }
        if (texts.isNotEmpty()) Hub.toast("Текст отправлен на ПК")
    }
}
