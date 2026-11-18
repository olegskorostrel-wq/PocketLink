package com.pocketlink.app

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Original images and videos. IDs include media type; downloads always use the original stream. */
object PhotoBridge {
    private val ctx get() = Hub.app
    fun handle(t: String, m: JSONObject) {
        when (t) {
            "photos.get" -> list(m.optLong("before", 0), m.optString("beforeId"), m.optInt("limit", 40).coerceIn(1, 60))
            "photo.fetch" -> fetch(m.optString("id"))
        }
    }
    private fun mediaUri(id: Long, video: Boolean) = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    private fun list(before: Long, beforeId: String, limit: Int) {
        val arr = JSONArray()
        var more = false
        var error = ""
        try {
            val types = mutableListOf<Int>()
            if (Features.hasPhotos()) types += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            if (Features.hasVideos()) types += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            if (types.isNotEmpty()) {
                val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Video.VideoColumns.DURATION)
                val conditions = mutableListOf("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${types.joinToString(",")})")
                val args = mutableListOf<String>()
                if (before > 0) {
                    val cursor = beforeId.removePrefix("i").removePrefix("v").toLongOrNull()
                    if (cursor != null) {
                        conditions += "(${MediaStore.MediaColumns.DATE_ADDED} < ? OR (${MediaStore.MediaColumns.DATE_ADDED} = ? AND ${MediaStore.MediaColumns._ID} < ?))"
                        args += before.toString(); args += before.toString(); args += cursor.toString()
                    } else { conditions += "${MediaStore.MediaColumns.DATE_ADDED} < ?"; args += before.toString() }
                }
                var bytes = 0
                ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, conditions.joinToString(" AND "), args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        if (arr.length() >= limit || bytes > 700_000) { more = true; break }
                        val id = c.getLong(0)
                        val video = c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val thumb = runCatching {
                            val bmp = ctx.contentResolver.loadThumbnail(mediaUri(id, video), Size(220, 220), null)
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out); bmp.recycle()
                            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        }.getOrDefault("")
                        bytes += thumb.length + 250
                        arr.put(JSONObject().put("id", (if (video) "v" else "i") + id).put("name", c.getString(1).orEmpty())
                            .put("time", c.getLong(2)).put("w", c.getInt(3)).put("h", c.getInt(4)).put("video", video)
                            .put("duration", if (video) c.getLong(6) else 0).put("thumb", thumb))
                    }
                }
            } else error = "Разрешите доступ к фото и видео на телефоне"
        } catch (_: Exception) { error = "Не удалось прочитать медиатеку телефона" }
        Hub.send("photos.list", "items" to arr, "more" to more, "error" to error)
    }
    private fun fetch(value: String) {
        val video = value.startsWith("v")
        val id = value.removePrefix("v").removePrefix("i").toLongOrNull() ?: return
        if (video && !Features.hasVideos() || !video && !Features.hasPhotos()) return
        val uri = mediaUri(id, video)
        // sendUri resolves original DISPLAY_NAME and size; no thumbnail conversion or generated photo_N filename.
        Hub.sendUri(uri)
    }
}
