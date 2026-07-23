package com.taowen.arglass.driver.xreal.light

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
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
    private val worker: Thread?
    private val heartbeat: Thread?

    init {
        check(mcu.claim(mcuPort.intf)) { "Cannot claim XREAL Light MCU interface" }
        transact('@', '3', "1")
        if (imuEnabled) {
            val native = requireNotNull(ov580)
            imuPorts.forEach { native.claim(it.intf) }
            imuPorts.mapNotNull(Port::output).forEach { native.transfer(it, byteArrayOf(2,0x19,1,0,0,0,0), 500) }
            worker = Thread(::readImu, "xreal-light-imu").also(Thread::start)
        } else worker = null
        heartbeat = if (displayEnabled) Thread(::heartbeatLoop, "xreal-light-heartbeat").also(Thread::start) else null
    }

    @Synchronized override fun queryDisplayMode(): DisplayMode? = transact('3','3')?.let(XrealLightProtocol::decodeMode)
    @Synchronized override fun setDisplayMode(mode: DisplayMode): Boolean {
        val value = XrealLightProtocol.wire(mode)
        return transact('1','3', value.toString())?.let(XrealLightProtocol::decodeMode) == mode
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
        status("${model.displayName} OV580 IMU 已启动")
        val native = requireNotNull(ov580)
        val buffers = imuPorts.associateWith { ByteArray(maxOf(128, it.input?.maxPacketSize ?: 128)) }
        while (running.get()) for ((port, bytes) in buffers) {
            val count = native.transfer(requireNotNull(port.input), bytes, 100)
            if (count > 0) XrealLightProtocol.decodeImu(bytes.copyOf(count))?.let { sample -> executor.execute { listener.onImuSample(sample) } }
        }
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
