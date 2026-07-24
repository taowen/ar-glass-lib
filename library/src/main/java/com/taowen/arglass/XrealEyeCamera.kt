package com.taowen.arglass

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import com.taowen.arglass.driver.xreal.onefamily.XrealOneNcmTransport
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** USB identities and descriptor checks for the XREAL Eye RGB camera path. */
object XrealEyeCameraCatalog {
    private const val XREAL_VENDOR_ID = 0x3318
    private const val LEGACY_RGB_VENDOR_ID = 0x0817
    private val oneFamilyMainProductIds = setOf(0x0436, 0x0438, 0x043e)
    private val rgbCompanionIdentities = setOf(
        LEGACY_RGB_VENDOR_ID to 0x0909,
        XREAL_VENDOR_ID to 0x0909,
        XREAL_VENDOR_ID to 0x0910,
    )

    fun identifyRgbCompanion(device: UsbDevice): Boolean =
        rgbCompanionIdentities.any { (vid, pid) -> device.vendorId == vid && device.productId == pid }

    fun identifyOneFamilyMain(device: UsbDevice): Boolean =
        device.vendorId == XREAL_VENDOR_ID && device.productId in oneFamilyMainProductIds

    fun hasVideoStreamingInterface(device: UsbDevice): Boolean = (0 until device.interfaceCount).any {
        val intf = device.getInterface(it)
        intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2
    }

    fun identifyOpenCameraDevice(device: UsbDevice): Boolean =
        identifyRgbCompanion(device) || (identifyOneFamilyMain(device) && hasVideoStreamingInterface(device))

    fun findOpenCameraDevice(devices: Collection<UsbDevice>): UsbDevice? =
        devices.firstOrNull(::identifyRgbCompanion)
            ?: devices.firstOrNull { identifyOneFamilyMain(it) && hasVideoStreamingInterface(it) }

    fun findOneFamilyMainDevice(devices: Collection<UsbDevice>): UsbDevice? =
        devices.firstOrNull(::identifyOneFamilyMain)

    fun describeAvailability(devices: Collection<UsbDevice>): String {
        val visible = devices.joinToString { "0x%04x:0x%04x".format(it.vendorId, it.productId) }
        return if (devices.any(::identifyOneFamilyMain)) {
            "已发现 XREAL One 主设备；One + Eye 的 RGB 视频通常走 USB Ethernet TCP/HEVC，而不是 UVC。可见 USB：$visible"
        } else {
            "未发现 XREAL Eye RGB companion；可见 USB：$visible"
        }
    }
}

/** Descriptor-driven XREAL Eye UVC MJPEG over libusb, with no vendor SO dependency. */
class XrealEyeOpenCameraSession(usbManager: UsbManager, device: UsbDevice) : Closeable {
    init {
        require(XrealEyeCameraCatalog.identifyOpenCameraDevice(device)) {
            "Not an XREAL Eye RGB/UVC camera device"
        }
    }

    private val delegate = UvcCameraSession(usbManager, device, "XREAL Eye RGB camera")
    fun readJpegFrame(): ByteArray? = delegate.readJpegFrame()
    override fun close() = delegate.close()
}

data class XrealOneEyeHevcFrame(
    val rawPayload: ByteArray,
    val metadata: ByteArray,
    val hevcBytes: ByteArray,
    val nalTypes: IntArray,
    val codecConfig: Boolean,
    val keyFrame: Boolean,
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
)

/**
 * Open XREAL One + Eye RGB camera path.
 *
 * Captured ARLauncher traffic shows this is not Android Camera2 and not UVC:
 * control uses 169.254.2.1:52999, start/stop commands are 0x2781/0x2782,
 * and the camera stream is 0x2785-framed HEVC over 169.254.2.1:52995.
 */
