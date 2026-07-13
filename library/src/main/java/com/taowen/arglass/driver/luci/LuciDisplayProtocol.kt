package com.taowen.arglass.driver.luci

internal object LuciDisplayProtocol {
    const val HID_SET_REPORT_REQUEST_TYPE = 0x21
    const val HID_SET_REPORT = 0x09
    const val HID_FEATURE_REPORT_ID_2 = 0x0302
    const val REPORT_SIZE = 64
    const val USB_TIMEOUT_MS = 1_000

    fun powerStateReport(enable3d: Boolean): ByteArray = ByteArray(REPORT_SIZE).apply {
        this[0] = 0x00
        this[1] = 0x01
        this[2] = 0x8d.toByte()
        this[3] = 0x05
        if (enable3d) {
            this[4] = 0x83.toByte()
            this[5] = 0x16
        } else {
            this[4] = 0x93.toByte()
            this[5] = 0x26
        }
    }
}
