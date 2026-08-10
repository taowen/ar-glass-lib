package com.taowen.arglass.driver.xreal.airfamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.xreal.XrealNativeUsbSession
import com.taowen.arglass.driver.xreal.XrealMcuDisplayModeProtocol
import com.taowen.arglass.driver.xreal.decodeXrealImuReport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Original Air/P55/P55E transport: MCU interface 4 and IMU interface 3. */
internal class XrealAirFamilySession(
    usbManager: UsbManager,
    device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
    private val displayModeProtocol: XrealMcuDisplayModeProtocol,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val usb = XrealNativeUsbSession(
        usbManager, device, displayEnabled, imuEnabled,
        mcuInterfaceId = 4, imuInterfaceId = 3,
    )
    private val worker = if (imuEnabled) Thread(::runImu, "${model.id}-imu") else null

    init { worker?.start() }

    @Synchronized
    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        return usb.mcuDisplayModeValue(displayModeProtocol.queryPayloadBytes).takeIf { it >= 0 }
            ?.let(displayModeProtocol::decodeProfile)
    }

    @Synchronized
    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        check(displayEnabled) { "This session was not opened for display-mode control" }
        val protocolValue = displayModeProtocol.encodeProfile(profile) ?: return false
        return usb.setMcuDisplayModeValue(protocolValue, displayModeProtocol.setPayloadBytes)
    }

    private fun runImu() {
        try {
            status("正在初始化 ${model.displayName} IMU")
            usb.imu(0x19, byteArrayOf(0))
            readCalibrationBestEffort()
            usb.imu(0x1a)
            val started = usb.imu(0x19, byteArrayOf(1))
            status(if (started.isEmpty()) "IMU 启动命令未收到响应；继续被动监听" else "IMU 已启动")
            while (running.get()) {
                usb.readImu()?.takeIf { it.size == 64 }?.let(::decodeXrealImuReport)
                    ?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) {
            if (running.get()) status("IMU 会话失败：${error.message}")
        }
    }

    private fun readCalibrationBestEffort() {
        val response = usb.imu(0x14)
        val total = if (response.size >= 13) {
            ByteBuffer.wrap(response, 9, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else 0
        if (total !in 1..1_000_000) return status("未取得有效校准长度，使用眼镜逐帧缩放参数")
        var received = 0
        while (running.get() && received < total) {
            val part = usb.imu(0x15)
            if (part.size <= 9) break
            received += part.size - 9
        }
        status("IMU 校准数据：$received / $total bytes")
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        worker?.interrupt()
        if (worker != null && Thread.currentThread() !== worker) worker.join(1_200)
        usb.close()
    }
}
