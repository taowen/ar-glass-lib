package com.taowen.arglass.driver.rokid.max2

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import java.util.concurrent.Executor

/** Rokid Max 2 high-speed IMU identity; this is not the older Air/Max HID report format. */
internal object RokidMax2Driver : GlassesDriver {
    override val id = "rokid_max_2"
    private const val VID = 0x04d2
    private const val PID = 0x2002

    override fun identify(device: UsbDevice): GlassesModel? =
        if (device.vendorId == VID && device.productId == PID) {
            GlassesModel(
                id = id,
                manufacturer = "Rokid",
                model = "Max 2",
                usbVendorId = VID,
                usbProductId = PID,
                capabilities = setOf(GlassesCapability.IMU),
                driverId = id,
                showInArctrlDisplayModeToggle = false,
                imuTrackingSupport = ImuTrackingSupport(9, ImuCalibrationState()),
            )
        } else {
            null
        }

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = RokidMax2Session(usbManager, device, model, feature, executor, listener)
}
