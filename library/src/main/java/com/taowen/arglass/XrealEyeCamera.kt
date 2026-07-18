package com.taowen.arglass

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable

data class XrealEyeRgbFrame(val rgb888: ByteArray, val width: Int, val height: Int)

/** Official ARLauncher/XREAL SDK backend. Vendor SO files are optional runtime inputs. */
class XrealEyeOfficialCameraSession(activity: Activity) : Closeable {
    private val handle = XrealEyeOfficialNative.start(activity).also {
        check(it != 0L) { XrealEyeOfficialNative.lastError().ifEmpty { "XREAL official RGB camera failed" } }
    }

    fun readFrame(): XrealEyeRgbFrame? {
        val bytes = XrealEyeOfficialNative.readFrame(handle) ?: return null
        return XrealEyeRgbFrame(bytes, XrealEyeOfficialNative.width(handle), XrealEyeOfficialNative.height(handle))
    }

    override fun close() = XrealEyeOfficialNative.stop(handle)
}

/** Open backend: descriptor-driven UVC MJPEG over libusb, with no vendor SO dependency. */
class XrealEyeOpenCameraSession(usbManager: UsbManager, device: UsbDevice) : Closeable {
    private val delegate = UvcCameraSession(usbManager, device)
    fun readJpegFrame(): ByteArray? = delegate.readJpegFrame()
    override fun close() = delegate.close()
}

internal object XrealEyeOfficialNative {
    init { System.loadLibrary("ar_glass") }
    external fun start(activity: Activity): Long
    external fun readFrame(handle: Long): ByteArray?
    external fun width(handle: Long): Int
    external fun height(handle: Long): Int
    external fun stop(handle: Long)
    external fun lastError(): String
}
