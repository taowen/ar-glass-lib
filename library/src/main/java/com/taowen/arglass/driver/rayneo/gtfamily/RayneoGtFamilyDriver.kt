package com.taowen.arglass.driver.rayneo.gtfamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.rayneo.airfamily.RayneoAirFamilySession
import java.util.concurrent.Executor

/** RayNeo GT and GT Max share the Gemini USB protocol and are distinguished by device info. */
internal object RayneoGtFamilyDriver : GlassesDriver {
    override val id = "rayneo_gt_family"

    override fun identify(device: UsbDevice): GlassesModel? =
        if (device.vendorId == RAYNEO_GEMINI_VENDOR_ID && device.productId == NORMAL_MODE_PRODUCT_ID) {
            GlassesModel(
                id = id,
                manufacturer = "RayNeo",
                model = "GT / GT Max",
                usbVendorId = device.vendorId,
                usbProductId = device.productId,
                capabilities = setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_RESOLUTION),
                driverId = id,
                showInArctrlDisplayModeToggle = false,
                imuTrackingSupport = ImuTrackingSupport(
                    axisCount = 9,
                    calibration = ImuCalibrationState(
                        accelerometer = ImuCalibrationLevel.FACTORY,
                        gyroscope = ImuCalibrationLevel.FACTORY,
                        // USB command 0x3c contains no magnetometer factory hard/soft-iron fit.
                        magnetometer = ImuCalibrationLevel.HOST_ESTIMATED,
                    ),
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
            "RayNeo Gemini open protocol currently exposes IMU only"
        }
        return RayneoAirFamilySession(usbManager, device, model, executor, listener)
    }

    private const val RAYNEO_GEMINI_VENDOR_ID = 0x3941
    private const val NORMAL_MODE_PRODUCT_ID = 0xaf50
}
