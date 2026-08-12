package com.taowen.arglass.driver.rayneo.airfamily

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuHostCalibrationProgress
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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

internal data class RayneoMagneticUpdate(
    val calibration: RayneoMagneticCalibration?,
    val progress: ImuHostCalibrationProgress,
    val usable: Boolean,
)

/**
 * Clean-room implementation of RayNeo's observed host-side calibration flow. It deliberately
 * mirrors the vendor runtime's online collection, ~2000 sample solve and disturbance rejection,
 * but does not load or link the vendor shared object.
 */
internal class RayneoMagneticCalibrator {
    private val minimum = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    private val maximum = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    private val normal = DoubleArray(FEATURES * FEATURES)
    private val rightHandSide = DoubleArray(FEATURES)
    private val directions = BooleanArray(DIRECTION_BINS)
    private var coordinateScale = 0.0
    private var acceptedSamples = 0
    private var rejectedDisturbanceSamples = 0
    private var consecutiveDisturbanceSamples = 0
    private var fieldNormStats = RunningStats()
    private var calibration: RayneoMagneticCalibration? = null

    @Synchronized
    fun useCalibration(value: RayneoMagneticCalibration?) {
        calibration = value
        fieldNormStats = RunningStats()
        consecutiveDisturbanceSamples = 0
    }

    @Synchronized
    fun reset() {
        minimum.fill(Float.POSITIVE_INFINITY)
        maximum.fill(Float.NEGATIVE_INFINITY)
        normal.fill(0.0)
        rightHandSide.fill(0.0)
        directions.fill(false)
        coordinateScale = 0.0
        acceptedSamples = 0
        rejectedDisturbanceSamples = 0
        consecutiveDisturbanceSamples = 0
        fieldNormStats = RunningStats()
        calibration = null
    }

    @Synchronized
    fun update(value: FloatArray): RayneoMagneticUpdate {
        val activeCalibration = calibration
        val fieldForDisturbance = activeCalibration?.apply(value) ?: value
        val fieldNorm = norm(fieldForDisturbance)
        if (!fieldNorm.isFinite() || fieldNorm <= 0f) return updateResult(usable = false)

        val thresholdFraction = if (activeCalibration == null) PRECALIBRATION_DISTURBANCE_FRACTION else DISTURBANCE_FRACTION
        val disturbanceThreshold = max(
            fieldNormStats.mean * thresholdFraction,
            fieldNormStats.standardDeviation * DISTURBANCE_SIGMA,
        )
        if (fieldNormStats.count >= DISTURBANCE_BASELINE_SAMPLES &&
            abs(fieldNorm - fieldNormStats.mean) > disturbanceThreshold
        ) {
            rejectedDisturbanceSamples++
            consecutiveDisturbanceSamples++
            return updateResult(usable = false)
        }
        consecutiveDisturbanceSamples = 0
        fieldNormStats.add(fieldNorm)

        if (activeCalibration != null) return updateResult(usable = true)
        if (coordinateScale == 0.0) coordinateScale = fieldNorm.toDouble()
        val scaled = DoubleArray(3) { value[it] / coordinateScale }
        val features = doubleArrayOf(
            scaled[0] * scaled[0], scaled[1] * scaled[1], scaled[2] * scaled[2],
            2.0 * scaled[0] * scaled[1], 2.0 * scaled[0] * scaled[2], 2.0 * scaled[1] * scaled[2],
            2.0 * scaled[0], 2.0 * scaled[1], 2.0 * scaled[2],
        )
        for (row in features.indices) {
            rightHandSide[row] += features[row]
            for (column in features.indices) normal[row * FEATURES + column] += features[row] * features[column]
        }
        value.indices.forEach { axis ->
            minimum[axis] = minOf(minimum[axis], value[axis])
            maximum[axis] = maxOf(maximum[axis], value[axis])
        }
        val dominantAxis = (0..2).maxBy { abs(scaled[it]) }
        directions[dominantAxis * 2 + if (scaled[dominantAxis] >= 0.0) 1 else 0] = true
        acceptedSamples++
        if (acceptedSamples >= REQUIRED_SAMPLES && acceptedSamples % SOLVE_INTERVAL == 0) {
            calibration = solveCalibration()
        }
        return updateResult(usable = true)
    }

