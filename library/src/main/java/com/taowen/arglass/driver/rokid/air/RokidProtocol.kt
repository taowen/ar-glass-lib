package com.taowen.arglass.driver.rokid.air

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.ImuSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RokidProtocol {
    const val INTERRUPT_ENDPOINT = 0x82
    private const val DISPLAY_2D = 0
    private const val DISPLAY_FULL_SBS_3D = 1
    private const val DISPLAY_HIGH_REFRESH_3D = 4
    val twoDimensionalProfile = profile(DISPLAY_2D, 1920, 1080, 60, GlassesDisplayLayout.MONO_2D)
    val fullSbs3dProfile = profile(DISPLAY_FULL_SBS_3D, 3840, 1080, 60, GlassesDisplayLayout.FULL_SBS_3D)
    val highRefresh3dProfile = profile(DISPLAY_HIGH_REFRESH_3D, 3840, 1080, 90, GlassesDisplayLayout.FULL_SBS_3D)
    val displayProfiles = listOf(twoDimensionalProfile, fullSbs3dProfile, highRefresh3dProfile)

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

    fun displayProfile(value: Int): GlassesDisplayProfile? = when (value) {
        DISPLAY_2D -> twoDimensionalProfile
        DISPLAY_FULL_SBS_3D -> fullSbs3dProfile
        DISPLAY_HIGH_REFRESH_3D -> highRefresh3dProfile
        else -> null
    }

    fun wireValue(profile: GlassesDisplayProfile): Int? = when (profile.id) {
        twoDimensionalProfile.id -> DISPLAY_2D
        fullSbs3dProfile.id -> DISPLAY_FULL_SBS_3D
        highRefresh3dProfile.id -> DISPLAY_HIGH_REFRESH_3D
        else -> null
    }

    private fun profile(value: Int, width: Int, height: Int, refreshRateHz: Int, layout: GlassesDisplayLayout) =
        GlassesDisplayProfile("rokid_display_mode_$value", width, height, refreshRateHz, layout)

    data class SensorReading(val type: Int, val timestamp: Long, val vector: FloatArray)
}
