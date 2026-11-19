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
    private var choosing = false
    private var shown = false
    private var pressed = false
    private val recording = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    override val root: View = vbox(act, 14).apply {
        addView(status)
        addView(muted(act, "На ПК открой «Микрофон телефона» → «Настроить микрофон». После первой установки перезагрузи ПК. В Discord выбери CABLE Output. PocketLink автоматически найдёт вход VB-CABLE; другое устройство можно выбрать вручную.", 13f))
        addView(btn(act, "Выбрать устройство ПК") { choosing = true; Hub.send("microphone.devices") })
        addView(btn(act, "Включить / выключить микрофон", 1) { if (pressed || recording.get()) stop() else requestStart() })
        addView(btn(act, "Разрешить микрофон") { act.askPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {} })
        addView(btn(act, "Удерживай, чтобы говорить", 1) {}.apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        requestStart()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
                }
                true
            }
        })
    }
    private fun requestStart() {
        if (Hub.state != Hub.State.ONLINE) { Hub.toast("Нет связи с ПК"); return }
        if (!Hub.can("microphone")) { Hub.toast("Разрешите микрофон для этого телефона на ПК"); return }
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.askPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {}; return
        }
        pressed = true
        status.text = "Подключаю микрофон…"
        Hub.send("microphone.start", "device" to selected)
    }
    override fun onShow() { shown = true; choosing = false; Hub.send("microphone.devices") }
    override fun onHide() { shown = false; stop() }
    override fun onHub(ev: String, data: Any?) {
        if (ev == "state" && Hub.state != Hub.State.ONLINE) { stop(); return }
        val message = data as? JSONObject ?: return
        if (ev != "json") return
        when (message.optString("t")) {
            "microphone.devices" -> {
                if (!shown) return
                if (message.has("message")) { choosing = false; status.text = message.optString("message"); return }
                val devices = message.optJSONArray("items") ?: return
                if (!choosing) {
                    for (i in 0 until devices.length()) {
                        val device = devices.getJSONObject(i)
                        if (device.optBoolean("recommended")) {
                            selected = device.optString("id"); status.text = "Готово: ${device.optString("name")}"; return
                        }
                    }
                    status.text = "На ПК открой «Микрофон телефона» и настрой компонент"
                    return
                }
                choosing = false
                AlertDialog.Builder(act).setTitle("Куда передавать голос").setItems(
                    Array(devices.length()) { devices.getJSONObject(it).optString("name") }
                ) { _, index ->
                    val device = devices.getJSONObject(index)
                    selected = device.optString("id")
                    status.text = device.optString("name")
                }.show()
            }
            "microphone.ready" -> {
                if (!message.optBoolean("ok")) { val reason = message.optString("message", "Не удалось включить микрофон на ПК"); stop(); status.text = reason; return }
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
