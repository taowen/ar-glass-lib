package com.taowen.arglass

import android.hardware.usb.UsbDevice
import com.taowen.arglass.driver.GlassesDriverRegistry

enum class GlassesCapability { IMU, DISPLAY_MODE, DISPLAY_RESOLUTION }

enum class DisplayMode(val wireValue: Int, val expectedWidth: Int, val expectedHeight: Int) {
    MIRROR_2D(1, 1920, 1080),
    HALF_SBS_3D(2, 1920, 1080),
    FULL_SBS_3D(3, 3840, 1080),
    HIGH_REFRESH_SBS_3D(4, 3840, 1080);

    companion object { fun fromWireValue(value: Int) = entries.firstOrNull { it.wireValue == value } }
}

data class GlassesModel(
    val id: String,
    val manufacturer: String,
    val model: String,
    val usbVendorId: Int,
    val usbProductId: Int,
    val capabilities: Set<GlassesCapability>,
    val supportedDisplayModes: Set<DisplayMode>,
    internal val driverId: String,
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
