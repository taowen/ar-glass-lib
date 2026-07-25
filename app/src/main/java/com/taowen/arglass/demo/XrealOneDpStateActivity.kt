package com.taowen.arglass.demo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.ArGlassesCatalog
import com.taowen.arglass.XrealOneDpDiagnostics
import com.taowen.arglass.XrealOneDpState

/** Read-only XREAL One-family DP state capture. Does not switch display modes. */
class XrealOneDpStateActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var copyButton: Button
    private var lastReport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("XREAL One DP EDID/input 读取", 25f, true))
        content.addView(
            label(
                "用途：采集当前眼镜显示状态的 EDID 和 inputMode。\n\n" +
                    "操作：先用官方 app、眼镜菜单或其他工具把眼镜设置到希望支持的状态，" +
                    "例如 3840×1080@60Hz Full SBS 3D；确认系统投屏提示后回到这里读取，" +
                    "再把读到的 EDID/inputMode 发给开发者。",
                16f,
            ),
            margins(top = 12, bottom = 12),
        )
        status = label(XrealOneDpDiagnostics.describeAvailability(this), 14f)
        content.addView(status, margins(bottom = 12))
        content.addView(Button(this).apply {
            text = "读取当前 EDID / inputMode"
            setOnClickListener { readCurrentState(this) }
        }, margins(bottom = 8))
        copyButton = Button(this).apply {
            text = "复制给开发者"
            isEnabled = false
            setOnClickListener { copyLastReport() }
        }
        content.addView(copyButton, margins(bottom = 12))
        result = label("", 16f)
        content.addView(result)
    }

    private fun readCurrentState(button: Button) {
        button.isEnabled = false
        copyButton.isEnabled = false
        status.text = "正在读取 XREAL One DP 状态…\n${XrealOneDpDiagnostics.describeAvailability(this)}"
        Thread({
            val availability = XrealOneDpDiagnostics.describeAvailability(this)
            val read = runCatching { XrealOneDpDiagnostics.readCurrentState(this) }
            runOnUiThread {
                button.isEnabled = true
                read.onSuccess { state ->
                    val report = formatReport(state, availability)
                    lastReport = report
                    result.text = report
                    status.text = "读取成功"
                    copyButton.isEnabled = true
                }.onFailure { error ->
                    lastReport = null
                    result.text = "读取失败：${error.message ?: error.javaClass.simpleName}\n\n$availability"
                    status.text = "读取失败"
                    copyButton.isEnabled = false
                }
            }
        }, "xreal-one-dp-state-read").start()
    }

    private fun formatReport(state: XrealOneDpState, availability: String): String {
        val notes = state.notes.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n", prefix = "\n网络备注：\n")
            .orEmpty()
        return """
            当前读数：
            EDID=${state.edid}
            inputMode=${state.inputMode}
            DP RPC=${state.host}:${state.port}
            USB Ethernet Network=${if (state.networkReady) "ready" else "not found / fallback route used"}$notes

            发给开发者：
            眼镜型号：${detectedGlassesLabel()}
            目标状态：请填写你刚才设置的显示模式，例如 3840×1080@60Hz Full SBS 3D
            读回状态：EDID=${state.edid}, inputMode=${state.inputMode}

            设备状态：
            $availability
        """.trimIndent()
    }

    private fun detectedGlassesLabel(): String {
        val usbManager = getSystemService(UsbManager::class.java)
        val models = usbManager.deviceList.values.mapNotNull { device ->
            ArGlassesCatalog.identify(device)?.displayName
        }.distinct()
        return models.joinToString().ifBlank { "未知 XREAL One family" }
    }

    private fun copyLastReport() {
        val report = lastReport ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("XREAL One DP state", report))
        status.text = "已复制给开发者的文本"
    }
}
