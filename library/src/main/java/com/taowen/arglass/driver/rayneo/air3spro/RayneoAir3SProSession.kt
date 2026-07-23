package com.taowen.arglass.driver.rayneo.air3spro

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class RayneoAir3SProSession(usbManager:UsbManager,device:UsbDevice,private val model:GlassesModel,
    private val executor:Executor,private val listener:ArGlassesListener):DriverSession {
    private data class Port(val intf:UsbInterface,val input:UsbEndpoint,val output:UsbEndpoint?)
    private val running=AtomicBoolean(true)
    private val usb=NativeUsbDeviceSession(usbManager,device)
    private val ports=(0 until device.interfaceCount).map(device::getInterface).mapNotNull{f->
        val endpoints=(0 until f.endpointCount).map(f::getEndpoint);val input=endpoints.firstOrNull{it.direction==UsbConstants.USB_DIR_IN}?:return@mapNotNull null
        Port(f,input,endpoints.firstOrNull{it.direction==UsbConstants.USB_DIR_OUT})}
    private val workers=mutableListOf<Thread>()
    init {check(ports.isNotEmpty()){ "${model.displayName} has no HID input endpoint"};ports.forEach{check(usb.claim(it.intf)){"Cannot claim RayNeo interface ${it.intf.id}"}}
        val start=ByteArray(64).also{it[0]=0x66;it[1]=1};ports.mapNotNull(Port::output).forEach{usb.transfer(it,start,500)}
        ports.forEach{p->workers+=Thread({read(p)},"rayneo-imu-${p.intf.id}").also(Thread::start)};status("${model.displayName} IMU 已请求")}
    private fun read(port:Port){val bytes=ByteArray(maxOf(64,port.input.maxPacketSize));while(running.get()){
        val n=usb.transfer(port.input,bytes,750);if(n>=58)decode(bytes)?.let{sample->executor.execute{listener.onImuSample(sample)}}}}
    private fun decode(p:ByteArray):ImuSample?{if(p[0]!=0x99.toByte()||p[1]!=0x65.toByte())return null;val b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val accel=floatArrayOf(b.getFloat(4),b.getFloat(8),b.getFloat(12));val gyro=floatArrayOf(b.getFloat(16),b.getFloat(20),b.getFloat(24));val mag=floatArrayOf(b.getFloat(32),b.getFloat(36),b.getFloat(52))
        if((accel+gyro+mag).any{!it.isFinite()})return null;return ImuSample((b.getInt(40).toLong()and 0xffffffffL)*1000L,accel,gyro,mag,b.getFloat(28),1)}
    private fun status(s:String)=executor.execute{listener.onStatus(s)}
    override fun close(){if(!running.compareAndSet(true,false))return;workers.forEach(Thread::interrupt);workers.forEach{if(Thread.currentThread()!==it)it.join(1200)};ports.forEach{usb.release(it.intf)};usb.close()}
}
