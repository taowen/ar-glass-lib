package com.taowen.arglass.demo

import android.widget.Button
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.GlassesDisplayLayout
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.SessionFeature

/** Full glasses-native display profile switch UI. USB/protocol work stays in library JNI/drivers. */
class DisplayProfileSwitchActivity : UsbCheckActivity() {
    override val titleText = "显示模式切换"
    override val sessionFeature = SessionFeature.DISPLAY_MODE
    private var initialized = false

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        if (initialized) return
        initialized = true
        val control = requireNotNull(session)
        val profiles = glasses.model.supportedDisplayProfiles.sortedWith(
            compareBy<GlassesDisplayProfile> { it.layout.ordinal }
                .thenBy { it.width }
                .thenBy { it.height }
                .thenBy { it.refreshRateHz },
        )
        if (profiles.isEmpty()) {
            status.text = "${glasses.model.displayName} 尚未声明可枚举的眼镜原生显示模式"
            return
        }
        status.text = "${glasses.model.displayName} · 支持 ${profiles.size} 个眼镜原生显示模式"
        content.addView(Button(this).apply {
            text = "读取当前眼镜显示模式"
            setOnClickListener { queryCurrent(control) }
        }, margins(top = 4, bottom = 12))
        profiles.forEach { profile ->
            content.addView(Button(this).apply {
                text = "切换到 ${profileLabel(profile)}"
                setOnClickListener { switchTo(control, profile) }
            }, margins(top = 8))
        }
        queryCurrent(control)
    }

    private fun queryCurrent(control: ArGlassesSession) {
        status.text = "正在读取当前眼镜显示模式…"
        Thread({
            val current = runCatching { control.queryDisplayProfile() }.getOrNull()
            runOnUiThread {
                status.text = current?.let { "当前眼镜显示模式：${profileLabel(it)}" }
                    ?: "当前眼镜显示模式：读取失败或未知协议值"
            }
        }, "display-profile-query").start()
    }

    private fun switchTo(control: ArGlassesSession, profile: GlassesDisplayProfile) {
        status.text = "正在切换到 ${profileLabel(profile)}…"
        Thread({
            val changed = runCatching { control.setDisplayProfile(profile) }.getOrDefault(false)
            val current = if (changed) runCatching { control.queryDisplayProfile() }.getOrNull() else null
            runOnUiThread {
                status.text = if (changed) {
                    "切换命令已完成：${profileLabel(profile)}" +
                        (current?.let { "\n读回：${profileLabel(it)}" } ?: "") +
                        xrealOneProjectionHint(control)
                } else {
                    "切换失败：${profileLabel(profile)}"
                }
            }
        }, "display-profile-set").start()
    }

    private fun profileLabel(profile: GlassesDisplayProfile): String =
        "${profile.width}×${profile.height}@${profile.refreshRateHz}Hz ${layoutLabel(profile.layout)}"

    private fun layoutLabel(layout: GlassesDisplayLayout): String = when (layout) {
        GlassesDisplayLayout.MONO_2D -> "2D"
        GlassesDisplayLayout.HALF_SBS_3D -> "Half SBS 3D"
        GlassesDisplayLayout.FULL_SBS_3D -> "Full SBS 3D"
    }

    private fun xrealOneProjectionHint(control: ArGlassesSession): String =
        if (control.model.id.startsWith("xreal_one")) {
            "\n如出现系统“是否开始投屏”确认，请手工点开始；外接显示会在确认后按新 EDID 重连。"
        } else {
            ""
        }
}
