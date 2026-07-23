package com.taowen.arglass.driver.xreal.air

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.xreal.airfamily.XrealAirFamilySession
import java.util.concurrent.Executor

internal object XrealAirDriver : GlassesDriver {
    override val id = "xreal_air"
    private const val VID = 0x3318
    private const val PID = 0x0424

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId == PID)
        model(id, "Air", PID) else null

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealAirFamilySession(usbManager, device, model, feature, executor, listener)
}

internal fun model(id: String, name: String, pid: Int) = GlassesModel(
    id, "XREAL", name, 0x3318, pid,
    setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
    setOf(DisplayMode.MIRROR_2D, DisplayMode.FULL_SBS_3D, DisplayMode.HALF_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D),
    id,
)
