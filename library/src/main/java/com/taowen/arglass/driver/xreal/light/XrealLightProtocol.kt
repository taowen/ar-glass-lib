package com.taowen.arglass.driver.xreal.light

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.ImuSample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

internal object XrealLightProtocol {
    private data class Entry(val wireValue: Char, val profile: GlassesDisplayProfile)

    val twoDimensionalProfile = GlassesDisplayProfile(
        id = "xreal_light_mode_1",
        width = 1920,
        height = 1080,
        refreshRateHz = 60,
        layout = GlassesDisplayLayout.MONO_2D,
    )
    val fullSbs3dProfile = GlassesDisplayProfile(
        id = "xreal_light_mode_3",
        width = 3840,
        height = 1080,
        refreshRateHz = 60,
        layout = GlassesDisplayLayout.FULL_SBS_3D,
    )

    private val profileEntries = listOf(
        Entry('1', twoDimensionalProfile),
        Entry(
            '2',
            GlassesDisplayProfile(
                id = "xreal_light_mode_2",
                width = 1920,
                height = 1080,
                refreshRateHz = 60,
                layout = GlassesDisplayLayout.HALF_SBS_3D,
            ),
        ),
        Entry('3', fullSbs3dProfile),
        Entry(
            '4',
            GlassesDisplayProfile(
                id = "xreal_light_mode_4",
                width = 3840,
                height = 1080,
                refreshRateHz = 72,
                layout = GlassesDisplayLayout.FULL_SBS_3D,
            ),
        ),
    )
    val displayProfiles: List<GlassesDisplayProfile> = profileEntries.map(Entry::profile)

    fun mcu(category: Char, command: Char, data: String = "x"): ByteArray {
        val prefix = "\u0002:$category:$command:$data:0:".encodeToByteArray()
        val crc = Adler32().apply { update(prefix) }.value.toString(16).padStart(8, ' ')
        return ByteArray(64).also { (prefix + crc.encodeToByteArray() + byteArrayOf(':'.code.toByte(), 3)).copyInto(it) }
    }

    fun decodeProfile(packet: ByteArray): GlassesDisplayProfile? {
        val end = packet.indexOf(3).takeIf { it > 0 } ?: return null
        val parts = packet.copyOfRange(1, end).decodeToString().split(':')
        val value = parts.getOrNull(3)?.firstOrNull()
        return profileEntries.firstOrNull { it.wireValue == value }?.profile
    }

    fun wire(profile: GlassesDisplayProfile): Char? =
        profileEntries.firstOrNull { it.profile.id == profile.id }?.wireValue

    fun decodeImu(bytes: ByteArray): ImuSample? {
        if (bytes.size < 108 || bytes[0].toInt() != 1) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = b.getLong(44)
        val gm=b.getInt(52).toFloat(); val gd=b.getInt(56).toFloat()
        val am=b.getInt(80).toFloat(); val ad=b.getInt(84).toFloat()
        if (gd == 0f || ad == 0f) return null
        val radians = (Math.PI / 180.0).toFloat()
        val gx=b.getInt(60)*gm/gd*radians; val gy=b.getInt(64)*gm/gd*radians; val gz=b.getInt(68)*gm/gd*radians
        val ax=b.getInt(88)*am/ad*9.81f; val ay=b.getInt(92)*am/ad*9.81f; val az=b.getInt(96)*am/ad*9.81f
        return ImuSample(timestamp, floatArrayOf(ax,-ay,-az), floatArrayOf(gx,-gy,-gz), null, Float.NaN, 1)
    }
}
