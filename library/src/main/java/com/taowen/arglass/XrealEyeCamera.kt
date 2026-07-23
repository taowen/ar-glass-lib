package com.taowen.arglass

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable

/** Descriptor-driven XREAL Eye UVC MJPEG over libusb, with no vendor SO dependency. */
class XrealEyeOpenCameraSession(usbManager: UsbManager, device: UsbDevice) : Closeable {
    private val delegate = UvcCameraSession(usbManager, device)
    fun readJpegFrame(): ByteArray? = delegate.readJpegFrame()
    override fun close() = delegate.close()
}
