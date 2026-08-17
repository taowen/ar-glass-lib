package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.TemperatureGyroscopeBias
import com.taowen.arglass.driver.xreal.XrealFactoryCalibration

/** Complete factory calibration metadata carried by XBX IMU commands 0x14/0x15. */
internal class XrealXbxCalibration private constructor(
    private val factory: XrealFactoryCalibration,
    private val official6fKnots: List<TemperatureGyroscopeBias>? = null,
) {
    val centerDisplayFov get() = factory.centerDisplayFov
    // XBX SDK 3.1 keeps transport decoding and pose-source calibration
    // separate. Publish the coefficients, but leave each decoded SI sample
    // untouched so the selected estimator can reproduce that ownership.
    // Official 0x1f5fa94 walks property 0x6f, whose type-7 peer is MCU
    // 0xe0 with a float temperature. Those 36 knots replace factory
    // gyro_bias_temp_data; they are already in the 0x6f reply frame.
    fun publicData(): ImuCalibrationData {
        val base = factory.publicData(parametersAppliedToSamples = false)
        return if (official6fKnots == null) base
        else base.copy(gyroscopeTemperatureBiases = official6fKnots)
    }

    fun withOfficial6fKnots(knots: List<TemperatureGyroscopeBias>): XrealXbxCalibration =
        XrealXbxCalibration(factory, knots)

    companion object {
        val FACTORY_CALIBRATION = ImuCalibrationState(
            accelerometer = ImuCalibrationLevel.FACTORY,
            gyroscope = ImuCalibrationLevel.FACTORY,
            magnetometer = ImuCalibrationLevel.FACTORY,
        )

        fun parse(bytes: ByteArray): XrealXbxCalibration =
            XrealXbxCalibration(XrealFactoryCalibration.parse(bytes))
    }
}
