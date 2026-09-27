package com.taowen.arglass.driver.rayneo.airfamily

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.GlassesTangentFov
import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.ImuSample
import com.taowen.arglass.ImuTransportMetadata
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
import kotlin.math.tan

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
    @Volatile private var supportsPanelFov = false
    @Volatile private var panelFov: GlassesTangentFov? = null
    private val panelFovReady = CountDownLatch(1)
    private var panelFovRequested = false
    @Volatile private var factoryCalibration: RayneoFactoryCalibration? = null
    @Volatile private var packageIsRuntimeFrame = false
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
    private val gyroscopeTemperatureRawPayloads = mutableMapOf<Int, ByteArray>()
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

    override fun requestMonoLayout(): Boolean {
        if (!packageIsRuntimeFrame) return false // verified Air4 board 0x39 only
        // RayNeoXR 2.1.1 libFFalconXRServer: XRService::SwitchTo2D at
        // 0x5a434, SendHidCommand(7, 0, {}) at 0x5a480..0x5a490.
        // A successful USB write is NOT a mode/readback or refresh-rate ACK.
        send(0x07)
        return true
    }

    private fun send(command: Int, parameter: Int = 0, payload: ByteArray = byteArrayOf()) {
        require(payload.size <= 61) { "RayNeo HID command payload is too large" }
        val packet = ByteArray(64).also {
            it[0] = SEND_MAGIC
            it[1] = command.toByte()
            it[2] = parameter.toByte()
            payload.copyInto(it, destinationOffset = 3)
        }
        check(usb.transfer(port.output, packet, 500) == packet.size) {
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
            COMMAND_PANEL_FOV -> decodePanelFov(packet)
        }
    }

    private fun decodeDeviceInfo(packet: ByteArray) {
        if (!deviceInfoHandled.compareAndSet(false, true)) return
        val boardId = packet[BOARD_ID_OFFSET].toInt() and 0xff
        // Board 0x39 wearer capture: yaw and gravity are package Y; pitch
        // is package X. The SDK legacy fusion's [x,-z,y] is an internal
        // engine basis, not this library's right/up/back sample contract.
        // Do not extrapolate this measured mounting to other boards.
        packageIsRuntimeFrame = protocol == RayneoUsbProtocol.TAURUS && boardId == BOARD_AIR_4
        supportsPanelFov = packet[23].toInt() == 1
        magneticCalibrationStoreKey = "$physicalDeviceKey:$boardId" +
            if (packageIsRuntimeFrame) ":runtime-axes-v2" else ""
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
        val reportedMagnetometerValid = packet[MAGNETOMETER_VALID_OFFSET].toInt() != 0
        // Air 4 board 0x39 returns zero in all four sensor-valid bytes even while
        // gyro and magnetic data are updating. Do not use that byte as a hardware
        // inventory. The 20260109 Taurus 4 firmware registers its magnetic driver
        // at 0x08013370; 0x0801a760 refreshes its cached XYZ every fifth IMU poll.
        // Confirmed on board 0x39 with changing 99 65 floats at 32/36/52 while
        // device-info[51] remains zero. Keep other boards' existing policy until
        // independently verified; a shared firmware image is not a board probe.
        val air4MagneticReport = protocol == RayneoUsbProtocol.TAURUS && boardId == BOARD_AIR_4
        magnetometerAvailable = reportedMagnetometerValid || air4MagneticReport
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
                append(when {
                    reportedMagnetometerValid -> "设备报告磁力计有效"
                    air4MagneticReport -> "磁力计有效位为 0，按已验证的 Air 4 原始磁场报文解码"
                    else -> "报告磁力计不可用"
                })
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
            accelerometerAdditiveOffsetMetersPerSecondSquared = values.copyOfRange(9, 12),
            rawFactoryPayload = packet.copyOf(),
            packageIsRuntimeFrame = packageIsRuntimeFrame,
        ).also { calibration ->
            if (!protocol.readsGyroscopeTemperatureBiases) {
                executor.execute { listener.onImuCalibration(calibration.publicData(magneticCalibration)) }
            }
        }
        factoryCalibrationReady.countDown()
        status("${resolvedModel?.displayName} 已发布 USB 0x3c IMU 工厂校准；样本保持协议解码后的 SI 数值；请绕三轴旋转以校准磁力计")
    }

    @Synchronized
    override fun queryCenterTangentFov(): GlassesTangentFov? {
        if (!supportsPanelFov || !running.get()) return null
        if (panelFovRequested) return panelFov
        send(COMMAND_PANEL_FOV)
        panelFovRequested = true
        if (!panelFovReady.await(DEVICE_INFO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            status("${resolvedModel?.displayName} 未返回 0x23 显示 FOV，不使用其他型号参数")
            return null
        }
        return panelFov
    }

    private fun decodePanelFov(packet: ByteArray) {
        // Official XrHidDeviceFov and XRService::UpdateDeviceFov, Android
        // RayNeoXR 2.1.1 libFFalconXRServer.so 0x924f8: unsigned LE16 fields
        // at 9/11/13/15 divided by 10 give left/right/up/down half-angles.
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val degrees = FloatArray(4) { (buffer.getShort(9 + it * 2).toInt() and 0xffff) / 10f }
        if (degrees.all { it > 0f && it < 90f }) {
            val tangents = FloatArray(4) { tan(degrees[it] * PI / 180.0).toFloat() }
            panelFov = GlassesTangentFov(-tangents[0], tangents[1], tangents[2], -tangents[3])
            status("${resolvedModel?.displayName} USB FOV 半角 L/R/U/D=${degrees.joinToString()}")
        } else {
            status("${resolvedModel?.displayName} USB FOV 无效 L/R/U/D=${degrees.joinToString()}")
        }
        panelFovReady.countDown()
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
        gyroscopeTemperatureRawPayloads[chunkIndex] = packet.copyOf()
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
                biasRadiansPerSecond = requireNotNull(bias),
            )
        }
        factoryCalibration = requireNotNull(factoryCalibration).copy(
            gyroscopeTemperatureBiases = temperatureBiases,
            rawGyroscopeTemperaturePayloads = gyroscopeTemperatureRawPayloads
                .toSortedMap()
                .values
                .map(ByteArray::copyOf),
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
            // Air4 capture established a dominant 2,000,000 ns device interval.
            // Do not extend that observation to every board sharing this USB PID.
            imuInputContract = com.taowen.arglass.ImuInputContract(
                com.taowen.arglass.ImuInputEncoding.RUNTIME_SI,
                nominalSamplePeriodNanos = if (detectedModel == "Air 4") 2_000_000L else null),
            capabilities = model.capabilities + GlassesCapability.IMU,
            imuTrackingSupport = ImuTrackingSupport(
                axisCount = if (hasMagnetometer) 9 else 6,
                calibration = if (hasMagnetometer) calibration else calibration.copy(
                    magnetometer = ImuCalibrationLevel.NONE,
                ),
            ),
        )
    }

    private fun sampleRuntimeFrame(value: FloatArray): FloatArray =
        if (packageIsRuntimeFrame) value.copyOf() else toRuntimeFrame(value)

    private fun decodeImu(packet: ByteArray, hostTimestampNanos: Long): ImuSample? {
        if (!streamStarted.get()) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val rawAcceleration = vector(buffer, 4)
        val rawGyroscope = vector(buffer, 16)
        val temperatureCelsius = buffer.getFloat(28)
        if ((rawAcceleration + rawGyroscope).any { !it.isFinite() } || !temperatureCelsius.isFinite()) return null
        val rawMagnetic = if (magnetometerAvailable == true) {
            floatArrayOf(buffer.getFloat(32), buffer.getFloat(36), buffer.getFloat(52))
                .takeIf { value -> value.all(Float::isFinite) && value.any { it != 0f } }
        } else {
            null
        }

        val radiansPerDegree = (PI / 180.0).toFloat()
        val acceleration = sampleRuntimeFrame(rawAcceleration)
        val angularVelocity = sampleRuntimeFrame(FloatArray(3) { rawGyroscope[it] * radiansPerDegree })
        // The official HID legacy-fusion path proves [x,-z,y] for accelerometer and gyroscope but
        // does not submit the 99 65 magnetometer fields to its nine-axis fusion object. RayNeo's
        // report exposes all three sensors as one package-coordinate record, so use the same rigid
        // board-specific package-to-runtime rotation here; keep rawReport below for verification.
        // Taurus 4's 0x0801a6e4..0x0801a74c converts unsigned 16-bit magnetic
        // counts as (count - 32768) / 1024 * 100, including package-axis signs.
        // This already converts gauss to microteslas; do not scale it a second time.
        // Matches MMC5603NJ Rev.B (2022-01-17), p2: 16-bit zero=32768,
        // sensitivity=1024 counts/G; the firmware also checks reg 0x39 == 0x10.
        val magneticField = rawMagnetic?.let(::sampleRuntimeFrame)

        var magneticUsable = false
        if (magneticField != null) {
            val update = magneticCalibrator.update(magneticField)
            magneticUsable = update.usable
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
            accelerationMetersPerSecondSquared = acceleration,
            angularVelocityRadiansPerSecond = angularVelocity,
            magneticField = magneticField,
            temperatureCelsius = temperatureCelsius,
            reportVersion = 1,
            hostTimestampNanos = hostTimestampNanos,
            // Driver eligibility mask, not a byte copied from the HID report.
            // Preserve raw magnetic values for diagnosis, but do not offer a
            // rejected disturbance to consumers. Freshness remains unknown.
            transportMetadata = ImuTransportMetadata(dataMask = if (magneticUsable) 7 else 3),
            rawReport = packet.copyOf(64),
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
        const val COMMAND_PANEL_FOV = 0x23
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
