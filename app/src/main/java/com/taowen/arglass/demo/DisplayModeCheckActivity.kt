package com.taowen.arglass.demo

import android.widget.Button
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
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
                    val profile = control.queryDisplayProfile()
                    runOnUiThread {
                        if (profile != null) {
                            status.text = "当前显示模式：${profileLabel(profile)}"
                        } else if (glasses.model.id != "viture_beast" && !status.text.toString().contains("失败")) {
                            status.text = "当前显示模式：读取失败"
                        }
                        isEnabled = true
                    }
                }, "display-mode-query").start()
            }
        })
        val preferred2dProfile = glasses.model.preferred2dDisplayProfile
        val preferred3dProfile = glasses.model.preferred3dDisplayProfile

        preferred3dProfile?.let { profile ->
            addModeButton("开启 3D", profile, control, target3d = true)
        }
        preferred2dProfile?.let { profile ->
            addModeButton("关闭 3D（恢复 2D）", profile, control, target3d = false)
        }
    }

    private fun addModeButton(caption: String, profile: GlassesDisplayProfile, control: ArGlassesSession, target3d: Boolean) {
        content.addView(Button(this).apply {
            text = caption
            setOnClickListener {
                isEnabled = false
                Thread({
                    val changed = runCatching {
                        if (target3d) control.switchTo3d() else control.switchTo2d()
                    }.getOrDefault(false)
                    runOnUiThread {
                        status.text = if (changed) {
                            val message = if (!target3d) {
                                "3D 已关闭，当前为 2D"
                            } else {
                                "3D 已开启（${profileLabel(profile)}）"
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

    private fun profileLabel(profile: GlassesDisplayProfile): String =
        "${profile.width}×${profile.height}@${profile.refreshRateHz}Hz ${layoutLabel(profile.layout)}"

    private fun layoutLabel(layout: GlassesDisplayLayout): String = when (layout) {
        GlassesDisplayLayout.MONO_2D -> "2D"
        GlassesDisplayLayout.HALF_SBS_3D -> "Half SBS"
        GlassesDisplayLayout.FULL_SBS_3D -> "Full SBS"
    }

    private fun xrealOneProjectionHint(): String =
        if (session?.model?.id?.startsWith("xreal_one") == true) {
            "\n如出现系统“是否开始投屏”确认，请手工点开始；分辨率会在确认后更新。"
        } else {
            ""
        }
}
