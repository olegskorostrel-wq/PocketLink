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
    private val navItems = mutableListOf<Pair<ImageView, TextView>>()
    private var current = -1
    private var utility: Page? = null
    private var utilityOrigin = 4

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Hub.init(this)
        T.update(this)
        Notifier.createChannels(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(T.bg))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !T.dark
            isAppearanceLightNavigationBars = !T.dark
        }

        pages = listOf(RemotePage(this), FilesPage(this), ToolsPage(this), ScreenPage(this), DevicePage(this))
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
        select(if (savedInstanceState != null) savedInstanceState.getInt("tab", 0) else if (Prefs.lastPc() == null) 4 else 0)
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
        // служба могла быть остановлена системой
        if (Hub.state == Hub.State.OFF && Prefs.autoConnect && Prefs.lastPc() != null) {
            LinkService.start(this)
            Hub.autoConnectIfNeeded()
        }
    }

    override fun onStop() {
        Hub.uiVisible = false
        Hub.unobserve(this)
        clipboard.removePrimaryClipChangedListener(clipListener)
        pages.getOrNull(current)?.onHide()
        utility?.onHide()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ отдаёт буфер обмена только приложению в фокусе: проверяем при возврате
        if (hasFocus) pushLocalClipboard()
    }

    // ---------- интерфейс ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(T.bg) }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val top = hbox(this).apply { setPadding(dp(16), dp(14), dp(16), dp(12)); setOnClickListener { select(4) } }
        dot = View(this).apply { background = roundBg(T.muted, 6, this@MainActivity) }
        top.addView(dot, lp(dp(10), dp(10), r = 8, ctx = this))
        statusText = tv(this, "PocketLink", 14f, T.text, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(statusText, lp(0, weight = 1f))
        root.addView(top)

        content = FrameLayout(this)
        for (p in pages) content.addView(p.root, FrameLayout.LayoutParams(-1, -1).also { p.root.visibility = View.GONE })
        root.addView(content, lp(h = 0, weight = 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(T.panel)
            setPadding(0, dp(4), 0, dp(4))
        }
        val items = listOf("Пульт" to R.drawable.ic_remote, "Файлы" to R.drawable.ic_files, "ПК" to R.drawable.ic_tools,
            "Экран" to R.drawable.ic_screen, "Настройки" to R.drawable.ic_device)
        items.forEachIndexed { i, (name, icon) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                minimumHeight = dp(60)
                background = ripple(roundBg(0, 12, this@MainActivity))
                setOnClickListener { select(i) }
            }
            val img = ImageView(this).apply { setImageResource(icon) }
            val label = tv(this, name, 11f, T.muted).apply {
                gravity = Gravity.CENTER
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(4), dp(2), 0)
                includeFontPadding = false
            }
            col.addView(img, LinearLayout.LayoutParams(dp(24), dp(24)))
            col.addView(label, LinearLayout.LayoutParams(-1, -2))
            navItems += img to label
            nav.addView(col, lp(0, weight = 1f))
        }
        root.addView(nav)
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
        navItems.forEachIndexed { idx, (img, label) ->
            val c = if (idx == i) T.accent else T.muted
            img.imageTintList = ColorStateList.valueOf(c)
            label.setTextColor(c)
        }
    }

    fun showCommands() = select(2)

    fun openUtility(page: Page) {
        if (current >= 0) utilityOrigin = current
        pages.getOrNull(current)?.let { it.onHide(); it.root.visibility = View.GONE }
        utility?.let { it.onHide(); content.removeView(it.root) }
        utility = page
        current = -1
        content.addView(page.root, FrameLayout.LayoutParams(-1, -1))
        page.onShow()
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val (color, text) = when (Hub.state) {
            Hub.State.ONLINE -> T.ok to "Подключено · ${Hub.pcName}"
            Hub.State.CONNECTING -> T.accent to "Подключаюсь…"
            Hub.State.OFF -> T.muted to "Нет связи с ПК"
        }
        (dot.background as? GradientDrawable)?.setColor(color)
        statusText.text = "PocketLink  ·  $text"
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
        if (Hub.state != Hub.State.ONLINE) {
            if (pendingUris.isNotEmpty() || pendingTexts.isNotEmpty()) Hub.toast("Нет связи с ПК: отправлю после подключения")
            return
        }
        val uris = pendingUris.toList(); pendingUris.clear()
        val texts = pendingTexts.toList(); pendingTexts.clear()
        uris.forEach { Hub.sendUri(it) }
        texts.forEach { Hub.send("share.text", "text" to it) }
        if (uris.isNotEmpty()) { select(1) }
        if (texts.isNotEmpty()) Hub.toast("Текст отправлен на ПК")
    }
}
