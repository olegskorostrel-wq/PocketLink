package com.pocketlink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@SuppressLint("ClickableViewAccessibility")
class MicrophonePage(act: MainActivity) : Page(act) {
    private val status = muted(act, "Выбери звуковое устройство на ПК")
    private var selected = ""
    private var shown = false
    private var pressed = false
    private val recording = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    override val root: View = vbox(act, 14).apply {
        addView(status)
        addView(muted(act, "Для использования голоса в Discord или игре выбери выход виртуального аудиокабеля, уже установленного на ПК. Выбор колонок выведет голос на колонки.", 13f))
        addView(btn(act, "Выбрать устройство ПК") { Hub.send("microphone.devices") })
        addView(btn(act, "Разрешить микрофон") { act.askPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {} })
        addView(btn(act, "Удерживай, чтобы говорить", 1) {}.apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (selected.isEmpty()) Hub.toast("Выбери устройство на ПК")
                        else if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            act.askPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {}
                        else if (Hub.state != Hub.State.ONLINE) Hub.toast("Нет связи с ПК")
                        else {
                            pressed = true
                            Hub.send("microphone.start", "device" to selected)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
                }
                true
            }
        })
    }
    override fun onShow() { shown = true }
    override fun onHide() { shown = false; stop() }
    override fun onHub(ev: String, data: Any?) {
        if (ev == "state" && Hub.state != Hub.State.ONLINE) { stop(); return }
        val message = data as? JSONObject ?: return
        if (ev != "json") return
        when (message.optString("t")) {
            "microphone.devices" -> {
                if (!shown) return
                val devices = message.optJSONArray("items") ?: return
                AlertDialog.Builder(act).setTitle("Куда передавать голос").setItems(
                    Array(devices.length()) { devices.getJSONObject(it).optString("name") }
                ) { _, index ->
                    val device = devices.getJSONObject(index)
                    selected = device.optString("id")
                    status.text = device.optString("name")
                }.show()
            }
            "microphone.ready" -> {
                if (!shown || !pressed || !message.optBoolean("ok")) { stop(); return }
                begin()
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun begin() {
        if (audio != null) { Hub.toast("Микрофон завершает предыдущую передачу"); return }
        if (!recording.compareAndSet(false, true)) return
        try {
            val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 9600))
            audio = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone unavailable")
            recorder.startRecording()
            status.text = "Передаю голос, пока кнопка удерживается"
            val connection = Hub.conn
            thread(name = "pl-microphone", isDaemon = true) {
                try {
                    val buffer = ByteArray(1920)
                    while (recording.get()) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count <= 0) break
                        connection?.sendMicrophone(buffer.copyOf(count))
                    }
                } finally {
                    recording.set(false)
                    runCatching { recorder.stop() }
                    recorder.release()
                    Hub.main.post { if (audio === recorder) { audio = null; status.text = "Передача голоса остановлена" } }
                }
            }
        } catch (_: Exception) {
            recording.set(false)
            audio?.release()
            audio = null
            Hub.toast("Микрофон недоступен")
            Hub.send("microphone.stop")
        }
    }
    private fun stop() {
        pressed = false
        recording.set(false)
        runCatching { audio?.stop() }
        Hub.send("microphone.stop")
    }
}
