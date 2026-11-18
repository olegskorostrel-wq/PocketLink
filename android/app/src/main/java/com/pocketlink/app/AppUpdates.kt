package com.pocketlink.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray

object AppUpdates {
    data class Release(val version: String, val tag: String, val url: String, val size: Long, val digest: String)
    private const val JOB = 14721
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun installed(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    private fun version(s: String): List<Int> = s.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    fun newer(a: String, b: String): Boolean {
        val left = version(a); val right = version(b)
        for (i in 0..2) { val delta = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 }); if (delta != 0) return delta > 0 }
        return false
    }
    fun check(ctx: Context): Release? {
        val conn = URL("https://api.github.com/repos/olegskorostrel-wq/PocketLink/releases?per_page=20").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "PocketLink-Android")
        try {
            if (conn.responseCode != 200) error("GitHub временно недоступен (${conn.responseCode})")
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                output.toByteArray()
            }
            if (bytes.size > 1024 * 1024) error("Слишком большой ответ GitHub")
            val data = JSONArray(bytes.toString(Charsets.UTF_8))
            var found: Release? = null
            for (i in 0 until data.length()) {
                val release = data.getJSONObject(i)
                val tag = release.optString("tag_name")
                if (release.optBoolean("draft") || !tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(-preview)?")) || !newer(tag, installed(ctx))) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val url = asset.optString("browser_download_url")
                    val expected = "https://github.com/olegskorostrel-wq/PocketLink/releases/download/$tag/PocketLink.apk"
                    val size = asset.optLong("size")
                    if (asset.optString("name") != "PocketLink.apk" || url != expected || size !in 1..200L*1024*1024) continue
                    if (found == null || newer(tag, found.version)) found = Release(tag.removePrefix("v"), tag, url, size, asset.optString("digest"))
                }
            }
            prefs(ctx).edit().putLong("checked", System.currentTimeMillis()).apply()
            return found
        } finally { conn.disconnect() }
    }
    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) != null) return
        scheduler.schedule(JobInfo.Builder(JOB, ComponentName(ctx, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(24 * 60 * 60 * 1000L).build())
    }
    fun notify(ctx: Context, release: Release) {
        if (prefs(ctx).getString("notified", "") == release.tag) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Обновления PocketLink", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).putExtra("open_updates", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(ctx, "updates").setSmallIcon(Notifier.smallIcon(ctx)).setLargeIcon(Notifier.brandIcon(ctx))
            .setContentTitle("PocketLink ${release.version} готов к обновлению")
            .setContentText("Нажми, чтобы посмотреть и скачать новую версию с GitHub")
            .setContentIntent(tap).setAutoCancel(true).build()
        if (nm.areNotificationsEnabled()) {
            nm.notify(77, notice)
            prefs(ctx).edit().putString("notified", release.tag).apply()
        }
    }
    fun download(ctx: Context, release: Release) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Папка загрузок недоступна")
        val file = File(dir, "PocketLink-update.apk")
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val old = prefs(ctx).getLong("download", -1)
        if (old != -1L) dm.remove(old)
        if (file.exists() && !file.delete()) error("Не удалось подготовить файл обновления")
        val request = DownloadManager.Request(Uri.parse(release.url)).setTitle("PocketLink ${release.version}")
            .setDescription("Обновление с GitHub").setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, file.name)
        val id = dm.enqueue(request)
        prefs(ctx).edit().putLong("download", id).putLong("size", release.size).putString("digest", release.digest).apply()
    }
    data class Progress(val status: Int, val percent: Int)
    fun progress(ctx: Context): Progress? {
        val id = prefs(ctx).getLong("download", -1)
        if (id == -1L) return null
        ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return Progress(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), if (total > 0) (done*100/total).toInt() else 0)
        }
        return null
    }
    fun verifiedUri(ctx: Context): Uri {
        if (progress(ctx)?.status != DownloadManager.STATUS_SUCCESSFUL) error("Сначала дождись окончания загрузки")
        val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PocketLink-update.apk")
        if (!file.isFile || file.length() != prefs(ctx).getLong("size", 0)) error("Файл загружен не полностью. Скачай его заново")
        val digest = prefs(ctx).getString("digest", "") ?: ""
        if (digest.startsWith("sha256:")) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != digest.removePrefix("sha256:")) error("Контрольная сумма не совпала. Установка отменена")
        }
        val pm = ctx.packageManager
        val own = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val apk = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Повреждённый APK")
        if (apk.packageName != ctx.packageName || apk.longVersionCode <= own.longVersionCode) error("Это не новое обновление PocketLink")
        val current = own.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val incoming = apk.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (current.isNullOrEmpty() || current != incoming) error("Подпись обновления отличается. Установка отменена")
        return ctx.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(prefs(ctx).getLong("download", -1)) ?: error("Файл обновления недоступен")
    }
}

class UpdateJobService : JobService() {
    @Volatile private var cancelled = false
    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        Thread { runCatching { AppUpdates.check(this)?.let { AppUpdates.notify(this, it) } }; if (!cancelled) jobFinished(params, false) }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelled = true; return true }
}
