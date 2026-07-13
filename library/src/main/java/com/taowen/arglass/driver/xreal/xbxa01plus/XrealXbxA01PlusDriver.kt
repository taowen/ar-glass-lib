package com.taowen.arglass.driver.xreal.xbxa01plus

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.xreal.xbx.XrealXbxModel
import com.taowen.arglass.driver.xreal.xbx.XrealXbxSession
import java.util.concurrent.Executor

internal object XrealXbxA01PlusDriver : GlassesDriver {
    override val id = "xreal_xbx_a01_plus"
    private val profile = XrealXbxModel(id, 0x0442, "XBX A01 Plus")

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == 0x3318 && device.productId == profile.productId)
        GlassesModel(
            id, "XREAL", profile.marketName, 0x3318, profile.productId,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            DisplayMode.entries.toSet(), id,
        ) else null

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealXbxSession(usbManager, device, model, feature, executor, listener)
}
