package com.taowen.arglass.driver.xreal.one

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.xreal.onefamily.XrealOneFamilySession
import java.util.concurrent.Executor

internal object XrealOneDriver : GlassesDriver {
    override val id = "xreal_one"
    private const val VID = 0x3318
    private const val PID = 0x0438

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId == PID) {
        GlassesModel(
            id, "XREAL", "One", VID, PID,
            setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION, GlassesCapability.CAMERA),
            id,
            supportedDisplayProfiles = XrealOneDisplayModeProtocol.profiles,
            preferred2dDisplayProfile = XrealOneDisplayModeProtocol.profiles.firstOrNull { it.layout == GlassesDisplayLayout.MONO_2D },
            preferred3dDisplayProfile = XrealOneDisplayModeProtocol.profiles.firstOrNull { it.layout == GlassesDisplayLayout.FULL_SBS_3D },
            showInArctrlDisplayModeToggle = false,
        )
    } else null

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = XrealOneFamilySession(null, usbManager, device, model, feature, executor, listener, XrealOneDisplayModeProtocol)

    override fun open(
        connectivityManager: ConnectivityManager?,
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = XrealOneFamilySession(connectivityManager, usbManager, device, model, feature, executor, listener, XrealOneDisplayModeProtocol)
}
