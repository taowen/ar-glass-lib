package com.taowen.arglass.driver.goovis.g3family

import com.taowen.arglass.ImuSample

internal object GoovisG3Protocol {
    const val VENDOR_ID = 0x880a
    const val REPORT_SIZE = 24
    const val MINIMUM_IMU_REPORT_SIZE = 13
    const val USB_TIMEOUT_MS = 1_000

    private const val GRAVITY_METERS_PER_SECOND_SQUARED = 9.80665f
    private const val ACCEL_G_PER_LSB = 4f / 32768f
    private const val GYRO_DEGREES_PER_SECOND_PER_LSB = 1000f / 32768f
    private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

    fun displayModeReport(enableSbs: Boolean): ByteArray = commandReport(
        group = 0,
        value = if (enableSbs) 0 else 1,
    )

    fun imuStreamReport(enable: Boolean): ByteArray = commandReport(
        group = 1,
        value = if (enable) 1 else 0,
    )

    fun decodeImuReport(
        bytes: ByteArray,
        length: Int,
        modelKind: GoovisModelKind,
        deviceTimestampNanos: Long,
        hostTimestampNanos: Long,
    ): ImuSample? {
        if (length < MINIMUM_IMU_REPORT_SIZE || length > bytes.size) return null
        val intervalMillis = bytes[12].toInt() and 0xff
        if (intervalMillis == 0) return null

        val acceleration = modelKind.toRuntimeCoordinates(floatArrayOf(
            signedBigEndian16(bytes, 0) * ACCEL_G_PER_LSB * GRAVITY_METERS_PER_SECOND_SQUARED,
            signedBigEndian16(bytes, 2) * ACCEL_G_PER_LSB * GRAVITY_METERS_PER_SECOND_SQUARED,
            signedBigEndian16(bytes, 4) * ACCEL_G_PER_LSB * GRAVITY_METERS_PER_SECOND_SQUARED,
        ))
        val angularVelocity = modelKind.toRuntimeCoordinates(floatArrayOf(
            signedBigEndian16(bytes, 6) * GYRO_DEGREES_PER_SECOND_PER_LSB * DEGREES_TO_RADIANS,
            signedBigEndian16(bytes, 8) * GYRO_DEGREES_PER_SECOND_PER_LSB * DEGREES_TO_RADIANS,
            signedBigEndian16(bytes, 10) * GYRO_DEGREES_PER_SECOND_PER_LSB * DEGREES_TO_RADIANS,
        ))
        return ImuSample(
            deviceTimestampNanos = deviceTimestampNanos,
            accelerationMetersPerSecondSquared = acceleration,
            angularVelocityRadiansPerSecond = angularVelocity,
            magneticField = null,
            temperatureCelsius = Float.NaN,
            reportVersion = 1,
            hostTimestampNanos = hostTimestampNanos,
        )
    }

    fun sampleIntervalNanos(bytes: ByteArray, length: Int): Long =
        if (length >= MINIMUM_IMU_REPORT_SIZE) (bytes[12].toInt() and 0xff) * 1_000_000L else 0L

    private fun commandReport(group: Int, value: Int): ByteArray = ByteArray(REPORT_SIZE).apply {
        this[0] = 0xaa.toByte()
        this[1] = 0x55
        this[2] = 0x55
        this[3] = 0xaa.toByte()
        this[4] = group.toByte()
        this[5] = value.toByte()
        this[6] = take(6).sumOf { it.toInt() }.toByte()
    }

    private fun signedBigEndian16(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)).toShort()
}

internal enum class GoovisModelKind {
    G3,
    G3X,
    G3XP,
    A1;

    /** Matches the proper-axis permutations used by the D4 runtime, without its pointer gain. */
    fun toRuntimeCoordinates(vector: FloatArray): FloatArray = when (this) {
        G3 -> vector
        G3X, G3XP -> floatArrayOf(-vector[0], vector[2], vector[1])
        A1 -> floatArrayOf(vector[1], vector[2], vector[0])
    }
}
