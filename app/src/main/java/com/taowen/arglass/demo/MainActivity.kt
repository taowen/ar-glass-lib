package com.taowen.arglass.demo

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.taowen.arglass.ArGlassesDiagnostics
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.ArGlassesManager
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.XrealEyeCameraCatalog
import java.io.File

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
            text = "导出诊断 zip"
            setOnClickListener { shareDiagnosticsZip() }
        }, margins(top = 12))
        manager = ArGlassesManager(this, mainExecutor, this)
    }

    override fun onResume() { super.onResume(); manager.scan() }

    override fun onDevicesChanged(devices: List<ConnectedGlasses>) {
        content.removeViews(3, (content.childCount - 3).coerceAtLeast(0))
        val glasses = devices.firstOrNull()
        val usbDevices = getSystemService(UsbManager::class.java).deviceList.values
        if (glasses == null) {
            status.text = "请通过 USB-C 插入 AR 眼镜\n\n支持：XREAL Air 2 Ultra / XBX A01 / XBX A01 Plus / One / One S、Rokid Air / Max、VITURE Beast、LUCI"
            content.addView(Button(this).apply { text = "重新扫描"; setOnClickListener { manager.scan() } }, margins(top = 20))
            if (usbDevices.any(XrealEyeCameraCatalog::identifyOneFamilyMain)) {
                addCheckButton("XREAL One EDID/input 读取", XrealOneDpStateActivity::class.java)
                addCheckButton("XREAL Eye 摄像头检测", XrealEyeCameraCheckActivity::class.java)
            }
            return
        }
        status.text = "已识别：${glasses.model.displayName}\n请选择需要检查的功能"
        if (devices.any { it.model.id == "xreal_one" || it.model.id == "xreal_one_pro" || it.model.id == "xreal_one_s" } ||
            usbDevices.any(XrealEyeCameraCatalog::identifyOneFamilyMain)
        ) {
            addCheckButton("XREAL One EDID/input 读取", XrealOneDpStateActivity::class.java)
            addCheckButton("XREAL Eye 摄像头检测", XrealEyeCameraCheckActivity::class.java)
        }
        if (GlassesCapability.IMU in glasses.model.capabilities) addCheckButton("IMU 检测", ImuCheckActivity::class.java)
        if (glasses.model.id.startsWith("rayneo_")) {
            addCheckButton("RayNeo 磁力计校准", RayneoMagneticCalibrationActivity::class.java)
        }
        if (GlassesCapability.DISPLAY_MODE in glasses.model.capabilities) addCheckButton("开启 / 关闭 3D", DisplayModeCheckActivity::class.java)
        if (GlassesCapability.DISPLAY_MODE in glasses.model.capabilities && glasses.model.supportedDisplayProfiles.isNotEmpty()) {
            addCheckButton("显示模式切换", DisplayProfileSwitchActivity::class.java)
        }
        if (devices.any { it.model.id == "viture_beast" }) addCheckButton("摄像头检测", CameraCheckActivity::class.java)
    }

    private fun addCheckButton(caption: String, activity: Class<out Activity>) {
        content.addView(Button(this).apply {
            text = caption
            setOnClickListener { startActivity(Intent(this@MainActivity, activity)) }
        }, margins(top = 12))
    }

    private fun shareDiagnosticsZip() {
        status.text = "正在生成诊断 zip…"
        Thread({
            val result = runCatching { ArGlassesDiagnostics.exportZipToCacheFile(this) }
            runOnUiThread {
                result
                    .onSuccess(::shareDiagnosticsZipFile)
                    .onFailure { status.text = "生成诊断 zip 失败：${it.message ?: it.javaClass.simpleName}" }
            }
        }, "ar-glass-diagnostics-export").start()
    }

    private fun shareDiagnosticsZipFile(zipFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AR Glass 诊断 zip")
            putExtra(Intent.EXTRA_TEXT, "AR Glass 诊断 zip：${zipFile.name}")
            clipData = ClipData.newUri(contentResolver, "AR Glass diagnostics zip", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "发送 AR Glass 诊断 zip"))
            status.text = "请选择一个应用发送诊断 zip"
        } catch (error: RuntimeException) {
            status.text = "打开分享界面失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    override fun onDestroy() { manager.close(); super.onDestroy() }
}
