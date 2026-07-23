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

    fun imu(command: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        NativeBridge.xrealImuCommand(handle, command, payload)

    fun readImu(timeoutMs: Int = 750): ByteArray? = NativeBridge.xrealReadImu(handle, timeoutMs)

    override fun close() {
        if (closed.compareAndSet(false, true)) NativeBridge.closeXrealUsbSession(handle)
    }
}
