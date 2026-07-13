package com.taowen.arglass.demo

import android.widget.Button
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.SessionFeature

/** XREAL MCU display-mode query and explicit 2D/3D switch checks. */
class DisplayModeCheckActivity : UsbCheckActivity() {
    override val titleText = "2D / 3D 切换检测"
    override val sessionFeature = SessionFeature.DISPLAY_MODE

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        val control = requireNotNull(session)
        status.text = "${glasses.model.displayName} · MCU 已连接"
        content.addView(Button(this).apply {
            text = "读取当前模式"
            setOnClickListener { status.text = "当前模式：${control.queryDisplayMode()?.name ?: "读取失败"}" }
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
                    status.text = if (control.setDisplayMode(mode))
                        "已发送 ${mode.name}，请观察眼镜画面" else "模式切换失败"
                }
            }, margins(top = 8))
        }
    }
}
