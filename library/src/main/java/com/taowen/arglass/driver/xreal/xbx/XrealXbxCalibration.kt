package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuSample
import com.taowen.arglass.driver.xreal.XrealFactoryCalibration

/** Complete factory calibration carried by XBX IMU commands 0x14/0x15. */
internal class XrealXbxCalibration private constructor(
    private val factory: XrealFactoryCalibration,
) {
    val centerDisplayFov get() = factory.centerDisplayFov
    fun publicData(): ImuCalibrationData = factory.publicData()

    fun calibrate(sample: ImuSample): ImuSample = factory.calibrateXbx(sample)

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
