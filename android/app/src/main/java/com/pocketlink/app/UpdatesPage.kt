package com.pocketlink.app

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ScrollView

class UpdatesPage(act: MainActivity) : Page(act) {
    private val body = vbox(act, 16)
    private val message = tv(act, "Проверить новые версии на GitHub", 16f)
    private val progress = muted(act, "")
    private var release: AppUpdates.Release? = null
    private var checking = false
    private var visible = false
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            showProgress()
            Hub.main.postDelayed(this, 1000)
        }
    }
    override val root: View = ScrollView(act).apply {
        addView(body)
        body.addView(tv(act, "Обновления PocketLink", 23f, T.text, true))
        body.addView(muted(act, "Установлена версия ${AppUpdates.installed(act)}"), lp(t = 6, b = 16, ctx = act))
        val info = card(act)
        info.addView(message)
        info.addView(muted(act, "Проверка при открытии и примерно раз в сутки в фоне. Android может откладывать фоновые задания. Предварительные версии тоже включены."), lp(t = 8, ctx = act))
        body.addView(info)
        body.addView(btn(act, "Проверить сейчас", 1) { check() }, lp(t = 12, ctx = act))
        body.addView(btn(act, "Скачать обновление") {
            val r = release
            if (r == null) { Hub.toast("Сначала проверь наличие обновления"); return@btn }
            val existing = AppUpdates.progress(act)
            if (existing != null && existing.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)) {
                Hub.toast("Обновление уже скачивается"); return@btn
            }
            runCatching { AppUpdates.download(act, r); showProgress() }.onFailure { Hub.toast(it.message ?: "Не удалось скачать") }
        }, lp(t = 8, ctx = act))
        body.addView(progress, lp(t = 12, b = 8, ctx = act))
        body.addView(btn(act, "Установить скачанное", 1) { install() })
        body.addView(muted(act, "APK проверяется по подписи установленного приложения и контрольной сумме GitHub, если она доступна. Установка подтверждается в системном окне Android. Настройки и сопряжённые ПК сохраняются."), lp(t = 16, ctx = act))
        body.addView(btn(act, "Открыть релизы на GitHub") {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/olegskorostrel-wq/PocketLink/releases")))
        }, lp(t = 16, ctx = act))
    }
    private fun check() {
        if (checking) return
        checking = true; message.text = "Проверяю GitHub…"
        Hub.work.execute {
            val result = runCatching { AppUpdates.check(act) }
            Hub.main.post {
                checking = false
                result.onSuccess { r -> release = r; message.text = if (r == null) "Установлена актуальная версия" else "Доступна версия ${r.version} · ${fmtSize(r.size)}" }
                    .onFailure { message.text = "Не удалось проверить обновления. ${it.message ?: "Проверь интернет"}" }
            }
        }
    }
    private fun showProgress() {
        val p = AppUpdates.progress(act)
        progress.text = when (p?.status) {
            DownloadManager.STATUS_PENDING -> "Загрузка ожидает запуска"
            DownloadManager.STATUS_RUNNING -> "Скачивание: ${p.percent}%"
            DownloadManager.STATUS_PAUSED -> "Загрузка приостановлена — Android продолжит её при доступной сети"
            DownloadManager.STATUS_SUCCESSFUL -> "APK скачан. Нажми «Установить скачанное»"
            DownloadManager.STATUS_FAILED -> "Не удалось скачать APK. Повтори загрузку"
            else -> "Обновление ещё не скачано"
        }
    }
    private fun install() {
        progress.text = "Проверяю подпись APK…"
        Hub.work.execute {
            val result = runCatching { AppUpdates.verifiedUri(act) }
            Hub.main.post {
                result.onSuccess { uri ->
                    if (!act.packageManager.canRequestPackageInstalls()) {
                        progress.text = "Разреши обновления из PocketLink, затем снова нажми «Установить скачанное»"
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                    } else {
                        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { progress.text = "Не удалось открыть установщик Android: ${it.message}" }
                    }
                }.onFailure { progress.text = it.message ?: "Проверка APK не пройдена" }
            }
        }
    }
    override fun onShow() { visible = true; Hub.main.removeCallbacks(tick); tick.run(); check() }
    override fun onHide() { visible = false; Hub.main.removeCallbacks(tick) }
}
