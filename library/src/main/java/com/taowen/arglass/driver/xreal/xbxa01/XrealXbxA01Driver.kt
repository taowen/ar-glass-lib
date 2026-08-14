package com.taowen.arglass.driver.xreal.xbxa01

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.driver.xreal.xbx.XrealXbxCalibration
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.xreal.xbx.XrealXbxModel
import com.taowen.arglass.driver.xreal.xbx.XrealXbxSession
import java.util.concurrent.Executor

internal object XrealXbxA01Driver : GlassesDriver {
    override val id = "xreal_xbx_a01"
    private val profile = XrealXbxModel(id, 0x0440, "XBX A01")

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == 0x3318 && device.productId == profile.productId)
        GlassesModel(
            id, "XREAL", profile.marketName, 0x3318, profile.productId,
            setOf(
                GlassesCapability.IMU,
                GlassesCapability.DISPLAY_MODE,
                GlassesCapability.DISPLAY_RESOLUTION,
                GlassesCapability.CALIBRATED_TIMEWARP_MESH,
            ),
            id,
            supportedDisplayProfiles = XrealXbxA01DisplayModeProtocol.profiles,
            preferred2dDisplayProfile = XrealXbxA01DisplayModeProtocol.profiles.firstOrNull {
                it.layout == GlassesDisplayLayout.MONO_2D && it.refreshRateHz == 60
            },
            preferred3dDisplayProfile = XrealXbxA01DisplayModeProtocol.profiles.firstOrNull {
                it.layout == GlassesDisplayLayout.FULL_SBS_3D && it.refreshRateHz == 60
            },
            showInArctrlDisplayModeToggle = true,
            imuTrackingSupport = ImuTrackingSupport(9, XrealXbxCalibration.FACTORY_CALIBRATION),
        ) else null

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealXbxSession(usbManager, device, model, feature, executor, listener, XrealXbxA01DisplayModeProtocol)
}
