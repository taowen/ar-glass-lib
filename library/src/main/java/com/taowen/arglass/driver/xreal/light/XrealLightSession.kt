package com.taowen.arglass.driver.xreal.light

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class XrealLightSession(
    usbManager: UsbManager, mcuDevice: UsbDevice, ov580Device: UsbDevice?, private val model: GlassesModel,
    feature: SessionFeature, private val executor: Executor, private val listener: ArGlassesListener,
) : DriverSession {
    private data class Port(val intf: UsbInterface, val input: UsbEndpoint?, val output: UsbEndpoint?)
    private val running = AtomicBoolean(true)
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val mcu = NativeUsbDeviceSession(usbManager, mcuDevice)
    private val mcuPort = ports(mcuDevice).first { it.input != null && it.output != null }
    private val ov580 = if (imuEnabled) NativeUsbDeviceSession(usbManager, requireNotNull(ov580Device) { "XREAL Light OV580 IMU device is not connected" }) else null
    private val imuPorts = if (imuEnabled) ports(requireNotNull(ov580Device)).filter { it.input != null && it.intf.id != 1 } else emptyList()
    private val imuCommandPort = if (imuEnabled) imuPorts.firstOrNull { it.input != null && it.output != null } else null
    private val worker: Thread?
    private val heartbeat: Thread?
    @Volatile private var factoryCalibration: XrealLightFactoryCalibration? = null

    init {
        check(mcu.claim(mcuPort.intf)) { "Cannot claim XREAL Light MCU interface" }
        transact('@', '3', "1")
        if (imuEnabled) {
            val native = requireNotNull(ov580)
            imuPorts.forEach { native.claim(it.intf) }
            worker = Thread(::readImu, "xreal-light-imu").also(Thread::start)
        } else worker = null
        heartbeat = if (displayEnabled) Thread(::heartbeatLoop, "xreal-light-heartbeat").also(Thread::start) else null
    }

    @Synchronized override fun queryDisplayProfile(): GlassesDisplayProfile? =
        transact('3','3')?.let(XrealLightProtocol::decodeProfile)

    @Synchronized override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        val value = XrealLightProtocol.wire(profile) ?: return false
        return transact('1','3', value.toString())?.let(XrealLightProtocol::decodeProfile)?.id == profile.id
    }

    private fun transact(category: Char, command: Char, data: String = "x"): ByteArray? {
        val out = requireNotNull(mcuPort.output)
        if (mcu.transfer(out, XrealLightProtocol.mcu(category, command, data), 500) < 0) return null
        val response = ByteArray(64)
        repeat(8) {
            val count = mcu.transfer(requireNotNull(mcuPort.input), response, 250)
            if (count > 0 && response[0].toInt() == 2) return response.copyOf(count)
        }
        return null
    }

    private fun heartbeatLoop() { while (running.get()) { Thread.sleep(250); synchronized(this) {
        mcuPort.output?.let { mcu.transfer(it, XrealLightProtocol.mcu('@','K'), 200) }
    } } }

    private fun readImu() {
        val native = requireNotNull(ov580)
        try {
            imuCommand(0x19, 0)
            factoryCalibration = readFactoryCalibration().also { calibration ->
                executor.execute { listener.onImuCalibration(calibration.publicData()) }
            }
            imuCommand(0x19, 1)
            status("${model.displayName} OV580 IMU 已启动；已发布设备原始工厂校准")
        } catch (error: Throwable) {
            if (running.get()) status("${model.displayName} OV580 IMU 初始化失败：${error.message}")
            return
        }
        val buffers = imuPorts.associateWith { ByteArray(maxOf(128, it.input?.maxPacketSize ?: 128)) }
        while (running.get()) for ((port, bytes) in buffers) {
            val count = native.transfer(requireNotNull(port.input), bytes, 100)
            if (count > 0) {
                val hostTimestampNanos = System.nanoTime()
                XrealLightProtocol.decodeImu(
                    bytes.copyOf(count),
                    hostTimestampNanos,
                    factoryCalibration?.let { XrealLightFactoryCalibration.STATE } ?: ImuCalibrationState(),
                )?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        }
    }

    private fun imuCommand(command: Int, subcommand: Int): ByteArray {
        val native = requireNotNull(ov580)
        val port = requireNotNull(imuCommandPort) { "XREAL Light OV580 has no bidirectional IMU interface" }
        val request = byteArrayOf(2, command.toByte(), subcommand.toByte(), 0, 0, 0, 0)
        check(native.transfer(requireNotNull(port.output), request, 500) >= 0) {
            "OV580 command 0x${command.toString(16)} write failed"
        }
        repeat(64) {
            check(running.get()) { "OV580 session closed" }
            val response = ByteArray(maxOf(128, requireNotNull(port.input).maxPacketSize))
            val count = native.transfer(port.input, response, 500)
            if (count > 0 && response[0].toInt() == 2) return response.copyOf(count)
        }
        error("OV580 command 0x${command.toString(16)} timed out")
    }

    private fun readFactoryCalibration(): XrealLightFactoryCalibration {
        imuCommand(0x14, 0)
        val config = ByteArrayOutputStream()
        while (running.get()) {
            val part = imuCommand(0x15, 0)
            if (part.size < 3 || part[1].toInt() != 1) break
            val length = part[2].toInt() and 0xff
            check(3 + length <= part.size) { "OV580 calibration chunk is truncated" }
            config.write(part, 3, length)
            check(config.size() <= 1_000_000) { "OV580 calibration stream is too large" }
        }
        val configBytes = config.toByteArray()
        val jsonStart = find(configBytes, byteArrayOf('\n'.code.toByte(), '\n'.code.toByte(), '{'.code.toByte()), 0x28)
        check(jsonStart >= 0) { "OV580 calibration JSON start was not found" }
        val jsonOffset = jsonStart + 2
        val jsonEnd = find(configBytes, byteArrayOf('\n'.code.toByte(), '\n'.code.toByte()), jsonOffset + 1)
        check(jsonEnd > jsonOffset) { "OV580 calibration JSON end was not found" }
        return XrealLightFactoryCalibration.parse(configBytes, configBytes.copyOfRange(jsonOffset, jsonEnd))
    }

    private fun find(bytes: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty()) return fromIndex.coerceAtMost(bytes.size)
        for (offset in fromIndex.coerceAtLeast(0)..bytes.size - needle.size) {
            if (needle.indices.all { bytes[offset + it] == needle[it] }) return offset
        }
        return -1
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }
    override fun close() {
        if (!running.compareAndSet(true,false)) return
        worker?.interrupt(); heartbeat?.interrupt()
        if (Thread.currentThread() !== worker) worker?.join(1_200)
        if (Thread.currentThread() !== heartbeat) heartbeat?.join(1_200)
        imuPorts.forEach { ov580?.release(it.intf) }; ov580?.close()
        mcu.release(mcuPort.intf); mcu.close()
    }
    private companion object {
        fun ports(device: UsbDevice) = (0 until device.interfaceCount).map(device::getInterface).map { intf ->
            val endpoints=(0 until intf.endpointCount).map(intf::getEndpoint)
            Port(intf,endpoints.firstOrNull{it.direction==UsbConstants.USB_DIR_IN},endpoints.firstOrNull{it.direction==UsbConstants.USB_DIR_OUT})
        }.filter { it.input != null || it.output != null }
    }
}
