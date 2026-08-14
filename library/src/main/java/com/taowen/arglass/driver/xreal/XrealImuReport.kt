package com.taowen.arglass.driver.xreal

import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the common 64-byte XREAL report and preserves an invalid magnetometer as null. */
internal fun decodeXrealImuReport(report: ByteArray): ImuSample? {
    val values = NativeBridge.decodeImuReport(report)?.takeIf { it.size == 12 } ?: return null
    val magneticField = floatArrayOf(values[7], values[8], values[9]).takeIf { magnetic ->
        magnetic.all(Float::isFinite)
    }
    return ImuSample(
        deviceTimestampNanos = ByteBuffer.wrap(report, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long,
        accelerationMetersPerSecondSquared = floatArrayOf(values[1], values[2], values[3]),
        angularVelocityRadiansPerSecond = floatArrayOf(values[4], values[5], values[6]),
        magneticField = magneticField,
        temperatureCelsius = values[10],
        reportVersion = values[11].toInt(),
        rawReport = report.copyOf(),
    )
}
