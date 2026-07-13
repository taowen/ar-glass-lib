package com.taowen.arglass.driver.luci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.tracedControlTransfer
import java.util.concurrent.atomic.AtomicBoolean

internal class LuciDisplaySession(
    usbManager: UsbManager,
    private val device: UsbDevice,
) : DriverSession {
    private val closed = AtomicBoolean(false)
    private val hidInterface = (0 until device.interfaceCount).map(device::getInterface)
        .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        ?: error("LUCI HID interface not found")
    private val connection = requireNotNull(usbManager.openDevice(device)) { "Cannot open LUCI USB device" }

    init {
        check(connection.claimInterface(hidInterface, true)) { "Cannot claim LUCI HID interface ${hidInterface.id}" }
        connection.setInterface(hidInterface)
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        val enable3d = when (mode) {
            DisplayMode.MIRROR_2D -> false
            DisplayMode.FULL_SBS_3D -> true
            else -> return false
        }
        val report = LuciDisplayProtocol.powerStateReport(enable3d)
        val transferred = connection.tracedControlTransfer(
            device,
            LuciDisplayProtocol.HID_SET_REPORT_REQUEST_TYPE,
            LuciDisplayProtocol.HID_SET_REPORT,
            LuciDisplayProtocol.HID_FEATURE_REPORT_ID_2,
            hidInterface.id,
            report,
            report.size,
            LuciDisplayProtocol.USB_TIMEOUT_MS,
        )
        return transferred >= 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection.releaseInterface(hidInterface)
        connection.close()
    }
}
