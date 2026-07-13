package com.taowen.arglass.demo

import android.app.Activity
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.ArGlassesManager
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.ImuSample
import java.util.Locale

class MainActivity : Activity(), ArGlassesListener {
    private lateinit var manager: ArGlassesManager
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private var selected: ConnectedGlasses? = null
    private var session: ArGlassesSession? = null
    private var imuCount = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        title = label("AR Glass Check", 26f, true)
        status = label("请插入 AR 眼镜", 16f)
        content.addView(title)
        content.addView(status, margins(top = 12))
        setContentView(ScrollView(this).apply { addView(content) })
        manager = ArGlassesManager(this, mainExecutor, this)
    }

    override fun onResume() { super.onResume(); manager.scan() }

    override fun onDevicesChanged(devices: List<ConnectedGlasses>) {
        val glasses = devices.firstOrNull()
        if (glasses == null) {
            selected = null
            session?.close(); session = null
            renderWaiting()
            return
        }
        if (selected?.device?.deviceId == glasses.device.deviceId && session != null) return
        selected = glasses
        if (manager.hasPermission(glasses.device)) connect(glasses) else {
            renderDetected(glasses, "需要 USB 权限才能检查功能")
            manager.requestPermission(glasses.device)
        }
    }

    override fun onPermissionResult(device: ConnectedGlasses, granted: Boolean) {
        if (granted) connect(device) else renderDetected(device, "USB 权限被拒绝")
    }

    private fun connect(glasses: ConnectedGlasses) {
        runCatching { manager.open(glasses.device) }
            .onSuccess { session = it; renderFunctions(glasses) }
            .onFailure { renderDetected(glasses, "打开失败：${it.message}") }
    }

    private fun renderWaiting() {
        content.removeAllViews(); content.addView(title)
        status.text = "请通过 USB-C 插入 AR 眼镜\n\n当前首个支持型号：XREAL Air 2 Ultra"
        content.addView(status, margins(top = 12))
        content.addView(Button(this).apply { text = "重新扫描"; setOnClickListener { manager.scan() } }, margins(top = 20))
    }

    private fun renderDetected(glasses: ConnectedGlasses, message: String) {
        content.removeAllViews(); content.addView(title)
        content.addView(label("已识别：${glasses.model.displayName}", 20f, true), margins(top = 16))
        status.text = message; content.addView(status, margins(top = 8))
    }

    private fun renderFunctions(glasses: ConnectedGlasses) {
        renderDetected(glasses, "请选择要检查的功能")
        section("IMU 检测")
        val imu = label("正在初始化 IMU…", 15f).also { it.tag = "imu" }
        content.addView(imu)
        section("2D / 3D 切换检测")
        content.addView(Button(this).apply {
            text = "读取当前模式"
            setOnClickListener { status.text = "当前模式：${session?.queryDisplayMode()?.name ?: "读取失败"}" }
        })
        DisplayMode.entries.forEach { mode ->
            content.addView(Button(this).apply {
                text = when (mode) {
                    DisplayMode.MIRROR_2D -> "切换到 2D"
                    DisplayMode.HALF_SBS_3D -> "切换到 Half SBS 3D"
                    DisplayMode.FULL_SBS_3D -> "切换到 Full SBS 3D"
                    DisplayMode.HIGH_REFRESH_SBS_3D -> "切换到高刷 SBS 3D"
                }
                setOnClickListener {
                    status.text = if (session?.setDisplayMode(mode) == true)
                        "已发送 ${mode.name}，请观察眼镜画面" else "模式切换失败"
                }
            })
        }
        section("分辨率检测")
        content.addView(Button(this).apply {
            text = "读取 Android 外接显示分辨率"
            setOnClickListener {
                val displays = manager.externalDisplayResolutions()
                status.text = displays.joinToString("\n").ifEmpty { "Android 尚未发现外接显示" }
            }
        })
    }

    override fun onStatus(message: String) { status.text = message }

    override fun onImuSample(sample: ImuSample) {
        imuCount++
        if (imuCount % 6 != 0L) return
        content.findViewWithTag<TextView>("imu")?.text = String.format(
            Locale.US,
            "报告 v%d · %,d 帧\nAccel  %+.3f  %+.3f  %+.3f m/s²\nGyro   %+.3f  %+.3f  %+.3f rad/s\n温度 %.1f °C",
            sample.reportVersion, imuCount,
            sample.accelerationMetersPerSecondSquared[0], sample.accelerationMetersPerSecondSquared[1], sample.accelerationMetersPerSecondSquared[2],
            sample.angularVelocityRadiansPerSecond[0], sample.angularVelocityRadiansPerSecond[1], sample.angularVelocityRadiansPerSecond[2],
            sample.temperatureCelsius,
        )
    }

    private fun section(text: String) {
        content.addView(label(text, 18f, true), margins(top = 24, bottom = 8))
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(0xff182033.toInt())
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() { manager.close(); super.onDestroy() }
}
