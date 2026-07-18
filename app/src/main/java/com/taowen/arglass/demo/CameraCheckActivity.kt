package com.taowen.arglass.demo

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.BeastCameraCatalog
import com.taowen.arglass.BeastCameraSession
import java.util.concurrent.atomic.AtomicBoolean

class CameraCheckActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var usbManager: UsbManager
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var beastSession: BeastCameraSession? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var pendingUsb: UsbDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: pendingUsb ?: return
            pendingUsb = null
            if (usbManager.hasPermission(device)) startNativePreview(device) else status.text = "Beast 摄像头 USB 权限被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("VITURE Beast 摄像头", 25f, true))
        status = label("正在查找 Beast 单目摄像头…", 16f)
        content.addView(status, margins(top = 12, bottom = 16))
        usbManager = getSystemService(UsbManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)
        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0,
        )
        ensureCameraPermissionAndStart()
    }

    private fun ensureCameraPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startPreview()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) startPreview()
    }

    private fun startPreview() {
        val beastCamera = usbManager.deviceList.values.firstOrNull(BeastCameraCatalog::identify)
            ?: return run { status.text = "检测到 Beast，但未发现摄像头 0C45:6368；请检查摄像头 USB 设备是否已枚举" }
        val externalCamera = if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            }
        } else null
        if (externalCamera != null) startCamera2Preview(externalCamera) else startUsbFallback(beastCamera)
    }

    private fun startCamera2Preview(cameraId: String) {
        val texture = TextureView(this)
        content.addView(texture, margins(bottom = 12).apply { height = dp(260) })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                openCamera2(cameraId, surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun openCamera2(cameraId: String, texture: SurfaceTexture) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return startUsbFallback()
        val sizes = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }
        size?.let { texture.setDefaultBufferSize(it.width, it.height) }
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surface = Surface(texture)
                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
                        session.setRepeatingRequest(request, null, null)
                        status.text = "Camera2 外接摄像头 $cameraId · ${size?.width ?: "?"}×${size?.height ?: "?"}"
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { status.text = "Camera2 配置失败" }
                }, null)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); status.text = "Beast 摄像头已断开" }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); status.text = "Camera2 打开失败：$error" }
        }, null)
    }

    private fun startUsbFallback(camera: UsbDevice? = usbManager.deviceList.values.firstOrNull(BeastCameraCatalog::identify)) {
        camera
            ?: return run { status.text = "检测到 Beast，但未发现摄像头 0C45:6368；请检查摄像头 USB 设备是否已枚举" }
        if (usbManager.hasPermission(camera)) startNativePreview(camera) else {
            pendingUsb = camera
            status.text = "等待 Beast 摄像头 USB 授权…"
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(camera, pendingIntent)
        }
    }

    private fun startNativePreview(camera: UsbDevice) {
        if (running.get()) return
        val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(image, margins(bottom = 12))
        runCatching { BeastCameraSession(usbManager, camera) }
            .onFailure { status.text = "原生 UVC 启动失败：${it.message}" }
            .onSuccess { session ->
                beastSession = session
                running.set(true)
                status.text = "原生 UVC · 1920×1080@30 MJPEG"
                readerThread = Thread({
                    while (running.get()) {
                        val jpeg = session.readJpegFrame() ?: continue
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        runOnUiThread { image.setImageBitmap(bitmap) }
                    }
                }, "beast-camera-preview").also(Thread::start)
            }
    }

    override fun onDestroy() {
        running.set(false)
        beastSession?.close()
        readerThread?.interrupt()
        captureSession?.close()
        cameraDevice?.close()
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val ACTION_USB_PERMISSION = "com.taowen.arglass.BEAST_CAMERA_USB_PERMISSION"
        const val CAMERA_PERMISSION_REQUEST = 7201
    }
}
