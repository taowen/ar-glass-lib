package com.taowen.arglass.driver.grawoow.g530

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.util.concurrent.Executor

internal object GrawoowG530Driver : CompositeGlassesDriver {
    override val id = "grawoow_g530"
    override fun identify(device: UsbDevice): GlassesModel? = if(device.vendorId==0x1ff7&&device.productId==0x0ff4)
        GlassesModel(id,"Grawoow / MetaVision","G530 / M53",device.vendorId,device.productId,
            setOf(GlassesCapability.IMU,GlassesCapability.DISPLAY_MODE,GlassesCapability.DISPLAY_RESOLUTION),
            setOf(DisplayMode.MIRROR_2D,DisplayMode.FULL_SBS_3D),id) else null
    override fun companionDevices(allDevices:Collection<UsbDevice>,primary:UsbDevice)=allDevices.filter{it.vendorId==0x05a9&&it.productId==0x0f87}
    override fun openComposite(usbManager:UsbManager,devices:List<UsbDevice>,model:GlassesModel,feature:SessionFeature,
                               executor:Executor,listener:ArGlassesListener):DriverSession = GrawoowG530Session(
        usbManager,devices.first{it.vendorId==0x1ff7},devices.firstOrNull{it.vendorId==0x05a9&&it.productId==0x0f87},model,feature,executor,listener)
    override fun open(usbManager:UsbManager,device:UsbDevice,model:GlassesModel,feature:SessionFeature,
                      executor:Executor,listener:ArGlassesListener):DriverSession=error("Grawoow G530 must be opened as a composite device")
}
