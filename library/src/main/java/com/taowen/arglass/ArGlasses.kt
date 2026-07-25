package com.taowen.arglass

import android.hardware.usb.UsbDevice
import com.taowen.arglass.driver.GlassesDriverRegistry

enum class GlassesCapability { IMU, DISPLAY_MODE, DISPLAY_RESOLUTION, CAMERA }

enum class GlassesDisplayLayout { MONO_2D, HALF_SBS_3D, FULL_SBS_3D }

data class GlassesDisplayProfile(
    val id: String,
    val width: Int,
    val height: Int,
    val refreshRateHz: Int,
    val layout: GlassesDisplayLayout,
) {
    val is3d: Boolean get() = layout != GlassesDisplayLayout.MONO_2D
}

data class GlassesModel(
    val id: String,
    val manufacturer: String,
    val model: String,
    val usbVendorId: Int,
    val usbProductId: Int,
    val capabilities: Set<GlassesCapability>,
    internal val driverId: String,
    val supportedDisplayProfiles: List<GlassesDisplayProfile> = emptyList(),
    val preferred2dDisplayProfile: GlassesDisplayProfile? =
        supportedDisplayProfiles.firstOrNull { !it.is3d },
    val preferred3dDisplayProfile: GlassesDisplayProfile? =
        supportedDisplayProfiles.firstOrNull { it.is3d },
    val showInArctrlDisplayModeToggle: Boolean = GlassesCapability.DISPLAY_MODE in capabilities,
) {
    val displayName: String get() = "$manufacturer $model"
}

data class ImuSample(
    val deviceTimestampNanos: Long,
    val accelerationMetersPerSecondSquared: FloatArray,
    val angularVelocityRadiansPerSecond: FloatArray,
    val magneticField: FloatArray?,
    val temperatureCelsius: Float,
    val reportVersion: Int,
)

object ArGlassesCatalog {
    fun identify(device: UsbDevice): GlassesModel? = GlassesDriverRegistry.identify(device)
}
