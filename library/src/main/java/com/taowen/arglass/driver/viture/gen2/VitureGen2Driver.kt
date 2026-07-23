package com.taowen.arglass.driver.viture.gen2

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.util.concurrent.Executor

internal object VitureGen2Driver:GlassesDriver {
    override val id="viture_gen2"
    private val models=mapOf(0x1131 to "Luma",0x1121 to "Luma Pro",0x1141 to "Luma Pro",0x1151 to "Luma Cyber")
    override fun identify(device:UsbDevice):GlassesModel?=models[device.productId]?.takeIf{device.vendorId==0x35ca}?.let{name->
        GlassesModel("viture_${device.productId.toString(16)}","VITURE",name,device.vendorId,device.productId,
            setOf(GlassesCapability.IMU,GlassesCapability.DISPLAY_RESOLUTION),emptySet(),id)}
    override fun open(usbManager:UsbManager,device:UsbDevice,model:GlassesModel,feature:SessionFeature,
                      executor:Executor,listener:ArGlassesListener):DriverSession{
        require(feature==SessionFeature.IMU||feature==SessionFeature.ALL){"Open VITURE Gen2 protocol currently exposes IMU only"}
        return VitureGen2Session(usbManager,device,model,executor,listener)
    }
}
