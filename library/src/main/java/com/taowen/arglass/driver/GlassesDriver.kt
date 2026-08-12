package com.taowen.arglass.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.viture.beast.VitureBeastDriver
import com.taowen.arglass.driver.goovis.g3family.GoovisG3FamilyDriver
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
import com.taowen.arglass.driver.xreal.light.XrealLightDriver
import com.taowen.arglass.driver.grawoow.g530.GrawoowG530Driver
import com.taowen.arglass.driver.rayneo.airfamily.RayneoAirFamilyDriver
import com.taowen.arglass.driver.rayneo.gtfamily.RayneoGtFamilyDriver
import com.taowen.arglass.driver.viture.gen2.VitureGen2Driver
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

    fun open(
        connectivityManager: ConnectivityManager?,
        usbManager: UsbManager,
        device: UsbDevice,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = open(usbManager, device, model, feature, executor, listener)
}

internal interface CompositeGlassesDriver : GlassesDriver {
    fun companionDevices(allDevices: Collection<UsbDevice>, primary: UsbDevice): List<UsbDevice>
    fun openComposite(
        usbManager: UsbManager,
        devices: List<UsbDevice>,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession

    fun openComposite(
        connectivityManager: ConnectivityManager?,
        usbManager: UsbManager,
        devices: List<UsbDevice>,
        model: GlassesModel,
        feature: SessionFeature,
        executor: Executor,
        listener: ArGlassesListener,
    ): DriverSession = openComposite(usbManager, devices, model, feature, executor, listener)
}

internal interface DriverSession : Closeable {
    fun resetHostImuCalibration(): Boolean = false
    fun queryDisplayProfile(): GlassesDisplayProfile? = null
    fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean =
        error("Display profile control is not supported")
    fun isIn3d(): Boolean? = queryDisplayProfile()?.is3d
    fun switchTo3d(profile: GlassesDisplayProfile): Boolean = setDisplayProfile(profile)
    fun switchTo2d(profile: GlassesDisplayProfile): Boolean = setDisplayProfile(profile)
}

internal object GlassesDriverRegistry {
    private val drivers: List<GlassesDriver> = listOf(
        XrealAirDriver,
        XrealAir2Driver,
        XrealAir2ProDriver,
        XrealAir2UltraDriver,
        XrealLightDriver,
        GrawoowG530Driver,
        RayneoAirFamilyDriver,
        RayneoGtFamilyDriver,
        VitureGen2Driver,
        XrealXbxA01Driver,
        XrealXbxA01PlusDriver,
        XrealOneProDriver,
        XrealOneDriver,
        XrealOneSDriver,
        RokidAirDriver,
        VitureBeastDriver,
        LuciDriver,
        GoovisG3FamilyDriver,
    )

    fun identify(device: UsbDevice): GlassesModel? = drivers.firstNotNullOfOrNull { it.identify(device) }

    fun driver(model: GlassesModel): GlassesDriver = drivers.first { it.id == model.driverId }
}
