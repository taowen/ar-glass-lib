package com.taowen.arglass.demo

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.ArGlassCameraFrameReaders
import com.taowen.arglass.ArGlassCameraSource
import com.taowen.arglass.ArGlassCameraSurfaceOptions
import com.taowen.arglass.ArGlassCameraSurfaceStream
import com.taowen.arglass.ArGlassCameraSurfaceWriters
import com.taowen.arglass.BeastCameraCatalog

class CameraCheckActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var usbManager: UsbManager
    private lateinit var preview: TextureView
    @Volatile private var previewSurface: Surface? = null
    private var stream: ArGlassCameraSurfaceStream? = null
    private var pendingUsb: UsbDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: pendingUsb ?: return
            pendingUsb = null
            if (usbManager.hasPermission(device)) {
                startBeastSurfaceStream()
            } else {
                status.text = "Beast 摄像头 USB 权限被拒绝"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("VITURE Beast 摄像头", 25f, true))
        status = label("使用 ar-glass-lib 统一 Surface 输出接口；Beast 摄像头走原生 UVC/MJPEG。", 16f)
        content.addView(status, margins(top = 12, bottom = 12))
        content.addView(Button(this).apply {
            text = "Beast 摄像头预览"
            setOnClickListener { startBeastPreview() }
        }, margins(bottom = 12))
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1920, 1080)
                    previewSurface = Surface(surfaceTexture)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1920, 1080)
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
        usbManager = getSystemService(UsbManager::class.java)
        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0,
        )
    }

    private fun startBeastPreview() {
        stopCurrent()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "等待 Android 摄像头权限；系统会用它保护 USB video-class 设备访问。"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        val camera = usbManager.deviceList.values.firstOrNull(BeastCameraCatalog::identify)
            ?: return run {
                status.text = "未发现 Beast 摄像头 0C45:6368\n${ArGlassCameraFrameReaders.describeAvailability(this)}"
            }
        if (!usbManager.hasPermission(camera)) {
            pendingUsb = camera
            status.text = "等待 Beast 摄像头 USB 授权..."
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(camera, pendingIntent)
            return
        }
        startBeastSurfaceStream()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startBeastPreview()
        } else {
            status.text = "Android 摄像头权限被拒绝；系统不会授权访问 Beast USB video-class 摄像头。"
        }
    }

    private fun startBeastSurfaceStream() {
        val surface = previewSurface?.takeIf { it.isValid }
            ?: return run { status.text = "Beast 预览启动失败：预览 Surface 未就绪" }
        status.text = "Beast：正在打开统一 Surface 输出"
        stream = ArGlassCameraSurfaceWriters.start(
            this,
            surface,
            ArGlassCameraSource.BEAST,
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
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.taowen.arglass.BEAST_CAMERA_USB_PERMISSION"
        const val CAMERA_PERMISSION_REQUEST = 7202
    }
}
