package com.taowen.arglass.driver.xreal.ones

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

internal object XrealOneSDriver : GlassesDriver {
    override val id = "xreal_one_s"
    private const val VID = 0x3318
    private const val PID = 0x043e

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId == PID)
        GlassesModel(
            id, "XREAL", "One S", VID, PID,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            setOf(DisplayMode.MIRROR_2D, DisplayMode.FULL_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D), id,
        ) else null

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = XrealOneSSession(usbManager, device, model, feature, executor, listener)
}
