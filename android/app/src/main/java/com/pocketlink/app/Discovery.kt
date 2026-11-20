package com.pocketlink.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Поиск ПК в локальной сети через UDP-broadcast. Вызывать не из главного потока. */
object Discovery {
    data class Found(val host: String, val name: String, val fingerprint: String = "")

    fun scan(timeoutMs: Long = 1500): List<Found> {
        val result = mutableListOf<Found>()
        try {
            DatagramSocket().use { socket ->
                LocalNetwork.bind(socket)
                socket.broadcast = true
                socket.soTimeout = 300
                val msg = "PLDISCOVER".toByteArray()
                for (address in LocalNetwork.broadcasts()) runCatching {
                    socket.send(DatagramPacket(msg, msg.size, address, Proto.DISCOVERY_PORT))
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        if (parts.size >= 3 && parts[0] == "PLHERE") {
                            val fingerprint = parts.getOrNull(3)?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: ""
                            result += Found(p.address.hostAddress ?: continue, parts[1], fingerprint)
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinctBy { it.host }
    }
}
