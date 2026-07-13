package com.taowen.arglass.driver.viture.beast

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

internal object VitureBeastDriver : GlassesDriver {
    override val id = "viture_beast"
    private const val VID = 0x35ca
    private val productIds = setOf(0x1201, 0x1211)

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId in productIds)
        GlassesModel(
            id, "VITURE", "Beast", VID, device.productId,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            setOf(DisplayMode.MIRROR_2D, DisplayMode.FULL_SBS_3D),
            id,
        ) else null

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = VitureBeastSession(usbManager, device, model, feature, executor, listener)
}
