package com.taowen.arglass.driver.viture.beast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuSample
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class VitureBeastSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private data class HidPort(val usbInterface: UsbInterface, val input: UsbEndpoint?, val output: UsbEndpoint?)

    private val running = AtomicBoolean(true)
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val ports = (0 until device.interfaceCount).map(device::getInterface)
        .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        .map { usbInterface ->
            val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            HidPort(
                usbInterface,
                endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN },
                endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT },
            )
        }
        .filter { it.input != null || it.output != null }
    private val workers = CopyOnWriteArrayList<Thread>()
    private val commandPort get() = ports.firstOrNull { it.output != null } ?: ports.first()
    private val responseLock = Object()
    @Volatile private var factoryCalibration: VitureV2Calibration? = null
    @Volatile private var displayModeValue: Int? = null
    @Volatile private var nativeModeValue: Int? = null
    @Volatile private var setDisplayStatus: Int? = null

    init {
        check(ports.isNotEmpty()) { "VITURE Beast has no HID protocol interfaces" }
        ports.forEach { check(usb.claim(it.usbInterface)) { "Cannot claim Beast HID interface ${it.usbInterface.id}" } }
        if (imuEnabled) {
            factoryCalibration = readFactoryCalibration()
            factoryCalibration?.let { calibration ->
                executor.execute { listener.onImuCalibration(calibration.publicData()) }
            }
            ports.mapNotNull { it.input }.forEach { input ->
                Thread({ readLoop(input) }, "viture-beast-hid-${input.address}").also { workers += it; it.start() }
            }
            send(VitureBeastProtocol.command(0x0301, byteArrayOf(2, 2)))
            status(
                if (factoryCalibration == null) {
                    "${model.displayName} RAW IMU 已请求（120 Hz），设备未返回完整九轴出厂校准"
                } else {
                    "${model.displayName} 已加载九轴出厂校准并请求 RAW IMU（120 Hz）"
                },
            )
        }
    }

    @Synchronized
    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        val nativeMode = queryNativeMode() ?: return null
        displayModeValue = null
        val query = if (nativeMode) VitureBeastProtocol.GET_NATIVE_DISPLAY_MODE else VitureBeastProtocol.GET_BYPASS_DISPLAY_MODE
        if (!send(VitureBeastProtocol.command(query)) || !awaitResponse { displayModeValue != null }) return null
        val value = displayModeValue
        val profile = when (value) {
            VitureBeastProtocol.MODE_2D_1080_60HZ -> VitureBeastProtocol.twoDimensionalProfile
            VitureBeastProtocol.NATIVE_MODE_3D_SBS_1080_60HZ -> if (nativeMode) VitureBeastProtocol.fullSbs3dProfile else null
            VitureBeastProtocol.BYPASS_MODE_3D_SBS_1080_60HZ -> if (!nativeMode) VitureBeastProtocol.fullSbs3dProfile else null
            else -> null
        }
        status("Beast 工作模式：${if (nativeMode) "Native" else "Bypass"}；显示：${profile?.let { if (it.is3d) "3D" else "2D" } ?: "未知(0x${value?.toString(16)})"}")
        return profile
    }

    @Synchronized
    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        val nativeMode = queryNativeMode() ?: return false
        val value = when (profile.id) {
            VitureBeastProtocol.twoDimensionalProfile.id -> VitureBeastProtocol.MODE_2D_1080_60HZ
            VitureBeastProtocol.fullSbs3dProfile.id -> if (nativeMode) {
                VitureBeastProtocol.NATIVE_MODE_3D_SBS_1080_60HZ
            } else {
                VitureBeastProtocol.BYPASS_MODE_3D_SBS_1080_60HZ
            }
            else -> return false
        }
        setDisplayStatus = null
        val command = if (nativeMode) VitureBeastProtocol.SET_NATIVE_DISPLAY_MODE else VitureBeastProtocol.SET_BYPASS_DISPLAY_MODE
        return send(VitureBeastProtocol.command(command, byteArrayOf(value.toByte()))) &&
            awaitResponse { setDisplayStatus != null } && setDisplayStatus == 0
    }

    private fun queryNativeMode(): Boolean? {
        nativeModeValue = null
        if (!send(VitureBeastProtocol.command(0x3140)) || !awaitResponse { nativeModeValue != null }) return null
        return when (nativeModeValue) { 1 -> true; 0 -> false; else -> null }
    }

    private fun send(command: ByteArray): Boolean {
        var sent = false
        ports.forEach { port ->
            val count = port.output?.let { output -> usb.transfer(output, command, 500) }
                ?: usb.control(0x21, 0x09, 0x0200, port.usbInterface.id, command, 500)
            if (count == command.size) sent = true
        }
        return sent
    }

    private fun awaitResponse(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 1_500_000_000L
        while (!done() && System.nanoTime() < deadline) {
            if (imuEnabled) {
                synchronized(responseLock) { if (!done()) responseLock.wait(25) }
            } else {
                ports.mapNotNull { it.input }.forEach { input ->
                    val bytes = ByteArray(maxOf(64, input.maxPacketSize))
                    val length = usb.transfer(input, bytes, 80)
                    if (length > 0) handlePacket(bytes, length)
                }
            }
        }
        return done()
    }

    private fun readLoop(input: UsbEndpoint) {
        val bytes = ByteArray(maxOf(64, input.maxPacketSize))
        while (running.get()) {
            val length = usb.transfer(input, bytes, 750)
            val hostTimestampNanos = System.nanoTime()
            if (length > 0) handlePacket(bytes.copyOf(length), length, hostTimestampNanos)
        }
    }

    private fun handlePacket(bytes: ByteArray, length: Int, hostTimestampNanos: Long = System.nanoTime()) {
        VitureBeastProtocol.decodeImu(bytes, length, hostTimestampNanos)?.let { rawSample ->
            executor.execute { listener.onImuSample(calibrate(rawSample)) }
            return
        }
        val packet = VitureBeastProtocol.decode(bytes, length) ?: return
        val value = packet.payload.getOrNull(1)?.toInt()?.and(0xff)
            ?: packet.payload.firstOrNull()?.toInt()?.and(0xff)
        synchronized(responseLock) {
            when (packet.messageId) {
                VitureBeastProtocol.NATIVE_MODE_RESPONSE -> nativeModeValue = value
                VitureBeastProtocol.NATIVE_DISPLAY_MODE_RESPONSE,
                VitureBeastProtocol.BYPASS_DISPLAY_MODE_RESPONSE -> displayModeValue = value
                VitureBeastProtocol.SET_NATIVE_DISPLAY_RESPONSE,
                VitureBeastProtocol.SET_BYPASS_DISPLAY_RESPONSE ->
                    setDisplayStatus = packet.payload.firstOrNull()?.toInt()?.and(0xff)
            }
            responseLock.notifyAll()
        }
    }

    private fun calibrate(rawSample: ImuSample): ImuSample {
        val factory = factoryCalibration
        val sample = factory?.calibrateFactory(rawSample) ?: rawSample.copy(
            accelerationMetersPerSecondSquared = FloatArray(3) {
                rawSample.accelerationMetersPerSecondSquared[it] * STANDARD_GRAVITY
            },
        )
        return sample
    }

    private fun readFactoryCalibration(): VitureV2Calibration? = runCatching {
        val gyroTemperature = readLongCalibrationPacket(CALIBRATION_GYRO_TEMPERATURE)
        val imu = readLongCalibrationPacket(CALIBRATION_IMU) ?: return@runCatching null
        val magnetometer = readLongCalibrationPacket(CALIBRATION_MAGNETOMETER) ?: return@runCatching null
        val accelerationTemperature = readLongCalibrationPacket(CALIBRATION_ACCELEROMETER_TEMPERATURE)
        val magnetometerTemperature = readLongCalibrationPacket(CALIBRATION_MAGNETOMETER_TEMPERATURE)
        VitureV2Calibration.parse(
            imu,
            magnetometer,
            gyroTemperature,
            accelerationTemperature,
            magnetometerTemperature,
        )
    }.onFailure { error ->
        status("${model.displayName} 九轴出厂校准读取失败：${error.message}")
    }.getOrNull()

    private fun readLongCalibrationPacket(messageId: Int): ByteArray? {
        val assembled = ByteArrayOutputStream()
        var appSequence = -1
        var totalSegments = -1
        var segment = 0
        while (totalSegments < 0 || segment < totalSegments) {
            val request = VitureBeastProtocol.command(
                messageId,
                byteArrayOf((segment and 0xff).toByte(), ((segment ushr 8) and 0xff).toByte()),
            )
            if (!sendToPort(commandPort, request)) return null
            val payload = awaitCalibrationResponse(messageId, segment) ?: return null
            val responseSequence = payload[0].toInt() and 0xff
            val responseTotal = uint16(payload, 1)
            val responseSegment = uint16(payload, 3)
            if (responseTotal == 0) return null
            if (responseTotal == 0xffff || responseSegment != segment) return null
            if (appSequence < 0) appSequence = responseSequence
            if (totalSegments < 0) totalSegments = responseTotal
            if (responseSequence != appSequence || responseTotal != totalSegments) return null
            assembled.write(payload, CALIBRATION_SEGMENT_HEADER_SIZE, payload.size - CALIBRATION_SEGMENT_HEADER_SIZE)
            segment++
        }
        val packet = assembled.toByteArray()
        if (packet.size < CALIBRATION_PACKET_HEADER_SIZE) return null
        val storedCrc = uint16(packet, 4)
        val calculatedCrc = crc16Ccitt(packet, 6, packet.size - 6)
        if (storedCrc != calculatedCrc) return null
        val declaredLength = uint32(packet, 0)
        if (declaredLength > packet.size || declaredLength < CALIBRATION_PACKET_HEADER_SIZE) return null
        return packet.copyOf(declaredLength)
    }

    private fun awaitCalibrationResponse(messageId: Int, requestedSegment: Int): ByteArray? {
        val deadline = System.nanoTime() + CALIBRATION_RESPONSE_TIMEOUT_NANOS
        val inputPorts = ports.mapNotNull { it.input }
        while (System.nanoTime() < deadline) {
            inputPorts.forEach { input ->
                val bytes = ByteArray(maxOf(64, input.maxPacketSize))
                val length = usb.transfer(input, bytes, CALIBRATION_READ_TIMEOUT_MS)
                if (length > 0) {
                    val packet = VitureBeastProtocol.decode(bytes, length)
                    if (packet != null && (packet.messageId and 0x0fff) == (messageId and 0x0fff)) {
                        calibrationSegmentPayload(packet.payload, requestedSegment)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** Some controller revisions retain the generic response-status byte; the SDK payload wrapper strips it. */
    private fun calibrationSegmentPayload(payload: ByteArray, requestedSegment: Int): ByteArray? {
        val candidates = buildList {
            if (payload.firstOrNull() == 0.toByte() && payload.size > CALIBRATION_SEGMENT_HEADER_SIZE) {
                add(payload.copyOfRange(1, payload.size))
            }
            add(payload)
        }
        return candidates.firstOrNull { candidate ->
            if (candidate.size < CALIBRATION_SEGMENT_HEADER_SIZE) return@firstOrNull false
            val total = uint16(candidate, 1)
            (total == 0 || total == 0xffff || total <= MAX_CALIBRATION_SEGMENTS) &&
                uint16(candidate, 3) == requestedSegment
        }
    }

    private fun sendToPort(port: HidPort, command: ByteArray): Boolean {
        val count = port.output?.let { output -> usb.transfer(output, command, 500) }
            ?: usb.control(0x21, 0x09, 0x0200, port.usbInterface.id, command, 500)
        return count == command.size
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Int =
        uint16(bytes, offset) or (uint16(bytes, offset + 2) shl 16)

    private fun crc16Ccitt(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (index in offset until offset + length) {
            crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1 xor 0x1021) else crc shl 1 }
            crc = crc and 0xffff
        }
        return crc
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        if (imuEnabled) runCatching { send(VitureBeastProtocol.command(0x0301, byteArrayOf(0, 0))) }
        workers.forEach(Thread::interrupt); workers.forEach { if (Thread.currentThread() !== it) it.join(1200) }
        ports.forEach { usb.release(it.usbInterface) }
        usb.close()
    }

    private companion object {
        const val CALIBRATION_GYRO_TEMPERATURE = 0x3302
        const val CALIBRATION_IMU = 0x3303
        const val CALIBRATION_MAGNETOMETER = 0x3304
        const val CALIBRATION_ACCELEROMETER_TEMPERATURE = 0x3305
        const val CALIBRATION_MAGNETOMETER_TEMPERATURE = 0x3306
        const val CALIBRATION_SEGMENT_HEADER_SIZE = 5
        const val CALIBRATION_PACKET_HEADER_SIZE = 8
        const val CALIBRATION_READ_TIMEOUT_MS = 90
        const val CALIBRATION_RESPONSE_TIMEOUT_NANOS = 2_000_000_000L
        const val MAX_CALIBRATION_SEGMENTS = 1_024
        const val STANDARD_GRAVITY = 9.80665f
    }
}
