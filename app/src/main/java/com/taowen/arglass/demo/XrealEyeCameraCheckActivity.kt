package com.taowen.arglass.demo

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.ArGlassCameraSource
import com.taowen.arglass.ArGlassCameraSurfaceOptions
import com.taowen.arglass.ArGlassCameraSurfaceStream
import com.taowen.arglass.ArGlassCameraSurfaceWriters

/** Lets the user explicitly test the open XREAL Eye camera implementation. */
class XrealEyeCameraCheckActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var preview: TextureView
    @Volatile private var previewSurface: Surface? = null
    private var stream: ArGlassCameraSurfaceStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("XREAL Eye 摄像头", 25f, true))
        status = label(
            "使用 ar-glass-lib 统一 Surface 输出接口；One + Eye 走 USB Ethernet TCP/HEVC，不走 UVC。",
            16f,
        )
        content.addView(status, margins(top = 12, bottom = 12))
        content.addView(Button(this).apply {
            text = "XREAL One Eye 预览"
            setOnClickListener { startXrealEyePreview() }
        }, margins(bottom = 12))
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    previewSurface = Surface(surfaceTexture)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    stopCurrent()
                    previewSurface?.release()
                    previewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360),
            ).apply { bottomMargin = dp(12) },
        )
    }

    private fun startXrealEyePreview() {
        stopCurrent()
        val surface = previewSurface?.takeIf { it.isValid }
            ?: return run { status.text = "XREAL Eye 预览启动失败：预览 Surface 未就绪" }
        status.text = "XREAL Eye：正在打开统一 Surface 输出"
        stream = ArGlassCameraSurfaceWriters.start(
            this,
            surface,
            ArGlassCameraSource.XREAL_ONE_EYE,
            ArGlassCameraSurfaceOptions(maxEmptyReads = 0),
        ) { cameraStatus ->
            runOnUiThread { status.text = cameraStatus.toDebugString() }
        }
    }

    private fun stopCurrent() {
        stream?.close()
        stream = null
    }

    override fun onDestroy() {
        stopCurrent()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }
}
