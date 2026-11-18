package com.pocketlink.app

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class ResumableDownload(
    private val context: Context,
    fingerprint: String,
    private val send: (JSONObject) -> Unit,
    private val update: (Transfer) -> Unit
) : Closeable {
    private data class Open(val file: RandomAccessFile, val partial: File, val metadata: File, val transfer: Transfer, val hash: String)
    private val directory = File(context.filesDir, "downloads/$fingerprint").apply { mkdirs() }
    private val files = mutableMapOf<String, Open>()
    private fun valid(id: String) = id.matches(Regex("[0-9a-f]{32}"))

    @Synchronized fun message(message: JSONObject) {
        val id = message.optString("id")
        if (!valid(id)) return
        when (message.optString("t")) {
            "download.begin" -> {
                files.remove(id)?.file?.close()
                if (files.size >= 8) return
                val size = message.optLong("size", -1)
                val hash = message.optString("hash")
                if (size < 0 || size > 8L * 1024 * 1024 * 1024 || !hash.matches(Regex("[0-9a-f]{64}"))) return
                val name = message.optString("name").replace('/', '_').replace('\\', '_').take(150).ifEmpty { "file" }
                val partial = File(directory, "$id.part")
                val metadata = File(directory, "$id.json")
                if (metadata.exists()) {
                    val old = runCatching { JSONObject(metadata.readText()) }.getOrNull()
                    if (old?.optString("hash") != hash || old?.optLong("size") != size) partial.delete()
                }
                metadata.writeText(JSONObject().put("name", name).put("size", size).put("hash", hash).toString())
                val file = RandomAccessFile(partial, "rw")
                if (file.length() > size) file.setLength(0)
                file.seek(file.length())
                val transfer = Transfer("d$id", true, name, size).apply { done = file.length() }
                files[id] = Open(file, partial, metadata, transfer, hash)
                update(transfer)
                send(JSONObject().put("t", "download.offset").put("id", id).put("offset", file.length()))
            }
            "download.finish" -> finish(id)
        }
    }

    @Synchronized fun chunk(bytes: ByteArray) {
        if (bytes.size <= 32) return
        val id = String(bytes, 0, 32, Charsets.US_ASCII)
        val item = files[id] ?: return
        if (item.file.length() + bytes.size - 32 > item.transfer.size) { cancel(id); return }
        item.file.write(bytes, 32, bytes.size - 32)
        item.transfer.done = item.file.length()
        update(item.transfer)
    }

    private fun finish(id: String) {
        val item = files.remove(id) ?: return
        item.file.close()
        var success = false
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            item.partial.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    hash.update(buffer, 0, read)
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (item.partial.length() == item.transfer.size && actual == item.hash) {
                val target = IncomingStorage.open(context, item.transfer.name) ?: throw java.io.IOException("Нет места для файла")
                try {
                    item.partial.inputStream().use { input -> target.stream.use { output -> input.copyTo(output) } }
                    target.finish(true)
                    success = true
                } finally { if (!success) target.finish(false) }
            }
        } finally {
            item.transfer.state = if (success) "done" else "failed"
            update(item.transfer)
            if (success) {
                item.partial.delete()
                item.metadata.delete()
                send(JSONObject().put("t", "download.complete").put("id", id))
            } else {
                item.partial.delete()
            }
        }
    }

    @Synchronized fun cancel(id: String) {
        if (!valid(id)) return
        files.remove(id)?.let { it.file.close(); it.transfer.state = "cancelled"; update(it.transfer) }
        File(directory, "$id.part").delete()
        File(directory, "$id.json").delete()
        send(JSONObject().put("t", "download.cancel").put("id", id))
    }

    @Synchronized override fun close() {
        for (item in files.values) {
            runCatching { item.file.close() }
            item.transfer.state = "failed"
            update(item.transfer)
        }
        files.clear()
    }
}
