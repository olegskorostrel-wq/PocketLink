package com.pocketlink.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Звонки: состояние вызова уходит на ПК, оттуда можно ответить, сбросить и набрать номер. */
object CallBridge {
    private val ctx get() = Hub.app
    private var receiver: BroadcastReceiver? = null
    private var lastState = ""
    private var lastNumber = ""

    fun start() {
        if (receiver != null) return
        lastState = "idle"
        lastNumber = ""
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                // номер приходит отдельной рассылкой (только с правом READ_CALL_LOG), поэтому сравниваем и его
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                val state = when (st) {
                    TelephonyManager.EXTRA_STATE_RINGING -> "ring"
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
                    else -> "idle"
                }
                if (number.isNotEmpty()) lastNumber = number
                if (state == "idle") { lastNumber = "" }
                if (state == lastState && (number.isEmpty() || state != "ring")) return
                lastState = state
                val n = lastNumber
                Hub.work.execute {
                    Hub.send(
                        JSONObject().put("t", "call.state").put("state", state).put("number", n)
                            .put("name", SmsBridge.contactName(n))
                    )
                }
            }
        }
        receiver = r
        try { Features.register(r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) } catch (_: Exception) { receiver = null }
    }

    fun stop() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun handle(t: String, m: JSONObject) {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            when (t) {
                "call.answer" -> if (Features.granted(Manifest.permission.ANSWER_PHONE_CALLS)) tm.acceptRingingCall()
                "call.hangup" -> if (Features.granted(Manifest.permission.ANSWER_PHONE_CALLS)) tm.endCall()
                "call.dial" -> {
                    val number = m.optString("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (number.isNotEmpty() && Features.granted(Manifest.permission.CALL_PHONE)) {
                        tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
