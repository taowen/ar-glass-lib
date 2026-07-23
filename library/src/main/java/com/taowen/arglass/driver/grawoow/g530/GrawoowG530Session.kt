package com.taowen.arglass.driver.grawoow.g530

import android.hardware.usb.*
import com.taowen.arglass.*
import com.taowen.arglass.driver.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class GrawoowG530Session(usbManager:UsbManager,private val mcuDevice:UsbDevice,ovDevice:UsbDevice?,private val model:GlassesModel,
    feature:SessionFeature,private val executor:Executor,private val listener:ArGlassesListener):DriverSession {
    private val running=AtomicBoolean(true)
    private val mcu=NativeUsbDeviceSession(usbManager,mcuDevice)
    private val imuEnabled=feature==SessionFeature.IMU||feature==SessionFeature.ALL
    private val ovDevice=if(imuEnabled)requireNotNull(ovDevice){"Grawoow OV580 IMU device is not connected"}else null
    private val ov=ovDevice?.let{NativeUsbDeviceSession(usbManager,it)}
    private val imuPort=ovDevice?.let(::findImuPort)
    private val worker:Thread?
    init { check(mcu.claim(mcuDevice.getInterface(0))){"Cannot claim Grawoow MCU"}
        if(imuEnabled){check(requireNotNull(ov).claim(requireNotNull(imuPort).first)){"Cannot claim Grawoow IMU"};worker=Thread(::readImu,"grawoow-g530-imu").also(Thread::start)}else worker=null }
    @Synchronized override fun queryDisplayMode()=command(0x8007)?.firstOrNull()?.let{if(it.toInt()==1)DisplayMode.FULL_SBS_3D else DisplayMode.MIRROR_2D}
    @Synchronized override fun setDisplayMode(mode:DisplayMode):Boolean { val v=when(mode){DisplayMode.MIRROR_2D->0;DisplayMode.FULL_SBS_3D->1;else->return false};return command(0x8008,byteArrayOf(v.toByte()))!=null }
    private fun command(id:Int,data:ByteArray=byteArrayOf()):ByteArray?{
        val packet=byteArrayOf(0xaa.toByte(),0xbb.toByte(),(id shr 8).toByte(),id.toByte(),0,data.size.toByte())+data
        val request=packet+byteArrayOf(packet.drop(2).sumOf{it.toInt()and 255}.toByte())
        if(mcu.control(0x21,9,0x201,0,request,1000)<0)return null
        val response=ByteArray(256);val count=mcu.control(0xa1,1,0x102,0,response,1000)
        if(count<6||response[0]!=0xaa.toByte()||response[1]!=0xbb.toByte()||(response[2].toInt()and 255)!=(id shr 8)||(response[3].toInt()and 255)!=(id and 255)||response[4].toInt()!=0)return null
        return response.copyOfRange(6,(6+(response[5].toInt()and 255)).coerceAtMost(count))
    }
    private fun readImu(){status("${model.displayName} IMU 已连接");val bytes=ByteArray(128);val endpoint=requireNotNull(imuPort).second
        val start=System.nanoTime();while(running.get()){val n=requireNotNull(ov).transfer(endpoint,bytes,250);if(n>=100)decode(bytes,start)?.let{sample->executor.execute{listener.onImuSample(sample)}}}}
    private fun decode(p:ByteArray,start:Long):ImuSample?{val b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);val g=(Math.PI/180.0/16.4).toFloat();val a=9.81f/16384f
        val gx=b.getInt(0x3c)*g;val gy=b.getInt(0x40)*g;val gz=b.getInt(0x44)*g;val ax=b.getInt(0x58)*a;val ay=b.getInt(0x5c)*a;val az=b.getInt(0x60)*a
        return ImuSample(System.nanoTime()-start,floatArrayOf(-ay,-az,ax),floatArrayOf(-gy,-gz,gx),null,Float.NaN,1)}
    private fun status(s:String)=executor.execute{listener.onStatus(s)}
    override fun close(){if(!running.compareAndSet(true,false))return;worker?.interrupt();if(Thread.currentThread()!==worker)worker?.join(1200);imuPort?.first?.let{ov?.release(it)};ov?.close();mcu.release(mcuDevice.getInterface(0));mcu.close()}
    private companion object {fun findImuPort(d:UsbDevice):Pair<UsbInterface,UsbEndpoint>{for(i in 0 until d.interfaceCount){val f=d.getInterface(i);for(e in 0 until f.endpointCount){val p=f.getEndpoint(e);if(p.address==0x89)return f to p}};error("Grawoow IMU endpoint 0x89 not found")}}
}
