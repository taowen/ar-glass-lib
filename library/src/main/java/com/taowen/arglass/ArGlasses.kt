package com.taowen.arglass

import android.hardware.usb.UsbDevice

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
    internal val imuInterface: Int,
    internal val mcuInterface: Int,
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
    private val xrealAir2Ultra = GlassesModel(
        id = "xreal_air_2_ultra",
        manufacturer = "XREAL",
        model = "Air 2 Ultra",
        usbVendorId = 0x3318,
        usbProductId = 0x0426,
        capabilities = setOf(
            GlassesCapability.IMU,
            GlassesCapability.DISPLAY_MODE,
            GlassesCapability.DISPLAY_RESOLUTION,
        ),
        imuInterface = 1,
        mcuInterface = 0,
    )

    fun identify(device: UsbDevice): GlassesModel? = when {
        device.vendorId == xrealAir2Ultra.usbVendorId && device.productId == xrealAir2Ultra.usbProductId -> xrealAir2Ultra
        else -> null
    }
}
