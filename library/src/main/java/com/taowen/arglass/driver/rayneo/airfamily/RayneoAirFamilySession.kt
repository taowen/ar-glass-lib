package com.taowen.arglass.driver.rayneo.airfamily

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.ImuSample
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
import com.taowen.arglass.driver.rayneo.RayneoMagneticCalibrationStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI

internal enum class RayneoUsbProtocol(
    val interfaceId: Int,
    val outputEndpointAddress: Int,
    val inputEndpointAddress: Int,
    val readsGyroscopeTemperatureBiases: Boolean,
) {
    TAURUS(0, 0x01, 0x81, false),
    GEMINI(5, 0x04, 0x85, true),
}

internal class RayneoAirFamilySession(
    usbManager: UsbManager,
    device: UsbDevice,
    private val model: GlassesModel,
    private val executor: Executor,
    private val listener: ArGlassesListener,
    private val protocol: RayneoUsbProtocol,
) : DriverSession {
    private data class Port(
        val intf: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    private val running = AtomicBoolean(true)
    private val port = requireNotNull(
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull { intf ->
            intf.id == protocol.interfaceId && intf.interfaceClass == UsbConstants.USB_CLASS_HID
        }?.let { intf ->
            val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
            val input = endpoints.singleOrNull {
                it.address == protocol.inputEndpointAddress &&
                    it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            val output = endpoints.singleOrNull {
                it.address == protocol.outputEndpointAddress &&
                    it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            if (input != null && output != null) Port(intf, input, output) else null
        },
    ) {
        "${model.displayName} 缺少固件定义的 HID interface=${protocol.interfaceId} " +
            "OUT=0x${protocol.outputEndpointAddress.toString(16)} IN=0x${protocol.inputEndpointAddress.toString(16)}"
    }
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val magneticCalibrator = RayneoMagneticCalibrator()
    private val workers = mutableListOf<Thread>()
    private val physicalDeviceKey = runCatching { device.serialNumber }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "${device.vendorId}:${device.productId}:${device.productName.orEmpty()}"

    @Volatile private var magnetometerAvailable: Boolean? = null
    @Volatile private var factoryCalibration: RayneoFactoryCalibration? = null
    @Volatile private var magneticCalibration: RayneoMagneticCalibration? = null
    @Volatile private var magneticCalibrationStoreKey: String? = null
    private val streamStarted = AtomicBoolean(false)
    private val deviceInfoHandled = AtomicBoolean(false)
    private val deviceInfoReady = CountDownLatch(1)
    private val factoryCalibrationReady = CountDownLatch(1)
    private val gyroscopeTemperatureBiasesReady = CountDownLatch(1)
    @Volatile private var factoryCalibrationExpected = false
    @Volatile private var factoryCalibrationFailure: String? = null
    @Volatile private var gyroscopeTemperatureBiasesFailure: String? = null
    private val gyroscopeTemperatureBiases = arrayOfNulls<FloatArray>(GYROSCOPE_TEMPERATURE_COUNT)
    private val gyroscopeTemperatureChunks = mutableSetOf<Int>()
    private var gyroscopeTemperatureChunkCount = -1
    @Volatile private var probeFailure: String? = null
    @Volatile override var resolvedModel: GlassesModel? = null
        private set
    private var lastProgressSamples = -PROGRESS_INTERVAL
    private var lastProgressPhase: ImuHostCalibrationPhase? = null

    init {
        check(usb.claim(port.intf)) { "Cannot claim RayNeo HID interface ${port.intf.id}" }
        workers += Thread({ read(port) }, "rayneo-imu-${port.intf.id}").also(Thread::start)
        send(COMMAND_DEVICE_INFO)
        status("${model.displayName} 正在读取板号，确认协议后再启动 IMU")
        if (!deviceInfoReady.await(DEVICE_INFO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            close()
            error("${model.displayName} 未在 ${DEVICE_INFO_TIMEOUT_MILLIS}ms 内返回设备信息，不回退到通用协议")
        }
        probeFailure?.let { failure ->
            close()
            error(failure)
        }
        try {
            factoryCalibrationExpected = true
            send(COMMAND_IMU_CALIBRATION)
            if (!factoryCalibrationReady.await(CALIBRATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                error("${resolvedModel?.displayName} 未在 ${CALIBRATION_TIMEOUT_MILLIS}ms 内返回 0x3c 工厂校准")
            }
            factoryCalibrationFailure?.let(::error)
            if (protocol.readsGyroscopeTemperatureBiases) {
                send(
                    COMMAND_GYROSCOPE_TEMPERATURE_BIASES,
                    GYROSCOPE_MINIMUM_TEMPERATURE_CELSIUS,
                    byteArrayOf(GYROSCOPE_TEMPERATURE_COUNT.toByte()),
                )
                if (!gyroscopeTemperatureBiasesReady.await(GYROSCOPE_BIASES_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    error("${resolvedModel?.displayName} 未在 ${GYROSCOPE_BIASES_TIMEOUT_MILLIS}ms 内返回完整 0x3e 陀螺仪温度 bias 表")
                }
                gyroscopeTemperatureBiasesFailure?.let(::error)
            }
            send(COMMAND_IMU_ON)
            streamStarted.set(true)
            val calibrationCommands = if (protocol.readsGyroscopeTemperatureBiases) "0x3c + 0x3e" else "0x3c"
            status("${resolvedModel?.displayName} 已启动固件已验证的 $calibrationCommands + 99 65 IMU 协议")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun send(command: Int, parameter: Int = 0, payload: ByteArray = byteArrayOf()) {
        require(payload.size <= 61) { "RayNeo HID command payload is too large" }
        val packet = ByteArray(64).also {
            it[0] = SEND_MAGIC
            it[1] = command.toByte()
            it[2] = parameter.toByte()
            payload.copyInto(it, destinationOffset = 3)
        }
        check(usb.transfer(port.output, packet, 500) >= 0) {
            "RayNeo command 0x${command.toString(16)} failed"
        }
    }

    private fun read(port: Port) {
        val bytes = ByteArray(maxOf(64, port.input.maxPacketSize))
        while (running.get()) {
            val length = usb.transfer(port.input, bytes, 750)
            val hostTimestampNanos = System.nanoTime()
            if (length >= 64 && bytes[0] == ACK_MAGIC) {
                when (bytes[1].toInt() and 0xff) {
                    COMMAND_ACK -> decodeCommandAck(bytes)
                    COMMAND_IMU_DATA -> decodeImu(bytes, hostTimestampNanos)?.let { sample ->
                        executor.execute { listener.onImuSample(sample) }
                    }
                }
            }
        }
    }

    /** Command responses use report type 0xc8 and echo the original command at byte 8. */
    private fun decodeCommandAck(packet: ByteArray) {
        when (packet[ACK_COMMAND_OFFSET].toInt() and 0xff) {
            COMMAND_DEVICE_INFO -> decodeDeviceInfo(packet)
            COMMAND_IMU_CALIBRATION -> decodeFactoryCalibration(packet)
            COMMAND_GYROSCOPE_TEMPERATURE_BIASES -> decodeGyroscopeTemperatureBiases(packet)
        }
    }

    private fun decodeDeviceInfo(packet: ByteArray) {
        if (!deviceInfoHandled.compareAndSet(false, true)) return
        val boardId = packet[BOARD_ID_OFFSET].toInt() and 0xff
        magneticCalibrationStoreKey = "$physicalDeviceKey:$boardId"
        val detectedModel = when (boardId) {
            BOARD_AIR_3 -> "Air 3"
            BOARD_AIR_3S -> "Air 3s"
            BOARD_AIR_3S_PRO -> "Air 3s Pro"
            BOARD_AIR_4 -> "Air 4"
            BOARD_AIR_4_PRO -> "Air 4 Pro"
            BOARD_GT -> "GT"
            BOARD_GT_MAX -> "GT Max"
            else -> null
        }
        val protocolVerified = when (protocol) {
            RayneoUsbProtocol.TAURUS -> boardId in VERIFIED_TAURUS_RAW_IMU_BOARDS
            RayneoUsbProtocol.GEMINI -> boardId in VERIFIED_GEMINI_RAW_IMU_BOARDS
        }
        if (!protocolVerified || detectedModel == null) {
            probeFailure = "RayNeo board 0x${boardId.toString(16).padStart(2, '0')} " +
                "不在当前 driver 的固件验证集合中，已拒绝启动 IMU"
            status(requireNotNull(probeFailure))
            deviceInfoReady.countDown()
            return
        }
        magnetometerAvailable = packet[MAGNETOMETER_VALID_OFFSET].toInt() != 0
        if (magnetometerAvailable == true && magneticCalibration == null) {
            RayneoMagneticCalibrationStore.load(requireNotNull(magneticCalibrationStoreKey))?.let { saved ->
                magneticCalibration = saved
                magneticCalibrator.useCalibration(saved)
                reportCalibration(saved)
            }
        }
        status(
            buildString {
                append("RayNeo ")
                append(detectedModel)
                append(" (board 0x${boardId.toString(16).padStart(2, '0')}) ")
                append(if (magnetometerAvailable == true) "已确认磁力计有效" else "报告磁力计不可用")
            },
        )
        resolvedModel = resolvedModel(detectedModel, boardId)
        deviceInfoReady.countDown()
    }

    private fun decodeFactoryCalibration(packet: ByteArray) {
        if (!factoryCalibrationExpected) return
        if (packet[CALIBRATION_PAYLOAD_OFFSET] == 0xff.toByte()) {
            factoryCalibrationFailure = "${resolvedModel?.displayName} 明确报告无 0x3c IMU 工厂校准"
            factoryCalibrationReady.countDown()
            return
        }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(CALIBRATION_FLOATS) { index ->
            buffer.getFloat(CALIBRATION_PAYLOAD_OFFSET + index * Float.SIZE_BYTES)
        }
        if (values.any { !it.isFinite() }) {
            factoryCalibrationFailure = "${resolvedModel?.displayName} 返回了无效的 0x3c IMU 工厂校准"
            factoryCalibrationReady.countDown()
            return
        }
        factoryCalibration = RayneoFactoryCalibration(
            sensorTransform = values.copyOfRange(0, 9),
            accelerationOffset = values.copyOfRange(9, 12),
        ).also { calibration ->
            if (!protocol.readsGyroscopeTemperatureBiases) {
                executor.execute { listener.onImuCalibration(calibration.publicData(magneticCalibration)) }
            }
        }
        factoryCalibrationReady.countDown()
        status("${resolvedModel?.displayName} 已发布 USB 0x3c IMU 工厂校准；样本保持协议解码后的 SI 数值；请绕三轴旋转以校准磁力计")
    }

    @Synchronized
    private fun decodeGyroscopeTemperatureBiases(packet: ByteArray) {
        if (!protocol.readsGyroscopeTemperatureBiases || gyroscopeTemperatureBiasesReady.count == 0L) return
        val chunkIndex = packet[GYROSCOPE_BIAS_CHUNK_INDEX_OFFSET].toInt() and 0xff
        val chunkCount = packet[GYROSCOPE_BIAS_CHUNK_COUNT_OFFSET].toInt() and 0xff
        val firstTemperature = packet[GYROSCOPE_BIAS_FIRST_TEMPERATURE_OFFSET].toInt()
        val valueCount = packet[GYROSCOPE_BIAS_VALUE_COUNT_OFFSET].toInt() and 0xff
        fun fail(reason: String) {
            gyroscopeTemperatureBiasesFailure = "${resolvedModel?.displayName} 返回了无效的 0x3e 陀螺仪温度 bias 表：$reason"
            gyroscopeTemperatureBiasesReady.countDown()
        }
        if (chunkCount !in 1..GYROSCOPE_MAXIMUM_CHUNKS || chunkIndex !in 0 until chunkCount) {
            fail("chunk=$chunkIndex/$chunkCount")
            return
        }
        if (valueCount !in 1..GYROSCOPE_BIASES_PER_PACKET ||
            GYROSCOPE_BIAS_VALUES_OFFSET + valueCount * GYROSCOPE_BIAS_VALUE_BYTES > packet.size
        ) {
            fail("valueCount=$valueCount")
            return
        }
        if (gyroscopeTemperatureChunkCount == -1) gyroscopeTemperatureChunkCount = chunkCount
        if (gyroscopeTemperatureChunkCount != chunkCount) {
            fail("chunk count changed from $gyroscopeTemperatureChunkCount to $chunkCount")
            return
        }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        repeat(valueCount) { valueIndex ->
            val temperature = firstTemperature + valueIndex
            val tableIndex = temperature - GYROSCOPE_MINIMUM_TEMPERATURE_CELSIUS
            if (tableIndex !in gyroscopeTemperatureBiases.indices) {
                fail("temperature=$temperature")
                return
            }
            val offset = GYROSCOPE_BIAS_VALUES_OFFSET + valueIndex * GYROSCOPE_BIAS_VALUE_BYTES
            val bias = vector(buffer, offset)
            if (bias.any { !it.isFinite() }) {
                fail("non-finite value at temperature=$temperature")
                return
            }
            gyroscopeTemperatureBiases[tableIndex] = bias
        }
        gyroscopeTemperatureChunks += chunkIndex
        if (gyroscopeTemperatureChunks.size != gyroscopeTemperatureChunkCount) return
        if (gyroscopeTemperatureBiases.any { it == null }) {
            fail("incomplete ${gyroscopeTemperatureBiases.count { it != null }}/$GYROSCOPE_TEMPERATURE_COUNT values")
            return
        }
        val temperatureBiases = gyroscopeTemperatureBiases.mapIndexed { index, bias ->
            RayneoGyroscopeTemperatureBias(
                temperatureCelsius = (GYROSCOPE_MINIMUM_TEMPERATURE_CELSIUS + index).toFloat(),
                biasDegreesPerSecond = requireNotNull(bias),
            )
        }
        factoryCalibration = requireNotNull(factoryCalibration).copy(
            gyroscopeTemperatureBiases = temperatureBiases,
        ).also { calibration ->
            executor.execute { listener.onImuCalibration(calibration.publicData(magneticCalibration)) }
        }
        gyroscopeTemperatureBiasesReady.countDown()
        status("${resolvedModel?.displayName} 已发布 USB 0x3e 的 $GYROSCOPE_TEMPERATURE_COUNT 点陀螺仪温度 bias 表")
    }

    private fun resolvedModel(detectedModel: String, boardId: Int): GlassesModel {
        val boardMatchesProtocol = when (protocol) {
            RayneoUsbProtocol.TAURUS -> boardId in VERIFIED_TAURUS_RAW_IMU_BOARDS
            RayneoUsbProtocol.GEMINI -> boardId in VERIFIED_GEMINI_RAW_IMU_BOARDS
        }
        check(boardMatchesProtocol) {
            "Unverified RayNeo board reached model resolution"
        }
        val calibration = ImuCalibrationState(
            accelerometer = ImuCalibrationLevel.FACTORY,
            gyroscope = ImuCalibrationLevel.FACTORY,
            magnetometer = ImuCalibrationLevel.HOST_ESTIMATED,
        )
        val hasMagnetometer = magnetometerAvailable == true
        return model.copy(
            model = detectedModel,
            capabilities = model.capabilities + GlassesCapability.IMU,
            imuTrackingSupport = ImuTrackingSupport(
                axisCount = if (hasMagnetometer) 9 else 6,
                calibration = if (hasMagnetometer) calibration else calibration.copy(
                    magnetometer = ImuCalibrationLevel.NONE,
                ),
            ),
        )
    }

    private fun decodeImu(packet: ByteArray, hostTimestampNanos: Long): ImuSample? {
        if (!streamStarted.get()) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val rawAcceleration = vector(buffer, 4)
        val rawGyroscope = vector(buffer, 16)
        val temperatureCelsius = buffer.getFloat(28)
        if ((rawAcceleration + rawGyroscope).any { !it.isFinite() } || !temperatureCelsius.isFinite()) return null
        val rawMagnetic = if (magnetometerAvailable == true) {
            floatArrayOf(buffer.getFloat(32), buffer.getFloat(36), buffer.getFloat(52))
                .takeIf { value -> value.all(Float::isFinite) }
        } else {
            null
        }

        val radiansPerDegree = (PI / 180.0).toFloat()
        val angularVelocity = FloatArray(3) { rawGyroscope[it] * radiansPerDegree }

        if (rawMagnetic != null) {
            val update = magneticCalibrator.update(rawMagnetic)
            reportMagneticProgress(update)
            val next = update.calibration
            if (next != null && magneticCalibration == null) {
                magneticCalibration = next
                magneticCalibrationStoreKey?.let { RayneoMagneticCalibrationStore.save(it, next) }
                reportCalibration(next)
                status("${model.displayName} 磁力计 host 三轴椭球校准完成并已保存")
            }
        }

        return ImuSample(
            deviceTimestampNanos = (buffer.getInt(40).toLong() and 0xffffffffL) * DEVICE_TICK_NANOS,
            accelerationMetersPerSecondSquared = rawAcceleration,
            angularVelocityRadiansPerSecond = angularVelocity,
            magneticField = rawMagnetic,
            temperatureCelsius = temperatureCelsius,
            reportVersion = 1,
            hostTimestampNanos = hostTimestampNanos,
        )
    }

    private fun vector(buffer: ByteBuffer, offset: Int): FloatArray =
        floatArrayOf(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))

    private fun reportMagneticProgress(update: RayneoMagneticUpdate) {
        val progress = update.progress
        if (progress.phase != lastProgressPhase || progress.acceptedSamples - lastProgressSamples >= PROGRESS_INTERVAL) {
            lastProgressPhase = progress.phase
            lastProgressSamples = progress.acceptedSamples
            executor.execute { listener.onImuHostCalibrationProgress(progress) }
        }
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun resetHostImuCalibration(): Boolean {
        magneticCalibrationStoreKey?.let(RayneoMagneticCalibrationStore::clear)
        magneticCalibration = null
        magneticCalibrator.reset()
        lastProgressSamples = -PROGRESS_INTERVAL
        lastProgressPhase = null
        factoryCalibration?.let { calibration ->
            executor.execute { listener.onImuCalibration(calibration.publicData()) }
        } ?: executor.execute {
            listener.onImuCalibration(
                ImuCalibrationData(
                    source = ImuCalibrationSource.HOST_ESTIMATE,
                    state = ImuCalibrationState(),
                    accelerometerBiasMetersPerSecondSquared = FloatArray(3),
                    gyroscopeBiasRadiansPerSecond = FloatArray(3),
                    magnetometerBias = null,
                    parametersAppliedToSamples = false,
                ),
            )
        }
        status("${model.displayName} 已清除磁力计 host 校准；请缓慢绕三个轴旋转眼镜")
        return true
    }

    private fun reportCalibration(magnetic: RayneoMagneticCalibration) {
        val data = factoryCalibration?.publicData(magnetic) ?: magnetic.publicData()
        executor.execute { listener.onImuCalibration(data) }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        if (streamStarted.get()) runCatching { send(COMMAND_IMU_OFF) }
        workers.forEach(Thread::interrupt)
        workers.forEach { if (Thread.currentThread() !== it) it.join(1_200) }
        usb.release(port.intf)
        usb.close()
    }

    private companion object {
        const val SEND_MAGIC: Byte = 0x66
        const val ACK_MAGIC: Byte = 0x99.toByte()
        const val COMMAND_DEVICE_INFO = 0x00
        const val COMMAND_IMU_ON = 0x01
        const val COMMAND_IMU_OFF = 0x02
        const val COMMAND_IMU_CALIBRATION = 0x3c
        const val COMMAND_GYROSCOPE_TEMPERATURE_BIASES = 0x3e
        const val COMMAND_IMU_DATA = 0x65
        const val COMMAND_ACK = 0xc8
        const val ACK_COMMAND_OFFSET = 8
        const val BOARD_ID_OFFSET = 21
        const val MAGNETOMETER_VALID_OFFSET = 51
        const val CALIBRATION_PAYLOAD_OFFSET = 9
        const val CALIBRATION_FLOATS = 12
        const val GYROSCOPE_BIAS_CHUNK_INDEX_OFFSET = 9
        const val GYROSCOPE_BIAS_CHUNK_COUNT_OFFSET = 10
        const val GYROSCOPE_BIAS_FIRST_TEMPERATURE_OFFSET = 11
        const val GYROSCOPE_BIAS_VALUE_COUNT_OFFSET = 12
        const val GYROSCOPE_BIAS_VALUES_OFFSET = 13
        const val GYROSCOPE_BIAS_VALUE_BYTES = 12
        const val GYROSCOPE_BIASES_PER_PACKET = 4
        const val GYROSCOPE_MINIMUM_TEMPERATURE_CELSIUS = -20
        const val GYROSCOPE_MAXIMUM_TEMPERATURE_CELSIUS = 60
        const val GYROSCOPE_TEMPERATURE_COUNT =
            GYROSCOPE_MAXIMUM_TEMPERATURE_CELSIUS - GYROSCOPE_MINIMUM_TEMPERATURE_CELSIUS + 1
        const val GYROSCOPE_MAXIMUM_CHUNKS = 32
        const val DEVICE_TICK_NANOS = 100_000L
        const val PROGRESS_INTERVAL = 100
        const val DEVICE_INFO_TIMEOUT_MILLIS = 1_500L
        const val CALIBRATION_TIMEOUT_MILLIS = 1_500L
        const val GYROSCOPE_BIASES_TIMEOUT_MILLIS = 3_000L
        const val BOARD_AIR_3 = 0x35
        const val BOARD_AIR_3S = 0x36
        const val BOARD_AIR_3S_PRO = 0x37
        const val BOARD_AIR_4 = 0x39
        const val BOARD_AIR_4_PRO = 0x3a
        const val BOARD_GT = 0x40
        const val BOARD_GT_MAX = 0x41
        val VERIFIED_TAURUS_RAW_IMU_BOARDS = setOf(
            BOARD_AIR_3,
            BOARD_AIR_3S,
            BOARD_AIR_3S_PRO,
            BOARD_AIR_4,
            BOARD_AIR_4_PRO,
        )
        val VERIFIED_GEMINI_RAW_IMU_BOARDS = setOf(BOARD_GT, BOARD_GT_MAX)
    }
}
