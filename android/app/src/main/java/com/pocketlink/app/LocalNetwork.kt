package com.pocketlink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/** Route only home-network control sockets directly over Wi-Fi; the user's VPN stays active. */
object LocalNetwork {
    private fun wifi(): Network? = runCatching {
        val manager = Hub.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrNull()
    fun socket(host: String): Socket {
        val local = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*").matches(host)
        return if (local) runCatching { wifi()?.socketFactory?.createSocket() }.getOrNull() ?: Socket() else Socket()
    }
    fun bind(socket: DatagramSocket) { runCatching { wifi()?.bindSocket(socket) } }
    fun broadcasts(): List<InetAddress> {
        val all = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val manager = Hub.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = wifi() ?: return@runCatching
            manager.getLinkProperties(network)?.linkAddresses?.filter { it.address is Inet4Address && it.prefixLength in 1..30 }?.forEach {
                val bytes = it.address.address
                val address = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
                val hostMask = (1L shl (32 - it.prefixLength)) - 1
                val broadcast = address or hostMask
                all += InetAddress.getByAddress(ByteArray(4) { n -> (broadcast shr ((3 - n) * 8)).toByte() })
            }
        }
        return all.distinct()
    }
}
