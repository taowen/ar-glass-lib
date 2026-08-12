package com.taowen.arglass.driver.xreal.xbx

import com.taowen.arglass.ImuSample
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Factory calibration carried by XBX IMU commands 0x14/0x15. */
internal class XrealXbxCalibration private constructor(
    private val accelerometerBias: FloatArray,
    private val factoryGyroscopeBias: FloatArray,
    private val gyroscopeBiasByTemperature: List<TemperatureBias>,
    private val magnetometerBias: FloatArray,
    val noiseSigmas: FloatArray,
) {
    fun calibrate(sample: ImuSample): ImuSample {
        val temperatureBias = gyroscopeBiasByTemperature.minByOrNull {
            abs(it.temperatureCelsius - sample.temperatureCelsius)
        }?.bias ?: factoryGyroscopeBias

        // The JSON vectors are in the sensor frame. decode_xreal_imu already
        // maps accel/gyro as (-x, z, y), so map their biases the same way.
        val mappedAccelerationBias = sensorToRuntime(accelerometerBias)
        val mappedGyroscopeBias = sensorToRuntime(temperatureBias)

        // decode_xreal_imu deliberately leaves magnetic values in sensor-axis
        // order. XBX declares accel_q_gyro and gyro_q_mag as identity, so the
        // magnetic vector must receive the same (-x, z, y) runtime mapping.
        val magneticField = sample.magneticField?.let { rawMagneticField ->
            sensorToRuntime(floatArrayOf(
                rawMagneticField[0] - magnetometerBias[0],
                rawMagneticField[1] - magnetometerBias[1],
                rawMagneticField[2] - magnetometerBias[2],
            ))
        }
        return sample.copy(
            accelerationMetersPerSecondSquared = subtract(
                sample.accelerationMetersPerSecondSquared,
                mappedAccelerationBias,
            ),
            angularVelocityRadiansPerSecond = subtract(
                sample.angularVelocityRadiansPerSecond,
                mappedGyroscopeBias,
            ),
            magneticField = magneticField,
        )
    }

    private data class TemperatureBias(
        val temperatureCelsius: Float,
        val bias: FloatArray,
    )

    companion object {
        fun parse(bytes: ByteArray): XrealXbxCalibration {
            val json = JSONObject(bytes.toString(Charsets.UTF_8).trimEnd('\u0000'))
            val imu = json.getJSONObject("IMU").getJSONObject("device_1")
            return XrealXbxCalibration(
                accelerometerBias = imu.float3("accel_bias"),
                factoryGyroscopeBias = imu.float3("gyro_bias"),
                gyroscopeBiasByTemperature = imu.getJSONArray("gyro_bias_temp_data")
                    .objects()
                    .map { TemperatureBias(it.getDouble("temp").toFloat(), it.float3("bias")) },
                magnetometerBias = imu.float3("mag_bias"),
                noiseSigmas = imu.getJSONArray("imu_noises").floats(),
            )
        }

        private fun JSONObject.float3(name: String): FloatArray =
            getJSONArray(name).floats().also { require(it.size == 3) }

        private fun JSONArray.floats(): FloatArray =
            FloatArray(length()) { index -> getDouble(index).toFloat() }

        private fun JSONArray.objects(): List<JSONObject> =
            List(length()) { index -> getJSONObject(index) }

        private fun sensorToRuntime(value: FloatArray): FloatArray =
            floatArrayOf(-value[0], value[2], value[1])

        private fun subtract(value: FloatArray, bias: FloatArray): FloatArray =
            floatArrayOf(value[0] - bias[0], value[1] - bias[1], value[2] - bias[2])
    }
}
