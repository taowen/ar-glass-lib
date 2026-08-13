package com.taowen.arglass.driver.goovis.g3family

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuTrackingSupport
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import java.util.concurrent.Executor

internal object GoovisG3FamilyDriver : GlassesDriver {
    override val id = "goovis_g3_family"
    private const val VENDOR_ID = 0x880a

    private val products = mapOf(
        // GOOVIS sells G3 Max, G3X and G3X Pro as distinct products. Their
        // public SKUs line up with D4's GOOVIS_G3/G3X/G3XP USB labels.
        0x3501 to product(
            id = "goovis_g3_max",
            displayName = "G3 Max",
            kind = GoovisModelKind.G3,
            eyeWidth = 2560,
            eyeHeight = 1440,
        ),
        0x3502 to product(
            id = "goovis_a1",
            displayName = "A1",
            kind = GoovisModelKind.A1,
            eyeWidth = 1920,
            eyeHeight = 1080,
        ),
        0x3503 to product(
            id = "goovis_g3x",
            displayName = "G3X",
            kind = GoovisModelKind.G3X,
            eyeWidth = 1920,
            eyeHeight = 1080,
        ),
        0x3506 to product(
            id = "goovis_g3x_pro",
            displayName = "G3X Pro",
            kind = GoovisModelKind.G3XP,
            eyeWidth = 1920,
            eyeHeight = 1080,
        ),
    )

    override fun identify(device: UsbDevice): GlassesModel? {
        if (device.vendorId != VENDOR_ID) return null
        val product = products[device.productId] ?: return null
        return GlassesModel(
            id = product.id,
            manufacturer = "GOOVIS",
            model = product.displayName,
            usbVendorId = device.vendorId,
            usbProductId = device.productId,
            capabilities = setOf(GlassesCapability.IMU, GlassesCapability.DISPLAY_MODE),
            driverId = id,
            supportedDisplayProfiles = product.displayProfiles,
            preferred2dDisplayProfile = product.twoDimensionalProfile,
            preferred3dDisplayProfile = product.fullSbs3dProfile,
            showInArctrlDisplayModeToggle = true,
            imuTrackingSupport = ImuTrackingSupport(
                axisCount = 6,
                calibration = ImuCalibrationState(
                    accelerometer = ImuCalibrationLevel.NONE,
                    gyroscope = ImuCalibrationLevel.NONE,
                    magnetometer = ImuCalibrationLevel.NONE,
                ),
            ),
        )
    }

    override fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = GoovisG3FamilySession(
        usbManager = usbManager,
        device = device,
        model = model,
        modelKind = requireNotNull(products[device.productId]).kind,
        feature = feature,
        executor = executor,
        listener = listener,
    )

    private fun product(
        id: String,
        displayName: String,
        kind: GoovisModelKind,
        eyeWidth: Int,
        eyeHeight: Int,
    ): Product {
        val twoDimensionalProfile = GlassesDisplayProfile(
            id = "${id}_2d_${eyeWidth}_${eyeHeight}_60",
            width = eyeWidth,
            height = eyeHeight,
            refreshRateHz = 60,
            layout = GlassesDisplayLayout.MONO_2D,
        )
        val fullSbs3dProfile = GlassesDisplayProfile(
            id = "${id}_full_sbs_${eyeWidth * 2}_${eyeHeight}_60",
            width = eyeWidth * 2,
            height = eyeHeight,
            refreshRateHz = 60,
            layout = GlassesDisplayLayout.FULL_SBS_3D,
        )
        return Product(id, displayName, kind, twoDimensionalProfile, fullSbs3dProfile)
    }

    private data class Product(
        val id: String,
        val displayName: String,
        val kind: GoovisModelKind,
        val twoDimensionalProfile: GlassesDisplayProfile,
        val fullSbs3dProfile: GlassesDisplayProfile,
    ) {
        val displayProfiles = listOf(twoDimensionalProfile, fullSbs3dProfile)
    }
}
