package com.taowen.arglass.driver.xreal.air2ultra

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.xreal.XrealNativeUsbSession
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
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayModeEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val usb = XrealNativeUsbSession(usbManager, device, displayModeEnabled, imuEnabled)
    private val worker = if (imuEnabled) Thread(::runImu, "xreal-air2-ultra-imu") else null

    init { worker?.start() }

    @Synchronized
    override fun queryDisplayMode(): DisplayMode? {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val value = usb.mcuDisplayModeValue(payloadBytes = 1).takeIf { it >= 0 } ?: return null
        return XrealAir2UltraDisplayModeProtocol.decode(value)
    }

    @Synchronized
    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val value = usb.mcuDisplayModeValue(payloadBytes = 1).takeIf { it >= 0 } ?: return null
        return XrealAir2UltraDisplayModeProtocol.decodeProfile(value)
    }

    @Synchronized
    override fun setDisplayMode(mode: DisplayMode): Boolean {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val floraMode = XrealAir2UltraDisplayModeProtocol.encode(mode)
        return usb.setMcuDisplayModeValue(floraMode, payloadBytes = 1)
    }

    @Synchronized
    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        check(displayModeEnabled) { "This session was not opened for display-mode control" }
        val floraMode = XrealAir2UltraDisplayModeProtocol.encodeProfile(profile) ?: return false
        return usb.setMcuDisplayModeValue(floraMode, payloadBytes = 1)
    }

    private fun runImu() {
        try {
            status("正在初始化 ${model.displayName} IMU")
            usb.imu(0x19, byteArrayOf(0)); readCalibrationBestEffort(); usb.imu(0x1a)
            val started = usb.imu(0x19, byteArrayOf(1))
            status(if (started.isEmpty()) "IMU 启动命令未收到响应；继续被动监听" else "IMU 已启动")
            while (running.get()) {
                usb.readImu()?.takeIf { it.size == 64 }?.let(::decodeSample)?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) { if (running.get()) status("IMU 会话失败：${error.message}") }
    }

    private fun readCalibrationBestEffort() {
        val response = usb.imu(0x14)
        val total = if (response.size >= 13) ByteBuffer.wrap(response, 9, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
        if (total !in 1..1_000_000) return status("未取得有效校准长度，使用眼镜逐帧缩放参数")
        var received = 0
        while (running.get() && received < total) {
            val part = usb.imu(0x15); if (part.size <= 9) break; received += part.size - 9
        }
        status("IMU 校准数据：$received / $total bytes")
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
        usb.close()
    }
}
