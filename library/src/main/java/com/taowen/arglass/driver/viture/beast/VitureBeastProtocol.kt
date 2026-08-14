package com.taowen.arglass.driver.viture.beast

import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.ImuSample
import com.taowen.arglass.ImuTransportMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object VitureBeastProtocol {
    const val RAW_IMU_REPORT = 0x7309
    const val NATIVE_MODE_RESPONSE = 0x5140
    const val GET_BYPASS_DISPLAY_MODE = 0x3122
    const val SET_BYPASS_DISPLAY_MODE = 0x0122
    const val BYPASS_DISPLAY_MODE_RESPONSE = 0x5122
    const val SET_BYPASS_DISPLAY_RESPONSE = 0x2122
    const val GET_NATIVE_DISPLAY_MODE = 0x3142
    const val SET_NATIVE_DISPLAY_MODE = 0x0142
    const val NATIVE_DISPLAY_MODE_RESPONSE = 0x5142
    const val SET_NATIVE_DISPLAY_RESPONSE = 0x2142

    const val MODE_2D_1080_60HZ = 0x31
    const val BYPASS_MODE_3D_SBS_1080_60HZ = 0x32
    const val NATIVE_MODE_3D_SBS_1080_60HZ = 0x37
    val twoDimensionalProfile = GlassesDisplayProfile(
        "viture_beast_mode_2d_1920_1080_60",
        1920,
        1080,
        60,
        GlassesDisplayLayout.MONO_2D,
    )
    val fullSbs3dProfile = GlassesDisplayProfile(
        "viture_beast_mode_full_sbs_3840_1080_60",
        3840,
        1080,
        60,
        GlassesDisplayLayout.FULL_SBS_3D,
    )
    val displayProfiles = listOf(twoDimensionalProfile, fullSbs3dProfile)

    data class Packet(val messageId: Int, val payload: ByteArray)

    fun command(messageId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val checksum = payload.sumOf { it.toInt() and 0xff } and 0xffff
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x10.toShort()); putShort(messageId.toShort()); putShort(payload.size.toShort()); putShort(checksum.toShort()); put(payload)
        }.array()
    }

    fun decode(bytes: ByteArray, length: Int): Packet? {
        if (length < 8 || bytes[0] != 0x10.toByte() || bytes[1] != 0.toByte()) return null
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val messageId = buffer.getShort(2).toInt() and 0xffff
        val payloadLength = buffer.getShort(4).toInt() and 0xffff
        if (payloadLength > length - 8) return null
        val expected = buffer.getShort(6).toInt() and 0xffff
        val actual = (8 until 8 + payloadLength).sumOf { bytes[it].toInt() and 0xff } and 0xffff
        return if (actual == expected) Packet(messageId, bytes.copyOfRange(8, 8 + payloadLength)) else null
    }

    fun decodeImu(bytes: ByteArray, length: Int, hostTimestampNanos: Long = System.nanoTime()): ImuSample? {
        val packet = decode(bytes, length) ?: return null
        if (packet.messageId != RAW_IMU_REPORT || packet.payload.size < V2_IMU_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
        fun vector(offset: Int) = floatArrayOf(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))
        val accelerationG = vector(22)
        val gyro = vector(10)
        val magnet = vector(34)
        if ((accelerationG + gyro + magnet).any { !it.isFinite() }) return null
        val acceleration = imuPackageToRuntime(
            FloatArray(3) { accelerationG[it] * STANDARD_GRAVITY },
        )
        val baseMilliseconds = buffer.getInt(4).toLong() and 0xffffffffL
        val sampleCounterMicroseconds = buffer.getInt(0).toLong() and 0xffffffffL
        val imuAgeMicroseconds = uint24(buffer, 46).toLong()
        val vsyncAgeMicroseconds = uint24(buffer, 52).toLong()
        val baseTimestampNanos = baseMilliseconds * 1_000_000L
        return ImuSample(
            baseTimestampNanos + (sampleCounterMicroseconds - imuAgeMicroseconds) * 1_000L,
            acceleration,
            imuPackageToRuntime(gyro),
            imuPackageToRuntime(magnet),
            (buffer.getShort(8).toInt() and 0xffff) * 0.2f,
            2,
            hostTimestampNanos,
            transportMetadata = ImuTransportMetadata(
                vsyncTimestampNanos =
                    baseTimestampNanos + (sampleCounterMicroseconds - vsyncAgeMicroseconds) * 1_000L,
            ),
            rawReport = bytes.copyOf(length),
        )
    }

    private fun uint24(buffer: ByteBuffer, offset: Int): Int =
        (buffer.get(offset).toInt() and 0xff) or
            ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
            ((buffer.get(offset + 2).toInt() and 0xff) shl 16)

    /**
     * Beast's IMU package -> glasses transform was verified from live motion as 180 degrees
     * about runtime +X. Luma, Luma Pro/Cyber, and Pro 2 use the same official V2 parser, but
     * the available SDK and firmware do not prove their physical IMU mounting. They currently
     * retain this transform for compatibility until each model can be checked on hardware.
     */
    private fun imuPackageToRuntime(value: FloatArray) =
        floatArrayOf(value[0], -value[1], -value[2])

    private const val V2_IMU_PAYLOAD_SIZE = 56
    private const val STANDARD_GRAVITY = 9.80665f
}
