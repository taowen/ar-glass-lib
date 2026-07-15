package com.taowen.arglass.driver.xreal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.NativeBridge
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
) : Closeable {
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open XREAL USB device" }
    private val mcuInterface = if (useMcu) device.interfaceById(0) else null
    private val imuInterface = if (useImu) device.interfaceById(1) else null
    private val closed = AtomicBoolean(false)
    private val handle = NativeBridge.createXrealUsbSession(
        connection, device,
        mcuInterface, mcuInterface?.inputEndpoint(), mcuInterface?.outputEndpoint(),
        imuInterface, imuInterface?.inputEndpoint(), imuInterface?.outputEndpoint(),
    )

    fun mcu(command: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        NativeBridge.xrealMcuCommand(handle, command, payload)

    fun imu(command: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        NativeBridge.xrealImuCommand(handle, command, payload)

    fun readImu(timeoutMs: Int = 750): ByteArray? = NativeBridge.xrealReadImu(handle, timeoutMs)

    override fun close() {
        if (closed.compareAndSet(false, true)) NativeBridge.closeXrealUsbSession(handle)
    }
}
