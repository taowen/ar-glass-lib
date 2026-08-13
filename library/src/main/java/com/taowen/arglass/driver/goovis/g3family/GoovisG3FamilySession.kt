package com.taowen.arglass.driver.goovis.g3family

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class GoovisG3FamilySession(
    usbManager: UsbManager,
    device: UsbDevice,
    private val model: GlassesModel,
    private val modelKind: GoovisModelKind,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val usb = GoovisNativeUsbSession(usbManager, device, modelKind, requireInput = imuEnabled)
    private val worker: Thread?

    init {
        if (imuEnabled) {
            check(usb.setImuEnabled(true)) {
                "Cannot enable ${model.displayName} IMU stream"
            }
            worker = Thread(::readImu, "goovis-g3-family-imu").also(Thread::start)
            status("${model.displayName} native 六轴 IMU 已连接（无磁力计/出厂校准）")
        } else {
            worker = null
        }
    }

    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        check(displayEnabled) { "Display mode control was not opened for ${model.displayName}" }
        // The recovered GOOVIS protocol has no confirmed query command or acknowledgement.
        return null
    }

    @Synchronized
    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        check(displayEnabled) { "Display mode control was not opened for ${model.displayName}" }
        val enableSbs = when (profile.layout) {
            GlassesDisplayLayout.MONO_2D -> false
            GlassesDisplayLayout.FULL_SBS_3D -> true
            else -> return false
        }
        return usb.setDisplaySbs(enableSbs)
    }

    private fun readImu() {
        try {
            while (running.get()) {
                usb.readImu()?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) {
            if (running.get()) status("${model.displayName} IMU 会话失败：${error.message}")
        }
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        if (imuEnabled) usb.setImuEnabled(false)
        worker?.interrupt()
        if (Thread.currentThread() !== worker) worker?.join(1_200)
        usb.close()
    }
}
