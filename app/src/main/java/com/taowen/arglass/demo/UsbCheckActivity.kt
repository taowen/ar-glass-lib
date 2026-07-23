package com.taowen.arglass.demo

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.ArGlassesManager
import com.taowen.arglass.ArGlassesSession
import com.taowen.arglass.ConnectedGlasses
import com.taowen.arglass.SessionFeature

/** Shared USB discovery/lifecycle only; each subclass owns its check logic and UI. */
abstract class UsbCheckActivity : Activity(), ArGlassesListener {
    protected lateinit var manager: ArGlassesManager
    protected lateinit var content: LinearLayout
    protected lateinit var status: TextView
    protected var session: ArGlassesSession? = null
    protected abstract val titleText: String
    protected abstract val sessionFeature: SessionFeature?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label(titleText, 25f, true))
        status = label("正在查找眼镜…", 16f)
        content.addView(status, margins(top = 12, bottom = 16))
        manager = ArGlassesManager(this, mainExecutor, this)
    }

    override fun onResume() { super.onResume(); manager.scan() }

    override fun onDevicesChanged(devices: List<ConnectedGlasses>) {
        val glasses = devices.firstOrNull() ?: run {
            status.text = "未检测到支持的 AR 眼镜"
            return
        }
        if (sessionFeature == null) {
            status.text = "已识别：${glasses.model.displayName}"
            onReady(glasses, null)
        } else if (manager.hasPermission(glasses)) {
            open(glasses)
        } else {
            status.text = "等待 ${glasses.model.displayName} USB 授权…"
            manager.requestPermission(glasses)
        }
    }

    override fun onPermissionResult(device: ConnectedGlasses, granted: Boolean) {
        if (granted) open(device) else status.text = "USB 权限被拒绝"
    }

    private fun open(glasses: ConnectedGlasses) {
        if (session != null) return
        runCatching { manager.open(glasses.device, requireNotNull(sessionFeature)) }
            .onSuccess { opened -> session = opened; onReady(glasses, opened) }
            .onFailure { status.text = "打开失败：${it.message}" }
    }

    protected abstract fun onReady(glasses: ConnectedGlasses, session: ArGlassesSession?)

    override fun onStatus(message: String) { status.text = message }

    override fun onDestroy() { manager.close(); super.onDestroy() }
}
