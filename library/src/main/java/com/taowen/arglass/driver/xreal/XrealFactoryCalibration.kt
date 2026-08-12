package com.taowen.arglass.driver.xreal

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuSample
import com.taowen.arglass.TemperatureGyroscopeBias
import com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticCalibration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Host-side application of the factory JSON returned by XREAL IMU commands 0x14/0x15. */
internal class XrealFactoryCalibration private constructor(
    private val accelerationBias: FloatArray,
    private val gyroscopeBias: FloatArray,
    private val gyroscopeTemperatureBiases: List<TemperatureBias>,
    private val magneticBias: FloatArray,
    private val accelerationMatrix: FloatArray,
    private val gyroscopeMatrix: FloatArray,
    private val magneticMatrix: FloatArray,
    private val gyroscopeGSensitivity: FloatArray,
    val noiseStandardDeviations: FloatArray,
) {
    fun calibrate(sample: ImuSample, hostMagnetic: RayneoMagneticCalibration?): ImuSample {
        val acceleration = subtract(transform(accelerationMatrix, sample.accelerationMetersPerSecondSquared), accelerationBias)
        val temperatureBias = gyroscopeTemperatureBiases.minByOrNull {
            abs(it.temperatureCelsius - sample.temperatureCelsius)
        }?.bias ?: gyroscopeBias
        val gravitySensitiveBias = transform(gyroscopeGSensitivity, acceleration)
        val angularVelocity = subtract(
            subtract(transform(gyroscopeMatrix, sample.angularVelocityRadiansPerSecond), temperatureBias),
            gravitySensitiveBias,
        )
        val magneticField = sample.magneticField?.let { sensorMagnetic ->
            val runtimeMagnetic = sensorToRuntime(
                transform(magneticMatrix, subtract(sensorMagnetic, magneticBias)),
            )
            hostMagnetic?.apply(runtimeMagnetic) ?: runtimeMagnetic
        }
        return sample.copy(
            accelerationMetersPerSecondSquared = acceleration,
            angularVelocityRadiansPerSecond = angularVelocity,
            magneticField = magneticField,
            calibration = calibrationState(sample.magneticField != null, hostMagnetic != null),
        )
    }

    fun runtimeMagnetic(sample: ImuSample): FloatArray? = sample.magneticField?.let { sensorMagnetic ->
        sensorToRuntime(transform(magneticMatrix, subtract(sensorMagnetic, magneticBias)))
    }

    fun publicData(
        hostMagnetic: RayneoMagneticCalibration? = null,
        hasMagnetometer: Boolean = true,
    ): ImuCalibrationData = ImuCalibrationData(
        source = if (hostMagnetic == null) ImuCalibrationSource.DEVICE_FACTORY else ImuCalibrationSource.MIXED,
        state = calibrationState(hasMagnetometer, hostMagnetic != null),
        accelerometerBiasMetersPerSecondSquared = accelerationBias.copyOf(),
        gyroscopeBiasRadiansPerSecond = gyroscopeBias.copyOf(),
        magnetometerBias = hostMagnetic?.bias?.copyOf() ?: sensorToRuntime(magneticBias),
        gyroscopeTemperatureBiases = gyroscopeTemperatureBiases.map {
            TemperatureGyroscopeBias(it.temperatureCelsius, it.bias.copyOf())
        },
        noiseStandardDeviations = noiseStandardDeviations.copyOf(),
        accelerometerCorrectionMatrix = accelerationMatrix.copyOf(),
        gyroscopeCorrectionMatrix = gyroscopeMatrix.copyOf(),
        magnetometerCorrectionMatrix = hostMagnetic?.correctionMatrix?.copyOf()
            ?: sensorMatrixToRuntime(magneticMatrix),
    )

    private fun calibrationState(hasMagnetometer: Boolean, hasHostMagnetic: Boolean) = ImuCalibrationState(
        accelerometer = ImuCalibrationLevel.FACTORY,
        gyroscope = ImuCalibrationLevel.FACTORY,
        magnetometer = when {
            !hasMagnetometer -> ImuCalibrationLevel.NONE
            hasHostMagnetic -> ImuCalibrationLevel.HOST_ESTIMATED
            else -> ImuCalibrationLevel.FACTORY
        },
    )

    private data class TemperatureBias(val temperatureCelsius: Float, val bias: FloatArray)

    companion object {
        fun parse(bytes: ByteArray): XrealFactoryCalibration {
            val root = JSONObject(bytes.toString(Charsets.UTF_8).trimEnd('\u0000'))
            val imu = root.getJSONObject("IMU").getJSONObject("device_1")
            val intrinsics = imu.optJSONObject("imu_intrinsics")
            val accelerationMatrixSensor = intrinsics?.floatArrayOrNull("accl_calib_mat", 9)
                ?: scaleSkewMatrix(imu, "scale_accel", "skew_accel")
            val gyroscopeMatrixSensor = intrinsics?.floatArrayOrNull("gyro_calib_mat", 9)
                ?: scaleSkewMatrix(imu, "scale_gyro", "skew_gyro")
            val accelerationToGyroscope = transpose(
                jplRotation(imu.floatArrayOrNull("accel_q_gyro", 4) ?: IDENTITY_QUATERNION),
            )
            val magneticToGyroscope =
                jplRotation(imu.floatArrayOrNull("gyro_q_mag", 4) ?: IDENTITY_QUATERNION)
            val effectiveAccelerationMatrixSensor = multiply(accelerationToGyroscope, accelerationMatrixSensor)
            val effectiveGyroscopeMatrixSensor = gyroscopeMatrixSensor
            val effectiveMagneticMatrixSensor = multiply(
                magneticToGyroscope,
                scaleSkewMatrix(imu, "scale_mag", "skew_mag"),
            )
            val accelerationBiasSensor = transform(
                effectiveAccelerationMatrixSensor,
                imu.floatArrayOrZeros("accel_bias", 3),
            )
            val gyroscopeBiasSensor = transform(
                effectiveGyroscopeMatrixSensor,
                imu.floatArrayOrZeros("gyro_bias", 3),
            )
            val temperatureBiases = imu.optJSONArray("gyro_bias_temp_data")?.objects()?.map {
                TemperatureBias(
                    it.optDouble("temp", Float.NaN.toDouble()).toFloat(),
                    sensorToRuntime(transform(effectiveGyroscopeMatrixSensor, it.floatArrayOrZeros("bias", 3))),
                )
            }?.filter { it.temperatureCelsius.isFinite() }?.toMutableList() ?: mutableListOf()
            if (temperatureBiases.isEmpty() && imu.has("bias_temperature")) {
                temperatureBiases += TemperatureBias(
                    imu.optDouble("bias_temperature", Float.NaN.toDouble()).toFloat(),
                    sensorToRuntime(gyroscopeBiasSensor),
                )
            }
            return XrealFactoryCalibration(
                accelerationBias = sensorToRuntime(accelerationBiasSensor),
                gyroscopeBias = sensorToRuntime(gyroscopeBiasSensor),
                gyroscopeTemperatureBiases = temperatureBiases.filter { it.temperatureCelsius.isFinite() },
                magneticBias = imu.floatArrayOrZeros("mag_bias", 3),
                accelerationMatrix = sensorMatrixToRuntime(effectiveAccelerationMatrixSensor),
                gyroscopeMatrix = sensorMatrixToRuntime(effectiveGyroscopeMatrixSensor),
                magneticMatrix = effectiveMagneticMatrixSensor,
                gyroscopeGSensitivity = sensorMatrixToRuntime(imu.floatArrayOrZeros("gyro_g_sensitivity", 9)),
                noiseStandardDeviations = imu.optJSONArray("imu_noises")?.floats() ?: floatArrayOf(),
            )
        }

        private fun scaleSkewMatrix(json: JSONObject, scaleName: String, skewName: String): FloatArray {
            val scale = json.floatArrayOrOnes(scaleName, 3)
            val skew = json.floatArrayOrZeros(skewName, 3)
            return floatArrayOf(
                scale[0], skew[0], skew[1],
                0f, scale[1], skew[2],
                0f, 0f, scale[2],
            )
        }

        private fun jplRotation(quaternion: FloatArray): FloatArray {
            val x = quaternion[0]
            val y = quaternion[1]
            val z = quaternion[2]
            val w = quaternion[3]
            val norm = x * x + y * y + z * z + w * w
            if (!norm.isFinite() || norm <= 0f) return IDENTITY_MATRIX.copyOf()
            val scale = 2f / norm
            // JPL is the passive/transpose form of the Hamilton rotation matrix.
            return floatArrayOf(
                1f - scale * (y * y + z * z), scale * (x * y + z * w), scale * (x * z - y * w),
                scale * (x * y - z * w), 1f - scale * (x * x + z * z), scale * (y * z + x * w),
                scale * (x * z + y * w), scale * (y * z - x * w), 1f - scale * (x * x + y * y),
            )
        }

        private fun multiply(left: FloatArray, right: FloatArray): FloatArray = FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0..2).sumOf { left[row * 3 + it] * right[it * 3 + column].toDouble() }.toFloat()
        }

        private fun transpose(matrix: FloatArray): FloatArray = FloatArray(9) { index ->
            matrix[(index % 3) * 3 + index / 3]
        }

        private fun JSONObject.floatArrayOrZeros(name: String, size: Int): FloatArray =
            floatArrayOrNull(name, size) ?: FloatArray(size)

        private fun JSONObject.floatArrayOrOnes(name: String, size: Int): FloatArray =
            floatArrayOrNull(name, size) ?: FloatArray(size) { 1f }

        private fun JSONObject.floatArrayOrNull(name: String, size: Int): FloatArray? =
            optJSONArray(name)?.floats()?.takeIf { it.size == size && it.all(Float::isFinite) }

        private fun JSONArray.floats(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

        private fun transform(matrix: FloatArray, value: FloatArray): FloatArray = FloatArray(3) { row ->
            matrix[row * 3] * value[0] + matrix[row * 3 + 1] * value[1] + matrix[row * 3 + 2] * value[2]
        }

        private fun subtract(value: FloatArray, bias: FloatArray): FloatArray =
            FloatArray(3) { value[it] - bias[it] }

        private fun sensorToRuntime(value: FloatArray): FloatArray = floatArrayOf(-value[0], value[2], value[1])

        /** S * M * S^-1 for S(x,y,z)=(-x,z,y). */
        private fun sensorMatrixToRuntime(matrix: FloatArray): FloatArray {
            val basis = arrayOf(
                floatArrayOf(-1f, 0f, 0f),
                floatArrayOf(0f, 0f, 1f),
                floatArrayOf(0f, 1f, 0f),
            )
            return FloatArray(9) { index ->
                val row = index / 3
                val column = index % 3
                var value = 0f
                for (i in 0..2) for (j in 0..2) value += basis[row][i] * matrix[i * 3 + j] * basis[column][j]
                value
            }
        }

        private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private val IDENTITY_QUATERNION = floatArrayOf(0f, 0f, 0f, 1f)
    }
}
