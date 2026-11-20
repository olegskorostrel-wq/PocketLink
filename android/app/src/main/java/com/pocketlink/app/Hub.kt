package com.pocketlink.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Единая точка связи с ПК: владеет соединением, переподключается, хранит состояние для экранов
 * и раздаёт входящие сообщения функциям (уведомления, SMS, звонки, фото, «найти телефон»).
 * Состояние меняется в главном потоке; поля помечены @Volatile там, где их читают из других потоков.
 */
object Hub {
    enum class State { OFF, CONNECTING, ONLINE }

    interface Observer {
        /** ev: state, json, screen, transfer, config, status, toast. Вызывается в главном потоке. */
        fun onHub(ev: String, data: Any?)
    }

    lateinit var app: Context
        private set
    private var inited = false
    val main = Handler(Looper.getMainLooper())
    val work = Executors.newCachedThreadPool { r -> Thread(r, "pl-work").apply { isDaemon = true } }
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile var state = State.OFF
    @Volatile var conn: PcConnection? = null
    @Volatile var serviceAlive = false
    @Volatile var uiVisible = false

    var pcName = ""
    var pcHost = ""
    var pcOs = ""
    var allow = JSONObject()
    var commands = JSONArray()
    var macs = listOf<String>()
    var status: JSONObject? = null
    var lastError = ""

    private val transferMap = LinkedHashMap<String, Transfer>()
    private data class Upload(val id: String, val uri: Uri, val name: String, val size: Long, val hash: String, val fingerprint: String)
    private val uploads = java.util.concurrent.ConcurrentHashMap<String, Upload>()
    private val uploadRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private lateinit var outbox: UploadOutbox
    private var targetPc: SavedPc? = null
    private var activeFingerprint = ""
    private var attemptStarted = 0L
    private var discovering = false
    private var lastDiscovery = 0L

