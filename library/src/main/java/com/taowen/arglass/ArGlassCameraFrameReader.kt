package com.taowen.arglass

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import com.taowen.arglass.driver.xreal.onefamily.XrealOneNcmTransport
import java.io.Closeable

class ArGlassCameraFrame(
    val format: Int,
    val bytes: ByteArray,
    val codecConfig: Boolean,
    val keyFrame: Boolean,
    val width: Int,
    val height: Int,
    val frameRate: Int,
) {
    companion object {
        const val FORMAT_JPEG = 1
        const val FORMAT_HEVC_ANNEX_B = 2

        @JvmStatic
        fun jpeg(bytes: ByteArray): ArGlassCameraFrame =
            ArGlassCameraFrame(FORMAT_JPEG, bytes, false, false, 0, 0, 0)

        @JvmStatic
        fun hevc(frame: XrealOneEyeHevcFrame): ArGlassCameraFrame =
            ArGlassCameraFrame(
                FORMAT_HEVC_ANNEX_B,
                frame.hevcBytes,
                frame.codecConfig,
                frame.keyFrame,
                frame.width,
                frame.height,
                frame.frameRate,
            )
    }
}

interface ArGlassCameraFrameReader : Closeable {
    val name: String
    fun readFrame(): ArGlassCameraFrame?
}

object ArGlassCameraFrameReaders {
    @JvmStatic
    fun openBest(context: Context): ArGlassCameraFrameReader? {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java) ?: return null
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val devices = usbManager.deviceList.values.toList()
        return openXrealOneEye(connectivityManager, devices)
            ?: openXrealUvc(usbManager, devices)
            ?: openBeastUvc(usbManager, devices)
    }

    private fun openXrealOneEye(
        connectivityManager: ConnectivityManager?,
        devices: List<UsbDevice>,
    ): ArGlassCameraFrameReader? {
        if (devices.none(XrealEyeCameraCatalog::identifyOneFamilyMain)) return null
        if (connectivityManager == null ||
            XrealOneNcmTransport.findNetwork(connectivityManager) == null
        ) return null
        return runCatching {
            XrealOneEyeReader(
                XrealOneEyeCameraSession(
                    connectivityManager,
                    connectTimeoutMs = 500,
                    readTimeoutMs = 150,
                ),
            )
        }.getOrNull()
    }

    private fun openXrealUvc(
        usbManager: UsbManager,
        devices: List<UsbDevice>,
    ): ArGlassCameraFrameReader? {
        val device = XrealEyeCameraCatalog.findOpenCameraDevice(devices)
            ?.takeIf(usbManager::hasPermission)
            ?: return null
        return runCatching {
            val session = XrealEyeOpenCameraSession(usbManager, device)
            JpegReader("XREAL Eye UVC", session) { session.readJpegFrame() }
        }.getOrNull()
    }

    private fun openBeastUvc(
        usbManager: UsbManager,
        devices: List<UsbDevice>,
    ): ArGlassCameraFrameReader? {
        val device = devices.firstOrNull(BeastCameraCatalog::identify)
            ?.takeIf(usbManager::hasPermission)
            ?: return null
        return runCatching {
            val session = BeastCameraSession(usbManager, device)
            JpegReader("VITURE Beast UVC", session) { session.readJpegFrame() }
        }.getOrNull()
    }

    private class JpegReader(
        override val name: String,
        private val session: Closeable,
        private val readJpegFrame: () -> ByteArray?,
    ) : ArGlassCameraFrameReader {
        override fun readFrame(): ArGlassCameraFrame? =
            readJpegFrame()?.let(ArGlassCameraFrame::jpeg)

        override fun close() = session.close()
    }

    private class XrealOneEyeReader(
        private val session: XrealOneEyeCameraSession,
    ) : ArGlassCameraFrameReader {
        override val name: String = "XREAL One Eye HEVC"

        override fun readFrame(): ArGlassCameraFrame? =
            session.readHevcFrame()?.let(ArGlassCameraFrame::hevc)

        override fun close() = session.close()
    }
}
