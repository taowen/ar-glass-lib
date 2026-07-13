package com.taowen.arglass.demo

import android.widget.Button
import android.widget.TextView
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.SessionFeature

/** Android DisplayManager resolution/mode inspection; it never opens USB endpoints. */
class ResolutionCheckActivity : UsbCheckActivity() {
    override val titleText = "分辨率检测"
    override val sessionFeature: SessionFeature? = null
    private lateinit var result: TextView

    override fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?) {
        if (::result.isInitialized) return
        result = label("点击按钮读取外接显示状态", 16f)
        content.addView(result)
        content.addView(Button(this).apply {
            text = "读取 Android 外接显示分辨率"
            setOnClickListener { refresh() }
        }, margins(top = 12))
        refresh()
    }

    private fun refresh() {
        val displays = manager.externalDisplayResolutions()
        result.text = displays.joinToString("\n\n") { display ->
            "${display.name} (display ${display.displayId})\n${display.width} × ${display.height} @ ${"%.2f".format(display.refreshRate)} Hz"
        }.ifEmpty { "Android 尚未发现外接显示" }
    }
}