    @Volatile private var gen = 0
    private var wantOnline = false
    private var host = ""
    private var pin: String? = null
    private var attempt = 0
    private var lastClipFromPc: String? = null

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Prefs.init(app)
        outbox = UploadOutbox(app)
        LocalNetwork.watch(app) { networkChanged() }
        work.execute {
            for (item in outbox.load()) {
                val upload = Upload(item.id, Uri.fromFile(item.file), item.name, item.size, item.hash, item.fingerprint)
                uploads[item.id] = upload
                main.post { showQueued(upload); resumeUpload(upload) }
            }
        }
    }

    fun observe(o: Observer) { if (!observers.contains(o)) observers.add(o) }
    fun unobserve(o: Observer) { observers.remove(o) }

    fun emit(ev: String, data: Any? = null) {
        main.post { for (o in observers) o.onHub(ev, data) }
    }

    fun toast(text: String) = emit("toast", text)

    fun transfers(): List<Transfer> = synchronized(transferMap) { transferMap.values.toList().asReversed() }

    fun can(what: String): Boolean = allow.optBoolean(what, false)

    // ---------- подключение ----------

    /** Запуск при старте приложения или после перезагрузки. */
    fun autoConnectIfNeeded() {
        main.post {
            if (state != State.OFF || wantOnline) return@post
            if (!Prefs.autoConnect) return@post
            val pc = Prefs.lastPc() ?: return@post
            if (pc.key == null) return@post
            connect(pc.host, null)
        }
    }

    /** pin != null: сопряжение по коду, иначе по сохранённому ключу. */
    fun connect(h: String, pinCode: String?) {
        main.post {
            if (!inited) return@post
            LinkService.start(app)
            wantOnline = true
            host = h.trim()
            targetPc = Prefs.pcByHost(host)
            pin = pinCode
            attempt = 0
            lastError = ""
            startAttempt()
        }
    }

    fun disconnect() {
        main.post {
            wantOnline = false
            main.removeCallbacks(retry)
            gen++
            conn?.disconnect()
            conn = null
            goOffline("")
            LinkService.stop(app)
        }
    }

    private val retry = Runnable { if (wantOnline && state != State.ONLINE) startAttempt() }

    private fun startAttempt() {
        main.removeCallbacks(retry)
        attemptStarted = android.os.SystemClock.elapsedRealtime()
        gen++
        conn?.disconnect()
        val my = gen
        val c = PcConnection(ConnListener(my))
        conn = c
        updateLinkState(State.CONNECTING)

        val saved = targetPc ?: Prefs.pcByHost(host)
        val usePin = pin
        val mode: Int
        val secret: ByteArray
        val fingerprint: String
        if (usePin != null) {
            if (!usePin.matches(Regex("[0-9]{6}\\.[0-9a-fA-F]{64}"))) {
                wantOnline = false
                conn = null
                goOffline("Нужен полный код сопряжения из окна PocketLink на ПК")
                return
            }
            fingerprint = usePin.substringAfter('.').lowercase()
            mode = Proto.AUTH_PIN; secret = usePin.substringBefore('.').toByteArray(Charsets.UTF_8)
        } else {
            val key = saved?.key
            if (key == null) {
                wantOnline = false
                lastError = "Для этого ПК нужен код сопряжения"
                conn = null
                goOffline(lastError)
                return
            }
            fingerprint = saved.fingerprint
            mode = Proto.AUTH_KEY; secret = Base64.decode(key, Base64.NO_WRAP)
        }
        activeFingerprint = fingerprint
        c.connect(host, mode, secret, Prefs.deviceId, Prefs.deviceName, fingerprint)
    }

    private fun scheduleRetry(reason: String) {
        lastError = reason
        if (!wantOnline) { goOffline(reason); return }
        attempt++
        val delay = if (uiVisible || uploads.isNotEmpty()) minOf(350L * attempt, 3000L) else minOf(1000L * attempt, 12000L)
        updateLinkState(State.CONNECTING)
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
        // Search immediately after the first failure; keep the pinned identity until authentication.
        val now = android.os.SystemClock.elapsedRealtime()
        if (pin == null && !discovering && now - lastDiscovery > 3000) {
            discovering = true; lastDiscovery = now
            work.execute { rediscover() }
        }
    }

    private fun rediscover() {
        val identity = targetPc
        val found = identity?.let { pc -> Discovery.scan(1000).firstOrNull {
            it.host != host && (it.fingerprint == pc.fingerprint || it.fingerprint.isEmpty() && it.name.equals(pc.name, ignoreCase = true))
        } }
        main.post {
            discovering = false
            if (found != null && targetPc === identity && wantOnline && state != State.ONLINE && found.host != host) {
                host = found.host
                // Never save an unauthenticated UDP advertisement as the trusted PC address.
                startAttempt()
            }
        }
    }

    fun foreground() {
        main.post {
            conn?.checkNow()
            if (wantOnline && state != State.ONLINE && android.os.SystemClock.elapsedRealtime() - attemptStarted > 6000) startAttempt()
            else autoConnectIfNeeded()
        }
    }
    private fun networkChanged() {
        main.post {
            if (wantOnline) {
                if (state == State.ONLINE) conn?.invalidate("Сеть изменилась, восстанавливаю связь")
                main.removeCallbacks(retry); main.postDelayed(retry, 250)
            }
            else autoConnectIfNeeded()
        }
    }
    fun connectForShare() {
        main.post {
            if (state == State.ONLINE) { conn?.checkNow(); return@post }
            if (wantOnline) { if (android.os.SystemClock.elapsedRealtime() - attemptStarted > 4000) startAttempt(); return@post }
            val pc = Prefs.lastPc()
            if (pc?.key != null) connect(pc.host, null) else toast("Сначала подключите ПК в настройках")
        }
    }

    private fun goOffline(reason: String) {
        Features.stop()
        status = null
        updateLinkState(State.OFF)
        if (reason.isNotEmpty()) toast(reason)
    }

    private fun updateLinkState(s: State) {
        state = s
        emit("state")
        if (serviceAlive) Notifier.refreshLink(app)
    }

    private class ConnListener(val g: Int) : PcConnection.Listener {
        private fun live() = g == gen

        override fun onConnected(host: String) {
            main.post {
                if (!live()) return@post
                attempt = 0
                pcHost = host
                updateLinkState(State.ONLINE)
                sendHello()
                Features.start()
                for (upload in uploads.values) resumeUpload(upload)
            }
        }

        override fun onDisconnected(reason: String) {
            main.post {
                if (!live()) return@post
                Features.stop()
                conn = null
                status = null
                scheduleRetry(reason)
            }
        }

        override fun onAuthFailed(reason: Int) {
            main.post {
                if (!live()) return@post
                wantOnline = false
                conn = null
                val msg = when (reason) {
                    Proto.FAIL_UNKNOWN -> {
                        // ПК забыл этот телефон: старый ключ бесполезен
                        (targetPc ?: Prefs.pcByHost(host))?.let { it.key = null; Prefs.savePc(it) }
                        "ПК не знает этот телефон: введите код сопряжения заново"
                    }
                    Proto.FAIL_PAIRING_OFF -> "На ПК выключены новые сопряжения"
                    else -> "Неверный код сопряжения"
                }
                lastError = msg
                goOffline(msg)
                LinkService.stop(app)
            }
        }

        override fun onClipboard(text: String) {
            main.post {
                if (!live()) return@post
                lastClipFromPc = text
                try {
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PocketLink", text))
                } catch (_: Exception) { }
                emit("clip", text)
            }
        }

        override fun onJson(msg: JSONObject) {
            if (live()) onMessage(msg, g)
        }

        override fun onScreen(jpeg: ByteArray) {
            if (live()) emit("screen", jpeg)
        }

        override fun onTransfer(t: Transfer) {
            if (!live()) return
            if (t.id.startsWith("u") && t.state == "done") {
                val id = t.id.substring(1); uploads.remove(id); uploadRetries.remove(id); work.execute { outbox.remove(id) }
            } else if (t.id.startsWith("u") && t.state == "failed") {
                val id = t.id.substring(1)
                val count = uploadRetries.merge(id, 1) { a,b -> a+b } ?: 1
                main.postDelayed({ if (live() && wantOnline) uploads[id]?.let { resumeUpload(it) } }, minOf(1500L * count, 30000L))
            }
            synchronized(transferMap) {
                transferMap[t.id] = t
                while (transferMap.size > 40) {
                    val k = transferMap.keys.first()
                    transferMap.remove(k)
                }
            }
            emit("transfer", t)
        }

        override fun openIncomingFile(name: String, size: Long): IncomingTarget? = IncomingStorage.open(app, name)
    }

    /** Последний текст, пришедший с ПК: нужен, чтобы не отправлять его обратно как «новый буфер». */
    fun isFromPc(text: String) = text == lastClipFromPc

    // ---------- сообщения ----------

    private fun appearanceJson() = JSONObject()
        .put("mode", Prefs.themeMode).put("accent", Prefs.themeAccent).put("enabled", Prefs.syncAppearanceToPc)
        .put("dark", Prefs.themeMode == "dark" || Prefs.themeMode == "system" && (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)

    fun sendAppearance() { send(appearanceJson().put("t", "appearance.set")) }

    private fun sendHello() {
        val bat = Features.batteryJson()
        send(
            JSONObject().put("t", "hello")
                .put("name", Prefs.deviceName).put("model", Build.MODEL ?: "").put("sdk", Build.VERSION.RELEASE ?: "")
                .put("version", app.packageManager.getPackageInfo(app.packageName, 0).versionName).put("clipboardAck", true)
                .put("appearance", appearanceJson())
                .put("battery", bat)
                .put("perms", Features.permsJson())
        )
    }

    private fun onMessage(m: JSONObject, g: Int) {
        when (val t = m.optString("t")) {
            "hello" -> main.post { if (g == gen) handleHello(m) }
            "pc.config" -> main.post {
                if (g != gen) return@post
                m.optJSONObject("allow")?.let { allow = it }
                m.optJSONArray("commands")?.let { commands = it }
                emit("config")
                for (upload in uploads.values) resumeUpload(upload)
            }
            "pc.status" -> main.post { if (g == gen) { status = m; emit("status", m) } }
            "clip.apply" -> main.post {
                if (g != gen) return@post
                var ok = false
                val text = m.optString("text")
                if (can("clipboard") && text.toByteArray(Charsets.UTF_8).size <= 128 * 1024) {
                    try {
                        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PocketLink", text))
                        lastClipFromPc = text
                        emit("clip", text)
                        ok = true
                    } catch (_: Exception) { }
                }
                send(JSONObject().put("t", "clip.applied").put("id", m.optInt("id")).put("ok", ok))
            }
            "clip.pc.result" -> main.post { if (g == gen) toast(m.optString("message")) }
            "clip.ack" -> main.post {
                if (g == gen && pendingClipboard.remove(m.optInt("id"))) {
                    toast(if (m.optBoolean("ok")) "Получено на ПК" else "Не удалось записать в буфер ПК")
                }
            }
            "denied" -> main.post { if (g == gen) toast(deniedText(m.optString("what"))) }
            "ping" -> main.post {
                if (g == gen) {
                    val txt = m.optString("msg").ifEmpty { "Ping с ПК" }
                    Notifier.info(app, "PocketLink", txt)
                }
            }
            else -> {
                // функции телефона; остальное (fs.list, apps.list, proc.list, screen.info…) отдаём экранам
                if (!Features.handle(m)) emit("json", m)
                else if (t == "share.text") emit("json", m)
            }
        }
    }

    private fun deniedText(what: String) = when (what) {
        "keyboard" -> "На ПК запрещены мышь и клавиатура"
        "files" -> "На ПК запрещён доступ к файлам"
        "power" -> "На ПК запрещено управление питанием"
        "commands" -> "На ПК запрещены команды и программы"
        "processes" -> "На ПК запрещён диспетчер задач"
        "screen" -> "На ПК запрещён просмотр экрана"
        else -> "Действие запрещено на ПК"
    }

    private fun handleHello(m: JSONObject) {
        pcName = m.optString("name")
        pcOs = m.optString("os")
        m.optJSONObject("allow")?.let { allow = it }
        m.optJSONArray("commands")?.let { commands = it }
        val ml = mutableListOf<String>()
        m.optJSONArray("mac")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("mac")?.takeIf { it.isNotEmpty() }?.let { ml += it }
        }
        macs = ml
        val prev = targetPc ?: Prefs.pcByHost(host) ?: Prefs.pcs().firstOrNull { it.name == pcName }
        val key = m.optString("key").ifEmpty { prev?.key }
        val fingerprint = activeFingerprint.takeIf { it.length == 64 } ?: return
        targetPc = SavedPc(host, pcName, key, ml, fingerprint)
        Prefs.savePc(targetPc!!)
        pin = null // после сопряжения дальше работаем по ключу
        if (serviceAlive) Notifier.refreshLink(app)
        emit("config")
        for (upload in uploads.values) resumeUpload(upload)
    }

    fun send(o: JSONObject) { conn?.sendJson(o) }

    fun send(t: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("t", t)
        for ((k, v) in kv) o.put(k, v)
        send(o)
    }

    fun sendClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        if (isFromPc(text)) return false
        return conn?.takeIf { it.isConnected }?.sendClipboard(text) ?: false
    }

    private var clipboardSequence = 0
    private val pendingClipboard = mutableSetOf<Int>()

    fun sendClipboardManually(text: String) {
        if (text.isEmpty()) { toast("Буфер обмена пуст"); return }
        val id = ++clipboardSequence
        if (conn?.requestClipboard(text, id) != true) {
            toast(if (state == State.ONLINE) "Не удалось отправить буфер" else "Нет связи с ПК")
            return
        }
        pendingClipboard += id
        main.postDelayed({
            if (pendingClipboard.remove(id)) toast("ПК не подтвердил получение")
        }, 6000)
    }

    // ---------- файлы ----------

    fun sendUri(uri: Uri, relativeName: String? = null): Boolean {
        val destination = targetPc?.fingerprint ?: pin?.substringAfter('.')?.takeIf { it.length == 64 } ?: Prefs.lastPc()?.fingerprint
        if (destination == null) { toast("Сначала подключите ПК в настройках"); return false }
        connectForShare()
        work.execute {
            val power = TransferPower.acquire()
            try {
                val cr = app.contentResolver
                var name = uri.lastPathSegment ?: "file"
                runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        it.getString(0)?.let { n -> name = n }
                    }
                } }
                val item = outbox.enqueue(relativeName ?: name, destination) { cr.openInputStream(uri) }
                val upload = Upload(item.id, Uri.fromFile(item.file), item.name, item.size, item.hash, item.fingerprint)
                uploads[item.id] = upload
                main.post { showQueued(upload); resumeUpload(upload) }
            } catch (e: Exception) {
                toast(e.message ?: "Не удалось поставить файл в очередь")
            } finally { TransferPower.release(power) }
        }
        return true
    }

    private fun showQueued(upload: Upload) {
        val transfer = Transfer("u" + upload.id, false, upload.name, upload.size).apply { state = "queued" }
        synchronized(transferMap) { transferMap[transfer.id] = transfer }
        emit("transfer", transfer)
    }

    private fun resumeUpload(upload: Upload) {
        if (activeFingerprint != upload.fingerprint || !can("files")) return
        conn?.takeIf { it.isConnected }?.upload(upload.id, upload.name, upload.size, upload.hash) {
            app.contentResolver.openInputStream(upload.uri)
        }
    }

    fun cancelUpload(transferId: String) {
        if (transferId.startsWith("d")) { conn?.cancelDownload(transferId.substring(1)); return }
        if (!transferId.startsWith("u")) return
        val id = transferId.substring(1)
        uploads.remove(id)
        uploadRetries.remove(id)
        synchronized(transferMap) { transferMap[transferId]?.state = "cancelled" }
        emit("transfer")
        work.execute { outbox.remove(id) }
        conn?.cancelUpload(id)
    }

    fun retryUpload(transferId: String) {
        val upload = uploads[transferId.removePrefix("u")] ?: return
        connectForShare()
        resumeUpload(upload)
    }

    fun sendFolder(tree: Uri) {
        work.execute {
            try {
                val resolver = app.contentResolver
                val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val pending = java.util.ArrayDeque<Pair<String, String>>()
                pending.add(rootId to "")
                var count = 0
                while (pending.isNotEmpty() && count < 2000) {
                    val (parent, prefix) = pending.removeFirst()
                    if (prefix.count { it == '/' } > 14) continue
                    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                    resolver.query(children, arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext() && count < 2000) {
                            val id = cursor.getString(0)
                            val name = prefix + cursor.getString(1).replace('/', '_').replace('\\', '_')
                            if (cursor.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id to "$name/")
                            else {
                                count++
                                sendUri(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id), name)
                            }
                        }
                    }
                }
                toast("Файлов в очереди: $count")
            } catch (_: Exception) { toast("Не удалось прочитать выбранную папку") }
        }
    }

    fun sendFileStream(name: String, size: Long, opener: () -> java.io.InputStream?) {
        val c = conn ?: return
        work.execute {
            val s = try { opener() } catch (_: Exception) { null }
            if (s == null) toast("Не удалось открыть файл") else c.sendFile(name, size, s)
        }
    }

    // ---------- Wake-on-LAN ----------

    fun wake(pc: SavedPc) {
        work.execute {
            var sent = 0
            for (mac in pc.macs) {
                val bytes = parseMac(mac) ?: continue
                val pkt = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) pkt[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(bytes, 0, pkt, 6 + i * 6, 6)
                try {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        for (port in intArrayOf(9, 7)) {
                            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), port))
                        }
                    }
                    sent++
                } catch (_: Exception) { }
            }
            toast(if (sent > 0) "Сигнал включения отправлен" else "Нет сохранённых MAC-адресов ПК: подключитесь к нему хотя бы раз")
        }
    }

    private fun parseMac(s: String): ByteArray? {
        val parts = s.split('-', ':').filter { it.isNotEmpty() }
        if (parts.size != 6) return null
        return try { ByteArray(6) { parts[it].toInt(16).toByte() } } catch (_: Exception) { null }
    }
}

