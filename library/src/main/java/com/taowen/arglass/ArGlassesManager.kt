package com.taowen.arglass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.Display
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class ConnectedGlasses(val device: UsbDevice, val model: GlassesModel)
data class DisplayResolution(val displayId: Int, val name: String, val width: Int, val height: Int, val refreshRate: Float)

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

    fun open(device: UsbDevice): ArGlassesSession {
        require(usbManager.hasPermission(device)) { "USB permission has not been granted" }
        val model = requireNotNull(ArGlassesCatalog.identify(device)) { "Unsupported AR glasses" }
        session?.close()
        return ArGlassesSession(usbManager, device, model, executor, listener).also { session = it }
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
    usbManager: UsbManager,
    val device: UsbDevice,
    val model: GlassesModel,
    private val executor: Executor,
    private val listener: ArGlassesListener,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val connection: UsbDeviceConnection = requireNotNull(usbManager.openDevice(device)) { "Cannot open USB device" }
    private val imuInterface = findInterface(model.imuInterface)
    private val mcuInterface = findInterface(model.mcuInterface)
    private val imuIn = requireEndpoint(imuInterface, UsbConstants.USB_DIR_IN)
    private val imuOut = requireEndpoint(imuInterface, UsbConstants.USB_DIR_OUT)
    private val mcuIn = requireEndpoint(mcuInterface, UsbConstants.USB_DIR_IN)
    private val mcuOut = requireEndpoint(mcuInterface, UsbConstants.USB_DIR_OUT)
    private val worker = Thread(::runImu, "ar-glass-imu")
    private var requestId = 1

    init {
        check(connection.claimInterface(mcuInterface, true)) { "Cannot claim XREAL MCU interface ${mcuInterface.id}" }
        check(connection.claimInterface(imuInterface, true)) { "Cannot claim XREAL IMU interface ${imuInterface.id}" }
        worker.start()
    }

    @Synchronized
    fun queryDisplayMode(): DisplayMode? {
        val response = mcuCommand(0x07)
        val value = when {
            response.size >= 27 -> ByteBuffer.wrap(response, 23, 4).order(ByteOrder.LITTLE_ENDIAN).int
            response.size >= 24 -> response[23].toInt() and 0xff
            else -> return null
        }
        return DisplayMode.fromWireValue(value)
    }

    @Synchronized
    fun setDisplayMode(mode: DisplayMode): Boolean = mcuCommand(0x08, byteArrayOf(mode.wireValue.toByte())).let {
        it.size >= 23 && (it[22].toInt() and 0xff) == 0
    }

    private fun runImu() {
        try {
            status("正在初始化 ${model.displayName} IMU")
            imuCommand(0x19, byteArrayOf(0))
            readCalibrationBestEffort()
            imuCommand(0x1a)
            val started = imuCommand(0x19, byteArrayOf(1))
            if (started.isEmpty()) status("IMU 启动命令未收到响应；继续被动监听") else status("IMU 已启动")
            val packet = ByteArray(maxOf(64, imuIn.maxPacketSize))
            while (running.get()) {
                val length = connection.bulkTransfer(imuIn, packet, packet.size, 750)
                if (length != 64) continue
                decodeSample(packet.copyOf(length))?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Throwable) {
            if (running.get()) status("IMU 会话失败：${error.message}")
        }
    }

    private fun readCalibrationBestEffort() {
        val lengthResponse = imuCommand(0x14)
        val total = if (lengthResponse.size >= 13)
            ByteBuffer.wrap(lengthResponse, 9, 4).order(ByteOrder.LITTLE_ENDIAN).int else 0
        if (total !in 1..1_000_000) {
            status("未取得有效校准长度，使用眼镜逐帧缩放参数")
            return
        }
        var received = 0
        while (running.get() && received < total) {
            val part = imuCommand(0x15)
            if (part.size <= 9) break
            received += part.size - 9
        }
        status("IMU 校准数据：$received / $total bytes")
    }

    private fun imuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray = synchronized(connection) {
        val packet = NativeBridge.makeImuCommand(command, payload)
        if (connection.bulkTransfer(imuOut, packet, packet.size, 500) != packet.size) return@synchronized byteArrayOf()
        readMatching(imuIn, 0xaa, command)
    }

    private fun mcuCommand(command: Int, payload: ByteArray = byteArrayOf()): ByteArray = synchronized(connection) {
        val id = requestId++
        val packet = NativeBridge.makeMcuCommand(command, id, payload)
        if (connection.bulkTransfer(mcuOut, packet, packet.size, 500) != packet.size) return@synchronized byteArrayOf()
        readMatching(mcuIn, 0xfd, command, id)
    }

    private fun readMatching(endpoint: UsbEndpoint, magic: Int, command: Int, id: Int? = null): ByteArray {
        val packet = ByteArray(maxOf(64, endpoint.maxPacketSize))
        repeat(10) {
            val length = connection.bulkTransfer(endpoint, packet, packet.size, 300)
            if (length < 8 || (packet[0].toInt() and 0xff) != magic) return@repeat
            val responseCommand = if (magic == 0xfd && length >= 17)
                (packet[15].toInt() and 0xff) or ((packet[16].toInt() and 0xff) shl 8) else packet[7].toInt() and 0xff
            val responseId = if (magic == 0xfd && length >= 11)
                ByteBuffer.wrap(packet, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int else null
            if (responseCommand == command && (id == null || responseId == id)) return packet.copyOf(length)
        }
        return byteArrayOf()
    }

    private fun decodeSample(packet: ByteArray): ImuSample? {
        val values = NativeBridge.decodeImuReport(packet) ?: return null
        val timestamp = ByteBuffer.wrap(packet, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return ImuSample(
            timestamp,
            floatArrayOf(values[1], values[2], values[3]),
            floatArrayOf(values[4], values[5], values[6]),
            floatArrayOf(values[7], values[8], values[9]),
            values[10],
            values[11].toInt(),
        )
    }

    private fun findInterface(id: Int): UsbInterface = (0 until device.interfaceCount)
        .map(device::getInterface).first { it.id == id }

    private fun requireEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint =
        (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).first { it.direction == direction }

    private fun status(message: String) = executor.execute { listener.onStatus(message) }

    override fun close() {
        if (!running.getAndSet(false)) return
        worker.interrupt()
        if (Thread.currentThread() !== worker) worker.join(1200)
        connection.releaseInterface(imuInterface)
        connection.releaseInterface(mcuInterface)
        connection.close()
    }
}