class XrealOneEyeCameraSession(
    private val connectivityManager: ConnectivityManager?,
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 1_500,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val videoSocket = connect(XrealOneNcmTransport.RGB_VIDEO_PORT)
    private val controlSocket = connect(XrealOneNcmTransport.CONTROL_PORT)
    private val videoIn = BufferedInputStream(videoSocket.getInputStream())
    private val controlIn = BufferedInputStream(controlSocket.getInputStream())
    private val controlOut = controlSocket.getOutputStream()
    private var sequence = 1

    init {
        runCatching { transact(CAMERA_START_COMMAND) }
            .onFailure { error ->
                closeSockets()
                throw error
            }
    }

    fun readHevcFrame(): XrealOneEyeHevcFrame? {
        check(!closed.get()) { "Camera session is closed" }
        while (!closed.get()) {
            val frame = readFrame(videoIn, MAX_VIDEO_PAYLOAD) ?: return null
            if (frame.command != CAMERA_FRAME_COMMAND) continue
            val hevcOffset = findHevcAnnexBOffset(frame.payload)
            if (hevcOffset < 0) continue
            val metadata = frame.payload.copyOfRange(0, hevcOffset)
            val hevc = frame.payload.copyOfRange(hevcOffset, frame.payload.size)
            val nalTypes = collectHevcNalTypes(hevc)
            return XrealOneEyeHevcFrame(
                rawPayload = frame.payload,
                metadata = metadata,
                hevcBytes = hevc,
                nalTypes = nalTypes,
                codecConfig = nalTypes.any { it == HEVC_VPS || it == HEVC_SPS || it == HEVC_PPS },
                keyFrame = nalTypes.any { it in HEVC_IRAP_NAL_TYPES },
            )
        }
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transact(CAMERA_STOP_COMMAND) }
        closeSockets()
    }

    private fun connect(port: Int): Socket = XrealOneNcmTransport.connectSocket(
        connectivityManager,
        XrealOneNcmTransport.GLASSES_HOST,
        port,
        connectTimeoutMs,
        readTimeoutMs,
    )

    private fun transact(command: Int) {
        val requestSequence = sequence++ and 0xffff
        controlOut.write(makeRequest(command, requestSequence))
        controlOut.flush()
        for (attempt in 0 until 24) {
            val frame = readFrame(controlIn, MAX_CONTROL_PAYLOAD) ?: break
            if (frame.command != command) continue
            if (!frame.isResponseFor(requestSequence)) continue
            check(frame.payload.size >= 6 && frame.payload[4] == ACK_TAG && frame.payload[5] == ACK_OK) {
                "XREAL One Eye command 0x${command.toString(16)} failed: ${frame.payload.toHex()}"
            }
            return
        }
        error("timed out waiting for XREAL One Eye command 0x${command.toString(16)} ACK")
    }

    private fun closeSockets() {
        runCatching { videoSocket.close() }
        runCatching { controlSocket.close() }
    }

    private data class XrealFrame(val command: Int, val payload: ByteArray) {
        fun isResponseFor(sequence: Int): Boolean = payload.size >= 4 &&
            payload[0] == RESPONSE_FLAG &&
            payload[1] == 0.toByte() &&
            payload[2] == ((sequence ushr 8) and 0xff).toByte() &&
            payload[3] == (sequence and 0xff).toByte()
    }

    private companion object {
        const val CAMERA_START_COMMAND = 0x2781
        const val CAMERA_STOP_COMMAND = 0x2782
        const val CAMERA_FRAME_COMMAND = 0x2785
        const val MAX_CONTROL_PAYLOAD = 4_096
        const val MAX_VIDEO_PAYLOAD = 2_000_000
        const val REQUEST_FLAG = 0x80.toByte()
        const val RESPONSE_FLAG = 0x00.toByte()
        const val ACK_TAG = 0x22.toByte()
        const val ACK_OK = 0x00.toByte()
        const val START_CODE_SEARCH_FLOOR = 64
        val HEVC_SLICE_NAL_TYPES = 0..31
        val HEVC_IRAP_NAL_TYPES = 16..23
        const val HEVC_VPS = 32
        const val HEVC_SPS = 33
        const val HEVC_PPS = 34
        val LIKELY_FIRST_NAL_TYPES = HEVC_SLICE_NAL_TYPES.toSet() + setOf(HEVC_VPS, HEVC_SPS, HEVC_PPS)

        fun makeRequest(command: Int, sequence: Int): ByteArray = byteArrayOf(
            ((command ushr 8) and 0xff).toByte(),
            (command and 0xff).toByte(),
            0x00,
            0x00,
            0x00,
            0x06,
            REQUEST_FLAG,
            0x00,
            ((sequence ushr 8) and 0xff).toByte(),
            (sequence and 0xff).toByte(),
            0x1a,
            0x00,
        )

        fun readFrame(input: InputStream, maxPayload: Int): XrealFrame? {
            return try {
                var commandHigh: Int
                do {
                    commandHigh = input.read()
                    if (commandHigh < 0) return null
                } while (commandHigh != 0x27)
                val header = ByteArray(5)
                input.readFully(header)
                val command = (commandHigh shl 8) or header[0].u8()
                val payloadSize = (header[1].u8() shl 24) or
                    (header[2].u8() shl 16) or
                    (header[3].u8() shl 8) or
                    header[4].u8()
                require(payloadSize in 0..maxPayload) { "invalid XREAL frame payload size: $payloadSize" }
                val payload = ByteArray(payloadSize)
                input.readFully(payload)
                XrealFrame(command, payload)
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: EOFException) {
                null
            }
        }

        fun InputStream.readFully(buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val count = read(buffer, offset, buffer.size - offset)
                if (count < 0) throw EOFException()
                offset += count
            }
        }

        fun findHevcAnnexBOffset(bytes: ByteArray): Int {
            var i = START_CODE_SEARCH_FLOOR.coerceAtMost(bytes.size)
            while (i + 5 < bytes.size) {
                val startCodeSize = startCodeSizeAt(bytes, i)
                if (startCodeSize > 0) {
                    val nalHeader = i + startCodeSize
                    val nalType = (bytes[nalHeader].u8() ushr 1) and 0x3f
                    if (nalType in LIKELY_FIRST_NAL_TYPES) return i
                }
                i += 1
            }
            return -1
        }

        fun collectHevcNalTypes(bytes: ByteArray): IntArray {
            val types = mutableListOf<Int>()
            var i = 0
            while (i + 5 < bytes.size) {
                val startCodeSize = startCodeSizeAt(bytes, i)
                if (startCodeSize > 0) {
                    val nalHeader = i + startCodeSize
                    types += (bytes[nalHeader].u8() ushr 1) and 0x3f
                    i = nalHeader + 2
                } else {
                    i += 1
                }
            }
            return types.toIntArray()
        }

        fun startCodeSizeAt(bytes: ByteArray, offset: Int): Int = when {
            offset + 3 < bytes.size &&
                bytes[offset] == 0.toByte() &&
                bytes[offset + 1] == 0.toByte() &&
                bytes[offset + 2] == 0.toByte() &&
                bytes[offset + 3] == 1.toByte() -> 4
            offset + 2 < bytes.size &&
                bytes[offset] == 0.toByte() &&
                bytes[offset + 1] == 0.toByte() &&
                bytes[offset + 2] == 1.toByte() -> 3
            else -> 0
        }

        fun Byte.u8(): Int = toInt() and 0xff
        fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.u8()) }
    }
}
