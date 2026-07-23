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
    private val productIds = setOf(0x162b, 0x162c, 0x162d, 0x162e, 0x162f, 0x2002, 0x2180)

    override fun identify(device: UsbDevice): GlassesModel? {
        if (device.vendorId != VID || device.productId !in productIds) return null
        val productName = device.productName?.trim()?.takeIf(String::isNotEmpty)
        val model = when {
            productName?.contains("Max", ignoreCase = true) == true -> "Max"
            device.productId == 0x162f -> "Air"
            else -> productName ?: "Glasses %04X".format(device.productId)
        }
        return GlassesModel(
            id = "rokid_${device.productId.toString(16)}",
            manufacturer = "Rokid",
            model = model,
            usbVendorId = VID,
            usbProductId = device.productId,
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