    private fun updateResult(usable: Boolean): RayneoMagneticUpdate {
        val phase = when {
            calibration != null -> ImuHostCalibrationPhase.READY
            consecutiveDisturbanceSamples >= DISTURBANCE_REPORT_COUNT -> ImuHostCalibrationPhase.DISTURBED
            else -> ImuHostCalibrationPhase.COLLECTING
        }
        return RayneoMagneticUpdate(
            calibration = calibration,
            progress = ImuHostCalibrationProgress(
                phase = phase,
                acceptedSamples = acceptedSamples,
                requiredSamples = REQUIRED_SAMPLES,
                orientationCoverage = orientationCoverage(),
                rejectedDisturbanceSamples = rejectedDisturbanceSamples,
            ),
            usable = usable,
        )
    }

    private fun orientationCoverage(): Float {
        val directionCoverage = directions.count { it }.toFloat() / directions.size
        val spans = FloatArray(3) { maximum[it] - minimum[it] }
        val largestSpan = spans.maxOrNull()?.takeIf(Float::isFinite) ?: return 0f
        val spanCoverage = if (largestSpan > 0f) (spans.minOrNull() ?: 0f) / largestSpan else 0f
        return minOf(directionCoverage, spanCoverage.coerceIn(0f, 1f))
    }

    private fun solveCalibration(): RayneoMagneticCalibration? {
        if (directions.count { it } < DIRECTION_BINS || orientationCoverage() < MINIMUM_COVERAGE) return null
        val parameters = solveLinearSystem(normal, rightHandSide, FEATURES) ?: return null
        val quadratic = doubleArrayOf(
            parameters[0], parameters[3], parameters[4],
            parameters[3], parameters[1], parameters[5],
            parameters[4], parameters[5], parameters[2],
        )
        val inverse = inverse3x3(quadratic) ?: return null
        val linear = doubleArrayOf(parameters[6], parameters[7], parameters[8])
        val center = multiply(inverse, linear).map { -it }.toDoubleArray()
        val ellipsoidScale = 1.0 + dot(center, multiply(quadratic, center))
        if (!ellipsoidScale.isFinite() || ellipsoidScale <= 0.0) return null
        val normalizedQuadratic = DoubleArray(9) { quadratic[it] / ellipsoidScale }
        val squareRoot = symmetricSquareRoot3x3(normalizedQuadratic) ?: return null
        val residual = algebraicResidual(parameters)
        if (!residual.isFinite() || residual > MAXIMUM_ALGEBRAIC_RESIDUAL) return null
        val targetField = fieldNormStats.mean.takeIf { it.isFinite() && it > 0.0 } ?: coordinateScale
        val correctionScale = targetField / coordinateScale
        return RayneoMagneticCalibration(
            bias = FloatArray(3) { (center[it] * coordinateScale).toFloat() },
            correctionMatrix = FloatArray(9) { (squareRoot[it] * correctionScale).toFloat() },
        )
    }

    private fun algebraicResidual(parameters: DoubleArray): Double {
        var quadratic = 0.0
        for (row in 0 until FEATURES) for (column in 0 until FEATURES) {
            quadratic += parameters[row] * normal[row * FEATURES + column] * parameters[column]
        }
        val linear = parameters.indices.sumOf { parameters[it] * rightHandSide[it] }
        return sqrt(max(0.0, acceptedSamples - 2.0 * linear + quadratic) / acceptedSamples)
    }

    private fun solveLinearSystem(matrix: DoubleArray, vector: DoubleArray, size: Int): DoubleArray? {
        val augmented = Array(size) { row -> DoubleArray(size + 1) { column ->
            if (column == size) vector[row] else matrix[row * size + column]
        } }
        for (pivot in 0 until size) {
            val best = (pivot until size).maxBy { abs(augmented[it][pivot]) }
            if (abs(augmented[best][pivot]) < MINIMUM_PIVOT) return null
            val swap = augmented[pivot]
            augmented[pivot] = augmented[best]
            augmented[best] = swap
            val divisor = augmented[pivot][pivot]
            for (column in pivot until size + 1) augmented[pivot][column] /= divisor
            for (row in 0 until size) if (row != pivot) {
                val factor = augmented[row][pivot]
                for (column in pivot until size + 1) augmented[row][column] -= factor * augmented[pivot][column]
            }
        }
        return DoubleArray(size) { augmented[it][size] }
    }

