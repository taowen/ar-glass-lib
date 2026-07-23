package com.taowen.arglass.driver.rayneo.air3spro

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.util.concurrent.Executor

internal object RayneoAir3SProDriver:GlassesDriver {
    override val id="rayneo_air_3s_pro"
    override fun identify(device:UsbDevice):GlassesModel?=if(device.vendorId==0x1bbb&&device.productId==0xaf50)
        GlassesModel(id,"RayNeo","Air 3S Pro",device.vendorId,device.productId,
            setOf(GlassesCapability.IMU,GlassesCapability.DISPLAY_RESOLUTION),emptySet(),id)else null
    override fun open(usbManager:UsbManager,device:UsbDevice,model:GlassesModel,feature:SessionFeature,
                      executor:Executor,listener:ArGlassesListener):DriverSession {
        require(feature==SessionFeature.IMU||feature==SessionFeature.ALL){"RayNeo open protocol currently exposes IMU only"}
        return RayneoAir3SProSession(usbManager,device,model,executor,listener)
    }
}
