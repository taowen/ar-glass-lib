package com.taowen.arglass.demo

import android.widget.TextView
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.ImuSample
import com.taowen.arglass.SessionFeature
import java.util.Locale

/** IMU initialization, stream validation, and sample presentation. */
class ImuCheckActivity : UsbCheckActivity() {
    override val titleText = "IMU 检测"
    override val sessionFeature = SessionFeature.IMU
    private lateinit var readings: TextView
    private var sampleCount = 0L

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        status.text = "${glasses.model.displayName} · 正在初始化 IMU"
        readings = label("等待传感器数据，请转动眼镜…", 16f)
        content.addView(readings)
    }

    override fun onImuSample(sample: ImuSample) {
        sampleCount++
        if (sampleCount % 6 != 0L || !::readings.isInitialized) return
        readings.text = String.format(
            Locale.US,
            "报告 v%d · %,d 帧\n设备时间 %,d ns\n\nAccel\n%+.3f  %+.3f  %+.3f m/s²\n\nGyro\n%+.3f  %+.3f  %+.3f rad/s\n\n温度 %.1f °C",
            sample.reportVersion, sampleCount, sample.deviceTimestampNanos,
            sample.accelerationMetersPerSecondSquared[0], sample.accelerationMetersPerSecondSquared[1], sample.accelerationMetersPerSecondSquared[2],
            sample.angularVelocityRadiansPerSecond[0], sample.angularVelocityRadiansPerSecond[1], sample.angularVelocityRadiansPerSecond[2],
            sample.temperatureCelsius,
        )
    }
}
