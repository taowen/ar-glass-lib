package com.taowen.arglass.driver.viture.beast

import com.taowen.arglass.ImuSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object VitureBeastProtocol {
    const val RAW_IMU_REPORT = 0x7309
    const val NATIVE_MODE_RESPONSE = 0x5140
    const val DISPLAY_MODE_RESPONSE = 0x5142
    const val SET_DISPLAY_RESPONSE = 0x2142

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

    fun decodeImu(bytes: ByteArray, length: Int): ImuSample? {
        val packet = decode(bytes, length) ?: return null
        if (packet.messageId != RAW_IMU_REPORT || length < 64) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun vector(offset: Int) = floatArrayOf(buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8))
        val accelerationG = vector(30)
        val gyro = vector(18)
        val magnet = vector(42)
        if ((accelerationG + gyro + magnet).any { !it.isFinite() }) return null
        return ImuSample(
            (buffer.getInt(60).toLong() and 0xffffffffL) * 1_000L,
            FloatArray(3) { accelerationG[it] * 9.81f },
            gyro,
            magnet,
            (buffer.getShort(16).toInt() and 0xffff) / 5f,
            2,
        )
    }
}
