package com.pocketlink.app

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

/**
 * Куда писать входящий файл. [finish] вызывается ровно один раз: success = true, если файл получен целиком,
 * иначе нужно удалить недокачанные данные.
 */
class IncomingTarget(val stream: OutputStream, private val onFinish: (success: Boolean) -> Unit) {
    private val done = AtomicBoolean(false)

    /** Безопасно вызывать повторно: сработает только первый вызов. */
    fun finish(success: Boolean) {
        if (done.compareAndSet(false, true)) onFinish(success)
    }
}

/** Состояние одной передачи файла (для списка передач). */
class Transfer(val id: String, val incoming: Boolean, val name: String, val size: Long) {
    @Volatile var done = 0L
    @Volatile var state = "active" // active | done | failed
}

/**
 * Одно соединение с ПК. После закрытия объект не переиспользуется, для нового подключения создаётся новый.
 * Все команды идут по TLS. Дельты мыши накапливаются в отдельном потоке.
 * Все колбэки Listener приходят из сетевых потоков, не из UI.
 */
class PcConnection(private val listener: Listener) {

    interface Listener {
        fun onConnected(host: String)
        fun onDisconnected(reason: String)
        fun onAuthFailed(reason: Int)
        fun onClipboard(text: String)
        fun onJson(msg: JSONObject)
        fun onScreen(jpeg: ByteArray)
        fun onTransfer(t: Transfer)

        /** Вернуть null, если файл принять нельзя. */
        fun openIncomingFile(name: String, size: Long): IncomingTarget?
    }

    private class Incoming(val target: IncomingTarget, val tr: Transfer) {
        var lastReport = 0L
    }

    private val incoming = HashMap<Int, Incoming>()
    private val closed = AtomicBoolean(false)

    @Volatile
    var isConnected = false
        private set

    private var socket: Socket? = null
    private var tunnel: RelayTunnel? = null
    private var downloads: ResumableDownload? = null
    private var out: OutputStream? = null
    private val writeLock = Any()


    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-ctl").apply { isDaemon = true } }
    private val fileExec = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-file").apply { isDaemon = true } }
    private val nextFileId = AtomicInteger(1)
    private val microphoneSending = AtomicBoolean(false)
    private val cameraSending = AtomicBoolean(false)
    val cameraBusy: Boolean get() = cameraSending.get() || !isConnected
    private val cameraExec = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-camera-send").apply { isDaemon = true } }
    private val audioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-audio").apply { isDaemon = true } }
    private val uploadReplies = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val activeUploads = ConcurrentHashMap.newKeySet<String>()
    private val cancelledUploads = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastReceive = android.os.SystemClock.elapsedRealtime()
    @Volatile private var healthDeadline = Long.MAX_VALUE

    private val mouseSignal = Semaphore(0)
    private val mouseLock = Any()
    private var pendMoveX = 0
    private var pendMoveY = 0
    private var pendScrollX = 0
    private var pendScrollY = 0

    // ---------- подключение ----------

