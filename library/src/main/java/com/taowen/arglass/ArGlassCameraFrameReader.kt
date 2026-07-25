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
            ?: openBeastUvc(usbManager, devices)
    }

    @JvmStatic
    fun openXrealOneEye(context: Context): ArGlassCameraFrameReader? {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java) ?: return null
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        return openXrealOneEye(connectivityManager, usbManager.deviceList.values.toList())
    }

    @JvmStatic
    fun openBeastUvc(context: Context): ArGlassCameraFrameReader? {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java) ?: return null
        return openBeastUvc(usbManager, usbManager.deviceList.values.toList())
    }

    internal fun openBeastUvcOrThrow(context: Context): ArGlassCameraFrameReader? {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java) ?: return null
        return openBeastUvc(
            usbManager,
            usbManager.deviceList.values.toList(),
            throwOnSessionFailure = true,
        )
    }

    @JvmStatic
    fun describeAvailability(context: Context): String {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java)
            ?: return "当前设备不支持 USB host，无法发现 XREAL Eye 或 Beast 摄像头。"
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val devices = usbManager.deviceList.values.toList()
        val visible = devices.joinToString { "0x%04x:0x%04x".format(it.vendorId, it.productId) }
            .ifBlank { "无" }
        val xrealMain = devices.firstOrNull(XrealEyeCameraCatalog::identifyOneFamilyMain)
        val xrealNetworkReady = connectivityManager?.let(XrealOneNcmTransport::findNetwork) != null
        val beast = devices.firstOrNull(BeastCameraCatalog::identify)
        val beastPermission = beast?.let(usbManager::hasPermission) == true
        return buildString {
            append("可见 USB：").append(visible)
            append("\nXREAL One 主设备：").append(if (xrealMain != null) "已发现" else "未发现")
            append("\nXREAL Eye USB Ethernet：").append(if (xrealNetworkReady) "已就绪" else "未就绪")
            append("\nBeast UVC：").append(
                when {
                    beast == null -> "未发现"
                    beastPermission -> "已授权"
                    else -> "已发现但 Arctrl 未获得 USB 权限"
                }
            )
        }
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

    private fun openBeastUvc(
        usbManager: UsbManager,
        devices: List<UsbDevice>,
        throwOnSessionFailure: Boolean = false,
    ): ArGlassCameraFrameReader? {
        val device = devices.firstOrNull(BeastCameraCatalog::identify)
            ?.takeIf(usbManager::hasPermission)
            ?: return null
        return runCatching {
            val session = BeastCameraSession(usbManager, device)
            JpegReader("VITURE Beast UVC", session) { session.readJpegFrame() }
        }.getOrElse { error ->
            if (throwOnSessionFailure) {
                throw IllegalStateException("VITURE Beast UVC open failed", error)
            }
            null
        }
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
