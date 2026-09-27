package com.taowen.arglass.driver.xreal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.NativeBridge
import com.taowen.arglass.ImuProtocolResponse
import com.taowen.arglass.driver.inputEndpoint
import com.taowen.arglass.driver.interfaceById
import com.taowen.arglass.driver.outputEndpoint
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Single native owner for every XREAL USB interface, transfer and command transaction. */
internal class XrealNativeUsbSession(
    usbManager: UsbManager,
    device: UsbDevice,
    useMcu: Boolean,
    useImu: Boolean,
    mcuInterfaceId: Int = 0,
    imuInterfaceId: Int = 1,
) : Closeable {
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open XREAL USB device" }
    private val mcuInterface = if (useMcu) device.interfaceById(mcuInterfaceId) else null
    private val imuInterface = if (useImu) device.interfaceById(imuInterfaceId) else null
    private val closed = AtomicBoolean(false)
    private val handle = NativeBridge.createXrealUsbSession(
        connection.fileDescriptor, device.vendorId, device.productId,
        mcuInterface?.id ?: -1,
        mcuInterface?.inputEndpoint()?.address ?: 0,
        mcuInterface?.outputEndpoint()?.address ?: 0,
        imuInterface?.id ?: -1,
        imuInterface?.inputEndpoint()?.address ?: 0,
        imuInterface?.outputEndpoint()?.address ?: 0,
    )

    fun mcu(command: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        NativeBridge.xrealMcuCommand(handle, command, payload)

    fun mcuDisplayModeValue(payloadBytes: Int): Int =
        NativeBridge.xrealMcuGetDisplayModeValue(handle, payloadBytes)

    fun setMcuDisplayModeValue(modeValue: Int, payloadBytes: Int): Boolean =
        NativeBridge.xrealMcuSetDisplayModeValue(handle, modeValue, payloadBytes)

    @Volatile var imuProtocolResponse: ImuProtocolResponse? = null
        private set

    fun imu(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val response = NativeBridge.xrealImuCommand(handle, command, payload)
        // Retain the complete matched AA response returned by transact(), including
        // its header. Do not infer an identifier from USB PID or strip a presumed
        // model-specific header here. This adds no USB command or success fallback.
        if (command == 0x1a && payload.isEmpty()) {
            imuProtocolResponse = ImuProtocolResponse("xreal.imu-aa-response", command, response)
        }
        return response
    }

    fun readImu(timeoutMs: Int = 750): ByteArray? = NativeBridge.xrealReadImu(handle, timeoutMs)

    /** Starts the post-bootstrap native reader. Commands must be complete first. */
    fun startImuStream(): Boolean = NativeBridge.xrealStartImuStream(handle)

    /** Little-endian CLOCK_MONOTONIC ns followed by one 64-byte wire report. */
    fun readImuRecord(timeoutMs: Int = 750): ByteArray? =
        NativeBridge.xrealReadImuRecord(handle, timeoutMs)

    override fun close() {
        if (closed.compareAndSet(false, true)) NativeBridge.closeXrealUsbSession(handle)
    }
}
