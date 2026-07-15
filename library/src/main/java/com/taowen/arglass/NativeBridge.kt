package com.taowen.arglass

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.taowen.arglass.driver.tracedBulkTransfer

internal object NativeBridge {
    init { System.loadLibrary("ar_glass") }

    external fun makeImuCommand(command: Int, payload: ByteArray): ByteArray
    external fun makeMcuCommand(command: Int, requestId: Int, payload: ByteArray): ByteArray
    external fun decodeImuReport(report: ByteArray): FloatArray?

    external fun createXrealUsbSession(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        mcuInterface: UsbInterface?,
        mcuIn: UsbEndpoint?,
        mcuOut: UsbEndpoint?,
        imuInterface: UsbInterface?,
        imuIn: UsbEndpoint?,
        imuOut: UsbEndpoint?,
    ): Long
    external fun xrealMcuCommand(handle: Long, command: Int, payload: ByteArray): ByteArray
    external fun xrealImuCommand(handle: Long, command: Int, payload: ByteArray): ByteArray
    external fun xrealReadImu(handle: Long, timeoutMs: Int): ByteArray?
    external fun closeXrealUsbSession(handle: Long)

    /** Native owns all XREAL sequencing; this is its single logged Android USB syscall. */
    @JvmStatic
    fun tracedTransfer(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int = connection.tracedBulkTransfer(device, endpoint, buffer, buffer.size, timeoutMs)
}
