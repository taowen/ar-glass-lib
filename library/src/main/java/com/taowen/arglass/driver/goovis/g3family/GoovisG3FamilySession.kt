package com.taowen.arglass.driver.goovis.g3family

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
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
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val displayEnabled = feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL
    private val port = findHidPort(device, requireInput = imuEnabled)
    private val worker: Thread?
    @Volatile private var selectedProfile: GlassesDisplayProfile? = null
    private var deviceTimestampNanos = 0L

    init {
        check(usb.claim(port.usbInterface)) {
            "Cannot claim ${model.displayName} HID interface ${port.usbInterface.id}"
        }
        if (imuEnabled) {
            check(writeReport(GoovisG3Protocol.imuStreamReport(enable = true))) {
                "Cannot enable ${model.displayName} IMU stream"
            }
            worker = Thread(::readImu, "goovis-g3-family-imu").also(Thread::start)
            status("${model.displayName} 六轴 IMU 已连接（无磁力计/出厂校准）")
        } else {
            worker = null
        }
    }

    override fun queryDisplayProfile(): GlassesDisplayProfile? = selectedProfile

    @Synchronized
    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        check(displayEnabled) { "Display mode control was not opened for ${model.displayName}" }
        val enableSbs = when (profile.layout) {
            GlassesDisplayLayout.MONO_2D -> false
            GlassesDisplayLayout.FULL_SBS_3D -> true
            else -> return false
        }
        if (!writeReport(GoovisG3Protocol.displayModeReport(enableSbs))) return false
        selectedProfile = profile
        return true
    }

    private fun readImu() {
        val input = requireNotNull(port.inputEndpoint)
        val bytes = ByteArray(maxOf(GoovisG3Protocol.REPORT_SIZE, input.maxPacketSize))
        while (running.get()) {
            val length = usb.transfer(input, bytes, 250)
            if (length < GoovisG3Protocol.MINIMUM_IMU_REPORT_SIZE) continue
            val hostTimestampNanos = System.nanoTime()
            val intervalNanos = GoovisG3Protocol.sampleIntervalNanos(bytes, length)
            if (intervalNanos == 0L) continue
            deviceTimestampNanos += intervalNanos
            val sample = GoovisG3Protocol.decodeImuReport(
                bytes = bytes,
                length = length,
                modelKind = modelKind,
                deviceTimestampNanos = deviceTimestampNanos,
                hostTimestampNanos = hostTimestampNanos,
            ) ?: continue
            executor.execute { listener.onImuSample(sample) }
        }
    }

    private fun writeReport(report: ByteArray): Boolean =
        usb.transfer(port.outputEndpoint, report, GoovisG3Protocol.USB_TIMEOUT_MS) == report.size

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        if (imuEnabled) writeReport(GoovisG3Protocol.imuStreamReport(enable = false))
        worker?.interrupt()
        if (Thread.currentThread() !== worker) worker?.join(1_200)
        usb.release(port.usbInterface)
        usb.close()
    }

    private data class HidPort(
        val usbInterface: UsbInterface,
        val inputEndpoint: UsbEndpoint?,
        val outputEndpoint: UsbEndpoint,
    )

    private companion object {
        fun findHidPort(device: UsbDevice, requireInput: Boolean): HidPort {
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
                }
                if (output != null && (!requireInput || input != null)) return HidPort(usbInterface, input, output)
            }
            error("GOOVIS HID interface with interrupt OUT${if (requireInput) "/IN" else ""} endpoints not found")
        }
    }
}
