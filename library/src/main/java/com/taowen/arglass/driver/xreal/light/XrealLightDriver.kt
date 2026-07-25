package com.taowen.arglass.driver.xreal.light

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.CompositeGlassesDriver
import com.taowen.arglass.driver.DriverSession
import java.util.concurrent.Executor

internal object XrealLightDriver : CompositeGlassesDriver {
    override val id = "xreal_light"
    override fun identify(device: UsbDevice): GlassesModel? =
        if (device.vendorId == 0x0486 && device.productId == 0x573c) GlassesModel(
            id, "XREAL", "Light", device.vendorId, device.productId,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            id,
            supportedDisplayProfiles = XrealLightProtocol.displayProfiles,
            preferred2dDisplayProfile = XrealLightProtocol.twoDimensionalProfile,
            preferred3dDisplayProfile = XrealLightProtocol.fullSbs3dProfile,
            showInArctrlDisplayModeToggle = true,
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
