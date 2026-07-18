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

class BeastCameraSession(
    usbManager: UsbManager,
    val device: UsbDevice,
) : Closeable {
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open VITURE Beast camera" }
    private val handle = BeastCameraNative.start(connection.fileDescriptor).also {
        if (it == 0L) connection.close()
    }
    private val closed = AtomicBoolean(false)

    init {
        require(BeastCameraCatalog.identify(device)) { "Not a VITURE Beast camera" }
        check(handle != 0L) { "Beast UVC 1920x1080 MJPEG negotiation failed" }
    }

    fun readJpegFrame(): ByteArray? {
        check(!closed.get()) { "Camera session is closed" }
        return BeastCameraNative.readFrame(handle)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        BeastCameraNative.stop(handle)
        connection.close()
    }
}

internal object BeastCameraNative {
    init { System.loadLibrary("ar_glass") }
    external fun start(javaFd: Int): Long
    external fun readFrame(handle: Long): ByteArray?
    external fun stop(handle: Long)
}
