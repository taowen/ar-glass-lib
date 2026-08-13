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

/** Guided clean-room implementation of the host calibration used by RayNeo's nine-axis path. */
class RayneoMagneticCalibrationActivity : UsbCheckActivity() {
    override val titleText = "RayNeo 磁力计校准"
    override val sessionFeature = SessionFeature.IMU
    private lateinit var instructions: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var fieldText: TextView
    private var rayneoSession: ArGlassesSession? = null
    private var latestProgress: ImuHostCalibrationProgress? = null
    private var samples = 0L

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        if (!glasses.model.id.startsWith("rayneo_")) {
            status.text = "此入口仅用于 RayNeo Air / GT 系列"
            session?.close()
            return
        }
        rayneoSession = session
        status.text = "${glasses.model.displayName} · 等待磁力计数据"
        instructions = label(
            "远离音箱、磁吸保护壳和大块金属。手持眼镜缓慢绕俯仰、横滚、航向三个轴连续旋转，" +
                "让镜腿朝向空间各个方向；中途不要拔线。",
            16f,
        )
        content.addView(instructions)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1_000
            progress = 0
        }
        content.addView(progressBar, margins(top = 18))
        progressText = label("正在收集样本…", 16f)
        content.addView(progressText, margins(top = 8))
        fieldText = label("磁场：等待数据", 15f)
        content.addView(fieldText, margins(top = 18))
        content.addView(Button(this).apply {
            text = "清除已有结果并重新校准"
            setOnClickListener {
                if (rayneoSession?.resetHostImuCalibration() == true) {
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
        val sampleFraction = progress.acceptedSamples.toFloat() / progress.requiredSamples
        val totalProgress = minOf(sampleFraction, progress.orientationCoverage).coerceIn(0f, 1f)
        progressBar.progress = (totalProgress * progressBar.max).toInt()
        val phaseText = when (progress.phase) {
            ImuHostCalibrationPhase.COLLECTING -> "正在采集"
            ImuHostCalibrationPhase.DISTURBED -> "检测到磁场干扰，请换一个位置"
            ImuHostCalibrationPhase.READY -> "校准完成，结果已保存"
        }
        progressText.text = String.format(
            Locale.US,
            "%s\n有效样本 %,d / %,d\n姿态覆盖 %.0f%%\n已拒绝干扰样本 %,d",
            phaseText,
            progress.acceptedSamples,
            progress.requiredSamples,
            progress.orientationCoverage * 100f,
            progress.rejectedDisturbanceSamples,
        )
    }

    override fun onImuCalibration(calibration: ImuCalibrationData) {
        if (!::progressText.isInitialized || calibration.state.magnetometer != ImuCalibrationLevel.HOST_ESTIMATED) return
        progressBar.progress = progressBar.max
        progressText.text = "校准完成，完整 3×3 hard/soft-iron 系数已保存并发布。样本保持协议解码后的 SI 数值。"
    }

    override fun onImuSample(sample: ImuSample) {
        samples++
        if (samples % 10 != 0L || !::fieldText.isInitialized) return
        val magnetic = sample.magneticField
        fieldText.text = if (magnetic == null) {
            if (latestProgress?.phase == ImuHostCalibrationPhase.DISTURBED) "磁场：受扰，当前样本未改写" else "磁场：不可用"
        } else {
            val magnitude = sqrt(magnetic.sumOf { (it * it).toDouble() })
            String.format(
                Locale.US,
                "磁场向量\n%+.3f  %+.3f  %+.3f\n模长 %.3f",
                magnetic[0], magnetic[1], magnetic[2], magnitude,
            )
        }
    }
}
