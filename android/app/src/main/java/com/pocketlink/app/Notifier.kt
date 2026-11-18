package com.pocketlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Уведомления самого приложения: значок службы и короткие сообщения с ПК. */
object Notifier {
    const val CH_LINK = "link"
    const val CH_EVENTS = "events"
    const val ID_LINK = 1
    private var nextId = 100
    private var cachedSmallIcon: android.graphics.drawable.Icon? = null
    fun smallIcon(ctx: Context): android.graphics.drawable.Icon = cachedSmallIcon ?: run {
        val drawable = ctx.getDrawable(R.drawable.ic_notification_brand)!!
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(android.graphics.Canvas(bitmap))
        android.graphics.drawable.Icon.createWithBitmap(bitmap).also { cachedSmallIcon = it }
    }
    private var cachedBrand: android.graphics.Bitmap? = null
    fun brandIcon(ctx: Context): android.graphics.Bitmap = cachedBrand ?: run {
        val icon = ctx.applicationInfo.loadIcon(ctx.packageManager)
        val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 128, 128)
        icon.draw(android.graphics.Canvas(bitmap))
        bitmap.also { cachedBrand = it }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels(ctx: Context) {
        val m = nm(ctx)
        m.createNotificationChannel(
            NotificationChannel(CH_LINK, "Связь с ПК", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Значок, пока PocketLink держит связь с ПК"
                setShowBadge(false)
            }
        )
        m.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Сообщения с ПК", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Текст и ссылки, отправленные с ПК"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Постоянное уведомление службы. Кнопка «Буфер на ПК» читает буфер обмена из прозрачной Activity (так разрешает Android). */
    fun linkNotification(ctx: Context): Notification {
        val text = when (Hub.state) {
            Hub.State.ONLINE -> "Подключено: ${Hub.pcName}"
            Hub.State.CONNECTING -> "Подключение…"
            Hub.State.OFF -> "Нет связи с ПК"
        }
        val b = Notification.Builder(ctx, CH_LINK)
            .setSmallIcon(smallIcon(ctx))
            .setLargeIcon(brandIcon(ctx))
            .setContentTitle("PocketLink")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (Hub.state == Hub.State.ONLINE) {
            val clip = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(0, "Буфер на ПК", clip).build())
        }
        return b.build()
    }

    fun refreshLink(ctx: Context) {
        try { nm(ctx).notify(ID_LINK, linkNotification(ctx)) } catch (_: Exception) { }
    }

    /** Короткое сообщение с ПК. Если в тексте ссылка, нажатие откроет её. */
    fun info(ctx: Context, title: String, text: String) {
        val url = Regex("https?://\\S+").find(text)?.value
        val tap = if (url != null) PendingIntent.getActivity(
            ctx, nextId, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ) else openApp(ctx)
        val n = Notification.Builder(ctx, CH_EVENTS)
            .setSmallIcon(smallIcon(ctx))
            .setLargeIcon(brandIcon(ctx))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try { nm(ctx).notify(nextId++, n) } catch (_: Exception) { }
        if (nextId > 200) nextId = 100
    }
}
