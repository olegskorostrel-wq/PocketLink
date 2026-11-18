package com.pocketlink.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class RelayTunnel : Closeable {
    private val closed = AtomicBoolean(false)
    private var websocket: WebSocket? = null
    private var local: Socket? = null
    private var listening: ServerSocket? = null
    private var client: OkHttpClient? = null
    private val ready = CountDownLatch(1)
    private var failure: String? = null

    fun connect(url: String, room: String, token: String): Socket {
        require(url.startsWith("wss://") && room.matches(Regex("[a-f0-9]{32}")) && token.matches(Regex("[a-f0-9]{64}")))
        val request = Request.Builder().url(url.trimEnd('/') + "/room/" + room)
            .header("X-PocketLink-Role", "phone").header("X-PocketLink-Token", token).build()
        val service = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
        client = service
        websocket = service.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ready") ready.countDown() else close()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { local?.getOutputStream()?.write(bytes.toByteArray()) ?: throw IOException("Local tunnel closed") }
                catch (_: IOException) { close() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t.message
                ready.countDown()
                close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        if (!ready.await(12, TimeUnit.SECONDS) || closed.get() || failure != null) {
            close()
            throw IOException("Не удалось соединиться с ПК через посредника")
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket().apply { bind(InetSocketAddress(loopback, 0)); soTimeout = 5000 }
        listening = server
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(loopback, server.localPort), 3000)
            local = server.accept().apply { tcpNoDelay = true }
            server.close()
            listening = null
        } catch (e: Exception) {
            socket.close()
            close()
            throw e
        }
        thread(name = "pl-relay", isDaemon = true) {
            try {
                val input = local!!.getInputStream()
                val buffer = ByteArray(32768)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val ws = websocket ?: break
                    if (ws.queueSize() > 2 * 1024 * 1024 || !ws.send(buffer.toByteString(0, count))) break
                }
            } catch (_: IOException) {
            } finally { close() }
        }
        return socket
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown()
        runCatching { local?.close() }
        runCatching { listening?.close() }
        websocket?.cancel()
        client?.connectionPool?.evictAll()
        client?.dispatcher?.executorService?.shutdown()
    }
}
