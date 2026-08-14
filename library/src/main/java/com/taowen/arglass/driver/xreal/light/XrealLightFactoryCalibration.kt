package com.taowen.arglass.driver.xreal.light

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuRawCalibrationPayload
import org.json.JSONArray
import org.json.JSONObject

/** Factory values embedded in the Light OV580 0x14/0x15 configuration stream. */
internal class XrealLightFactoryCalibration private constructor(
    private val accelerationBias: FloatArray,
    private val gyroscopeBias: FloatArray,
    private val configBytes: ByteArray,
    private val jsonBytes: ByteArray,
) {
    fun publicData(): ImuCalibrationData = ImuCalibrationData(
        source = ImuCalibrationSource.DEVICE_FACTORY,
        state = STATE,
        accelerometerBiasMetersPerSecondSquared = accelerationBias.copyOf(),
        gyroscopeBiasRadiansPerSecond = gyroscopeBias.copyOf(),
        magnetometerBias = null,
        parametersAppliedToSamples = false,
        rawPayloads = listOf(
            ImuRawCalibrationPayload("xreal-light.ov580-config", id = 0x15, bytes = configBytes.copyOf()),
            ImuRawCalibrationPayload("xreal.factory-json", bytes = jsonBytes.copyOf()),
        ),
    )

    companion object {
        val STATE = ImuCalibrationState(
            accelerometer = ImuCalibrationLevel.FACTORY,
            gyroscope = ImuCalibrationLevel.FACTORY,
            magnetometer = ImuCalibrationLevel.NONE,
        )

        fun parse(configBytes: ByteArray, jsonBytes: ByteArray): XrealLightFactoryCalibration {
            val imu = JSONObject(jsonBytes.toString(Charsets.UTF_8))
                .getJSONObject("IMU")
                .getJSONObject("device_1")
            // Light's report decoder maps sensor [x,y,z] to runtime
            // [x,-y,-z]. Publish biases in that same public frame; unlike the
            // common 64-byte protocol this old path has no transform=1 route.
            return XrealLightFactoryCalibration(
                accelerationBias = runtimeVector(imu.getJSONArray("accel_bias")),
                gyroscopeBias = runtimeVector(imu.getJSONArray("gyro_bias")),
                configBytes = configBytes.copyOf(),
                jsonBytes = jsonBytes.copyOf(),
            )
        }

        private fun runtimeVector(values: JSONArray): FloatArray {
            require(values.length() >= 3) { "XREAL Light calibration vector is incomplete" }
            val x = values.getDouble(0).toFloat()
            val y = values.getDouble(1).toFloat()
            val z = values.getDouble(2).toFloat()
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "XREAL Light calibration vector is invalid" }
            return floatArrayOf(x, -y, -z)
        }
    }
}
