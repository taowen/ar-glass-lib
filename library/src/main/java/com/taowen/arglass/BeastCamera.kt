package com.taowen.arglass

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

object BeastCameraCatalog {
    const val VENDOR_ID = 0x0c45
    const val PRODUCT_ID = 0x6368

    fun identify(device: UsbDevice): Boolean = device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
}

open class UvcCameraSession(
    usbManager: UsbManager,
    val device: UsbDevice,
) : Closeable {
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open VITURE Beast camera" }
    private val handle = UvcCameraNative.start(connection.fileDescriptor).also {
        if (it == 0L) connection.close()
    }
    private val closed = AtomicBoolean(false)

    init {
        check(handle != 0L) { "UVC 1920x1080 MJPEG negotiation failed" }
    }

    fun readJpegFrame(): ByteArray? {
        check(!closed.get()) { "Camera session is closed" }
        return UvcCameraNative.readFrame(handle)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        UvcCameraNative.stop(handle)
        connection.close()
    }
}

class BeastCameraSession(usbManager: UsbManager, device: UsbDevice) : UvcCameraSession(usbManager, device) {
    init { require(BeastCameraCatalog.identify(device)) { "Not a VITURE Beast camera" } }
}

internal object UvcCameraNative {
    init { System.loadLibrary("ar_glass") }
    external fun start(javaFd: Int): Long
    external fun readFrame(handle: Long): ByteArray?
    external fun stop(handle: Long)
}
