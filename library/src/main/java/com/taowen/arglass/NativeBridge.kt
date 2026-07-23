package com.taowen.arglass

internal object NativeBridge {
    init { System.loadLibrary("ar_glass") }

    external fun makeImuCommand(command: Int, payload: ByteArray): ByteArray
    external fun makeMcuCommand(command: Int, requestId: Int, payload: ByteArray): ByteArray
    external fun decodeImuReport(report: ByteArray): FloatArray?

    external fun createXrealUsbSession(
        fileDescriptor: Int,
        vendorId: Int,
        productId: Int,
        mcuInterface: Int,
        mcuIn: Int,
        mcuOut: Int,
        imuInterface: Int,
        imuIn: Int,
        imuOut: Int,
    ): Long
    external fun xrealMcuCommand(handle: Long, command: Int, payload: ByteArray): ByteArray
    external fun xrealImuCommand(handle: Long, command: Int, payload: ByteArray): ByteArray
    external fun xrealReadImu(handle: Long, timeoutMs: Int): ByteArray?
    external fun closeXrealUsbSession(handle: Long)
    external fun createXrealOneTcpImuSession(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Long
    external fun xrealOneReadImu(handle: Long): ByteArray?
    external fun closeXrealOneTcpImuSession(handle: Long)
    external fun xrealOneDpGetCurrentEdid(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Int
    external fun xrealOneDpSetDisplayMode(
        host: String,
        port: Int,
        edid: Int,
        inputMode: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Boolean
    external fun createUsbSession(fileDescriptor: Int, vendorId: Int, productId: Int): Long
    external fun usbClaimInterface(handle: Long, interfaceId: Int): Boolean
    external fun usbReleaseInterface(handle: Long, interfaceId: Int)
    external fun usbEndpointTransfer(handle: Long, endpoint: Int, interrupt: Boolean, buffer: ByteArray, timeoutMs: Int): Int
    external fun usbControlTransfer(
        handle: Long, requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray, timeoutMs: Int,
    ): Int
    external fun closeUsbSession(handle: Long)
    external fun configureUsbDiagnostics(path: String)
}
