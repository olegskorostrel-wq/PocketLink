package com.pocketlink.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** «Найти телефон»: громкий звонок и вибрация, пока не нажмут «стоп» на ПК (или 60 секунд). */
object FindPhone {
    private var ringtone: Ringtone? = null
    private var oldVolume = -1
    private val stopper = Runnable { stop() }

    fun start() {
        Hub.main.post {
            if (ringtone != null) return@post
            val ctx = Hub.app
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(ctx, uri)
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.isLooping = true
                r.play()
                ringtone = r

                @Suppress("DEPRECATION")
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
            } catch (_: Exception) { }
            Hub.main.removeCallbacks(stopper)
            Hub.main.postDelayed(stopper, 60_000)
        }
    }

    fun stop() {
        Hub.main.post {
            Hub.main.removeCallbacks(stopper)
            val ctx = Hub.app
            try { ringtone?.stop() } catch (_: Exception) { }
            ringtone = null
            try {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            } catch (_: Exception) { }
            if (oldVolume >= 0) {
                try {
                    (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
                } catch (_: Exception) { }
                oldVolume = -1
            }
        }
    }
}
