package com.taowen.arglass.driver.xreal.onefamily

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState

/**
 * The One-family firmware applies its per-sensor factory matrices and biases before publishing
 * NRImuSubmitExt. The coefficients are device-owned and are not serialized in that carrier.
 */
internal object XrealOneFactoryCalibration {
    val STATE = ImuCalibrationState(
        accelerometer = ImuCalibrationLevel.FACTORY,
        gyroscope = ImuCalibrationLevel.FACTORY,
        magnetometer = ImuCalibrationLevel.FACTORY,
    )

    fun publicData() = ImuCalibrationData(
        source = ImuCalibrationSource.DEVICE_FACTORY,
        state = STATE,
        accelerometerBiasMetersPerSecondSquared = FloatArray(3),
        gyroscopeBiasRadiansPerSecond = FloatArray(3),
        magnetometerBias = null,
        parametersAppliedByDevice = true,
        parametersAppliedToSamples = true,
    )
}
