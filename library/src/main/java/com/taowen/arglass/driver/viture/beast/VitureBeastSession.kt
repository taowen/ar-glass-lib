package com.taowen.arglass.driver.viture.beast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
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
    private val connection: UsbDeviceConnection = requireNotNull(usbManager.openDevice(device)) { "Cannot open USB device" }
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
    @Volatile private var displayModeValue: Int? = null
    @Volatile private var nativeModeValue: Int? = null
    @Volatile private var setDisplayStatus: Int? = null

    init {
        check(ports.isNotEmpty()) { "VITURE Beast has no HID protocol interfaces" }
        ports.forEach { check(connection.claimInterface(it.usbInterface, true)) { "Cannot claim Beast HID interface ${it.usbInterface.id}" } }
        if (imuEnabled) {
            ports.mapNotNull { it.input }.forEach { input ->
                Thread({ readLoop(input) }, "viture-beast-hid-${input.address}").also { workers += it; it.start() }
            }
            send(VitureBeastProtocol.command(0x0301, byteArrayOf(2, 2)))
            status("${model.displayName} RAW IMU 已请求（120 Hz）")
        }
    }

    override fun queryDisplayMode(): DisplayMode? {
        nativeModeValue = null; displayModeValue = null
        if (!send(VitureBeastProtocol.command(0x3140)) || !send(VitureBeastProtocol.command(0x3142))) return null
        if (!imuEnabled) readResponsesUntil { displayModeValue != null && nativeModeValue != null }
        val native = when (nativeModeValue) { 1 -> "Native"; 0 -> "Bypass"; else -> "未知" }
        status("Beast 工作模式：$native；显示：${if (displayModeValue == 0x37) "3D" else if (displayModeValue == 0x31) "2D" else "未知"}")
        return when (displayModeValue) { 0x31 -> DisplayMode.MIRROR_2D; 0x37 -> DisplayMode.FULL_SBS_3D; else -> null }
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        val value = when (mode) {
            DisplayMode.MIRROR_2D -> 0x31
            DisplayMode.FULL_SBS_3D -> 0x37
            else -> return false
        }
        setDisplayStatus = null
        if (!send(VitureBeastProtocol.command(0x0142, byteArrayOf(value.toByte())))) return false
        if (!imuEnabled) readResponsesUntil { setDisplayStatus != null }
        return setDisplayStatus == 0
    }

    private fun send(command: ByteArray): Boolean {
        var sent = false
        ports.forEach { port ->
            val count = port.output?.let { output -> connection.bulkTransfer(output, command, command.size, 500) }
                ?: connection.controlTransfer(0x21, 0x09, 0x0200, port.usbInterface.id, command, command.size, 500)
            if (count == command.size) sent = true
        }
        return sent
    }

    private fun readResponsesUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 1_500_000_000L
        while (!done() && System.nanoTime() < deadline) {
            ports.mapNotNull { it.input }.forEach { input ->
                val bytes = ByteArray(maxOf(64, input.maxPacketSize))
                val length = connection.bulkTransfer(input, bytes, bytes.size, 80)
                if (length > 0) handlePacket(bytes, length)
            }
        }
    }

    private fun readLoop(input: UsbEndpoint) {
        val bytes = ByteArray(maxOf(64, input.maxPacketSize))
        while (running.get()) {
            val length = connection.bulkTransfer(input, bytes, bytes.size, 750)
            if (length > 0) handlePacket(bytes.copyOf(length), length)
        }
    }

    private fun handlePacket(bytes: ByteArray, length: Int) {
        VitureBeastProtocol.decodeImu(bytes, length)?.let { sample ->
            executor.execute { listener.onImuSample(sample) }
            return
        }
        val packet = VitureBeastProtocol.decode(bytes, length) ?: return
        val value = packet.payload.getOrNull(1)?.toInt()?.and(0xff)
            ?: packet.payload.firstOrNull()?.toInt()?.and(0xff)
        when (packet.messageId) {
            VitureBeastProtocol.NATIVE_MODE_RESPONSE -> nativeModeValue = value
            VitureBeastProtocol.DISPLAY_MODE_RESPONSE -> displayModeValue = value
            VitureBeastProtocol.SET_DISPLAY_RESPONSE -> setDisplayStatus = packet.payload.firstOrNull()?.toInt()?.and(0xff)
        }
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        workers.forEach(Thread::interrupt); workers.forEach { if (Thread.currentThread() !== it) it.join(1200) }
        ports.forEach { connection.releaseInterface(it.usbInterface) }
        connection.close()
    }
}
