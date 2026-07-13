package com.taowen.arglass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.Display
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriverRegistry
import java.io.Closeable
import java.util.concurrent.Executor

data class ConnectedGlasses(val device: UsbDevice, val model: GlassesModel)
data class DisplayResolution(val displayId: Int, val name: String, val width: Int, val height: Int, val refreshRate: Float)
enum class SessionFeature { IMU, DISPLAY_MODE, ALL }

interface ArGlassesListener {
    fun onDevicesChanged(devices: List<ConnectedGlasses>) {}
    fun onPermissionResult(device: ConnectedGlasses, granted: Boolean) {}
    fun onStatus(message: String) {}
    fun onImuSample(sample: ImuSample) {}
}

class ArGlassesManager(
    context: Context,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : Closeable {
    companion object { private const val ACTION_USB_PERMISSION = "com.taowen.arglass.USB_PERMISSION" }
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private var pendingPermission: UsbDevice? = null
    private var session: ArGlassesSession? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: pendingPermission ?: return
                    pendingPermission = null
                    val identified = ArGlassesCatalog.identify(device)?.let { ConnectedGlasses(device, it) } ?: return
                    dispatch { listener.onPermissionResult(identified, usbManager.hasPermission(device)) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> scan()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        appContext.registerReceiver(receiver, filter, if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
    }

    fun scan(): List<ConnectedGlasses> = usbManager.deviceList.values.mapNotNull { device ->
        ArGlassesCatalog.identify(device)?.let { ConnectedGlasses(device, it) }
    }.also { result -> dispatch { listener.onDevicesChanged(result) } }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        pendingPermission = device
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    fun open(device: UsbDevice, feature: SessionFeature = SessionFeature.ALL): ArGlassesSession {
        require(usbManager.hasPermission(device)) { "USB permission has not been granted" }
        val model = requireNotNull(ArGlassesCatalog.identify(device)) { "Unsupported AR glasses" }
        session?.close()
        val driverSession = GlassesDriverRegistry.driver(model).open(usbManager, device, model, feature, executor, listener)
        return ArGlassesSession(device, model, driverSession).also { session = it }
    }

    fun externalDisplayResolutions(): List<DisplayResolution> =
        appContext.getSystemService(DisplayManager::class.java).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { display ->
                val mode = display.mode
                DisplayResolution(display.displayId, display.name, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
            }

    private fun dispatch(block: () -> Unit) = executor.execute(block)

    override fun close() {
        session?.close()
        session = null
        appContext.unregisterReceiver(receiver)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}

class ArGlassesSession internal constructor(
    val device: UsbDevice,
    val model: GlassesModel,
    private val delegate: DriverSession,
) : Closeable {
    fun queryDisplayMode(): DisplayMode? = delegate.queryDisplayMode()
    fun setDisplayMode(mode: DisplayMode): Boolean = delegate.setDisplayMode(mode)
    override fun close() = delegate.close()
}
