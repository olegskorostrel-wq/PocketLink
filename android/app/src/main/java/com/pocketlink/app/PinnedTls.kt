package com.pocketlink.app

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal object PinnedTls {
    fun connect(socket: Socket, host: String, fingerprint: String): SSLSocket {
        require(fingerprint.length == 64)
        val expected = ByteArray(32) { index ->
            fingerprint.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("Client certificate is not supported")

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw CertificateException("Missing PC certificate")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                if (!MessageDigest.isEqual(actual, expected)) {
                    throw CertificateException("PC certificate changed")
                }
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val secured = context.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        secured.enabledProtocols = secured.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        try {
            secured.startHandshake()
            return secured
        } catch (e: Exception) {
            secured.close()
            throw e
        }
    }
}
