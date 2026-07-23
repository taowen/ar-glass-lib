package com.taowen.arglass.driver.xreal.air2pro

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.GlassesDriver
import com.taowen.arglass.driver.xreal.air.model
import com.taowen.arglass.driver.xreal.airfamily.XrealAirFamilySession
import java.util.concurrent.Executor

internal object XrealAir2ProDriver : GlassesDriver {
    override val id = "xreal_air_2_pro"
    private const val VID = 0x3318
    private const val PID = 0x0432

    override fun identify(device: UsbDevice): GlassesModel? = if (device.vendorId == VID && device.productId == PID)
        model(id, "Air 2 Pro", PID) else null

    override fun open(usbManager: UsbManager, device: UsbDevice, model: GlassesModel, feature: SessionFeature,
                      executor: Executor, listener: ArGlassesListener): DriverSession =
        XrealAirFamilySession(usbManager, device, model, feature, executor, listener)
}
