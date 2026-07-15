package com.taowen.arglass.driver.xreal.xbx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicInteger

/** Official Helen/Helen Pro MCU bootstrap and common-AA IMU transport. */
internal class XrealXbxSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val connection: UsbDeviceConnection = requireNotNull(usbManager.openDevice(device)) { "Cannot open ${model.displayName}" }
    private val mcuInterface = device.interfaceById(0)
    private val mcuIn = mcuInterface.inputEndpoint()
    private val mcuOut = mcuInterface.outputEndpoint()
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val imuInterface = if (imuEnabled) device.interfaceById(1) else null
    private val imuIn = imuInterface?.inputEndpoint()
    private val imuOut = imuInterface?.outputEndpoint()
    private val requestId = AtomicInteger(1)
    private val heartbeatRunning = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null
    private val imuThread = if (imuEnabled) Thread(::runImu, "${model.id}-imu") else null
    @Volatile private var mcuReady = false

    init {
        check(connection.claimInterface(mcuInterface, true)) { "Cannot claim ${model.displayName} MCU interface 0" }
        imuThread?.start()
    }

    @Synchronized
    private fun ensureMcuReady() {
        if (mcuReady) return
        status("正在初始化 ${model.displayName} MCU")
        val one = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()
        val commands = listOf(
            0x26 to byteArrayOf(), 0x57 to byteArrayOf(), 0x12 to one,
            0x02 to one, 0x34 to byteArrayOf(), 0x35 to byteArrayOf(),
        )
        commands.forEach { (command, payload) ->
            check(mcuCommand(command, payload).isNotEmpty()) { "MCU init command 0x${command.toString(16)} failed" }
        }
        check(mcuCommand(0x31, "3.1.1".encodeToByteArray()).isNotEmpty()) { "MCU SDK 3.1.1 handshake failed" }
        repeat(2) {
            check(sendHeartbeat(waitForAck = true)) { "MCU initial heartbeat failed" }
        }
        mcuReady = true
        startHeartbeat()
        status("${model.displayName} MCU 和 SDK 3.1.1 握手完成")
    }

    override fun queryDisplayMode(): DisplayMode? {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        ensureMcuReady()
        val response = mcuCommand(0x07)
        val value = when {
            response.size >= 27 -> ByteBuffer.wrap(response, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
            response.size >= 24 -> response[23].toInt() and 0xff
            else -> return null
        }
        return XrealXbxDisplayModeProtocol.decode(value)
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        ensureMcuReady()
        val helenMode = XrealXbxDisplayModeProtocol.encode(mode)
        val response = mcuCommand(0x08, byteArrayOf(helenMode.toByte()))
        return response.size >= 23 && (response[22].toInt() and 0xff) == 0
    }

    private fun runImu() {
        try {
            ensureMcuReady()
            val usbInterface = requireNotNull(imuInterface)
            check(connection.claimInterface(usbInterface, true)) { "Cannot claim ${model.displayName} IMU interface 1" }
            status("正在读取 ${model.displayName} IMU 校准")
            imuCommand(0x19, byteArrayOf(0))
            readCalibration()
            check(imuCommand(0x1a).isNotEmpty()) { "IMU sync failed" }
            check(imuCommand(0x19, byteArrayOf(1)).isNotEmpty()) { "IMU start failed" }
            status("${model.displayName} IMU 已启动")
            val input = requireNotNull(imuIn)
            val report = ByteArray(maxOf(64, input.maxPacketSize))
            while (running.get()) {
                val length = connection.tracedBulkTransfer(device, input, report, report.size, 750)
                if (length == 64) decodeImu(report.copyOf(length))?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) {
            if (running.get()) status("${model.displayName} IMU 会话失败：${error.message}")
        }
    }

    private fun readCalibration() {
        val length = imuCommand(0x14)
        val expected = if (length.size >= 12) ByteBuffer.wrap(length, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
        check(expected in 1..128 * 1024) { "Invalid IMU calibration length $expected" }
        var received = 0
        while (running.get() && received < expected) {
            val part = imuCommand(0x15)
            if (part.size < 8) break
            val bytes = ((part[5].toInt() and 0xff) or ((part[6].toInt() and 0xff) shl 8)) - 3
            if (bytes <= 0) break
            received += bytes
        }
        check(received >= expected) { "Incomplete IMU calibration $received/$expected" }
        status("IMU 校准数据：$received / $expected bytes")
    }

    private fun imuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val packet = NativeBridge.makeImuCommand(command, payload)
        synchronized(connection) {
            if (connection.tracedBulkTransfer(device, requireNotNull(imuOut), packet, packet.size, 750) != packet.size) return byteArrayOf()
        }
        return readMatching(requireNotNull(imuIn), 0xaa, command)
    }

    private fun mcuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val id = requestId.getAndIncrement()
        val packet = NativeBridge.makeMcuCommand(command, id, payload)
        synchronized(connection) {
            if (connection.tracedBulkTransfer(device, mcuOut, packet, packet.size, 750) != packet.size) return byteArrayOf()
        }
        return readMatching(mcuIn, 0xfd, command, id)
    }

    private fun sendHeartbeat(waitForAck: Boolean): Boolean {
        val id = requestId.getAndIncrement()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(SystemClock.elapsedRealtimeNanos()).array()
        val packet = NativeBridge.makeMcuCommand(0x1a, id, payload)
        val written = synchronized(connection) { connection.tracedBulkTransfer(device, mcuOut, packet, packet.size, 250) == packet.size }
        return written && (!waitForAck || readMatching(mcuIn, 0xfd, 0x1a, id).isNotEmpty())
    }

    private fun startHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) return
        heartbeatThread = Thread({
            while (heartbeatRunning.get() && running.get()) {
                sendHeartbeat(waitForAck = false)
                SystemClock.sleep(100)
            }
        }, "${model.id}-heartbeat").also(Thread::start)
    }

    private fun readMatching(endpoint: UsbEndpoint, magic: Int, command: Int, id: Int? = null): ByteArray {
        val packet = ByteArray(maxOf(64, endpoint.maxPacketSize))
        repeat(12) {
            val length = synchronized(connection) { connection.tracedBulkTransfer(device, endpoint, packet, packet.size, 500) }
            if (length < 8 || (packet[0].toInt() and 0xff) != magic) return@repeat
            val responseCommand = if (magic == 0xfd && length >= 17)
                (packet[15].toInt() and 0xff) or ((packet[16].toInt() and 0xff) shl 8) else packet[7].toInt() and 0xff
            val responseId = if (magic == 0xfd && length >= 11) ByteBuffer.wrap(packet, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int else null
            if (responseCommand == command && (id == null || responseId == id)) return packet.copyOf(length)
        }
        return byteArrayOf()
    }

    private fun decodeImu(report: ByteArray): ImuSample? {
        val values = NativeBridge.decodeImuReport(report) ?: return null
        return ImuSample(
            ByteBuffer.wrap(report, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long,
            floatArrayOf(values[1], values[2], values[3]),
            floatArrayOf(values[4], values[5], values[6]),
            floatArrayOf(values[7], values[8], values[9]),
            values[10], values[11].toInt(),
        )
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        heartbeatRunning.set(false)
        heartbeatThread?.interrupt(); heartbeatThread?.join(800)
        imuThread?.interrupt(); if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
        imuInterface?.let { runCatching { connection.releaseInterface(it) } }
        runCatching { connection.releaseInterface(mcuInterface) }
        connection.close()
    }
}
