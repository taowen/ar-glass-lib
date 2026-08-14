package com.taowen.arglass.driver.xreal

import com.taowen.arglass.ImuSample
import com.taowen.arglass.ImuTransportMetadata
import com.taowen.arglass.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the common XREAL report without applying factory calibration. */
internal fun decodeXrealImuReport(report: ByteArray): ImuSample? {
    val values = NativeBridge.decodeImuReport(report)?.takeIf { it.size == 12 } ?: return null
    val magneticField = floatArrayOf(values[7], values[8], values[9])
    val version = values[11].toInt()
    val sensorTimestampOffset = if (version == 1) 48 else 54
    val magneticFreshOffset = if (version == 1) 56 else 62
    val magneticFresh = (report[magneticFreshOffset].toInt() and 0xff) != 0
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
            dataMask = 0xb or if (magneticFresh) 0x4 else 0,
            magneticFieldFresh = magneticFresh,
        ),
        rawReport = report.copyOf(),
    )
}
