package com.taowen.arglass

internal object NativeBridge {
    init { System.loadLibrary("ar_glass") }

    external fun makeImuCommand(command: Int, payload: ByteArray): ByteArray
    external fun makeMcuCommand(command: Int, requestId: Int, payload: ByteArray): ByteArray
    external fun decodeImuReport(report: ByteArray): FloatArray?
}
