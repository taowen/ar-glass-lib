package com.taowen.arglass.driver.luci

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import java.util.concurrent.Executor

internal object LuciDriver : GlassesDriver {
    override val id = "luci_display"
    private const val VID = 0x2c30
    private val productIds = setOf(0x1030, 0x1031)
    val twoDimensionalProfile = GlassesDisplayProfile(
        "luci_display_mode_2d_1920_1080_60",
        1920,
        1080,
        60,
        GlassesDisplayLayout.MONO_2D,
    )
    val fullSbs3dProfile = GlassesDisplayProfile(
        "luci_display_mode_full_sbs_3840_1080_60",
        3840,
        1080,
        60,
        GlassesDisplayLayout.FULL_SBS_3D,
    )
    val displayProfiles = listOf(twoDimensionalProfile, fullSbs3dProfile)

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId in productIds)
        GlassesModel(
            id = id,
            manufacturer = "LUCI",
            model = when (device.productId) { 0x1030 -> "Display 1030"; else -> "Display 1031" },
            usbVendorId = VID,
            usbProductId = device.productId,
            capabilities = setOf(GlassesCapability.DISPLAY_MODE, GlassesCapability.DISPLAY_RESOLUTION),
            driverId = id,
            supportedDisplayProfiles = displayProfiles,
            preferred2dDisplayProfile = twoDimensionalProfile,
            preferred3dDisplayProfile = fullSbs3dProfile,
            showInArctrlDisplayModeToggle = true,
        ) else null

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession {
        require(feature != SessionFeature.IMU) { "LUCI does not expose IMU through this protocol" }
        return LuciDisplaySession(usbManager, device)
    }
}
