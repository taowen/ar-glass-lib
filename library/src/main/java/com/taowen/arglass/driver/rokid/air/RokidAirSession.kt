package com.taowen.arglass.driver.rokid.air

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuSample
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.NativeUsbDeviceSession
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class RokidAirSession(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val usb = NativeUsbDeviceSession(usbManager, device)
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val imuPort: Pair<UsbInterface, UsbEndpoint>? = if (imuEnabled) findImuPort() else null
    private val imuThread: Thread?
    private var acceleration: RokidProtocol.SensorReading? = null
    private var gyroscope: RokidProtocol.SensorReading? = null
    private var magnetometer: FloatArray? = null

    init {
        if (imuEnabled) {
            val (usbInterface, endpoint) = requireNotNull(imuPort) {
                "${model.displayName} has no interrupt endpoint 0x%02X".format(expectedImuEndpoint())
            }
            check(usb.claim(usbInterface)) { "Cannot claim ${model.displayName} IMU interface ${usbInterface.id}" }
            imuThread = Thread({ readImu(endpoint) }, "rokid-air-imu").also { it.start() }
            status("${model.displayName} IMU 已连接")
        } else {
            imuThread = null
        }
    }

    override fun queryDisplayMode(): DisplayMode? {
        val response = ByteArray(64)
        val length = usb.control(0xc0, 0x81, 0, 1, response, 500)
        return if (length >= 2) RokidProtocol.displayMode(response[1].toInt() and 0xff) else null
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        val value = RokidProtocol.wireValue(mode) ?: return false
        val payload = byteArrayOf(0)
        return usb.control(0x40, 0x01, value, 1, payload, 500) >= 0
    }

    private fun findImuPort(): Pair<UsbInterface, UsbEndpoint>? {
        val expectedEndpoint = expectedImuEndpoint()
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.address == expectedEndpoint &&
                    endpoint.direction == UsbConstants.USB_DIR_IN &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) return usbInterface to endpoint
            }
        }
        return null
    }

    // Rokid's own SDK and ar-drivers-rs use 0x83 for PID 0x162D; the other
    // known application identities expose the sensor stream on 0x82.
    private fun expectedImuEndpoint(): Int = if (device.productId == 0x162d) 0x83 else RokidProtocol.INTERRUPT_ENDPOINT

    private fun readImu(endpoint: UsbEndpoint) {
        val bytes = ByteArray(maxOf(64, endpoint.maxPacketSize))
        while (running.get()) {
            val length = usb.transfer(endpoint, bytes, 750)
            if (length <= 0) continue
            RokidProtocol.decodeCombined(bytes, length)?.let { emit(it); continue }
            val reading = RokidProtocol.decodeSensor(bytes, length) ?: continue
            when (reading.type) {
                1 -> acceleration = reading
                2 -> gyroscope = reading
                3 -> magnetometer = reading.vector
            }
            val accel = acceleration
            val gyro = gyroscope
            if (accel != null && gyro != null && accel.timestamp == gyro.timestamp) {
                acceleration = null
                gyroscope = null
                emit(ImuSample(accel.timestamp, accel.vector, gyro.vector, magnetometer, Float.NaN, 4))
            }
        }
    }

    private fun emit(sample: ImuSample) = executor.execute { listener.onImuSample(sample) }
    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        imuThread?.interrupt()
        if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
        imuPort?.first?.let(usb::release)
        usb.close()
    }
}
