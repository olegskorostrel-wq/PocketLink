package com.pocketlink.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Private durable copies: a gallery's temporary URI grant must not expire before reconnection. */
class UploadOutbox(context: Context, directory: String = "upload-outbox") {
    data class Item(val id: String, val name: String, val size: Long, val hash: String, val fingerprint: String, val file: File)
    private val root = File(context.filesDir, directory).apply { mkdirs() }
    init {
        // Only abandoned files created by this queue; never remove a gallery original.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        root.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { file ->
            if (file.name.matches(Regex("[a-f0-9]{32}\\.(part|json\\.part)")) ||
                file.name.matches(Regex("[a-f0-9]{32}\\.bin")) && !File(root, file.name.removeSuffix(".bin") + ".json").exists()) file.delete()
        }
    }
    @Synchronized fun load(): List<Item> = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}\\.json")) }.mapNotNull { metadata ->
        runCatching {
            val o = JSONObject(metadata.readText())
            val id = metadata.name.removeSuffix(".json")
            val data = File(root, "$id.bin")
            val hash = o.getString("hash"); val fingerprint = o.getString("fingerprint"); val size = o.getLong("size")
            require(hash.matches(Regex("[a-f0-9]{64}")) && fingerprint.matches(Regex("[a-f0-9]{64}")))
            require(size >= 0 && data.isFile && data.length() == size)
            Item(id, o.getString("name"), size, hash, fingerprint, data)
        }.getOrNull()
    }
    @Synchronized fun enqueue(name: String, fingerprint: String, open: () -> java.io.InputStream?): Item {
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Сначала подключите и сохраните ПК" }
        val existing = load()
        require(existing.size < 128) { "Очередь передач заполнена" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val part = File(root, "$id.part"); val data = File(root, "$id.bin"); val metadata = File(root, "$id.json")
        val used = existing.sumOf { it.size }; val limit = 8L * 1024 * 1024 * 1024
        val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
        try {
            (open() ?: error("Не удалось открыть файл")).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count
                        require(used + size <= limit && root.usableSpace > 16L * 1024 * 1024) { "Недостаточно места для очереди отправки" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(part.renameTo(data)) { "Не удалось сохранить очередь" }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val pending = File(root, "$id.json.part")
            pending.writeText(JSONObject().put("name", name).put("size", size).put("hash", hash).put("fingerprint", fingerprint).toString())
            check(pending.renameTo(metadata)) { "Не удалось сохранить очередь" }
            return Item(id, name, size, hash, fingerprint, data)
        } catch (e: Exception) { part.delete(); data.delete(); metadata.delete(); File(root,"$id.json.part").delete(); throw e }
    }
    @Synchronized fun remove(id: String) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        File(root,"$id.json").delete(); File(root,"$id.bin").delete()
    }
}
