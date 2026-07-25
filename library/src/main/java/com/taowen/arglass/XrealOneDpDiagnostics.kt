package com.taowen.arglass

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import com.taowen.arglass.driver.xreal.onefamily.XrealOneNcmTransport

data class XrealOneDpState(
    val edid: Int,
    val inputMode: Int,
    val networkReady: Boolean,
    val host: String,
    val port: Int,
    val notes: List<String> = emptyList(),
)

object XrealOneDpDiagnostics {
    @JvmStatic
    @JvmOverloads
    fun readCurrentState(
        context: Context,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 800,
    ): XrealOneDpState {
        val appContext = context.applicationContext
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val networkReady = connectivityManager?.let(XrealOneNcmTransport::findNetwork) != null
        val notes = mutableListOf<String>()
        return XrealOneNcmTransport.withBoundNetwork(connectivityManager, notes::add) {
            val edid = NativeBridge.xrealOneDpGetCurrentEdid(
                XrealOneNcmTransport.GLASSES_HOST,
                XrealOneNcmTransport.CONTROL_PORT,
                connectTimeoutMs,
                readTimeoutMs,
            )
            val inputMode = NativeBridge.xrealOneDpGetInputMode(
                XrealOneNcmTransport.GLASSES_HOST,
                XrealOneNcmTransport.CONTROL_PORT,
                connectTimeoutMs,
                readTimeoutMs,
            )
            XrealOneDpState(
                edid = edid,
                inputMode = inputMode,
                networkReady = networkReady,
                host = XrealOneNcmTransport.GLASSES_HOST,
                port = XrealOneNcmTransport.CONTROL_PORT,
                notes = notes.toList(),
            )
        }
    }

    @JvmStatic
    fun describeAvailability(context: Context): String {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(UsbManager::class.java)
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val usbDevices = usbManager.deviceList.values.toList()
        val visibleUsb = usbDevices.joinToString { "0x%04x:0x%04x".format(it.vendorId, it.productId) }
            .ifBlank { "无" }
        val hasOneMain = usbDevices.any(XrealEyeCameraCatalog::identifyOneFamilyMain)
        val networkReady = connectivityManager?.let(XrealOneNcmTransport::findNetwork) != null
        return buildString {
            append("XREAL One 主设备：").append(if (hasOneMain) "已发现" else "未发现")
            append("\nUSB Ethernet Network：").append(if (networkReady) "已就绪" else "未就绪")
            append("\nDP RPC：")
            append(XrealOneNcmTransport.GLASSES_HOST)
            append(':')
            append(XrealOneNcmTransport.CONTROL_PORT)
            append("\n可见 USB：").append(visibleUsb)
        }
    }
}
