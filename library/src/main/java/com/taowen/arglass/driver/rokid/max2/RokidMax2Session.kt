package com.taowen.arglass.driver.rokid.max2

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class RokidMax2Session(
    usbManager: UsbManager,
    device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val highSpeedPort = if (imuEnabled) requireNotNull(findHighSpeedPort(device)) {
        "${model.displayName} has no high-speed IMU endpoint 0x82 on interface 2"
    } else {
        null
    }
    private val imuThread = if (imuEnabled) {
        val (intf, _) = requireNotNull(highSpeedPort)
        check(usb.claim(intf)) { "Cannot claim ${model.displayName} high-speed IMU interface ${intf.id}" }
        Thread(::readHighSpeedImu, "rokid-max2-imu").also(Thread::start)
    } else {
        null
    }

    private fun readHighSpeedImu() {
        val (_, endpoint) = requireNotNull(highSpeedPort)
        status("${model.displayName} high-speed 9-axis IMU 0x82 已连接")
        val packet = ByteArray(HIGH_SPEED_TRANSFER_SIZE)
        while (running.get()) {
            // The captured implementation intentionally submits bulk URBs to endpoint 0x82,
            // even though some Android descriptors classify interface 2 as HID interrupt.
            val length = usb.bulkTransfer(endpoint, packet, 750)
            val hostTimestampNanos = System.nanoTime()
            if (length <= 0) continue
            val samples = RokidMax2Protocol.decodeHighSpeed(
                packet,
                length,
                hostTimestampNanos,
            )
            if (samples.isEmpty()) continue
            executor.execute {
                samples.forEach(listener::onImuSample)
            }
        }
    }

    private fun findHighSpeedPort(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val intf = device.getInterface(interfaceIndex)
            if (intf.id != HIGH_SPEED_INTERFACE) continue
            for (endpointIndex in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(endpointIndex)
                if (endpoint.address == HIGH_SPEED_ENDPOINT &&
                    endpoint.direction == UsbConstants.USB_DIR_IN
                ) return intf to endpoint
            }
        }
        return null
    }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        imuThread?.interrupt()
        if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
        highSpeedPort?.first?.let(usb::release)
        usb.close()
    }

    private companion object {
        const val HIGH_SPEED_INTERFACE = 2
        const val HIGH_SPEED_ENDPOINT = 0x82
        const val HIGH_SPEED_TRANSFER_SIZE = 4096
    }
}
