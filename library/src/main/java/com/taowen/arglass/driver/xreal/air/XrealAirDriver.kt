package com.taowen.arglass.driver.xreal.air

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuTrackingSupport
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
        model(id, "Air", PID, XrealAirDisplayModeProtocol.profiles) else null

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealAirFamilySession(usbManager, device, model, feature, executor, listener, XrealAirDisplayModeProtocol)
}

internal fun model(id: String, name: String, pid: Int, profiles: List<com.taowen.arglass.GlassesDisplayProfile>) = GlassesModel(
    id, "XREAL", name, 0x3318, pid,
    setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
    id,
    supportedDisplayProfiles = profiles,
    preferred2dDisplayProfile = profiles.firstOrNull { it.layout == GlassesDisplayLayout.MONO_2D },
    preferred3dDisplayProfile = profiles.firstOrNull { it.layout == GlassesDisplayLayout.FULL_SBS_3D },
    showInArctrlDisplayModeToggle = true,
    imuTrackingSupport = ImuTrackingSupport(
        // All SDK 3.1 common-report variants carry gyro, accel and magnetic
        // vectors. Magnetic calibration readiness is separate from axis count.
        axisCount = 9,
        calibration = ImuCalibrationState(
            accelerometer = ImuCalibrationLevel.FACTORY,
            gyroscope = ImuCalibrationLevel.FACTORY,
            magnetometer = ImuCalibrationLevel.FACTORY,
        ),
    ),
)
