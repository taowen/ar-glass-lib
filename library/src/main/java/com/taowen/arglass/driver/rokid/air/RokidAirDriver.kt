package com.taowen.arglass.driver.rokid.air

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import java.util.concurrent.Executor

internal object RokidAirDriver : GlassesDriver {
    override val id = "rokid_air"
    private const val VID = 0x04d2
    private const val PID = 0x162f

    override fun identify(device: UsbDevice): GlassesModel? {
        if (device.vendorId != VID || device.productId != PID) return null
        val model = if (device.productName?.contains("Max", ignoreCase = true) == true) "Max" else "Air"
        return GlassesModel(
            id = if (model == "Max") "rokid_max" else id,
            manufacturer = "Rokid",
            model = model,
            usbVendorId = VID,
            usbProductId = PID,
            capabilities = setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            supportedDisplayModes = setOf(
                DisplayMode.MIRROR_2D,
                DisplayMode.FULL_SBS_3D,
                DisplayMode.HIGH_REFRESH_SBS_3D,
            ),
            driverId = id,
        )
    }

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = RokidAirSession(usbManager, device, model, feature, executor, listener)
}
