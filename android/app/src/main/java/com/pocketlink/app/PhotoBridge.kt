package com.pocketlink.app

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Фото с телефона: список с миниатюрами и скачивание оригинала обычной передачей файла. */
object PhotoBridge {
    private val ctx get() = Hub.app
    private val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun handle(t: String, m: JSONObject) {
        when (t) {
            "photos.get" -> list(m.optLong("before", 0), m.optInt("limit", 40).coerceIn(1, 60))
            "photo.fetch" -> fetch(m.optString("id"))
        }
    }

    private fun list(before: Long, limit: Int) {
        val arr = JSONArray()
        var more = false
        if (Features.hasPhotos()) {
            try {
                val cols = arrayOf(
                    MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
                )
                val sel = if (before > 0) "${MediaStore.Images.Media.DATE_ADDED} < ?" else null
                val args = if (before > 0) arrayOf(before.toString()) else null
                var bytes = 0
                ctx.contentResolver.query(base, cols, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use {
                    while (it.moveToNext()) {
                        if (arr.length() >= limit || bytes > 700_000) { more = true; break }
                        val id = it.getLong(0)
                        val thumb = thumb(id)
                        if (thumb.isEmpty()) continue
                        bytes += thumb.length + 150
                        arr.put(
                            JSONObject().put("id", id.toString()).put("name", it.getString(1).orEmpty())
                                .put("time", it.getLong(2)).put("w", it.getInt(3)).put("h", it.getInt(4)).put("thumb", thumb)
                        )
                    }
                }
            } catch (_: Exception) { }
        }
        Hub.send("photos.list", "items" to arr, "more" to more)
    }

    private fun thumb(id: Long): String = try {
        val uri = ContentUris.withAppendedId(base, id)
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(220, 220), null)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { "" }

    private fun fetch(idStr: String) {
        val id = idStr.toLongOrNull() ?: return
        if (!Features.hasPhotos()) return
        val uri = ContentUris.withAppendedId(base, id)
        var name = "photo_$id.jpg"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    size = it.getLong(1)
                }
            }
        } catch (_: Exception) { return }
        if (size < 0) return
        Hub.sendFileStream(name, size) { ctx.contentResolver.openInputStream(uri) }
    }
}
