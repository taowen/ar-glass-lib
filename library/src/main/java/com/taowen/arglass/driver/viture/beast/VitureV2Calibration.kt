package com.taowen.arglass.driver.viture.beast

import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuCalibrationSource
import com.taowen.arglass.ImuCalibrationState
import com.taowen.arglass.ImuSample
import com.taowen.arglass.TemperatureGyroscopeBias
import com.taowen.arglass.driver.rayneo.airfamily.RayneoMagneticCalibration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Factory sensor calibration returned by the V2 0x3302..0x3306 long-packet commands. */
internal class VitureV2Calibration private constructor(
    private val gyroscopeBias: FloatArray,
    private val gyroscopeMatrix: FloatArray,
    private val accelerometerScale: Float,
    private val accelerometerBias: FloatArray,
    private val accelerometerMatrix: FloatArray,
    private val magnetometerBias: FloatArray,
    private val magnetometerMatrix: FloatArray,
    private val gyroscopeTemperatureBiases: List<TemperatureBias>,
    private val accelerometerTemperatureBiases: List<TemperatureBias>,
    private val magnetometerTemperatureBiases: List<TemperatureBias>,
    private val noiseStandardDeviations: FloatArray,
) {
    fun calibrateFactory(sample: ImuSample): ImuSample {
        val temperature = sample.temperatureCelsius
        val gyroBiasAtTemperature = nearestBias(gyroscopeTemperatureBiases, temperature) ?: gyroscopeBias
        val accelerationBiasAtTemperature = nearestBias(accelerometerTemperatureBiases, temperature) ?: accelerometerBias
        val magneticBiasAtTemperature = nearestBias(magnetometerTemperatureBiases, temperature) ?: magnetometerBias
        val acceleration = transform(
            accelerometerMatrix,
            subtract(sample.accelerationMetersPerSecondSquared, accelerationBiasAtTemperature),
        ).also { vector -> vector.indices.forEach { vector[it] *= accelerometerScale } }
        val gyroscope = transform(
            gyroscopeMatrix,
            subtract(sample.angularVelocityRadiansPerSecond, gyroBiasAtTemperature),
        )
        val magnetic = sample.magneticField?.let {
            transform(magnetometerMatrix, subtract(it, magneticBiasAtTemperature))
        }
        return sample.copy(
            accelerationMetersPerSecondSquared = acceleration,
            angularVelocityRadiansPerSecond = gyroscope,
            magneticField = magnetic,
            calibration = FACTORY_STATE,
        )
    }

    fun publicData(hostMagnetic: RayneoMagneticCalibration?): ImuCalibrationData {
        val accelerationCorrection = scale(accelerometerMatrix, accelerometerScale)
        val factoryMagneticBias = transform(magnetometerMatrix, magnetometerBias)
        val effectiveMagneticMatrix = hostMagnetic?.let { multiply(it.correctionMatrix, magnetometerMatrix) }
            ?: magnetometerMatrix
        val effectiveMagneticBias = hostMagnetic?.let {
            transform(it.correctionMatrix, add(factoryMagneticBias, it.bias))
        } ?: factoryMagneticBias
        return ImuCalibrationData(
            source = if (hostMagnetic == null) ImuCalibrationSource.DEVICE_FACTORY else ImuCalibrationSource.MIXED,
            state = calibrationState(hostMagnetic != null),
            accelerometerBiasMetersPerSecondSquared = transform(accelerationCorrection, accelerometerBias),
            gyroscopeBiasRadiansPerSecond = transform(gyroscopeMatrix, gyroscopeBias),
            magnetometerBias = effectiveMagneticBias,
            gyroscopeTemperatureBiases = gyroscopeTemperatureBiases.map {
                TemperatureGyroscopeBias(it.temperatureCelsius, transform(gyroscopeMatrix, it.bias))
            },
            noiseStandardDeviations = noiseStandardDeviations.copyOf(),
            accelerometerCorrectionMatrix = accelerationCorrection.copyOf(),
            gyroscopeCorrectionMatrix = gyroscopeMatrix.copyOf(),
            magnetometerCorrectionMatrix = effectiveMagneticMatrix.copyOf(),
        )
    }

    private fun nearestBias(values: List<TemperatureBias>, temperature: Float): FloatArray? =
        values.minByOrNull { abs(it.temperatureCelsius - temperature) }?.bias

    private data class TemperatureBias(val temperatureCelsius: Float, val bias: FloatArray)

    companion object {
        val FACTORY_STATE = ImuCalibrationState(
            accelerometer = ImuCalibrationLevel.FACTORY,
            gyroscope = ImuCalibrationLevel.FACTORY,
            magnetometer = ImuCalibrationLevel.FACTORY,
        )

        fun parse(
            imuPacket: ByteArray,
            magnetometerPacket: ByteArray,
            gyroscopeTemperaturePacket: ByteArray?,
            accelerometerTemperaturePacket: ByteArray?,
            magnetometerTemperaturePacket: ByteArray?,
        ): VitureV2Calibration {
            require(imuPacket.size >= IMU_PACKET_SIZE) { "VITURE IMU calibration is shorter than 124 bytes" }
            require(magnetometerPacket.size >= MAGNETOMETER_PACKET_SIZE) {
                "VITURE magnetometer calibration is shorter than 56 bytes"
            }
            val imu = ByteBuffer.wrap(imuPacket).order(ByteOrder.LITTLE_ENDIAN)
            val magnetometer = ByteBuffer.wrap(magnetometerPacket).order(ByteOrder.LITTLE_ENDIAN)
            val magnetometerVersion = magnetometer.getShort(6).toInt() and 0xffff
            val magneticIntrinsic = matrix(magnetometer, 20)
            val magneticToImu = if (magnetometerVersion > 1 && magnetometerPacket.size >= 72) {
                quaternionXyzwToMatrix(vector4(magnetometer, 56))
            } else {
                IDENTITY_MATRIX
            }
            val accelerationScale = imu.getFloat(64).takeIf { it.isFinite() && it > 0f } ?: STANDARD_GRAVITY
            return VitureV2Calibration(
                gyroscopeBias = vector(imu, 8),
                gyroscopeMatrix = matrix(imu, 20),
                accelerometerScale = accelerationScale,
                accelerometerBias = vector(imu, 68),
                accelerometerMatrix = matrix(imu, 80),
                magnetometerBias = vector(magnetometer, 8),
                magnetometerMatrix = multiply(magneticToImu, magneticIntrinsic),
                gyroscopeTemperatureBiases = parseTemperatureBiases(gyroscopeTemperaturePacket),
                accelerometerTemperatureBiases = parseTemperatureBiases(accelerometerTemperaturePacket),
                magnetometerTemperatureBiases = parseTemperatureBiases(magnetometerTemperaturePacket),
                noiseStandardDeviations = floatArrayOf(
                    imu.getFloat(56),
                    imu.getFloat(60),
                    imu.getFloat(116),
                    imu.getFloat(120),
                ).filter(Float::isFinite).toFloatArray(),
            ).also { calibration ->
                require(
                    listOf(
                        calibration.gyroscopeBias,
                        calibration.gyroscopeMatrix,
                        calibration.accelerometerBias,
                        calibration.accelerometerMatrix,
                        calibration.magnetometerBias,
                        calibration.magnetometerMatrix,
                    ).all { values -> values.all(Float::isFinite) },
                ) { "VITURE calibration contains non-finite values" }
            }
        }

        fun calibrationState(hostMagneticReady: Boolean) = FACTORY_STATE.copy(
            magnetometer = if (hostMagneticReady) ImuCalibrationLevel.HOST_ESTIMATED else ImuCalibrationLevel.FACTORY,
        )

        private fun parseTemperatureBiases(packet: ByteArray?): List<TemperatureBias> {
            if (packet == null || packet.size < TEMPERATURE_HEADER_SIZE) return emptyList()
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt(8)
            if (count < 0 || TEMPERATURE_HEADER_SIZE + count * TEMPERATURE_GROUP_SIZE > packet.size) return emptyList()
            return List(count) { index ->
                val offset = TEMPERATURE_HEADER_SIZE + index * TEMPERATURE_GROUP_SIZE
                TemperatureBias(buffer.getFloat(offset), vector(buffer, offset + 4))
            }.filter { it.temperatureCelsius.isFinite() && it.bias.all(Float::isFinite) }
        }

        private fun vector(buffer: ByteBuffer, offset: Int) =
            floatArrayOf(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))

        private fun vector4(buffer: ByteBuffer, offset: Int) = FloatArray(4) { buffer.getFloat(offset + it * 4) }

        /** Firmware/SDK matrices are serialized column-major; the public driver contract is row-major. */
        private fun matrix(buffer: ByteBuffer, offset: Int): FloatArray {
            val columnMajor = FloatArray(9) { buffer.getFloat(offset + it * 4) }
            return FloatArray(9) { index -> columnMajor[(index % 3) * 3 + index / 3] }
        }

        private fun quaternionXyzwToMatrix(q: FloatArray): FloatArray {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            val norm = x * x + y * y + z * z + w * w
            if (!norm.isFinite() || norm < 1e-8f) return IDENTITY_MATRIX
            val s = 2f / norm
            return floatArrayOf(
                1f - s * (y * y + z * z), s * (x * y - z * w), s * (x * z + y * w),
                s * (x * y + z * w), 1f - s * (x * x + z * z), s * (y * z - x * w),
                s * (x * z - y * w), s * (y * z + x * w), 1f - s * (x * x + y * y),
            )
        }

        private fun transform(matrix: FloatArray, value: FloatArray) = FloatArray(3) { row ->
            matrix[row * 3] * value[0] + matrix[row * 3 + 1] * value[1] + matrix[row * 3 + 2] * value[2]
        }

        private fun multiply(left: FloatArray, right: FloatArray) = FloatArray(9) { index ->
            val row = index / 3; val column = index % 3
            (0..2).sumOf { middle -> (left[row * 3 + middle] * right[middle * 3 + column]).toDouble() }.toFloat()
        }

        private fun subtract(left: FloatArray, right: FloatArray) = FloatArray(3) { left[it] - right[it] }
        private fun add(left: FloatArray, right: FloatArray) = FloatArray(3) { left[it] + right[it] }
        private fun scale(matrix: FloatArray, value: Float) = FloatArray(9) { matrix[it] * value }

        private const val IMU_PACKET_SIZE = 124
        private const val MAGNETOMETER_PACKET_SIZE = 56
        private const val TEMPERATURE_HEADER_SIZE = 12
        private const val TEMPERATURE_GROUP_SIZE = 16
        private const val STANDARD_GRAVITY = 9.80665f
        private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
