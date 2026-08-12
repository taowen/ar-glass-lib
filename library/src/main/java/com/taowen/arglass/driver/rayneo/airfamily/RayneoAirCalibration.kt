package com.taowen.arglass.driver.rayneo.airfamily

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import kotlin.math.PI

internal data class RayneoFactoryCalibration(
    val sensorTransform: FloatArray,
    val accelerationOffset: FloatArray,
) {
    init {
        require(sensorTransform.size == 9)
        require(accelerationOffset.size == 3)
    }

    fun acceleration(raw: FloatArray): FloatArray = add(transform(raw), accelerationOffset)

    fun angularVelocity(rawDegreesPerSecond: FloatArray): FloatArray =
        transform(rawDegreesPerSecond).also { value ->
            val radiansPerDegree = (PI / 180.0).toFloat()
            value.indices.forEach { value[it] *= radiansPerDegree }
        }

    fun publicData(magnetic: RayneoMagneticCalibration? = null): ImuCalibrationData {
        val magneticReady = magnetic != null
        return ImuCalibrationData(
            source = if (magneticReady) ImuCalibrationSource.MIXED else ImuCalibrationSource.DEVICE_FACTORY,
            state = ImuCalibrationState(
                accelerometer = ImuCalibrationLevel.FACTORY,
                gyroscope = ImuCalibrationLevel.FACTORY,
                magnetometer = if (magneticReady) ImuCalibrationLevel.HOST_ESTIMATED else ImuCalibrationLevel.NONE,
            ),
            accelerometerBiasMetersPerSecondSquared = FloatArray(3) { -accelerationOffset[it] },
            gyroscopeBiasRadiansPerSecond = floatArrayOf(0f, 0f, 0f),
            magnetometerBias = magnetic?.bias?.copyOf(),
            accelerometerCorrectionMatrix = publicCorrectionMatrix(),
            gyroscopeCorrectionMatrix = publicCorrectionMatrix(),
            magnetometerCorrectionMatrix = magnetic?.correctionMatrix?.copyOf(),
        )
    }

    private fun transform(value: FloatArray): FloatArray = FloatArray(3) { output ->
        value[0] * sensorTransform[output] +
            value[1] * sensorTransform[3 + output] +
            value[2] * sensorTransform[6 + output]
    }

    private fun publicCorrectionMatrix(): FloatArray = floatArrayOf(
        sensorTransform[0], sensorTransform[3], sensorTransform[6],
        sensorTransform[1], sensorTransform[4], sensorTransform[7],
        sensorTransform[2], sensorTransform[5], sensorTransform[8],
    )

    private fun add(left: FloatArray, right: FloatArray): FloatArray =
        FloatArray(3) { left[it] + right[it] }
}

internal data class RayneoMagneticCalibration(
    val bias: FloatArray,
    val correctionMatrix: FloatArray,
) {
    fun apply(raw: FloatArray): FloatArray {
        val centered = FloatArray(3) { raw[it] - bias[it] }
        return FloatArray(3) { output ->
            correctionMatrix[output * 3] * centered[0] +
                correctionMatrix[output * 3 + 1] * centered[1] +
                correctionMatrix[output * 3 + 2] * centered[2]
        }
    }
}

/** Online hard-iron and diagonal soft-iron fit. The user must rotate the glasses around all axes. */
internal class RayneoMagneticCalibrator {
    private val minimum = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    private val maximum = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    private var samples = 0

    @Synchronized
    fun update(value: FloatArray): RayneoMagneticCalibration? {
        value.indices.forEach { axis ->
            minimum[axis] = minOf(minimum[axis], value[axis])
            maximum[axis] = maxOf(maximum[axis], value[axis])
        }
        samples++
        if (samples < MINIMUM_SAMPLES) return null

        val radius = FloatArray(3) { (maximum[it] - minimum[it]) * 0.5f }
        val averageRadius = radius.average().toFloat()
        if (!averageRadius.isFinite() || averageRadius <= 0f || radius.any { it < averageRadius * MINIMUM_AXIS_COVERAGE }) {
            return null
        }
        val bias = FloatArray(3) { (maximum[it] + minimum[it]) * 0.5f }
        val scale = FloatArray(3) { averageRadius / radius[it] }
        return RayneoMagneticCalibration(
            bias = bias,
            correctionMatrix = floatArrayOf(
                scale[0], 0f, 0f,
                0f, scale[1], 0f,
                0f, 0f, scale[2],
            ),
        )
    }

    private companion object {
        const val MINIMUM_SAMPLES = 300
        const val MINIMUM_AXIS_COVERAGE = 0.35f
    }
}
