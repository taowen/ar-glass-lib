package com.taowen.arglass.demo

import android.widget.Button
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.SessionFeature

/** Standalone 3D switch UI. Model-specific commands remain inside the library drivers. */
class DisplayModeCheckActivity : UsbCheckActivity() {
    override val titleText = "3D 开关"
    override val sessionFeature = SessionFeature.DISPLAY_MODE

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        val control = requireNotNull(session)
        status.text = "${glasses.model.displayName} · MCU 已连接"
        content.addView(Button(this).apply {
            text = if (glasses.model.id == "viture_beast") "读取 Native/Bypass 与 2D/3D 状态" else "读取当前模式"
            setOnClickListener {
                isEnabled = false
                Thread({
                    val mode = control.queryDisplayMode()
                    runOnUiThread {
                        if (mode != null) {
                            status.text = "当前显示模式：${mode.name}"
                        } else if (glasses.model.id != "viture_beast" && !status.text.toString().contains("失败")) {
                            status.text = "当前显示模式：${mode?.name ?: "读取失败"}"
                        }
                        isEnabled = true
                    }
                }, "display-mode-query").start()
            }
        })
        val supportedModes = glasses.model.supportedDisplayModes
        val preferred3dMode = glasses.model.preferred3dDisplayMode

        preferred3dMode?.let { mode ->
            addModeButton("开启 3D", mode, control)
        }
        if (DisplayMode.MIRROR_2D in supportedModes) {
            addModeButton("关闭 3D（恢复 2D）", DisplayMode.MIRROR_2D, control)
        }
    }

    private fun addModeButton(caption: String, mode: DisplayMode, control: ArGlassesSession) {
        content.addView(Button(this).apply {
            text = caption
            setOnClickListener {
                isEnabled = false
                Thread({
                    val changed = control.setDisplayMode(mode)
                    runOnUiThread {
                        status.text = if (changed) {
                            val message = if (mode == DisplayMode.MIRROR_2D) {
                                "3D 已关闭，当前为 2D"
                            } else {
                                "3D 已开启（${modeLabel(mode)}）"
                            }
                            message + xrealOneProjectionHint()
                        } else {
                            "模式切换失败"
                        }
                        isEnabled = true
                    }
                }, "display-mode-set").start()
            }
        }, margins(top = 8))
    }

    private fun modeLabel(mode: DisplayMode) = when (mode) {
        DisplayMode.MIRROR_2D -> "2D"
        DisplayMode.HALF_SBS_3D -> "Half SBS"
        DisplayMode.FULL_SBS_3D -> "Full SBS"
        DisplayMode.HIGH_REFRESH_SBS_3D -> "高刷 SBS"
    }

    private fun xrealOneProjectionHint(): String =
        if (session?.model?.id?.startsWith("xreal_one") == true) {
            "\n如出现系统“是否开始投屏”确认，请手工点开始；分辨率会在确认后更新。"
        } else {
            ""
        }
}
