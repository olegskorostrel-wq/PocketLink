package com.pocketlink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Standard Bluetooth HID reports. The operating system pairs and authenticates the host. */
@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class BluetoothPage(act: MainActivity) : Page(act) {
    private val adapter = (act.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var profile: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var shown = false
    private var mouseButtons = 0
    private var gameButtons = 0
    private var axisX = 0
    private var axisY = 0
    private var rightX = 0
    private var rightY = 0
    private var leftTrigger = -127
    private var rightTrigger = -127
    private val state = muted(act, "Нажми «Включить Bluetooth-пульт»")
    private val pad = TouchpadView(act)
    private val body = vbox(act, 12)
    override val root: View = android.widget.ScrollView(act).apply { addView(body) }

    init {
        body.addView(state)
        body.addView(btn(act, "Включить Bluetooth-пульт", 1) {
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
            act.askPerms(permissions) { start() }
        })
        body.addView(btn(act, "Сделать телефон видимым для ПК") {
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()
            act.askPerms(permissions) {
                try { act.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
                catch (_: Exception) { Hub.toast("Открой Bluetooth в настройках телефона") }
            }
        })
        body.addView(btn(act, "Выбрать сопряжённый ПК") { selectHost() })
        pad.callbacks = object : TouchpadView.Callbacks {
            override fun onMove(dx: Int, dy: Int) {
                var x = dx
                var y = dy
                while (x != 0 || y != 0) {
                    val px = x.coerceIn(-127, 127); val py = y.coerceIn(-127, 127)
                    mouse(px, py, 0)
                    x -= px; y -= py
                }
            }
            override fun onScroll(dx: Int, dy: Int) {
                scrollRemainder += dy
                val ticks = scrollRemainder / 120
                scrollRemainder -= ticks * 120
                if (ticks != 0) mouse(0, 0, ticks.coerceIn(-127, 127))
            }
            override fun onButton(button: Int, down: Boolean) {
                val mask = 1 shl button
                mouseButtons = if (down) mouseButtons or mask else mouseButtons and mask.inv()
                mouse(0, 0, 0)
            }
        }
        body.addView(pad, lp(h = act.dp(180)))
        val keys = hbox(act)
        for ((label, code) in listOf("Esc" to 41, "Enter" to 40, "Пробел" to 44, "⌫" to 42))
            keys.addView(btn(act, label) { keyboard(code); keyboard(0) }, lp(0, weight = 1f))
        body.addView(keys)
        body.addView(muted(act, "Геймпад Bluetooth · направления и кнопки", 12f))
        body.addView(btn(act, "Настроить кнопки геймпада") {
            val names = arrayOf("A", "B", "X", "Y", "L", "R", "Start", "Select")
            AlertDialog.Builder(act).setTitle("Переназначить кнопку").setItems(names) { _, index ->
                AlertDialog.Builder(act).setTitle(names[index]).setSingleChoiceItems(
                    Array(16) { "Кнопка " + (it + 1) }, Prefs.gamepadButton(index)
                ) { dialog, selected ->
                    gameButtons = 0
                    gamepad()
                    Prefs.setGamepadButton(index, selected)
                    dialog.dismiss()
                }.show()
            }.show()
        })
        val arrows = hbox(act)
        for ((label, x, y) in listOf(Triple("←", -127, 0), Triple("↑", 0, -127), Triple("↓", 0, 127), Triple("→", 127, 0))) {
            arrows.addView(held(label, { axisX = x; axisY = y; gamepad() }, { axisX = 0; axisY = 0; gamepad() }), lp(0, weight = 1f))
        }
        body.addView(arrows)
        val buttons = hbox(act)
        for ((index, label) in listOf("A", "B", "X", "Y", "L", "R", "Start", "Select").withIndex()) {
            buttons.addView(held(label, { gameButtons = gameButtons or (1 shl Prefs.gamepadButton(index)); gamepad() },
                { gameButtons = gameButtons and (1 shl Prefs.gamepadButton(index)).inv(); gamepad() }), lp(0, weight = 1f))
        }
        body.addView(buttons)
        val sticks = hbox(act)
        sticks.addView(JoystickView(act) { x, y -> axisX = x; axisY = y; gamepad() }, lp(0, h = act.dp(120), weight = 1f))
        sticks.addView(JoystickView(act) { x, y -> rightX = x; rightY = y; gamepad() }, lp(0, h = act.dp(120), weight = 1f))
        body.addView(sticks)
        for (left in listOf(true, false)) {
            body.addView(tv(act, if (left) "Левый курок" else "Правый курок", 12f))
            body.addView(android.widget.SeekBar(act).apply {
                max = 254
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                        if (left) leftTrigger = progress - 127 else rightTrigger = progress - 127
                        gamepad()
                    }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar) { bar.progress = 0 }
                })
            })
        }
    }

    private var scrollRemainder = 0
    private fun held(label: String, down: () -> Unit, up: () -> Unit): View = btn(act, label) {}.apply {
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> down()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up()
            }
            true
        }
    }

    private fun report(id: Int, bytes: ByteArray) {
        val target = host ?: return
        runCatching { profile?.sendReport(target, id, bytes) }
    }
    private fun mouse(x: Int, y: Int, wheel: Int) = report(1, byteArrayOf(mouseButtons.toByte(), x.toByte(), y.toByte(), wheel.toByte()))
    private fun keyboard(code: Int) = report(2, byteArrayOf(0, 0, code.toByte(), 0, 0, 0, 0, 0))
    private fun gamepad() = report(3, byteArrayOf(gameButtons.toByte(), (gameButtons shr 8).toByte(),
        axisX.toByte(), axisY.toByte(), rightX.toByte(), rightY.toByte(), leftTrigger.toByte(), rightTrigger.toByte()))

    private fun selectHost() {
        if (!registered) { Hub.toast("Сначала включи Bluetooth-пульт"); return }
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { Hub.toast("Сначала создай пару с ПК в настройках Bluetooth"); return }
        AlertDialog.Builder(act).setTitle("Выбрать ПК").setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, index ->
            profile?.connect(devices[index])
        }.show()
    }

    private fun start() {
        if (adapter == null || !adapter.isEnabled) { Hub.toast("Включи Bluetooth на телефоне"); return }
        if (profile != null) return
        state.text = "Подготавливаю Bluetooth-пульт…"
        val accepted = adapter.getProfileProxy(act, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                if (!shown) { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy); return }
                val hid = proxy as BluetoothHidDevice
                profile = hid
                val description = BluetoothHidDeviceAppSdpSettings("PocketLink", "Mouse, keyboard and gamepad", "PocketLink", 0, descriptor)
                val ok = hid.registerApp(description, null, null, act.mainExecutor, object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(device: BluetoothDevice?, active: Boolean) {
                        registered = active
                        state.text = if (active) "Готово. Выбери сопряжённый ПК." else "Bluetooth-пульт выключен системой"
                    }
                    override fun onConnectionStateChanged(device: BluetoothDevice, connection: Int) {
                        host = if (connection == BluetoothProfile.STATE_CONNECTED) device else null
                        state.text = if (host != null) "Подключено: " + device.name else "Нет Bluetooth-связи с ПК"
                    }
                })
                if (!ok) state.text = "Телефон не разрешил режим HID"
            }
            override fun onServiceDisconnected(type: Int) { profile = null; host = null; registered = false }
        }, BluetoothProfile.HID_DEVICE)
        if (!accepted) state.text = "Эта прошивка не поддерживает Bluetooth HID"
    }

    override fun onShow() { shown = true; pad.sensitivity = Prefs.pointerSensitivity }
    override fun onHide() {
        shown = false
        mouseButtons = 0; gameButtons = 0; axisX = 0; axisY = 0
        rightX = 0; rightY = 0; leftTrigger = -127; rightTrigger = -127
        mouse(0, 0, 0); keyboard(0); gamepad()
        runCatching { profile?.unregisterApp() }
        profile?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        profile = null; host = null; registered = false
    }

    companion object {
        // Three independent input reports, without vendor drivers.
        private val descriptor = intArrayOf(
            0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x01,0x09,0x01,0xA1,0x00,
            0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,
            0x95,0x01,0x75,0x05,0x81,0x03,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,
            0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0,
            0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x02,0x05,0x07,0x19,0xE0,0x29,0xE7,
            0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,0x75,0x08,0x95,0x01,0x81,0x03,
            0x19,0x00,0x29,0x65,0x15,0x00,0x25,0x65,0x75,0x08,0x95,0x06,0x81,0x00,0xC0,
            0x05,0x01,0x09,0x05,0xA1,0x01,0x85,0x03,0x05,0x09,0x19,0x01,0x29,0x10,
            0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x10,0x81,0x02,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x33,0x09,0x34,0x09,0x32,0x09,0x35,
            0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x06,0x81,0x02,0xC0
        ).map { it.toByte() }.toByteArray()
    }
}
