package com.pocketlink.app

import android.app.AlertDialog
import android.widget.SeekBar

object FunctionSettings {
    fun remote(act: MainActivity) {
        val box = vbox(act, 14)
        val label = tv(act, "Чувствительность: %.1f×".format(Prefs.pointerSensitivity), 15f)
        box.addView(label)
        box.addView(SeekBar(act).apply {
            max = 25; progress = (Prefs.pointerSensitivity * 10 - 5).toInt().coerceIn(0, 25)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user) { Prefs.pointerSensitivity = (value + 5) / 10f; label.text = "Чувствительность: %.1f×".format(Prefs.pointerSensitivity) } }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        box.addView(switchRow(act, "Естественная прокрутка", null, Prefs.naturalScroll) { Prefs.naturalScroll = it })
        box.addView(switchRow(act, "Кнопки громкости управляют ПК", null, Prefs.pcVolumeKeys) { Prefs.pcVolumeKeys = it })
        AlertDialog.Builder(act).setTitle("Настройки пульта").setView(box).setPositiveButton("Готово", null).show()
    }
    fun screen(act: MainActivity, changed: () -> Unit = {}) {
        val box = vbox(act, 14)
        box.addView(switchRow(act, "Касания управляют ПК", "Выключите для просмотра без случайных кликов", Prefs.screenControl) { Prefs.screenControl = it })
        box.addView(btn(act, "Качество трансляции") {
            AlertDialog.Builder(act).setTitle("Качество экрана").setItems(arrayOf("Экономный · 1280", "Чёткий текст · 1920", "Максимум · 2560")) { _, index ->
                Prefs.screenWidth = intArrayOf(1280, 1920, 2560)[index]
                Prefs.screenQuality = intArrayOf(60, 80, 88)[index]
                Prefs.screenFps = intArrayOf(8, 12, 15)[index]
                changed()
            }.show()
        })
        AlertDialog.Builder(act).setTitle("Настройки экрана ПК").setView(box).setPositiveButton("Готово", null).show()
    }
}