    private fun inverse3x3(matrix: DoubleArray): DoubleArray? {
        val determinant =
            matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
        if (!determinant.isFinite() || abs(determinant) < MINIMUM_PIVOT) return null
        return doubleArrayOf(
            matrix[4] * matrix[8] - matrix[5] * matrix[7], matrix[2] * matrix[7] - matrix[1] * matrix[8], matrix[1] * matrix[5] - matrix[2] * matrix[4],
            matrix[5] * matrix[6] - matrix[3] * matrix[8], matrix[0] * matrix[8] - matrix[2] * matrix[6], matrix[2] * matrix[3] - matrix[0] * matrix[5],
            matrix[3] * matrix[7] - matrix[4] * matrix[6], matrix[1] * matrix[6] - matrix[0] * matrix[7], matrix[0] * matrix[4] - matrix[1] * matrix[3],
        ).also { inverse -> inverse.indices.forEach { inverse[it] /= determinant } }
    }

    private fun symmetricSquareRoot3x3(matrix: DoubleArray): DoubleArray? {
        val diagonalized = matrix.copyOf()
        val eigenvectors = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(JACOBI_SWEEPS) {
            val pair = listOf(0 to 1, 0 to 2, 1 to 2).maxBy { abs(diagonalized[it.first * 3 + it.second]) }
            val p = pair.first
            val q = pair.second
            val offDiagonal = diagonalized[p * 3 + q]
            if (abs(offDiagonal) < JACOBI_EPSILON) return@repeat
            val angle = 0.5 * atan2(2.0 * offDiagonal, diagonalized[q * 3 + q] - diagonalized[p * 3 + p])
            val cosine = cos(angle)
            val sine = sin(angle)
            for (index in 0..2) {
                val left = diagonalized[index * 3 + p]
                val right = diagonalized[index * 3 + q]
                diagonalized[index * 3 + p] = cosine * left - sine * right
                diagonalized[index * 3 + q] = sine * left + cosine * right
            }
            for (index in 0..2) {
                val top = diagonalized[p * 3 + index]
                val bottom = diagonalized[q * 3 + index]
                diagonalized[p * 3 + index] = cosine * top - sine * bottom
                diagonalized[q * 3 + index] = sine * top + cosine * bottom
                val left = eigenvectors[index * 3 + p]
                val right = eigenvectors[index * 3 + q]
                eigenvectors[index * 3 + p] = cosine * left - sine * right
                eigenvectors[index * 3 + q] = sine * left + cosine * right
            }
        }
        val eigenvalues = doubleArrayOf(diagonalized[0], diagonalized[4], diagonalized[8])
        if (eigenvalues.any { !it.isFinite() || it <= MINIMUM_EIGENVALUE } ||
            eigenvalues.max() / eigenvalues.min() > MAXIMUM_CONDITION
        ) return null
        return DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0..2).sumOf { axis ->
                eigenvectors[row * 3 + axis] * sqrt(eigenvalues[axis]) * eigenvectors[column * 3 + axis]
            }
        }
    }

    private fun multiply(matrix: DoubleArray, vector: DoubleArray): DoubleArray = DoubleArray(3) { row ->
        (0..2).sumOf { column -> matrix[row * 3 + column] * vector[column] }
    }

    private fun dot(left: DoubleArray, right: DoubleArray): Double = left.indices.sumOf { left[it] * right[it] }
    private fun norm(value: FloatArray): Float = sqrt(value.sumOf { (it * it).toDouble() }).toFloat()

    private class RunningStats {
        var count: Int = 0
            private set
        var mean: Double = 0.0
            private set
        private var squaredDeviation = 0.0
        val standardDeviation: Double get() = if (count > 1) sqrt(squaredDeviation / (count - 1)) else 0.0

        fun add(value: Float) {
            count++
            val delta = value - mean
            mean += delta / count
            squaredDeviation += delta * (value - mean)
        }
    }

    private companion object {
        const val FEATURES = 9
        const val DIRECTION_BINS = 6
        const val REQUIRED_SAMPLES = 2_001
        const val SOLVE_INTERVAL = 100
        const val DISTURBANCE_BASELINE_SAMPLES = 64
        const val DISTURBANCE_REPORT_COUNT = 3
        const val PRECALIBRATION_DISTURBANCE_FRACTION = 0.80
        const val DISTURBANCE_FRACTION = 0.25
        const val DISTURBANCE_SIGMA = 6.0
        const val MINIMUM_COVERAGE = 0.45f
        const val MAXIMUM_ALGEBRAIC_RESIDUAL = 0.35
        const val MINIMUM_PIVOT = 1e-10
        const val MINIMUM_EIGENVALUE = 1e-7
        const val MAXIMUM_CONDITION = 100.0
        const val JACOBI_SWEEPS = 24
        const val JACOBI_EPSILON = 1e-12
    }
}
