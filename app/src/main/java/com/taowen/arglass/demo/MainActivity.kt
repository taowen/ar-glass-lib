package com.taowen.arglass.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.ArGlassesDiagnostics
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.ArGlassesManager
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.GlassesCapability

/** Device identification and navigation only. No hardware check runs here. */
class MainActivity : Activity(), ArGlassesListener {
    private lateinit var manager: ArGlassesManager
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("AR Glass Check", 26f, true))
        status = label("请通过 USB-C 插入 AR 眼镜", 16f)
        content.addView(status, margins(top = 12))
        content.addView(Button(this).apply {
            text = "导出诊断日志"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), EXPORT_LOGS_REQUEST) }
        }, margins(top = 12))
        manager = ArGlassesManager(this, mainExecutor, this)
    }

    override fun onResume() { super.onResume(); manager.scan() }

    override fun onDevicesChanged(devices: List<ConnectedGlasses>) {
        content.removeViews(3, (content.childCount - 3).coerceAtLeast(0))
        val glasses = devices.firstOrNull()
        if (glasses == null) {
            status.text = "请通过 USB-C 插入 AR 眼镜\n\n支持：XREAL Air 2 Ultra / XBX A01 / XBX A01 Plus / One / One S、Rokid Air / Max、VITURE Beast、LUCI"
            content.addView(Button(this).apply { text = "重新扫描"; setOnClickListener { manager.scan() } }, margins(top = 20))
            return
        }
        status.text = "已识别：${glasses.model.displayName}\n请选择需要检查的功能"
        if (GlassesCapability.IMU in glasses.model.capabilities) addCheckButton("IMU 检测", ImuCheckActivity::class.java)
        if (GlassesCapability.DISPLAY_MODE in glasses.model.capabilities) addCheckButton("开启 / 关闭 3D", DisplayModeCheckActivity::class.java)
        if (devices.any { it.model.id == "viture_beast" }) addCheckButton("摄像头检测", CameraCheckActivity::class.java)
        if (devices.any { it.model.id == "xreal_one" || it.model.id == "xreal_one_s" }) {
            addCheckButton("XREAL Eye 摄像头检测", XrealEyeCameraCheckActivity::class.java)
        }
    }

    private fun addCheckButton(caption: String, activity: Class<out Activity>) {
        content.addView(Button(this).apply {
            text = caption
            setOnClickListener { startActivity(Intent(this@MainActivity, activity)) }
        }, margins(top = 12))
    }

    @Deprecated("Uses framework Activity results to keep the diagnostic APK dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_LOGS_REQUEST || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        runCatching { ArGlassesDiagnostics.exportToTree(this, tree) }
            .onSuccess { status.text = "已导出：${it.joinToString()}" }
            .onFailure { status.text = "导出失败：${it.message}" }
    }

    override fun onDestroy() { manager.close(); super.onDestroy() }

    private companion object { const val EXPORT_LOGS_REQUEST = 7001 }
}
