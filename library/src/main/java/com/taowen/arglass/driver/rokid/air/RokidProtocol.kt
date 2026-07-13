package com.taowen.arglass.driver.rokid.air

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.ImuSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RokidProtocol {
    const val INTERRUPT_ENDPOINT = 0x82

    fun decodeCombined(bytes: ByteArray, length: Int): ImuSample? {
        if (length < 47 || bytes[0].toInt() and 0xff != 17) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(9) { buffer.getFloat(9 + it * 4) }
        if (values.any { !it.isFinite() }) return null
        return ImuSample(
            deviceTimestampNanos = buffer.getLong(1) / 1_000L,
            accelerationMetersPerSecondSquared = values.copyOfRange(0, 3),
            angularVelocityRadiansPerSecond = values.copyOfRange(3, 6),
            magneticField = values.copyOfRange(6, 9),
            temperatureCelsius = Float.NaN,
            reportVersion = 17,
        )
    }

    fun decodeSensor(bytes: ByteArray, length: Int): SensorReading? {
        if (length < 33 || bytes[0].toInt() and 0xff != 4) return null
        val type = bytes[1].toInt() and 0xff
        if (type !in 1..3) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(3) { buffer.getFloat(21 + it * 4) }
        if (vector.any { !it.isFinite() }) return null
        return SensorReading(type, buffer.getLong(9), vector)
    }

    fun displayMode(value: Int): DisplayMode = when (value) {
        0 -> DisplayMode.MIRROR_2D
        1 -> DisplayMode.FULL_SBS_3D
        2 -> DisplayMode.HALF_SBS_3D
        4 -> DisplayMode.HIGH_REFRESH_SBS_3D
        else -> DisplayMode.MIRROR_2D
    }

    fun wireValue(mode: DisplayMode): Int? = when (mode) {
        DisplayMode.MIRROR_2D -> 0
        DisplayMode.FULL_SBS_3D -> 1
        DisplayMode.HIGH_REFRESH_SBS_3D -> 4
        DisplayMode.HALF_SBS_3D -> null
    }

    data class SensorReading(val type: Int, val timestamp: Long, val vector: FloatArray)
}
