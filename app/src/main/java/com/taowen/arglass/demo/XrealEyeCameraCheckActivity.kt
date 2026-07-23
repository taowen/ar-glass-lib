package com.taowen.arglass.demo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.XrealEyeOpenCameraSession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Lets the user explicitly test the open XREAL Eye camera implementation. */
class XrealEyeCameraCheckActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var usbManager: UsbManager
    private var session: Closeable? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var pendingUsb: UsbDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: pendingUsb ?: return
            pendingUsb = null
            if (usbManager.hasPermission(device)) startOpen(device)
            else status.text = "开源后端：USB 权限被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("XREAL Eye 摄像头", 25f, true))
        status = label("开源 libusb/UVC 读取，不依赖官方 SO。", 16f)
        content.addView(status, margins(top = 12, bottom = 12))
        content.addView(Button(this).apply {
            text = "开源 libusb/UVC 读取"
            setOnClickListener { requestOpen() }
        }, margins(bottom = 12))
        image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(image, margins(bottom = 12))
        usbManager = getSystemService(UsbManager::class.java)
        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0,
        )
    }

    private fun requestOpen() {
        stopCurrent()
        val device = usbManager.deviceList.values.firstOrNull { candidate ->
            candidate.vendorId == XREAL_VENDOR_ID && candidate.productId in ONE_FAMILY_PRODUCT_IDS &&
                (0 until candidate.interfaceCount).any {
                    val intf = candidate.getInterface(it)
                    intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2
                }
        } ?: return run { status.text = "开源后端：未发现 XREAL One VideoStreaming 接口" }
        if (usbManager.hasPermission(device)) startOpen(device) else {
            pendingUsb = device
            status.text = "开源后端：等待 USB 权限…"
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    private fun startOpen(device: UsbDevice) {
        runCatching { XrealEyeOpenCameraSession(usbManager, device) }
            .onFailure { status.text = "开源后端启动失败：${it.message}" }
            .onSuccess { open ->
                session = open
                running.set(true)
                status.text = "开源后端：libusb/UVC MJPEG"
                readerThread = Thread({
                    while (running.get()) {
                        val jpeg = open.readJpegFrame() ?: continue
                        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        runOnUiThread { image.setImageBitmap(bitmap) }
                    }
                }, "xreal-eye-open-preview").also(Thread::start)
            }
    }

    private fun stopCurrent() {
        running.set(false)
        readerThread?.interrupt()
        if (readerThread != Thread.currentThread()) runCatching { readerThread?.join(1500) }
        readerThread = null
        session?.close()
        session = null
    }

    override fun onDestroy() {
        stopCurrent()
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val ACTION_USB_PERMISSION = "com.taowen.arglass.XREAL_EYE_USB_PERMISSION"
        const val XREAL_VENDOR_ID = 0x3318
        val ONE_FAMILY_PRODUCT_IDS = setOf(0x0438, 0x043e)
    }
}
