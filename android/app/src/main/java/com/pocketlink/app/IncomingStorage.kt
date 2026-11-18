package com.pocketlink.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Куда сохраняются файлы с ПК.
 * Android 10+: общая папка «Загрузки/PocketLink» через MediaStore (разрешения не нужны).
 * Android 8-9: папка приложения (Android/data/com.pocketlink.app/files/Download/PocketLink), тоже без разрешений.
 */
object IncomingStorage {

    fun open(context: Context, name: String): IncomingTarget? =
        if (Build.VERSION.SDK_INT >= 29) openMediaStore(context, name) else openLegacy(context, name)

    @RequiresApi(29)
    private fun openMediaStore(context: Context, name: String): IncomingTarget? {
        val resolver = context.contentResolver
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketLink")
            put(MediaStore.MediaColumns.IS_PENDING, 1) // пока файл пишется, другие приложения его не видят
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = try {
            resolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
        if (out == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return IncomingTarget(out) { success ->
            if (success) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun openLegacy(context: Context, name: String): IncomingTarget? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "PocketLink")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = uniqueFile(dir, name)
        val out = try {
            FileOutputStream(file)
        } catch (e: IOException) {
            return null
        }
        return IncomingTarget(out) { success -> if (!success) file.delete() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (file.exists()) {
            file = File(dir, "$stem ($i)$ext")
            i++
        }
        return file
    }
}
