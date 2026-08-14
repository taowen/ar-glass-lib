package com.taowen.arglass.driver.xreal.airfamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuSample
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.rayneo.RayneoMagneticCalibrationStore
import com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticCalibration
import com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticCalibrator
import com.taowen.arglass.driver.xreal.XrealFactoryCalibration
import com.taowen.arglass.driver.xreal.XrealNativeUsbSession
import com.taowen.arglass.driver.xreal.XrealMcuDisplayModeProtocol
import com.taowen.arglass.driver.xreal.decodeXrealImuReport
import java.io.ByteArrayOutputStream
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
    private val magneticCalibrator = RayneoMagneticCalibrator()
    private val calibrationStoreKey = "xreal:${model.id}:" + (
        runCatching { device.serialNumber }.getOrNull()?.takeIf(String::isNotBlank)
            ?: "${device.vendorId}:${device.productId}:${device.productName.orEmpty()}"
        )
    @Volatile private var factoryCalibration: XrealFactoryCalibration? = null
    @Volatile private var magneticCalibration: RayneoMagneticCalibration? = null
    private var lastProgressSamples = -PROGRESS_INTERVAL
    private var lastProgressPhase: ImuHostCalibrationPhase? = null

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
            factoryCalibration = readCalibration().also { calibration ->
                magneticCalibration = RayneoMagneticCalibrationStore.load(calibrationStoreKey)
                magneticCalibrator.useCalibration(magneticCalibration)
                executor.execute { listener.onImuCalibration(calibration.publicData(magneticCalibration)) }
            }
            usb.imu(0x1a)
            val started = usb.imu(0x19, byteArrayOf(1))
            status(if (started.isEmpty()) "IMU 启动命令未收到响应；继续被动监听" else "IMU 已启动")
            while (running.get()) {
                val report = usb.readImu()?.takeIf { it.size == 64 } ?: continue
                val sample = decodeXrealImuReport(report, System.nanoTime()) ?: continue
                executor.execute { listener.onImuSample(observeMagnetic(sample)) }
            }
        } catch (error: Throwable) {
            if (running.get()) status("IMU 会话失败：${error.message}")
        }
    }

    private fun readCalibration(): XrealFactoryCalibration {
        val response = usb.imu(0x14)
        val total = if (response.size >= 13) {
            ByteBuffer.wrap(response, 9, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else 0
        check(total in 1..1_000_000) { "未取得有效 IMU 校准长度 $total" }
        val bytes = ByteArrayOutputStream(total)
        while (running.get() && bytes.size() < total) {
            val part = usb.imu(0x15)
            if (part.size <= 9) break
            bytes.write(part, 9, minOf(part.size - 9, total - bytes.size()))
        }
        check(bytes.size() == total) { "IMU 校准数据不完整：${bytes.size()} / $total bytes" }
        status(
            "IMU 校准数据：${bytes.size()} / $total bytes；" +
                "已发布矩阵、偏置、温漂和重力敏感项；IMU 样本保持协议解码后的 SI 数值",
        )
        return XrealFactoryCalibration.parse(bytes.toByteArray())
    }

    private fun observeMagnetic(sample: ImuSample): ImuSample {
        // The report repeats the last magnetic vector between real magnetic
        // observations. SDK 3.1 gates its magnetic path with byte 56/62; do
        // the same so cached vectors cannot bias host coverage or fitting.
        if (sample.transportMetadata?.magneticFieldFresh != true) return sample
        val factory = factoryCalibration ?: return sample
        val magnetic = sample.magneticField ?: return sample
        val update = magneticCalibrator.update(magnetic)
        reportMagneticProgress(update.progress.phase, update.progress.acceptedSamples, update)
        if (update.calibration != null && magneticCalibration == null) {
            magneticCalibration = update.calibration
            RayneoMagneticCalibrationStore.save(calibrationStoreKey, update.calibration)
            executor.execute { listener.onImuCalibration(factory.publicData(update.calibration, hasMagnetometer = true)) }
            status("${model.displayName} 磁力计 host 三轴椭球校准完成并已保存")
        }
        return sample
    }

    private fun reportMagneticProgress(
        phase: ImuHostCalibrationPhase,
        acceptedSamples: Int,
        update: com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticUpdate,
    ) {
        if (phase != lastProgressPhase || acceptedSamples - lastProgressSamples >= PROGRESS_INTERVAL) {
            lastProgressPhase = phase
            lastProgressSamples = acceptedSamples
            executor.execute { listener.onImuHostCalibrationProgress(update.progress) }
        }
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun resetHostImuCalibration(): Boolean {
        RayneoMagneticCalibrationStore.clear(calibrationStoreKey)
        magneticCalibration = null
        magneticCalibrator.reset()
        lastProgressSamples = -PROGRESS_INTERVAL
        lastProgressPhase = null
        factoryCalibration?.let {
            executor.execute { listener.onImuCalibration(it.publicData()) }
        }
        status("${model.displayName} 已清除磁力计 host 校准；请缓慢绕三个轴旋转眼镜")
        return true
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        worker?.interrupt()
        if (worker != null && Thread.currentThread() !== worker) worker.join(1_200)
        usb.close()
    }

    private companion object { const val PROGRESS_INTERVAL = 100 }
}
