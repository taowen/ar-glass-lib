package com.taowen.arglass.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.NativeBridge
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Descriptor selection stays in Android; every USB syscall is executed by libusb in JNI. */
internal class NativeUsbDeviceSession(usbManager: UsbManager, device: UsbDevice) : Closeable {
    private val connection: UsbDeviceConnection = requireNotNull(usbManager.openDevice(device)) { "Cannot open USB device" }
    private val handle = NativeBridge.createUsbSession(connection.fileDescriptor, device.vendorId, device.productId)
    private val closed = AtomicBoolean(false)

    fun claim(usbInterface: UsbInterface): Boolean = NativeBridge.usbClaimInterface(handle, usbInterface.id)
    fun release(usbInterface: UsbInterface) = NativeBridge.usbReleaseInterface(handle, usbInterface.id)
    fun transfer(endpoint: UsbEndpoint, buffer: ByteArray, timeoutMs: Int): Int = NativeBridge.usbEndpointTransfer(
        handle, endpoint.address, endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT, buffer, timeoutMs,
    )
    fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, timeoutMs: Int): Int =
        NativeBridge.usbControlTransfer(handle, requestType, request, value, index, buffer, timeoutMs)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            NativeBridge.closeUsbSession(handle)
            connection.close()
        }
    }
}
