package com.taowen.arglass.driver.viture.gen2

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import com.taowen.arglass.driver.viture.beast.VitureBeastProtocol
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class VitureGen2Session(usbManager:UsbManager,device:UsbDevice,private val model:GlassesModel,
    private val executor:Executor,private val listener:ArGlassesListener):DriverSession {
    private data class Port(val intf:UsbInterface,val input:UsbEndpoint?,val output:UsbEndpoint?)
    private val running=AtomicBoolean(true);private val usb=NativeUsbDeviceSession(usbManager,device)
    private val ports=(0 until device.interfaceCount).map(device::getInterface).filter{it.interfaceClass==UsbConstants.USB_CLASS_HID}.map{f->
        val e=(0 until f.endpointCount).map(f::getEndpoint);Port(f,e.firstOrNull{it.direction==UsbConstants.USB_DIR_IN},e.firstOrNull{it.direction==UsbConstants.USB_DIR_OUT})}.filter{it.input!=null||it.output!=null}
    private val workers=mutableListOf<Thread>()
    init{check(ports.isNotEmpty()){ "${model.displayName} has no Gen2 HID interface"};ports.forEach{check(usb.claim(it.intf)){"Cannot claim VITURE interface ${it.intf.id}"}}
        val start=VitureBeastProtocol.command(0x0301,byteArrayOf(2,2));ports.forEach{p->p.output?.let{usb.transfer(it,start,500)}?:usb.control(0x21,9,0x0200,p.intf.id,start,500)}
        ports.mapNotNull{it.input}.forEach{input->workers+=Thread({read(input)},"viture-gen2-${input.address}").also(Thread::start)};status("${model.displayName} RAW IMU 已请求（120 Hz）")}
    private fun read(input:UsbEndpoint){val b=ByteArray(maxOf(64,input.maxPacketSize));while(running.get()){val n=usb.transfer(input,b,750);if(n>0)VitureBeastProtocol.decodeImu(b,n)?.let{s->executor.execute{listener.onImuSample(s)}}}}
    private fun status(s:String)=executor.execute{listener.onStatus(s)}
    override fun close(){if(!running.compareAndSet(true,false))return;workers.forEach(Thread::interrupt);workers.forEach{if(Thread.currentThread()!==it)it.join(1200)};ports.forEach{usb.release(it.intf)};usb.close()}
}
