package com.taowen.arglass.driver.xreal.light

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.util.concurrent.Executor

internal object XrealLightDriver : CompositeGlassesDriver {
    override val id = "xreal_light"
    override fun identify(device: UsbDevice): GlassesModel? =
        if (device.vendorId == 0x0486 && device.productId == 0x573c) GlassesModel(
            id, "XREAL", "Light", device.vendorId, device.productId,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            setOf(DisplayMode.MIRROR_2D, DisplayMode.HALF_SBS_3D, DisplayMode.FULL_SBS_3D, DisplayMode.HIGH_REFRESH_SBS_3D), id,
        ) else null

    override fun companionDevices(allDevices: Collection<UsbDevice>, primary: UsbDevice) =
        allDevices.filter { it.vendorId == 0x05a9 && it.productId == 0x0680 }

    override fun openComposite(usbManager: UsbManager, devices: List<UsbDevice>, model: GlassesModel,
                               feature: SessionFeature, executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealLightSession(usbManager, devices.first { it.vendorId == 0x0486 },
            devices.firstOrNull { it.vendorId == 0x05a9 && it.productId == 0x0680 }, model, feature, executor, listener)

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        error("XREAL Light must be opened as a composite device")
}
