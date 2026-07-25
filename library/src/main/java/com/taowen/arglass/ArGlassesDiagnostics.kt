package com.taowen.arglass

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.view.Display
import com.taowen.arglass.driver.xreal.onefamily.XrealOneNcmTransport
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArGlassesDiagnostics {
    private const val USB_FILE = "usb-transfers.bin"
    private const val XREAL_ONE_DP_FILE = "xreal-one-dp-rpc.bin"
    private const val CRASH_FILE = "crashes.bin"
    private const val EVENTS_FILE = "events.txt"
    private const val SUMMARY_FILE = "diagnostics.txt"
    private const val FORMAT_FILE = "formats.txt"
    private const val USB_MAGIC = 0x41525553
    private const val CRASH_MAGIC = 0x41524352
    private val exportFiles = listOf(
        SUMMARY_FILE,
        EVENTS_FILE,
        USB_FILE,
        XREAL_ONE_DP_FILE,
        CRASH_FILE,
        FORMAT_FILE,
    )
    private val lock = Any()
    @Volatile private var directory: File? = null

    internal fun initialize(context: Context) {
        if (directory != null) return
        synchronized(lock) {
            if (directory == null) directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
            val dir = requireNotNull(directory)
            NativeBridge.configureUsbDiagnostics(File(dir, USB_FILE).absolutePath)
            NativeBridge.configureXrealOneDpDiagnostics(File(dir, XREAL_ONE_DP_FILE).absolutePath)
        }
    }

    fun defaultZipFileName(): String =
        "ar-glass-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    internal fun recordUsb(
        device: UsbDevice,
        operation: Int,
        addressOrRequestType: Int,
        request: Int,
        value: Int,
        index: Int,
        result: Int,
        payload: ByteArray,
    ) = appendBinary(USB_FILE) { output ->
        output.writeInt(USB_MAGIC)
        output.writeShort(1)
        output.writeLong(System.currentTimeMillis())
        output.writeInt(device.vendorId)
        output.writeInt(device.productId)
        output.writeByte(operation)
        output.writeInt(addressOrRequestType)
        output.writeInt(request)
        output.writeInt(value)
        output.writeInt(index)
        output.writeInt(result)
        output.writeInt(payload.size)
        output.write(payload)
    }

    internal fun recordPermission(device: UsbDevice, requested: Boolean, granted: Boolean) {
        recordEvent(
            "usb permission ${if (requested) "request" else "result"} " +
                "vid=0x%04x pid=0x%04x granted=%s".format(device.vendorId, device.productId, granted),
        )
        recordUsb(device, if (requested) 3 else 4, 0, 0, 0, 0, if (granted) 1 else 0, byteArrayOf())
    }

    internal fun recordEvent(message: String) {
        val dir = directory ?: return
        val line = "%s %s\n".format(timestampText(System.currentTimeMillis()), message)
        synchronized(lock) { File(dir, EVENTS_FILE).appendText(line, Charsets.UTF_8) }
    }

    fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        initialize(context)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().toByteArray(Charsets.UTF_8)
        appendBinary(CRASH_FILE) { output ->
            output.writeInt(CRASH_MAGIC)
            output.writeShort(1)
            output.writeLong(System.currentTimeMillis())
            writeBytes(output, thread.name.toByteArray(Charsets.UTF_8))
            writeBytes(output, trace)
        }
        recordEvent("uncaught crash thread=${thread.name} error=${error.javaClass.name}: ${error.message ?: ""}")
    }

    fun exportZip(context: Context, targetUri: Uri): List<String> {
        initialize(context)
        recordEvent("export diagnostics zip uri=$targetUri")
        val appContext = context.applicationContext
        val dir = requireNotNull(directory)
        val generated = mapOf(
            SUMMARY_FILE to buildSummary(appContext),
            FORMAT_FILE to formatDescription(),
        )
        val resolver = appContext.contentResolver
        resolver.openOutputStream(targetUri, "w").use { output ->
            requireNotNull(output) { "Cannot open diagnostic zip target" }
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                exportFiles.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    val generatedText = generated[name]
                    if (generatedText != null) {
                        zip.write(generatedText.toByteArray(Charsets.UTF_8))
                    } else {
                        val source = File(dir, name).apply { if (!exists()) createNewFile() }
                        source.inputStream().use { input -> input.copyTo(zip) }
                    }
                    zip.closeEntry()
                }
            }
        }
        recordEvent("export diagnostics zip completed entries=${exportFiles.joinToString()}")
        return exportFiles
    }

    private fun buildSummary(context: Context): String {
        val now = System.currentTimeMillis()
        val usbManager = context.getSystemService(UsbManager::class.java)
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val usbDevices = usbManager.deviceList.values.toList().sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceName },
        )
        val identified = usbDevices.mapNotNull { device ->
            ArGlassesCatalog.identify(device)?.let { model -> device to model }
        }
        val hasXrealOneFamily = identified.any { (_, model) -> model.id in XREAL_ONE_FAMILY_IDS } ||
            usbDevices.any(XrealEyeCameraCatalog::identifyOneFamilyMain)
        val networkReady = connectivityManager?.let(XrealOneNcmTransport::findNetwork) != null
        val dpState = if (hasXrealOneFamily || networkReady) runCatching {
            XrealOneDpDiagnostics.readCurrentState(context, connectTimeoutMs = 1_200, readTimeoutMs = 600)
        } else null

        return buildString {
            appendLine("AR Glass Diagnostics")
            appendLine("generatedAt=${timestampText(now)}")
            appendLine("package=${context.packageName}")
            appendLine("appVersion=${appVersion(context)}")
            appendLine()
            appendLine("[Android]")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("product=${Build.PRODUCT}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("fingerprint=${Build.FINGERPRINT}")
            appendLine()
            appendLine("[Recognized glasses]")
            if (identified.isEmpty()) {
                appendLine("none")
            } else {
                identified.forEach { (device, model) ->
                    appendLine(
                        "0x%04x:0x%04x device=%s id=%s name=%s capabilities=%s profiles=%s".format(
                            device.vendorId,
                            device.productId,
                            device.deviceName,
                            model.id,
                            model.displayName,
                            model.capabilities.joinToString("|"),
                            model.supportedDisplayProfiles.joinToString("|") {
                                "${it.id}:${it.width}x${it.height}@${it.refreshRateHz}:${it.layout}"
                            }.ifBlank { "none" },
                        ),
                    )
                }
            }
            appendLine()
            appendLine("[XREAL One DP]")
            appendLine("networkReady=$networkReady")
            appendLine("host=${XrealOneNcmTransport.GLASSES_HOST}")
            appendLine("controlPort=${XrealOneNcmTransport.CONTROL_PORT}")
            appendLine("imuPort=${XrealOneNcmTransport.IMU_PORT}")
            appendLine("rgbVideoPort=${XrealOneNcmTransport.RGB_VIDEO_PORT}")
            appendLine("hasOneFamilyUsb=$hasXrealOneFamily")
            dpState?.onSuccess { state ->
                appendLine("readCurrentState=ok")
                appendLine("edid=${state.edid}")
                appendLine("inputMode=${state.inputMode}")
                appendLine("stateNetworkReady=${state.networkReady}")
                if (state.notes.isNotEmpty()) appendLine("notes=${state.notes.joinToString("; ")}")
            }?.onFailure { error ->
                appendLine("readCurrentState=failed")
                appendLine("error=${error.javaClass.simpleName}: ${error.message ?: ""}")
            } ?: appendLine("readCurrentState=skipped")
            appendLine()
            appendLine("[USB devices]")
            if (usbDevices.isEmpty()) appendLine("none")
            usbDevices.forEach { device -> appendUsbDevice(device) }
            appendLine()
            appendLine("[Android displays]")
            displayManager.getDisplays().forEach { display -> appendDisplay(display) }
            appendLine()
            appendLine("[Files]")
            exportFiles.filter { it != SUMMARY_FILE && it != FORMAT_FILE }.forEach { name ->
                val file = File(requireNotNull(directory), name)
                appendLine("$name bytes=${if (file.exists()) file.length() else 0}")
            }
        }
    }

    private fun StringBuilder.appendUsbDevice(device: UsbDevice) {
        appendLine(
            "deviceName=${device.deviceName} vid=0x%04x pid=0x%04x class=%d subclass=%d protocol=%d ifaceCount=%d manufacturer=%s product=%s serial=%s permissionName=%s".format(
                device.vendorId,
                device.productId,
                device.deviceClass,
                device.deviceSubclass,
                device.deviceProtocol,
                device.interfaceCount,
                safe { device.manufacturerName },
                safe { device.productName },
                safe { device.serialNumber },
                device.deviceName,
            ),
        )
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            appendLine(
                "  interface[$i] id=${intf.id} class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}",
            )
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                appendLine(
                    "    endpoint[$e] address=0x%02x attributes=0x%02x direction=%d type=%d maxPacket=%d interval=%d".format(
                        endpoint.address,
                        endpoint.attributes,
                        endpoint.direction,
                        endpoint.type,
                        endpoint.maxPacketSize,
                        endpoint.interval,
                    ),
                )
            }
        }
    }

    private fun StringBuilder.appendDisplay(display: Display) {
        val mode = display.mode
        val category = if (display.displayId == Display.DEFAULT_DISPLAY) "default" else "external-or-virtual"
        appendLine(
            "displayId=${display.displayId} category=$category name=${display.name} current=${mode.physicalWidth}x${mode.physicalHeight}@${"%.2f".format(Locale.US, mode.refreshRate)}Hz modeId=${mode.modeId}",
        )
        display.supportedModes.forEach { supported ->
            appendLine(
                "  supported modeId=${supported.modeId} ${supported.physicalWidth}x${supported.physicalHeight}@${"%.2f".format(Locale.US, supported.refreshRate)}Hz",
            )
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "unknown"}(${packageVersionCode(info)})"
    }.getOrElse { "unknown(${it.javaClass.simpleName}: ${it.message ?: ""})" }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun formatDescription(): String = """
        Diagnostic zip entries:
        - diagnostics.txt: generated device, display, USB, recognized-glasses, and XREAL One DP state summary.
        - events.txt: app-generated status, permission, crash, and export events. This is not logcat.
        - usb-transfers.bin: ARUS v1 append-only USB permission and raw control/bulk/interrupt/UVC transfer records.
        - xreal-one-dp-rpc.bin: ARDP v1 append-only XREAL One-family TCP DP RPC records for 169.254.2.1:52999.
        - crashes.bin: ARCR v1 append-only uncaught exception records.

        ARUS v1:
        magic u32 "ARUS"; version u16; timestampMs u64; vid u32; pid u32; operation u8;
        addressOrRequestType u32; request u32; value u32; index u32; result u32;
        payloadLength u32; payload bytes.
        operation: 1 device-to-host, 2 host-to-device, 3 permission request, 4 permission result.

        ARDP v1:
        magic u32 "ARDP"; version u16; timestampMs u64; hostLength u16; host bytes; port u32;
        operation u8; command u32; sequence u32; result u32; payloadLength u32; payload bytes.
        operation: 1 connect start, 2 connect success, 3 host-to-glasses request,
        4 glasses-to-host response or async frame, 6 error. Payload is raw DP RPC frame for
        operations 3/4 and UTF-8 error text for operation 6.

        ARCR v1:
        magic u32 "ARCR"; version u16; timestampMs u64; threadNameLength u32; threadName bytes;
        stackTraceLength u32; stackTrace UTF-8 bytes.
    """.trimIndent() + "\n"

    private fun appendBinary(name: String, write: (DataOutputStream) -> Unit) {
        val dir = directory ?: return
        synchronized(lock) {
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(dir, name), true))).use(write)
        }
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun timestampText(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date(timestamp))

    private inline fun <T> safe(block: () -> T?): String =
        runCatching { block()?.toString() ?: "null" }.getOrElse { "error:${it.javaClass.simpleName}" }

    private val XREAL_ONE_FAMILY_IDS = setOf("xreal_one", "xreal_one_pro", "xreal_one_s")
}
