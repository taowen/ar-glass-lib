package com.taowen.arglass

import android.hardware.usb.UsbDevice
import com.taowen.arglass.driver.GlassesDriverRegistry

enum class GlassesCapability { IMU, DISPLAY_MODE, DISPLAY_RESOLUTION, CAMERA }

enum class GlassesDisplayLayout { MONO_2D, HALF_SBS_3D, FULL_SBS_3D }

enum class ImuCalibrationLevel { NONE, HOST_ESTIMATED, FACTORY }

data class ImuCalibrationState(
    val accelerometer: ImuCalibrationLevel = ImuCalibrationLevel.NONE,
    val gyroscope: ImuCalibrationLevel = ImuCalibrationLevel.NONE,
    val magnetometer: ImuCalibrationLevel = ImuCalibrationLevel.NONE,
) {
    fun satisfies(requirement: ImuCalibrationLevel): Boolean =
        accelerometer >= requirement && gyroscope >= requirement && magnetometer >= requirement
}

enum class ImuCalibrationSource { DEVICE_FACTORY, HOST_ESTIMATE, MIXED }

enum class ImuHostCalibrationPhase { COLLECTING, DISTURBED, READY }

data class ImuHostCalibrationProgress(
    val phase: ImuHostCalibrationPhase,
    val acceptedSamples: Int,
    val requiredSamples: Int,
    val orientationCoverage: Float,
    val rejectedDisturbanceSamples: Int,
)

data class TemperatureGyroscopeBias(
    val temperatureCelsius: Float,
    /** Bias in the same runtime coordinate system and rad/s units as [ImuSample]. */
    val biasRadiansPerSecond: FloatArray,
)

/**
 * Calibration parameters owned and already applied by the driver/device. All vectors use the
 * same runtime coordinate system and SI units as [ImuSample]; consumers must not apply them
 * again. [parametersAppliedByDevice] means the coefficients remain opaque to the host.
 */
data class ImuCalibrationData(
    val source: ImuCalibrationSource,
    val state: ImuCalibrationState,
    val accelerometerBiasMetersPerSecondSquared: FloatArray,
    val gyroscopeBiasRadiansPerSecond: FloatArray,
    val magnetometerBias: FloatArray?,
    val gyroscopeTemperatureBiases: List<TemperatureGyroscopeBias> = emptyList(),
    val noiseStandardDeviations: FloatArray = floatArrayOf(),
    /** Row-major matrices applied before the corresponding bias is subtracted. */
    val accelerometerCorrectionMatrix: FloatArray? = null,
    val gyroscopeCorrectionMatrix: FloatArray? = null,
    val magnetometerCorrectionMatrix: FloatArray? = null,
    /** Row-major matrix mapping calibrated acceleration to gyroscope rate bias. */
    val gyroscopeAccelerationSensitivityMatrix: FloatArray? = null,
    /** True when the device/firmware already applied opaque factory coefficients. */
    val parametersAppliedByDevice: Boolean = false,
)

data class ImuTrackingSupport(
    val axisCount: Int,
    val calibration: ImuCalibrationState,
) {
    init { require(axisCount == 6 || axisCount == 9) { "IMU axis count must be 6 or 9" } }

    fun satisfies(requirement: ImuTrackingRequirement): Boolean =
        axisCount >= requirement.minimumAxisCount &&
            (!requirement.requireMagnetometer || axisCount == 9) &&
            calibration.satisfies(requirement.minimumCalibration)
}

data class ImuTrackingRequirement(
    val minimumAxisCount: Int = 6,
    val requireMagnetometer: Boolean = false,
    val minimumCalibration: ImuCalibrationLevel = ImuCalibrationLevel.NONE,
)

data class GlassesDisplayRequest(
    val width: Int,
    val height: Int,
    val refreshRateHz: Int,
    val layout: GlassesDisplayLayout,
)

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
    val imuTrackingSupport: ImuTrackingSupport? = null,
) {
    val displayName: String get() = "$manufacturer $model"

    fun supports(requirement: ImuTrackingRequirement): Boolean =
        imuTrackingSupport?.satisfies(requirement) == true

    fun supports(request: GlassesDisplayRequest): Boolean = supportedDisplayProfiles.any {
        it.width == request.width && it.height == request.height &&
            it.refreshRateHz == request.refreshRateHz && it.layout == request.layout
    }
}

data class ImuSample(
    val deviceTimestampNanos: Long,
    val accelerationMetersPerSecondSquared: FloatArray,
    val angularVelocityRadiansPerSecond: FloatArray,
    val magneticField: FloatArray?,
    val temperatureCelsius: Float,
    val reportVersion: Int,
    /** Android monotonic-clock time captured by the driver immediately after the transport read. */
    val hostTimestampNanos: Long = System.nanoTime(),
    val calibration: ImuCalibrationState = ImuCalibrationState(),
    /** Optional transport fields retained from an extended device IMU carrier. */
    val transportMetadata: ImuTransportMetadata? = null,
)

/** Per-device tangent-space field of view returned by the glasses calibration. */
data class GlassesTangentFov(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

/** Extended timing/scaling fields transported alongside an IMU sample. */
data class ImuTransportMetadata(
    val systemTimestampNanos: Long? = null,
    val sensorTimestampNanos: Long? = null,
    val dataMask: Int? = null,
    val imuId: Int? = null,
    val frameId: Long? = null,
    val gyroscopeNumerator: Float? = null,
    val accelerometerNumerator: Float? = null,
    val magnetometerNumerator: Float? = null,
    val outputNumeratorMask: Int? = null,
    val groupDelay: Float? = null,
)

object ArGlassesCatalog {
    fun identify(device: UsbDevice): GlassesModel? = GlassesDriverRegistry.identify(device)
}
