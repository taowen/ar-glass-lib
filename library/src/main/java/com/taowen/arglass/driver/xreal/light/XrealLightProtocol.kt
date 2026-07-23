package com.taowen.arglass.driver.xreal.light

import com.taowen.arglass.DisplayMode
import com.taowen.arglass.ImuSample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

internal object XrealLightProtocol {
    fun mcu(category: Char, command: Char, data: String = "x"): ByteArray {
        val prefix = "\u0002:$category:$command:$data:0:".encodeToByteArray()
        val crc = Adler32().apply { update(prefix) }.value.toString(16).padStart(8, ' ')
        return ByteArray(64).also { (prefix + crc.encodeToByteArray() + byteArrayOf(':'.code.toByte(), 3)).copyInto(it) }
    }
    fun decodeMode(packet: ByteArray): DisplayMode? {
        val end = packet.indexOf(3).takeIf { it > 0 } ?: return null
        val parts = packet.copyOfRange(1, end).decodeToString().split(':')
        val value = parts.getOrNull(3)?.firstOrNull()
        return when (value) { '1' -> DisplayMode.MIRROR_2D; '2' -> DisplayMode.HALF_SBS_3D
            '3' -> DisplayMode.FULL_SBS_3D; '4' -> DisplayMode.HIGH_REFRESH_SBS_3D; else -> null }
    }
    fun wire(mode: DisplayMode) = when (mode) { DisplayMode.MIRROR_2D -> '1'; DisplayMode.HALF_SBS_3D -> '2'
        DisplayMode.FULL_SBS_3D -> '3'; DisplayMode.HIGH_REFRESH_SBS_3D -> '4' }

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
