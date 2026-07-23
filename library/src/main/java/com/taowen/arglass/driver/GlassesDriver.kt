package com.taowen.arglass.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.viture.beast.VitureBeastDriver
import com.taowen.arglass.driver.luci.LuciDriver
import com.taowen.arglass.driver.rokid.air.RokidAirDriver
import com.taowen.arglass.driver.xreal.air2ultra.XrealAir2UltraDriver
import com.taowen.arglass.driver.xreal.air.XrealAirDriver
import com.taowen.arglass.driver.xreal.air2.XrealAir2Driver
import com.taowen.arglass.driver.xreal.air2pro.XrealAir2ProDriver
import com.taowen.arglass.driver.xreal.xbxa01.XrealXbxA01Driver
import com.taowen.arglass.driver.xreal.xbxa01plus.XrealXbxA01PlusDriver
import com.taowen.arglass.driver.xreal.ones.XrealOneSDriver
import com.taowen.arglass.driver.xreal.one.XrealOneDriver
import com.taowen.arglass.driver.xreal.onepro.XrealOneProDriver
import java.io.Closeable
import java.util.concurrent.Executor

internal interface GlassesDriver {
    val id: String
    fun identify(device: UsbDevice): GlassesModel?
    fun open(
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession
}

internal interface DriverSession : Closeable {
    fun queryDisplayMode(): DisplayMode? = error("Display-mode query is not supported")
    fun setDisplayMode(mode: DisplayMode): Boolean = error("Display-mode control is not supported")
}

internal object GlassesDriverRegistry {
    private val drivers: List<GlassesDriver> = listOf(
        XrealAirDriver,
        XrealAir2Driver,
        XrealAir2ProDriver,
        XrealAir2UltraDriver,
        XrealXbxA01Driver,
        XrealXbxA01PlusDriver,
        XrealOneProDriver,
        XrealOneDriver,
        XrealOneSDriver,
        RokidAirDriver,
        VitureBeastDriver,
        LuciDriver,
    )

    fun identify(device: UsbDevice): GlassesModel? = drivers.firstNotNullOfOrNull { it.identify(device) }

    fun driver(model: GlassesModel): GlassesDriver = drivers.first { it.id == model.driverId }
}
