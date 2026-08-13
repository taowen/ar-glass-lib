package com.taowen.arglass.driver.rokid.max2

import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Maps the reusable native batch decoder output to the public Kotlin IMU API. */
internal object RokidMax2Protocol {
    private const val NATIVE_SAMPLE_SIZE = 48

    fun decodeHighSpeed(
        packet: ByteArray,
        length: Int,
        hostTimestampNanos: Long,
    ): List<ImuSample> {
        val decoded = NativeBridge.decodeRokidMax2ImuBatch(packet, length)
        if (decoded.size % NATIVE_SAMPLE_SIZE != 0) return emptyList()
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        return buildList(decoded.size / NATIVE_SAMPLE_SIZE) {
            for (offset in decoded.indices step NATIVE_SAMPLE_SIZE) {
                add(
                    ImuSample(
                        deviceTimestampNanos = buffer.getLong(offset),
                        accelerationMetersPerSecondSquared = vector(buffer, offset + 8),
                        angularVelocityRadiansPerSecond = vector(buffer, offset + 20),
                        magneticField = vector(buffer, offset + 32),
                        temperatureCelsius = Float.NaN,
                        reportVersion = buffer.getInt(offset + 44),
                        hostTimestampNanos = hostTimestampNanos,
                    ),
                )
            }
        }
    }

    private fun vector(buffer: ByteBuffer, offset: Int) =
        FloatArray(3) { index -> buffer.getFloat(offset + index * Float.SIZE_BYTES) }
}
