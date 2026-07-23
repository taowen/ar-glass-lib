package com.taowen.arglass.driver.viture.beast

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
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
    private val responseLock = Object()
    @Volatile private var displayModeValue: Int? = null
    @Volatile private var nativeModeValue: Int? = null
    @Volatile private var setDisplayStatus: Int? = null

    init {
        check(ports.isNotEmpty()) { "VITURE Beast has no HID protocol interfaces" }
        ports.forEach { check(usb.claim(it.usbInterface)) { "Cannot claim Beast HID interface ${it.usbInterface.id}" } }
        if (imuEnabled) {
            ports.mapNotNull { it.input }.forEach { input ->
                Thread({ readLoop(input) }, "viture-beast-hid-${input.address}").also { workers += it; it.start() }
            }
            send(VitureBeastProtocol.command(0x0301, byteArrayOf(2, 2)))
            status("${model.displayName} RAW IMU 已请求（120 Hz）")
        }
    }

    @Synchronized
    override fun queryDisplayMode(): DisplayMode? {
        val nativeMode = queryNativeMode() ?: return null
        displayModeValue = null
        val query = if (nativeMode) VitureBeastProtocol.GET_NATIVE_DISPLAY_MODE else VitureBeastProtocol.GET_BYPASS_DISPLAY_MODE
        if (!send(VitureBeastProtocol.command(query)) || !awaitResponse { displayModeValue != null }) return null
        val value = displayModeValue
        val mode = when (value) {
            VitureBeastProtocol.MODE_2D_1080_60HZ -> DisplayMode.MIRROR_2D
            VitureBeastProtocol.NATIVE_MODE_3D_SBS_1080_60HZ -> if (nativeMode) DisplayMode.FULL_SBS_3D else null
            VitureBeastProtocol.BYPASS_MODE_3D_SBS_1080_60HZ -> if (!nativeMode) DisplayMode.FULL_SBS_3D else null
            else -> null
        }
        status("Beast 工作模式：${if (nativeMode) "Native" else "Bypass"}；显示：${mode?.let { if (it == DisplayMode.MIRROR_2D) "2D" else "3D" } ?: "未知(0x${value?.toString(16)})"}")
        return mode
    }

    @Synchronized
    override fun setDisplayMode(mode: DisplayMode): Boolean {
        val nativeMode = queryNativeMode() ?: return false
        val value = when (mode) {
            DisplayMode.MIRROR_2D -> VitureBeastProtocol.MODE_2D_1080_60HZ
            DisplayMode.FULL_SBS_3D -> if (nativeMode) {
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

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        workers.forEach(Thread::interrupt); workers.forEach { if (Thread.currentThread() !== it) it.join(1200) }
        ports.forEach { usb.release(it.usbInterface) }
        usb.close()
    }
}
