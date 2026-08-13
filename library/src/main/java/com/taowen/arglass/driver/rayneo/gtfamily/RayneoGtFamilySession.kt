package com.taowen.arglass.driver.rayneo.gtfamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.driver.DriverSession
import com.taowen.arglass.driver.rayneo.airfamily.RayneoAirFamilySession
import com.taowen.arglass.driver.rayneo.airfamily.RayneoUsbProtocol
import java.util.concurrent.Executor

/** Gemini uses the common RayNeo 0x3c factory-calibration and 64-byte raw-IMU protocol. */
internal class RayneoGtFamilySession(
    usbManager: UsbManager,
    device: UsbDevice,
    model: GlassesModel,
    executor: Executor,
    listener: ArGlassesListener,
) : DriverSession by RayneoAirFamilySession(
    usbManager = usbManager,
    device = device,
    model = model,
    executor = executor,
    listener = listener,
    protocol = RayneoUsbProtocol.GEMINI,
)
