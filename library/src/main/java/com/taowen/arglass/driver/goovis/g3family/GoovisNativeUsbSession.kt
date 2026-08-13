package com.taowen.arglass.driver.goovis.g3family

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Single native owner for GOOVIS HID commands, interrupt transfers and IMU decoding. */
internal class GoovisNativeUsbSession(
    usbManager: UsbManager,
    device: UsbDevice,
    modelKind: GoovisModelKind,
    requireInput: Boolean,
) : Closeable {
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open GOOVIS USB device" }
    private val port = findHidPort(device, requireInput)
    private val closed = AtomicBoolean(false)
    private val handle = NativeBridge.createGoovisUsbSession(
        connection.fileDescriptor,
        device.vendorId,
        device.productId,
        port.usbInterface.id,
        port.inputEndpoint?.address ?: 0,
        port.outputEndpoint.address,
        modelKind.nativeValue,
    )

    fun setDisplaySbs(enabled: Boolean): Boolean = NativeBridge.goovisSetDisplaySbs(handle, enabled)

    fun setImuEnabled(enabled: Boolean): Boolean = NativeBridge.goovisSetImuEnabled(handle, enabled)

    fun readImu(timeoutMs: Int = 250): ImuSample? {
        val packet = NativeBridge.goovisReadImu(handle, timeoutMs) ?: return null
        val hostTimestampNanos = SystemClock.elapsedRealtimeNanos()
        if (packet.size != NATIVE_SAMPLE_SIZE) return null
        val bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return ImuSample(
            deviceTimestampNanos = bytes.long,
            accelerationMetersPerSecondSquared = FloatArray(3) { bytes.float },
            angularVelocityRadiansPerSecond = FloatArray(3) { bytes.float },
            magneticField = null,
            temperatureCelsius = Float.NaN,
            reportVersion = bytes.get().toInt() and 0xff,
            hostTimestampNanos = hostTimestampNanos,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativeBridge.closeGoovisUsbSession(handle)
        connection.close()
    }

    private data class HidPort(
        val usbInterface: UsbInterface,
        val inputEndpoint: UsbEndpoint?,
        val outputEndpoint: UsbEndpoint,
    )

    private companion object {
        const val NATIVE_SAMPLE_SIZE = 33

        fun findHidPort(device: UsbDevice, requireInput: Boolean): HidPort {
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
                }
                if (output != null && (!requireInput || input != null)) return HidPort(usbInterface, input, output)
            }
            error("GOOVIS HID interface with interrupt OUT${if (requireInput) "/IN" else ""} endpoints not found")
        }
    }
}

internal enum class GoovisModelKind(val nativeValue: Int) {
    G3(0),
    G3X(1),
    G3XP(2),
    A1(3),
}
