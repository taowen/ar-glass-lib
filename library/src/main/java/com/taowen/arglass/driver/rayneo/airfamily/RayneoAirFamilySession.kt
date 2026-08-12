package com.taowen.arglass.driver.rayneo.airfamily

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuSample
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
import com.taowen.arglass.driver.rayneo.RayneoMagneticCalibrationStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI

internal class RayneoAirFamilySession(
    usbManager: UsbManager,
    device: UsbDevice,
    private val model: GlassesModel,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private data class Port(
        val intf: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint?,
    )

    private val running = AtomicBoolean(true)
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val ports = (0 until device.interfaceCount).map(device::getInterface).mapNotNull { intf ->
        val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            ?: return@mapNotNull null
        Port(intf, input, endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT })
    }
    private val commandPort = ports.firstOrNull { it.output != null }
    private val magneticCalibrator = RayneoMagneticCalibrator()
    private val workers = mutableListOf<Thread>()
    private val physicalDeviceKey = runCatching { device.serialNumber }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "${device.vendorId}:${device.productId}:${device.productName.orEmpty()}"

    @Volatile private var magnetometerAvailable: Boolean? = null
    @Volatile private var factoryCalibration: RayneoFactoryCalibration? = null
    @Volatile private var magneticCalibration: RayneoMagneticCalibration? = null
    @Volatile private var magneticCalibrationStoreKey: String? = null
    private var lastProgressSamples = -PROGRESS_INTERVAL
    private var lastProgressPhase: ImuHostCalibrationPhase? = null

    init {
        check(ports.isNotEmpty()) { "${model.displayName} has no HID input endpoint" }
        ports.forEach { check(usb.claim(it.intf)) { "Cannot claim RayNeo interface ${it.intf.id}" } }
        workers += ports.map { port ->
            Thread({ read(port) }, "rayneo-imu-${port.intf.id}").also(Thread::start)
        }
        send(COMMAND_DEVICE_INFO)
        send(COMMAND_IMU_CALIBRATION)
        send(COMMAND_IMU_ON)
        status("${model.displayName} 已请求设备信息、IMU 工厂校准和九轴数据")
    }

    private fun send(command: Int) {
        val port = commandPort ?: return
        val packet = ByteArray(64).also {
            it[0] = SEND_MAGIC
            it[1] = command.toByte()
        }
        check(usb.transfer(requireNotNull(port.output), packet, 500) >= 0) {
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
        }
    }

    private fun decodeDeviceInfo(packet: ByteArray) {
        val boardId = packet[BOARD_ID_OFFSET].toInt() and 0xff
        val cuType = packet[CU_TYPE_OFFSET].toInt() and 0xff
        magneticCalibrationStoreKey = "$physicalDeviceKey:$boardId:$cuType"
        val detectedModel = when (boardId) {
            BOARD_AIR_1S_VARIANT_1, BOARD_AIR_1S_VARIANT_2, BOARD_AIR_1S_VARIANT_3 -> "Air 1s"
            BOARD_AIR_PLUS -> "Air Plus"
            BOARD_AIR_2 -> "Air 2"
            BOARD_AIR_2S -> "Air 2s"
            BOARD_AIR_3 -> "Air 3"
            BOARD_AIR_3_OR_3S -> if (cuType == CU_AIR_3) "Air 3" else "Air 3s"
            BOARD_AIR_3S_PRO -> "Air 3s Pro"
            BOARD_TAURUS_2_PRO_OVERSEAS -> "Taurus 2.0 Pro (public model unavailable)"
            BOARD_AIR_4 -> "Air 4"
            BOARD_AIR_4_PRO -> "Air 4 Pro"
            BOARD_GT -> "GT"
            BOARD_GT_MAX -> "GT Max"
            else -> null
        }
        magnetometerAvailable = packet[MAGNETOMETER_VALID_OFFSET].toInt() != 0
        if (magnetometerAvailable == true && magneticCalibration == null) {
            RayneoMagneticCalibrationStore.load(requireNotNull(magneticCalibrationStoreKey))?.let { saved ->
                magneticCalibration = saved
                magneticCalibrator.useCalibration(saved)
                factoryCalibration?.let { calibration ->
                    executor.execute { listener.onImuCalibration(calibration.publicData(saved)) }
                }
            }
        }
        status(
            buildString {
                append("RayNeo ")
                append(detectedModel ?: "未知型号")
                append(" (board 0x${boardId.toString(16).padStart(2, '0')}) ")
                append("CU $cuType; ")
                append(if (magnetometerAvailable == true) "已确认磁力计有效" else "报告磁力计不可用")
            },
        )
    }

    private fun decodeFactoryCalibration(packet: ByteArray) {
        if (packet[CALIBRATION_PAYLOAD_OFFSET] == 0xff.toByte()) {
            status("${model.displayName} 未返回 IMU 工厂校准")
            return
        }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(CALIBRATION_FLOATS) { index ->
            buffer.getFloat(CALIBRATION_PAYLOAD_OFFSET + index * Float.SIZE_BYTES)
        }
        if (values.any { !it.isFinite() }) return
        factoryCalibration = RayneoFactoryCalibration(
            sensorTransform = values.copyOfRange(0, 9),
            accelerationOffset = values.copyOfRange(9, 12),
        ).also { calibration ->
            executor.execute { listener.onImuCalibration(calibration.publicData(magneticCalibration)) }
        }
        status("${model.displayName} 已加载 USB 0x3c IMU 工厂校准；请绕三轴旋转以校准磁力计")
    }

    private fun decodeImu(packet: ByteArray, hostTimestampNanos: Long): ImuSample? {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val rawAcceleration = vector(buffer, 4)
        val rawGyroscope = vector(buffer, 16)
        val rawMagnetic = floatArrayOf(buffer.getFloat(32), buffer.getFloat(36), buffer.getFloat(52))
        if ((rawAcceleration + rawGyroscope + rawMagnetic).any { !it.isFinite() }) return null

        val factory = factoryCalibration
        val acceleration = factory?.acceleration(rawAcceleration) ?: rawAcceleration
        val angularVelocity = factory?.angularVelocity(rawGyroscope) ?: rawGyroscope.also { value ->
            val radiansPerDegree = (PI / 180.0).toFloat()
            value.indices.forEach { value[it] *= radiansPerDegree }
        }

        var magnetic = rawMagnetic.takeIf { magnetometerAvailable != false }
        if (magnetic != null) {
            val update = magneticCalibrator.update(magnetic)
            reportMagneticProgress(update)
            val next = update.calibration
            if (next != null && magneticCalibration == null) {
                magneticCalibration = next
                magneticCalibrationStoreKey?.let { RayneoMagneticCalibrationStore.save(it, next) }
                factory?.let { calibration ->
                    executor.execute { listener.onImuCalibration(calibration.publicData(next)) }
                }
                status("${model.displayName} 磁力计 host 三轴椭球校准完成并已保存")
            }
            magnetic = if (update.usable) (magneticCalibration ?: next)?.apply(magnetic) ?: magnetic else null
        }

        val calibrationState = ImuCalibrationState(
            accelerometer = if (factory != null) ImuCalibrationLevel.FACTORY else ImuCalibrationLevel.NONE,
            gyroscope = if (factory != null) ImuCalibrationLevel.FACTORY else ImuCalibrationLevel.NONE,
            magnetometer = if (magneticCalibration != null) {
                ImuCalibrationLevel.HOST_ESTIMATED
            } else {
                ImuCalibrationLevel.NONE
            },
        )
        return ImuSample(
            deviceTimestampNanos = (buffer.getInt(40).toLong() and 0xffffffffL) * DEVICE_TICK_NANOS,
            accelerationMetersPerSecondSquared = acceleration,
            angularVelocityRadiansPerSecond = angularVelocity,
            magneticField = magnetic,
            temperatureCelsius = buffer.getFloat(28),
            reportVersion = 1,
            hostTimestampNanos = hostTimestampNanos,
            calibration = calibrationState,
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
        }
        status("${model.displayName} 已清除磁力计 host 校准；请缓慢绕三个轴旋转眼镜")
        return true
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { send(COMMAND_IMU_OFF) }
        workers.forEach(Thread::interrupt)
        workers.forEach { if (Thread.currentThread() !== it) it.join(1_200) }
        ports.forEach { usb.release(it.intf) }
        usb.close()
    }

    private companion object {
        const val SEND_MAGIC: Byte = 0x66
        const val ACK_MAGIC: Byte = 0x99.toByte()
        const val COMMAND_DEVICE_INFO = 0x00
        const val COMMAND_IMU_ON = 0x01
        const val COMMAND_IMU_OFF = 0x02
        const val COMMAND_IMU_CALIBRATION = 0x3c
        const val COMMAND_IMU_DATA = 0x65
        const val COMMAND_ACK = 0xc8
        const val ACK_COMMAND_OFFSET = 8
        const val BOARD_ID_OFFSET = 21
        const val CU_TYPE_OFFSET = 38
        const val MAGNETOMETER_VALID_OFFSET = 51
        const val CALIBRATION_PAYLOAD_OFFSET = 9
        const val CALIBRATION_FLOATS = 12
        const val DEVICE_TICK_NANOS = 100_000L
        const val PROGRESS_INTERVAL = 100
        const val BOARD_AIR_1S_VARIANT_1 = 0x21
        const val BOARD_AIR_1S_VARIANT_2 = 0x22
        const val BOARD_AIR_1S_VARIANT_3 = 0x23
        const val BOARD_AIR_PLUS = 0x24
        const val BOARD_AIR_2 = 0x30
        const val BOARD_AIR_2S = 0x31
        const val BOARD_AIR_3 = 0x35
        const val BOARD_AIR_3_OR_3S = 0x36
        const val BOARD_AIR_3S_PRO = 0x37
        const val BOARD_TAURUS_2_PRO_OVERSEAS = 0x38
        const val BOARD_AIR_4 = 0x39
        const val BOARD_AIR_4_PRO = 0x3a
        const val BOARD_GT = 0x40
        const val BOARD_GT_MAX = 0x41
        const val CU_AIR_3 = 4
    }
}
