package com.taowen.arglass.driver.xreal.air2ultra

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.inputEndpoint
import com.taowen.arglass.driver.interfaceById
import com.taowen.arglass.driver.outputEndpoint
import com.taowen.arglass.driver.tracedBulkTransfer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class XrealAir2UltraSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val connection: UsbDeviceConnection = requireNotNull(usbManager.openDevice(device)) { "Cannot open USB device" }
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayModeEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val imuInterface = if (imuEnabled) device.interfaceById(1) else null
    private val mcuInterface = if (displayModeEnabled) device.interfaceById(0) else null
    private val imuIn = imuInterface?.inputEndpoint()
    private val imuOut = imuInterface?.outputEndpoint()
    private val mcuIn = mcuInterface?.inputEndpoint()
    private val mcuOut = mcuInterface?.outputEndpoint()
    private val worker = if (imuEnabled) Thread(::runImu, "xreal-air2-ultra-imu") else null
    private var requestId = 1

    init {
        mcuInterface?.let { check(connection.claimInterface(it, true)) { "Cannot claim XREAL MCU interface ${it.id}" } }
        imuInterface?.let { check(connection.claimInterface(it, true)) { "Cannot claim XREAL IMU interface ${it.id}" } }
        worker?.start()
    }

    @Synchronized
    override fun queryDisplayMode(): DisplayMode? {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val response = mcuCommand(0x07)
        val value = when {
            response.size >= 27 -> ByteBuffer.wrap(response, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
            response.size >= 24 -> response[23].toInt() and 0xff
            else -> return null
        }
        return XrealAir2UltraDisplayModeProtocol.decode(value)
    }

    @Synchronized
    override fun setDisplayMode(mode: DisplayMode): Boolean {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val floraMode = XrealAir2UltraDisplayModeProtocol.encode(mode)
        return mcuCommand(0x08, byteArrayOf(floraMode.toByte())).let {
            it.size >= 23 && (it[22].toInt() and 0xff) == 0
        }
    }

    private fun runImu() {
        try {
            status("正在初始化 ${model.displayName} IMU")
            imuCommand(0x19, byteArrayOf(0)); readCalibrationBestEffort(); imuCommand(0x1a)
            val started = imuCommand(0x19, byteArrayOf(1))
            status(if (started.isEmpty()) "IMU 启动命令未收到响应；继续被动监听" else "IMU 已启动")
            val input = requireNotNull(imuIn)
            val packet = ByteArray(maxOf(64, input.maxPacketSize))
            while (running.get()) {
                val length = connection.tracedBulkTransfer(device, input, packet, packet.size, 750)
                if (length == 64) decodeSample(packet.copyOf(length))?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) { if (running.get()) status("IMU 会话失败：${error.message}") }
    }

    private fun readCalibrationBestEffort() {
        val response = imuCommand(0x14)
        val total = if (response.size >= 13) ByteBuffer.wrap(response, 9, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
        if (total !in 1..1_000_000) return status("未取得有效校准长度，使用眼镜逐帧缩放参数")
        var received = 0
        while (running.get() && received < total) {
            val part = imuCommand(0x15); if (part.size <= 9) break; received += part.size - 9
        }
        status("IMU 校准数据：$received / $total bytes")
    }

    private fun imuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray = synchronized(connection) {
        val packet = NativeBridge.makeImuCommand(command, payload)
        if (connection.tracedBulkTransfer(device, requireNotNull(imuOut), packet, packet.size, 500) != packet.size) return@synchronized byteArrayOf()
        readMatching(requireNotNull(imuIn), 0xaa, command)
    }

    private fun mcuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray = synchronized(connection) {
        val id = requestId++; val packet = NativeBridge.makeMcuCommand(command, id, payload)
        if (connection.tracedBulkTransfer(device, requireNotNull(mcuOut), packet, packet.size, 500) != packet.size) return@synchronized byteArrayOf()
        readMatching(requireNotNull(mcuIn), 0xfd, command, id)
    }

    private fun readMatching(endpoint: UsbEndpoint, magic: Int, command: Int, id: Int? = null): ByteArray {
        val packet = ByteArray(maxOf(64, endpoint.maxPacketSize))
        repeat(10) {
            val length = connection.tracedBulkTransfer(device, endpoint, packet, packet.size, 300)
            if (length < 8 || (packet[0].toInt() and 0xff) != magic) return@repeat
            val responseCommand = if (magic == 0xfd && length >= 17) (packet[15].toInt() and 0xff) or ((packet[16].toInt() and 0xff) shl 8) else packet[7].toInt() and 0xff
            val responseId = if (magic == 0xfd && length >= 11) ByteBuffer.wrap(packet, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int else null
            if (responseCommand == command && (id == null || responseId == id)) return packet.copyOf(length)
        }
        return byteArrayOf()
    }

    private fun decodeSample(packet: ByteArray): ImuSample? {
        val values = NativeBridge.decodeImuReport(packet) ?: return null
        return ImuSample(
            ByteBuffer.wrap(packet, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long,
            floatArrayOf(values[1], values[2], values[3]), floatArrayOf(values[4], values[5], values[6]),
            floatArrayOf(values[7], values[8], values[9]), values[10], values[11].toInt(),
        )
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        worker?.interrupt(); if (worker != null && Thread.currentThread() !== worker) worker.join(1200)
        imuInterface?.let(connection::releaseInterface); mcuInterface?.let(connection::releaseInterface); connection.close()
    }
}
