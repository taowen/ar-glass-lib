package com.taowen.arglass.driver.xreal.ones

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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** GS-family split transport: MCU over USB HID, IMU over USB Ethernet TCP. */
internal class XrealOneSSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val connection: UsbDeviceConnection? = if (displayEnabled) usbManager.openDevice(device) else null
    private val mcuInterface = if (displayEnabled) device.interfaceById(0) else null
    private val mcuIn = mcuInterface?.inputEndpoint()
    private val mcuOut = mcuInterface?.outputEndpoint()
    private val requestId = AtomicInteger(1)
    @Volatile private var socket: Socket? = null
    private val imuThread = if (imuEnabled) Thread(::readEthernetImu, "xreal-one-s-tcp-imu") else null

    init {
        if (displayEnabled) {
            checkNotNull(connection) { "Cannot open ${model.displayName} USB controller" }
            check(connection.claimInterface(requireNotNull(mcuInterface), true)) { "Cannot claim ${model.displayName} MCU interface 0" }
        }
        imuThread?.start()
    }

    override fun queryDisplayMode(): DisplayMode? {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        val response = mcuCommand(0x07)
        val value = when {
            response.size >= 27 -> ByteBuffer.wrap(response, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
            response.size >= 24 -> response[23].toInt() and 0xff
            else -> return null
        }
        return DisplayMode.fromWireValue(value)
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        val response = mcuCommand(0x08, byteArrayOf(mode.wireValue.toByte()))
        return response.size >= 23 && (response[22].toInt() and 0xff) == 0
    }

    private fun readEthernetImu() {
        val tcp = Socket()
        socket = tcp
        try {
            status("正在连接 ${model.displayName} USB Ethernet IMU")
            tcp.connect(InetSocketAddress(IMU_HOST, IMU_PORT), 2_000)
            tcp.soTimeout = 3_000
            status("${model.displayName} IMU 已连接 $IMU_HOST:$IMU_PORT")
            val input = tcp.getInputStream()
            val pending = ArrayList<Byte>()
            val chunk = ByteArray(4_096)
            while (running.get()) {
                val count = input.read(chunk)
                if (count < 0) break
                repeat(count) { pending += chunk[it] }
                while (true) {
                    val start = findHeader(pending)
                    if (start < 0) {
                        if (pending.size > HEADER.size) pending.subList(0, pending.size - HEADER.size).clear()
                        break
                    }
                    if (start > 0) pending.subList(0, start).clear()
                    if (pending.size < FRAME_SIZE) break
                    val frame = ByteArray(FRAME_SIZE) { pending[it] }
                    pending.subList(0, FRAME_SIZE).clear()
                    decodeImu(frame)?.let { sample -> executor.execute { listener.onImuSample(sample) } }
                }
            }
        } catch (error: Exception) {
            if (running.get()) status("${model.displayName} Ethernet IMU 不可达：${error.message}")
        } finally {
            runCatching { tcp.close() }
        }
    }

    private fun findHeader(bytes: List<Byte>): Int = (0..bytes.size - HEADER.size)
        .firstOrNull { offset -> HEADER.indices.all { bytes[offset + it] == HEADER[it] } } ?: -1

    private fun decodeImu(frame: ByteArray): ImuSample? {
        if ((0..frame.size - MARKER.size).none { offset -> MARKER.indices.all { frame[offset + it] == MARKER[it] } }) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val gx = buffer.getFloat(34); val gy = buffer.getFloat(38); val gz = buffer.getFloat(42)
        val ax = buffer.getFloat(46); val ay = buffer.getFloat(50); val az = buffer.getFloat(54)
        if (listOf(gx, gy, gz, ax, ay, az).any { !it.isFinite() } || sqrt(ax * ax + ay * ay + az * az) !in 5f..15f) return null
        return ImuSample(
            buffer.getLong(14) / 1_000L,
            floatArrayOf(-ax, -az, -ay),
            floatArrayOf(-gx, -gz, -gy),
            null,
            Float.NaN,
            1,
        )
    }

    private fun mcuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val id = requestId.getAndIncrement()
        val packet = NativeBridge.makeMcuCommand(command, id, payload)
        val usb = requireNotNull(connection)
        if (usb.bulkTransfer(requireNotNull(mcuOut), packet, packet.size, 750) != packet.size) return byteArrayOf()
        return readMatching(usb, requireNotNull(mcuIn), command, id)
    }

    private fun readMatching(usb: UsbDeviceConnection, endpoint: UsbEndpoint, command: Int, id: Int): ByteArray {
        val packet = ByteArray(maxOf(64, endpoint.maxPacketSize))
        repeat(12) {
            val length = usb.bulkTransfer(endpoint, packet, packet.size, 400)
            if (length < 17 || packet[0] != 0xfd.toByte()) return@repeat
            val responseId = ByteBuffer.wrap(packet, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val responseCommand = (packet[15].toInt() and 0xff) or ((packet[16].toInt() and 0xff) shl 8)
            if (responseId == id && responseCommand == command) return packet.copyOf(length)
        }
        return byteArrayOf()
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        imuThread?.interrupt(); if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
        mcuInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
    }

    private companion object {
        const val IMU_HOST = "169.254.2.1"
        const val IMU_PORT = 52_998
        const val FRAME_SIZE = 84
        val HEADER = byteArrayOf(0x28, 0x36, 0, 0, 0, 0x80.toByte())
        val MARKER = byteArrayOf(0, 0x40, 0x1f, 0, 0, 0x40)
    }
}
