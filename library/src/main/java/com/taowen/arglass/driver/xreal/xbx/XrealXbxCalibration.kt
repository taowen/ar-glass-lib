package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.driver.xreal.XrealFactoryCalibration

/** Complete factory calibration metadata carried by XBX IMU commands 0x14/0x15. */
internal class XrealXbxCalibration private constructor(
    private val factory: XrealFactoryCalibration,
) {
    val centerDisplayFov get() = factory.centerDisplayFov
    // XBX SDK 3.1 keeps transport decoding and pose-source calibration
    // separate. Publish the coefficients, but leave each decoded SI sample
    // untouched so the selected estimator can reproduce that ownership.
    fun publicData(): ImuCalibrationData = factory.publicData(
        parametersAppliedToSamples = false,
    )

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
