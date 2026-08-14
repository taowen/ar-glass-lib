package com.taowen.arglass.driver.xreal

import com.taowen.arglass.ImuSample
import com.taowen.arglass.ImuTransportMetadata
import com.taowen.arglass.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the common 64-byte XREAL report and preserves an invalid magnetometer as null. */
internal fun decodeXrealImuReport(report: ByteArray): ImuSample? {
    val values = NativeBridge.decodeImuReport(report)?.takeIf { it.size == 12 } ?: return null
    val magneticField = floatArrayOf(values[7], values[8], values[9]).takeIf { magnetic ->
        magnetic.all(Float::isFinite)
    }
    val version = values[11].toInt()
    val sensorTimestampOffset = if (version == 1) 48 else 54
    val magneticFreshOffset = if (version == 1) 56 else 62
    return ImuSample(
        deviceTimestampNanos = ByteBuffer.wrap(report, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long,
        accelerationMetersPerSecondSquared = floatArrayOf(values[1], values[2], values[3]),
        angularVelocityRadiansPerSecond = floatArrayOf(values[4], values[5], values[6]),
        magneticField = magneticField,
        temperatureCelsius = values[10],
        reportVersion = version,
        transportMetadata = ImuTransportMetadata(
            sensorTimestampNanos = ByteBuffer.wrap(report, sensorTimestampOffset, 8)
                .order(ByteOrder.LITTLE_ENDIAN).long,
            dataMask = 0xb or if ((report[magneticFreshOffset].toInt() and 0xff) != 0) 0x4 else 0,
        ),
        rawReport = report.copyOf(),
    )
}
