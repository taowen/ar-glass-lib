package com.taowen.arglass.driver.xreal.air2ultra

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

internal object XrealAir2UltraDriver : GlassesDriver {
    override val id = "xreal_air_2_ultra"
    private const val VID = 0x3318
    private const val PID = 0x0426

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId == PID)
        GlassesModel(
            id, "XREAL", "Air 2 Ultra", VID, PID,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            DisplayMode.entries.toSet(),
            id,
        ) else null

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = XrealAir2UltraSession(usbManager, device, model, feature, executor, listener)
}
