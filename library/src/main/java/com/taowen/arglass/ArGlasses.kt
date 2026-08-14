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

/** Encoding and provenance of one unmodified calibration payload. */
data class ImuRawCalibrationPayload(
    /** Driver-stable name such as `xreal.factory-json` or `viture.v2-packet`. */
    val format: String,
    /** Command/report identifier when the transport exposes one. */
    val id: Int? = null,
    /** Exact bytes received from the device or loaded from the host calibration store. */
    val bytes: ByteArray,
)

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
 * Factory/host calibration parameters accompanying [ImuSample]. All vectors use the same
 * runtime coordinate system and SI units as the sample. Transport drivers may expose raw SI
 * samples and leave calibration to an estimator; [parametersAppliedToSamples] says whether the
 * advertised coefficients have already been applied. [parametersAppliedByDevice] is the
 * narrower case where firmware applied opaque coefficients before the host received the data.
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
    /** True when the emitted [ImuSample] values already include these corrections. */
    val parametersAppliedToSamples: Boolean = true,
    /**
     * Unmodified vendor calibration records. The list is empty only when the device exposes no
     * readable calibration payload. Consumers must select records by [ImuRawCalibrationPayload.format]
     * instead of assuming one vendor schema.
     */
    val rawPayloads: List<ImuRawCalibrationPayload> = emptyList(),
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
    /**
     * Exact transport report when the driver receives a discrete report. Native streaming
     * transports may leave this null. This field is diagnostic input; fusion uses the decoded SI
     * vectors above.
     */
    val rawReport: ByteArray? = null,
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
