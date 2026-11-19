package com.pocketlink.app

object Proto {
    const val TCP_PORT = 48200
    const val DISCOVERY_PORT = 48202

    const val CHALLENGE = 0x02
    const val AUTH = 0x03
    const val AUTH_OK = 0x04
    const val AUTH_FAIL = 0x05
    const val BUTTON = 0x10
    const val POINTER = 0x11
    const val TEXT = 0x12
    const val CLIPBOARD = 0x20
    const val CLIPBOARD_REQUEST = 0x21
    const val FILE_START = 0x30
    const val FILE_CHUNK = 0x31
    const val FILE_END = 0x32
    const val RESUMABLE_CHUNK = 0x34
    const val PING = 0x40
    const val MSG = 0x60
    const val SCREEN = 0x70
    const val MICROPHONE = 0x71
    const val CAMERA = 0x72

    const val UDP_MOVE = 1
    const val UDP_SCROLL = 2

    const val MAX_FRAME = 1 shl 20

    const val AUTH_PIN = 0
    const val AUTH_KEY = 1

    const val FAIL_PIN = 1
    const val FAIL_UNKNOWN = 2
    const val FAIL_PAIRING_OFF = 3
}