    /** mode: Proto.AUTH_PIN (secret = код) или Proto.AUTH_KEY (secret = ключ устройства). */
    fun connect(host: String, mode: Int, secret: ByteArray, deviceId: String, deviceName: String, fingerprint: String) {
        if (closed.get()) return
        thread(name = "pl-connect", isDaemon = true) {
            try {
                val throughRelay = Prefs.useRelay && mode == Proto.AUTH_KEY
                val raw = if (throughRelay) {
                    RelayTunnel().also { tunnel = it }.connect(Prefs.relayUrl, Prefs.relayRoom, Prefs.relayToken)
                } else LocalNetwork.socket(host)
                socket = raw
                raw.tcpNoDelay = true
                raw.keepAlive = true
                if (closed.get()) { raw.close(); return@thread }
                if (!throughRelay) raw.connect(InetSocketAddress(host, Proto.TCP_PORT), 2500)
                raw.soTimeout = 5000
                val s = PinnedTls.connect(raw, host, fingerprint)
                socket = s
                if (closed.get()) { s.close(); return@thread }
                val monitored = object : java.io.FilterInputStream(s.getInputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                        val received = super.read(buffer, offset, count)
                        if (received > 0) { lastReceive = android.os.SystemClock.elapsedRealtime(); healthDeadline = Long.MAX_VALUE }
                        return received
                    }
                    override fun read(): Int {
                        val value = super.read()
                        if (value >= 0) { lastReceive = android.os.SystemClock.elapsedRealtime(); healthDeadline = Long.MAX_VALUE }
                        return value
                    }
                }
                val input = DataInputStream(monitored)
                out = s.getOutputStream()

                val (type, nonce) = readFrame(input)
                if (type != Proto.CHALLENGE || nonce.size != 16) throw IOException("bad handshake")

                val mac = Mac.getInstance("HmacSHA256")
                    .apply { init(SecretKeySpec(secret, "HmacSHA256")) }
                    .doFinal(nonce)
                val idB = deviceId.take(60).toByteArray(Charsets.UTF_8)
                val nameB = deviceName.take(30).toByteArray(Charsets.UTF_8)
                val auth = ByteBuffer.allocate(1 + 1 + idB.size + 1 + nameB.size + mac.size)
                    .put(mode.toByte())
                    .put(idB.size.toByte()).put(idB)
                    .put(nameB.size.toByte()).put(nameB)
                    .put(mac).array()
                writeFrame(Proto.AUTH, auth)

                val (rType, rData) = readFrame(input)
                if (rType == Proto.AUTH_FAIL) {
                    val reason = if (rData.isNotEmpty()) rData[0].toInt() else Proto.FAIL_PIN
                    if (closed.compareAndSet(false, true)) {
                        cleanup()
                        listener.onAuthFailed(reason)
                    }
                    return@thread
                }
                if (rType != Proto.AUTH_OK || rData.size < 10) throw IOException("bad auth reply")

                s.soTimeout = 45000 // Backup read timeout; the independent heartbeat watchdog reconnects earlier.

                isConnected = true
                lastReceive = android.os.SystemClock.elapsedRealtime()
                downloads = ResumableDownload(Hub.app, fingerprint, ::sendJson, listener::onTransfer)
                listener.onConnected(host)
                startMouseSender()
                startKeepAlive()

                while (!closed.get()) {
                    val (t, d) = readFrame(input)
                    when (t) {
                        Proto.CLIPBOARD -> listener.onClipboard(String(d, Charsets.UTF_8))
                        Proto.FILE_START -> startIncoming(d)
                        Proto.FILE_CHUNK -> chunkIncoming(d)
                        Proto.FILE_END -> endIncoming(d)
                        0x35 -> downloads?.chunk(d)
                        Proto.SCREEN -> listener.onScreen(d)
                        Proto.MSG -> {
                            val o = try { JSONObject(String(d, Charsets.UTF_8)) } catch (_: Exception) { null }
                            if (o != null) {
                                if (o.optString("t").startsWith("download.")) downloads?.message(o)
                                if (o.optString("t").startsWith("upload.")) {
                                    uploadReplies.remove(o.optString("id"))?.complete(o)
                                }
                                listener.onJson(o)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                close(describe(e))
            }
        }
    }

    private fun describe(e: Exception) = when (e) {
        is SSLHandshakeException -> "Не удалось подтвердить сертификат ПК. Проверь код и версию PocketLink."
        is SocketTimeoutException -> "Нет ответа от ПК"
        is ConnectException, is NoRouteToHostException -> "Не удалось подключиться к ПК"
        else -> "Соединение потеряно"
    }

    fun disconnect() = close(null)
    fun invalidate(reason: String) = close(reason)
    fun checkNow() {
        if (!isConnected || closed.get()) return
        if (android.os.SystemClock.elapsedRealtime() - lastReceive > 5000) healthDeadline = android.os.SystemClock.elapsedRealtime() + 3000
        thread(name="pl-health-probe",isDaemon=true) { runCatching { writeFrame(Proto.PING, ByteArray(0)) } }
    }

    private fun cleanup() {
        isConnected = false
        runCatching { socket?.close() }
        tunnel?.close()
        downloads?.close()
        control.shutdownNow()
        fileExec.shutdownNow()
        audioExec.shutdownNow()
        cameraExec.shutdownNow()
        for (reply in uploadReplies.values) reply.completeExceptionally(IOException("Connection closed"))
        uploadReplies.clear()
        mouseSignal.release()
        abortAllIncoming()
    }

    private fun close(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        isConnected = false
        // TLS close can flush bytes and block; never perform it on Android's UI thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            thread(name="pl-close",isDaemon=true) { cleanup() }
        else cleanup()
        if (reason != null) listener.onDisconnected(reason)
    }

    // ---------- входящие файлы (ПК -> телефон) ----------

    private fun startIncoming(d: ByteArray) {
        if (d.size <= 12) return
        val bb = ByteBuffer.wrap(d)
        val id = bb.getInt()
        val size = bb.getLong()
        val name = sanitizeName(String(d, 12, d.size - 12, Charsets.UTF_8))

        val previous = synchronized(incoming) { incoming.remove(id) }
        if (previous != null) abortIncoming(previous)

        val tooMany = synchronized(incoming) { incoming.size >= MAX_INCOMING }
        val tr = Transfer("i$id", true, name, size)
        if (tooMany || size < 0 || size > MAX_INCOMING_SIZE) {
            tr.state = "failed"; listener.onTransfer(tr)
            return
        }
        val target = try { listener.openIncomingFile(name, size) } catch (e: Exception) { null }
        if (target == null) {
            tr.state = "failed"; listener.onTransfer(tr)
            return
        }
        synchronized(incoming) { incoming[id] = Incoming(target, tr) }
        listener.onTransfer(tr)
    }

    private fun chunkIncoming(d: ByteArray) {
        if (d.size <= 4) return
        val id = ByteBuffer.wrap(d).getInt()
        val inc = synchronized(incoming) { incoming[id] } ?: return
        val n = d.size - 4
        if (inc.tr.done + n > inc.tr.size) {
            failIncoming(id, inc)
            return
        }
        try {
            inc.target.stream.write(d, 4, n)
        } catch (e: IOException) {
            failIncoming(id, inc)
            return
        }
        inc.tr.done += n
        val now = System.currentTimeMillis()
        if (now - inc.lastReport > 250) {
            inc.lastReport = now
            listener.onTransfer(inc.tr)
        }
    }

    private fun endIncoming(d: ByteArray) {
        if (d.size < 4) return
        val id = ByteBuffer.wrap(d).getInt()
        val inc = synchronized(incoming) { incoming.remove(id) } ?: return
        var ok = inc.tr.done == inc.tr.size
        try { inc.target.stream.close() } catch (e: IOException) { ok = false }
        inc.target.finish(ok)
        inc.tr.state = if (ok) "done" else "failed"
        listener.onTransfer(inc.tr)
    }

    private fun failIncoming(id: Int, inc: Incoming) {
        synchronized(incoming) { incoming.remove(id) }
        abortIncoming(inc)
        inc.tr.state = "failed"
        listener.onTransfer(inc.tr)
    }

    private fun abortIncoming(inc: Incoming) {
        runCatching { inc.target.stream.close() }
        runCatching { inc.target.finish(false) }
    }

    private fun abortAllIncoming() {
        val all = synchronized(incoming) {
            val copy = incoming.values.toList()
            incoming.clear()
            copy
        }
        if (all.isEmpty()) return
        thread(name = "pl-abort", isDaemon = true) {
            all.forEach { abortIncoming(it); it.tr.state = "failed"; listener.onTransfer(it.tr) }
        }
    }

    private fun sanitizeName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimEnd('.')
        if (n.isEmpty()) n = "file"
        if (n.length <= 220) return n
        val ext = n.substringAfterLast('.', "").take(20).let { if (it.isEmpty()) "" else ".$it" }
        return n.substringBeforeLast('.', n).take(220 - ext.length) + ext
    }

    private companion object {
        const val MAX_INCOMING = 8
        const val MAX_INCOMING_SIZE = 8L * 1024 * 1024 * 1024
    }

    // ---------- кадры TCP ----------

    private fun readFrame(inp: DataInputStream): Pair<Int, ByteArray> {
        val len = inp.readInt()
        if (len < 1 || len > Proto.MAX_FRAME) throw IOException("bad frame length")
        val type = inp.readUnsignedByte()
        val data = ByteArray(len - 1)
        inp.readFully(data)
        return type to data
    }

    private fun writeFrame(type: Int, data: ByteArray) {
        val frame = ByteBuffer.allocate(5 + data.size)
            .putInt(1 + data.size).put(type.toByte()).put(data).array()
        synchronized(writeLock) {
            val o = out ?: throw IOException("not connected")
            o.write(frame)
            o.flush()
        }
    }

    /** Отправка из любого потока (в том числе главного) через единый поток управления. */
    private fun submit(block: () -> Unit) {
        try {
            control.execute {
                try { block() } catch (e: Exception) { close("Соединение потеряно") }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun startKeepAlive() = thread(name = "pl-ping", isDaemon = true) {
        var checked = android.os.SystemClock.elapsedRealtime()
        var nextPing = checked
        while (!closed.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            val now = android.os.SystemClock.elapsedRealtime()
            // Give a live socket a chance to answer after Android wakes from deep sleep.
            if (now - checked > 10000) { lastReceive = now; healthDeadline = now + 5000 }
            checked = now
            if (now > healthDeadline || now - lastReceive > (if (Hub.uiVisible) 12000 else 25000)) { close("Восстанавливаю связь с ПК"); return@thread }
            if (now >= nextPing) {
                nextPing = now + 4000
                submit { writeFrame(Proto.PING, ByteArray(0)) }
            }
        }
    }

    // ---------- мышь ----------

    fun move(dx: Int, dy: Int) {
        if (!isConnected) return
        synchronized(mouseLock) { pendMoveX += dx; pendMoveY += dy }
        mouseSignal.release()
    }

    fun scroll(dx: Int, dy: Int) {
        if (!isConnected) return
        synchronized(mouseLock) { pendScrollX += dx; pendScrollY += dy }
        mouseSignal.release()
    }

    private fun startMouseSender() = thread(name = "pl-mouse", isDaemon = true) {
        try {
            while (!closed.get()) {
                mouseSignal.acquire()
                mouseSignal.drainPermits()
                var mx = 0
                var my = 0
                var sx = 0
                var sy = 0
                synchronized(mouseLock) {
                    mx = pendMoveX; my = pendMoveY; sx = pendScrollX; sy = pendScrollY
                    pendMoveX = 0; pendMoveY = 0; pendScrollX = 0; pendScrollY = 0
                }
                while (mx != 0 || my != 0) {
                    val cx = mx.coerceIn(-32767, 32767)
                    val cy = my.coerceIn(-32767, 32767)
                    sendPointer(Proto.UDP_MOVE, cx, cy)
                    mx -= cx; my -= cy
                }
                while (sx != 0 || sy != 0) {
                    val cx = sx.coerceIn(-32767, 32767)
                    val cy = sy.coerceIn(-32767, 32767)
                    sendPointer(Proto.UDP_SCROLL, cx, cy)
                    sx -= cx; sy -= cy
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            close(describe(e))
        }
    }

    private fun sendPointer(type: Int, a: Int, b: Int) {
        val data = ByteBuffer.allocate(5).put(type.toByte()).putShort(a.toShort()).putShort(b.toShort()).array()
        writeFrame(Proto.POINTER, data)
    }

    // ---------- остальное (TCP) ----------

    fun button(button: Int, down: Boolean) =
        submit { writeFrame(Proto.BUTTON, byteArrayOf(button.toByte(), if (down) 1 else 0)) }

    fun sendText(text: String) = submit { writeFrame(Proto.TEXT, text.toByteArray(Charsets.UTF_8)) }

    fun sendMicrophone(bytes: ByteArray) {
        if (!isConnected || !microphoneSending.compareAndSet(false, true)) return
        try {
            audioExec.execute {
                try { writeFrame(Proto.MICROPHONE, bytes) }
                catch (e: IOException) { close(describe(e)) }
                finally { microphoneSending.set(false) }
            }
        } catch (_: RejectedExecutionException) { microphoneSending.set(false) }
    }

    fun sendCamera(jpeg: ByteArray, orientation: Int, mirrored: Boolean) {
        if (!isConnected || jpeg.size > 900000 || !cameraSending.compareAndSet(false, true)) return
        try { cameraExec.execute {
            try { writeFrame(Proto.CAMERA, byteArrayOf(((orientation / 90) and 3).toByte(), if (mirrored) 1 else 0) + jpeg) }
            catch (error: IOException) { close(describe(error)) }
            finally { cameraSending.set(false) }
        } } catch (_: RejectedExecutionException) { cameraSending.set(false) }
    }

    fun sendClipboard(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Proto.MAX_FRAME - 16) return false
        submit { writeFrame(Proto.CLIPBOARD, bytes) }
        return true
    }

    fun requestClipboard(text: String, id: Int): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (!isConnected || bytes.size > Proto.MAX_FRAME - 16) return false
        return try {
            control.execute {
                try { writeFrame(Proto.CLIPBOARD_REQUEST, ByteBuffer.allocate(bytes.size + 4).putInt(id).put(bytes).array()) }
                catch (e: IOException) { close(describe(e)) }
            }
            true
        } catch (_: RejectedExecutionException) { false }
    }

    fun sendJson(o: JSONObject) {
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > Proto.MAX_FRAME - 16) return
        submit { writeFrame(Proto.MSG, bytes) }
    }

    fun cancelUpload(id: String) {
        cancelledUploads += id
        uploadReplies.remove(id)?.completeExceptionally(IOException("Cancelled"))
        sendJson(JSONObject().put("t", "upload.cancel").put("id", id))
    }

    fun cancelDownload(id: String) { downloads?.cancel(id) }

    fun upload(id: String, name: String, size: Long, hash: String, open: () -> InputStream?) {
        if (!activeUploads.add(id)) return
        try {
            fileExec.execute {
                val transfer = Transfer("u$id", false, name, size)
                val power = TransferPower.acquire()
                listener.onTransfer(transfer)
                try {
                    val offsetReply = CompletableFuture<JSONObject>()
                    uploadReplies[id] = offsetReply
                    writeFrame(Proto.MSG, JSONObject().put("t", "upload.begin").put("id", id).put("name", name)
                        .put("size", size).put("hash", hash).toString().toByteArray(Charsets.UTF_8))
                    val offset = offsetReply.get(12, TimeUnit.SECONDS).optLong("offset", -1)
                    if (offset < 0 || offset > size) throw IOException("Transfer rejected")
                    transfer.done = offset
                    val input = open() ?: throw IOException("Source is unavailable")
                    input.use {
                        var skip = offset
                        while (skip > 0) {
                            val skipped = it.skip(skip)
                            if (skipped > 0) skip -= skipped
                            else { if (it.read() < 0) throw IOException("Source changed"); skip-- }
                        }
                        val buffer = ByteArray(65536 + 32)
                        id.toByteArray(Charsets.US_ASCII).copyInto(buffer)
                        var reported = 0L
                        while (transfer.done < size) {
                            if (closed.get() || id in cancelledUploads) throw IOException("Transfer interrupted")
                            val count = it.read(buffer, 32, minOf(65536L, size - transfer.done).toInt())
                            if (count <= 0) throw IOException("Source changed")
                            writeFrame(Proto.RESUMABLE_CHUNK, buffer.copyOf(32 + count))
                            transfer.done += count
                            if (System.currentTimeMillis() - reported > 200) {
                                listener.onTransfer(transfer)
                                reported = System.currentTimeMillis()
                            }
                        }
                    }
                    val completed = CompletableFuture<JSONObject>()
                    uploadReplies[id] = completed
                    writeFrame(Proto.MSG, JSONObject().put("t", "upload.finish").put("id", id).toString().toByteArray(Charsets.UTF_8))
                    if (!completed.get(120, TimeUnit.SECONDS).optBoolean("ok")) throw IOException("Checksum verification failed")
                    transfer.state = "done"
                } catch (_: Exception) {
                    transfer.state = if (id in cancelledUploads) "cancelled" else "failed"
                } finally {
                    TransferPower.release(power)
                    uploadReplies.remove(id)
                    activeUploads.remove(id)
                    listener.onTransfer(transfer)
                }
            }
        } catch (_: RejectedExecutionException) { activeUploads.remove(id) }
    }

    /** Отправка файла в отдельном потоке, чтобы не блокировать мышь и кнопки. */
    fun sendFile(name: String, size: Long, stream: InputStream) {
        try {
            fileExec.execute { doSendFile(name, size, stream) }
        } catch (_: RejectedExecutionException) {
            runCatching { stream.close() }
        }
    }

    private fun doSendFile(name: String, size: Long, stream: InputStream) {
        val id = nextFileId.getAndIncrement()
        val tr = Transfer("o$id", false, name, size)
        listener.onTransfer(tr)
        try {
            stream.use { input ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                writeFrame(
                    Proto.FILE_START,
                    ByteBuffer.allocate(12 + nameBytes.size).putInt(id).putLong(size).put(nameBytes).array()
                )
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (_: IOException) { -2 }
                    if (n <= 0) break
                    writeFrame(Proto.FILE_CHUNK, ByteBuffer.allocate(4 + n).putInt(id).put(buf, 0, n).array())
                    tr.done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        listener.onTransfer(tr)
                    }
                }
                writeFrame(Proto.FILE_END, ByteBuffer.allocate(4).putInt(id).array())
                tr.state = if (tr.done == size) "done" else "failed"
                listener.onTransfer(tr)
            }
        } catch (e: Exception) {
            tr.state = "failed"
            listener.onTransfer(tr)
            close("Соединение потеряно")
        }
    }
}
