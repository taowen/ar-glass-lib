package com.taowen.arglass.driver.xreal.xbx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.xreal.XrealMcuDisplayModeProtocol
import com.taowen.arglass.driver.xreal.XrealNativeUsbSession
import com.taowen.arglass.driver.xreal.decodeXrealImuReport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Official Helen/Helen Pro MCU bootstrap and common-AA IMU transport. */
internal class XrealXbxSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
    private val displayModeProtocol: XrealMcuDisplayModeProtocol,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val usb = XrealNativeUsbSession(usbManager, device, useMcu = true, useImu = imuEnabled)
    private val heartbeatRunning = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null
    private val imuThread = if (imuEnabled) Thread(::runImu, "${model.id}-imu") else null
    @Volatile private var mcuReady = false
    @Volatile private var imuCalibration: XrealXbxCalibration? = null

    init { imuThread?.start() }

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

    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        ensureMcuReady()
        val value = usb.mcuDisplayModeValue(displayModeProtocol.queryPayloadBytes).takeIf { it >= 0 } ?: return null
        return displayModeProtocol.decodeProfile(value)
    }

    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        ensureMcuReady()
        val helenMode = displayModeProtocol.encodeProfile(profile) ?: return false
        return setDisplayModeValue(helenMode)
    }

    private fun setDisplayModeValue(value: Int): Boolean {
        val response = mcuCommand(0x08, displayModePayload(value))
        val status = if (response.size >= 23) response[22].toInt() and 0xff else -1
        return displayModeProtocol.acceptsSetStatus(value, status)
    }

    private fun displayModePayload(value: Int): ByteArray =
        if (displayModeProtocol.setPayloadBytes == 1) {
            byteArrayOf((value and 0xff).toByte())
        } else {
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        }

    private fun runImu() {
        try {
            ensureMcuReady()
            status("正在读取 ${model.displayName} IMU 校准")
            usb.imu(0x19, byteArrayOf(0))
            imuCalibration = readCalibration()
            executor.execute { listener.onImuCalibration(requireNotNull(imuCalibration).publicData()) }
            check(usb.imu(0x1a).isNotEmpty()) { "IMU sync failed" }
            check(usb.imu(0x19, byteArrayOf(1)).isNotEmpty()) { "IMU start failed" }
            status("${model.displayName} IMU 已启动")
            while (running.get()) {
                usb.readImu()?.takeIf { it.size == 64 }?.let { report ->
                    // Match the selected NR 3.1 receiver: capture the Android
                    // monotonic arrival clock at the USB boundary, before
                    // report decoding and factory calibration add latency.
                    val hostArrivalTimeNanos = SystemClock.elapsedRealtimeNanos()
                    decodeXrealImuReport(report)?.copy(
                        hostTimestampNanos = hostArrivalTimeNanos,
                    )
                }
                    ?.let { sample -> imuCalibration?.calibrate(sample) ?: sample }
                    ?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) {
            if (running.get()) status("${model.displayName} IMU 会话失败：${error.message}")
        }
    }

    private fun readCalibration(): XrealXbxCalibration {
        val length = usb.imu(0x14)
        val expected = if (length.size >= 12) ByteBuffer.wrap(length, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
        check(expected in 1..128 * 1024) { "Invalid IMU calibration length $expected" }
        val calibrationBytes = ByteArrayOutputStream(expected)
        while (running.get() && calibrationBytes.size() < expected) {
            val part = usb.imu(0x15)
            if (part.size < 8) break
            val bytes = ((part[5].toInt() and 0xff) or ((part[6].toInt() and 0xff) shl 8)) - 3
            if (bytes <= 0) break
            val available = minOf(bytes, part.size - 8, expected - calibrationBytes.size())
            if (available <= 0) break
            calibrationBytes.write(part, 8, available)
        }
        check(calibrationBytes.size() >= expected) {
            "Incomplete IMU calibration ${calibrationBytes.size()}/$expected"
        }
        val calibration = XrealXbxCalibration.parse(calibrationBytes.toByteArray())
        status(
            "IMU 校准数据：${calibrationBytes.size()} / $expected bytes；" +
                "已应用三轴偏置、scale/skew、传感器对齐、温漂和陀螺仪重力敏感度",
        )
        return calibration
    }

    private fun mcuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        return usb.mcu(command, payload)
    }

    private fun sendHeartbeat(waitForAck: Boolean): Boolean {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(SystemClock.elapsedRealtimeNanos()).array()
        val response = usb.mcu(0x1a, payload)
        return !waitForAck || response.isNotEmpty()
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

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        heartbeatRunning.set(false)
        heartbeatThread?.interrupt(); heartbeatThread?.join(800)
        imuThread?.interrupt(); if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
        usb.close()
    }
}
