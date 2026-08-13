package com.taowen.arglass.driver.rayneo.airfamily

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

internal object RayneoAirFamilyDriver : GlassesDriver {
    override val id = "rayneo_air_3_4_family"

    override fun identify(device: UsbDevice): GlassesModel? =
        if (device.vendorId == RAYNEO_VENDOR_ID && device.productId == RAYNEO_AIR_PRODUCT_ID) {
            GlassesModel(
                id = id,
                manufacturer = "RayNeo",
                model = modelName(device),
                usbVendorId = device.vendorId,
                usbProductId = device.productId,
                capabilities = setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_RESOLUTION),
                driverId = id,
                showInArctrlDisplayModeToggle = false,
                imuTrackingSupport = ImuTrackingSupport(
                    axisCount = 9,
                    // The shared descriptor cannot establish a supported board. The mandatory
                    // device-info probe returns the resolved model and calibration capability.
                    calibration = ImuCalibrationState(),
                ),
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
    ): DriverSession {
        require(feature == SessionFeature.IMU || feature == SessionFeature.ALL) {
            "RayNeo open protocol currently exposes IMU only"
        }
        return RayneoAirFamilySession(
            usbManager,
            device,
            model,
            executor,
            listener,
            RayneoUsbProtocol.TAURUS,
        )
    }

    private fun modelName(device: UsbDevice): String = when (device.productName?.trim()) {
        "RayNeo AR Glasses" -> "Air 4 / Air 4 Pro"
        "SmartGlasses" -> "Supported Air (board probe required)"
        else -> "Supported Air (board probe required)"
    }

    private const val RAYNEO_VENDOR_ID = 0x1bbb
    private const val RAYNEO_AIR_PRODUCT_ID = 0xaf50
}
