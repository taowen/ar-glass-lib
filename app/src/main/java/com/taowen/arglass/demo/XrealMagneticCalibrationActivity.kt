package com.taowen.arglass.demo

import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.ImuCalibrationData
import com.taowen.arglass.ImuCalibrationLevel
import com.taowen.arglass.ImuHostCalibrationPhase
import com.taowen.arglass.ImuHostCalibrationProgress
import com.taowen.arglass.ImuSample
import com.taowen.arglass.SessionFeature
import java.util.Locale
import kotlin.math.sqrt

/** Guided host hard/soft-iron calibration for XREAL nine-axis drivers. */
class XrealMagneticCalibrationActivity : UsbCheckActivity() {
    override val titleText = "XREAL 磁力计校准"
    override val sessionFeature = SessionFeature.IMU
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var fieldText: TextView
    private var glassesSession: ArGlassesSession? = null
    private var latestProgress: ImuHostCalibrationProgress? = null
    private var samples = 0L

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        if (glasses.model.id !in SUPPORTED_MODELS) {
            status.text = "此入口用于 XREAL Air 2 Ultra / One / One Pro / 1S"
            session?.close()
            return
        }
        glassesSession = session
        status.text = "${glasses.model.displayName} · 已加载出厂校准，等待磁力计数据"
        content.addView(label(
            "远离音箱、磁吸保护壳和大块金属。手持眼镜缓慢绕俯仰、横滚、航向三个轴连续旋转，" +
                "覆盖空间各个方向；中途不要拔线。",
            16f,
        ))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1_000 }
        content.addView(progressBar, margins(top = 18))
        progressText = label("正在收集样本…", 16f)
        content.addView(progressText, margins(top = 8))
        fieldText = label("磁场：等待数据", 15f)
        content.addView(fieldText, margins(top = 18))
        content.addView(Button(this).apply {
            text = "清除已有结果并重新校准"
            setOnClickListener {
                if (glassesSession?.resetHostImuCalibration() == true) {
                    latestProgress = null
                    progressBar.progress = 0
                    progressText.text = "旧结果已清除，请开始绕三个轴旋转"
                }
            }
        }, margins(top = 20))
    }

    override fun onImuHostCalibrationProgress(progress: ImuHostCalibrationProgress) {
        latestProgress = progress
        if (!::progressBar.isInitialized) return
        progressBar.progress = (minOf(
            progress.acceptedSamples.toFloat() / progress.requiredSamples,
            progress.orientationCoverage,
        ).coerceIn(0f, 1f) * progressBar.max).toInt()
        val phase = when (progress.phase) {
            ImuHostCalibrationPhase.COLLECTING -> "正在采集"
            ImuHostCalibrationPhase.DISTURBED -> "检测到磁场干扰，请换一个位置"
            ImuHostCalibrationPhase.READY -> "校准完成，结果已保存"
        }
        progressText.text = String.format(
            Locale.US,
            "%s\n有效样本 %,d / %,d\n姿态覆盖 %.0f%%\n已拒绝干扰样本 %,d",
            phase,
            progress.acceptedSamples,
            progress.requiredSamples,
            progress.orientationCoverage * 100f,
            progress.rejectedDisturbanceSamples,
        )
    }

    override fun onImuCalibration(calibration: ImuCalibrationData) {
        if (!::progressText.isInitialized || calibration.state.magnetometer != ImuCalibrationLevel.HOST_ESTIMATED) return
        progressBar.progress = progressBar.max
        progressText.text = "校准完成；出厂 IMU 校准和 host 3×3 hard/soft-iron 系数已发布，样本保持协议解码后的 SI 数值。"
    }

    override fun onImuSample(sample: ImuSample) {
        samples++
        if (samples % 10 != 0L || !::fieldText.isInitialized) return
        val magnetic = sample.magneticField
        fieldText.text = if (magnetic == null) {
            if (latestProgress?.phase == ImuHostCalibrationPhase.DISTURBED) "磁场：受扰，当前样本未改写" else "磁场：等待新样本"
        } else {
            val magnitude = sqrt(magnetic.sumOf { (it * it).toDouble() })
            String.format(Locale.US, "磁场向量\n%+.3f  %+.3f  %+.3f\n模长 %.3f", magnetic[0], magnetic[1], magnetic[2], magnitude)
        }
    }

    private companion object {
        val SUPPORTED_MODELS = setOf("xreal_air_2_ultra", "xreal_one", "xreal_one_pro", "xreal_one_s")
    }
}
