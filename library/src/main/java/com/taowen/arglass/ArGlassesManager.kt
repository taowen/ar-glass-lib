package com.taowen.arglass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.CompositeGlassesDriver
import com.taowen.arglass.driver.GlassesDriverRegistry
import java.io.Closeable
import java.util.concurrent.Executor

data class ConnectedGlasses(
    val device: UsbDevice,
    val model: GlassesModel,
    val devices: List<UsbDevice> = listOf(device),
)
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
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private var pendingPermission: UsbDevice? = null
    private var pendingGlasses: ConnectedGlasses? = null
    private var session: ArGlassesSession? = null
    private val diagnosticListener = object : ArGlassesListener {
        override fun onDevicesChanged(devices: List<ConnectedGlasses>) {
            ArGlassesDiagnostics.recordEvent(
                "devices changed count=${devices.size} " +
                    devices.joinToString { "0x%04x:0x%04x:%s".format(it.device.vendorId, it.device.productId, it.model.id) },
            )
            listener.onDevicesChanged(devices)
        }

        override fun onPermissionResult(device: ConnectedGlasses, granted: Boolean) {
            ArGlassesDiagnostics.recordEvent(
                "permission result model=${device.model.id} vid=0x%04x pid=0x%04x granted=$granted".format(
                    device.device.vendorId,
                    device.device.productId,
                ),
            )
            listener.onPermissionResult(device, granted)
        }

        override fun onStatus(message: String) {
            ArGlassesDiagnostics.recordEvent("status $message")
            listener.onStatus(message)
        }

        override fun onImuSample(sample: ImuSample) {
            listener.onImuSample(sample)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: pendingPermission ?: return
                    pendingPermission = null
                    val granted = usbManager.hasPermission(device)
                    ArGlassesDiagnostics.recordPermission(device, requested = false, granted = granted)
                    val glasses = pendingGlasses
                    if (granted && glasses != null) {
                        val next = glasses.devices.firstOrNull { !usbManager.hasPermission(it) }
                        if (next != null) return requestPermissionDevice(next)
                    }
                    pendingGlasses = null
                    val identified = glasses ?: ArGlassesCatalog.identify(device)?.let { ConnectedGlasses(device, it) } ?: return
                    dispatch { diagnosticListener.onPermissionResult(identified, granted && identified.devices.all(usbManager::hasPermission)) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> scan()
            }
        }
    }

    init {
        ArGlassesDiagnostics.initialize(appContext)
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        appContext.registerReceiver(receiver, filter, if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
    }

    fun scan(): List<ConnectedGlasses> = usbManager.deviceList.values.mapNotNull { device ->
        ArGlassesCatalog.identify(device)?.let { model ->
            val driver = GlassesDriverRegistry.driver(model)
            val devices = listOf(device) + if (driver is CompositeGlassesDriver)
                driver.companionDevices(usbManager.deviceList.values, device) else emptyList()
            ConnectedGlasses(device, model, devices.distinctBy(UsbDevice::getDeviceId))
        }
    }.also { result -> dispatch { diagnosticListener.onDevicesChanged(result) } }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)
    fun hasPermission(glasses: ConnectedGlasses): Boolean = glasses.devices.all(usbManager::hasPermission)

    fun requestPermission(device: UsbDevice) {
        requestPermissionDevice(device)
    }

    fun requestPermission(glasses: ConnectedGlasses) {
        pendingGlasses = glasses
        glasses.devices.firstOrNull { !usbManager.hasPermission(it) }?.let(::requestPermissionDevice)
            ?: dispatch { diagnosticListener.onPermissionResult(glasses, true) }
    }

    private fun requestPermissionDevice(device: UsbDevice) {
        pendingPermission = device
        ArGlassesDiagnostics.recordPermission(device, requested = true, granted = usbManager.hasPermission(device))
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    fun open(device: UsbDevice, feature: SessionFeature = SessionFeature.ALL): ArGlassesSession {
        require(usbManager.hasPermission(device)) { "USB permission has not been granted" }
        val model = requireNotNull(ArGlassesCatalog.identify(device)) { "Unsupported AR glasses" }
        ArGlassesDiagnostics.recordEvent(
            "open session model=${model.id} feature=$feature vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId),
        )
        session?.close()
        val driver = GlassesDriverRegistry.driver(model)
        val devices = listOf(device) + if (driver is CompositeGlassesDriver)
            driver.companionDevices(usbManager.deviceList.values, device) else emptyList()
        require(devices.all(usbManager::hasPermission)) { "USB permission has not been granted for every glasses component" }
        val driverSession = if (driver is CompositeGlassesDriver)
            driver.openComposite(connectivityManager, usbManager, devices.distinctBy(UsbDevice::getDeviceId), model, feature, executor, diagnosticListener)
        else driver.open(connectivityManager, usbManager, device, model, feature, executor, diagnosticListener)
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
    fun isIn3d(): Boolean? {
        ArGlassesDiagnostics.recordEvent("query 3d state model=${model.id}")
        return delegate.isIn3d().also {
            ArGlassesDiagnostics.recordEvent("query 3d state result model=${model.id} isIn3d=$it")
        }
    }

    fun switchTo3d(): Boolean {
        val profile = requireNotNull(model.preferred3dDisplayProfile) {
            "${model.displayName} does not declare a preferred 3D display profile"
        }
        ArGlassesDiagnostics.recordEvent("switch to 3d model=${model.id} profile=${profile.id}")
        return delegate.switchTo3d(profile).also {
            ArGlassesDiagnostics.recordEvent("switch to 3d result model=${model.id} profile=${profile.id} ok=$it")
        }
    }

    fun switchTo2d(): Boolean {
        val profile = requireNotNull(model.preferred2dDisplayProfile) {
            "${model.displayName} does not declare a preferred 2D display profile"
        }
        ArGlassesDiagnostics.recordEvent("switch to 2d model=${model.id} profile=${profile.id}")
        return delegate.switchTo2d(profile).also {
            ArGlassesDiagnostics.recordEvent("switch to 2d result model=${model.id} profile=${profile.id} ok=$it")
        }
    }

    fun queryDisplayProfile(): GlassesDisplayProfile? {
        ArGlassesDiagnostics.recordEvent("query display profile model=${model.id}")
        return delegate.queryDisplayProfile().also { ArGlassesDiagnostics.recordEvent("query display profile result model=${model.id} profile=$it") }
    }

    fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        require(model.supportedDisplayProfiles.any { it.id == profile.id }) {
            "Display profile ${profile.id} is not declared by ${model.displayName}"
        }
        ArGlassesDiagnostics.recordEvent("set display profile model=${model.id} profile=$profile")
        return delegate.setDisplayProfile(profile).also {
            ArGlassesDiagnostics.recordEvent("set display profile result model=${model.id} profile=${profile.id} ok=$it")
        }
    }
    override fun close() {
        ArGlassesDiagnostics.recordEvent("close session model=${model.id}")
        delegate.close()
    }
}
