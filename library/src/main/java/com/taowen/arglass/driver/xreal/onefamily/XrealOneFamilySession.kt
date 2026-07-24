package com.taowen.arglass.driver.xreal.onefamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.taowen.arglass.ArGlassesListener
import com.taowen.arglass.DisplayMode
import com.taowen.arglass.GlassesCapability
import com.taowen.arglass.GlassesDisplayProfile
import com.taowen.arglass.GlassesModel
import com.taowen.arglass.ImuSample
import com.taowen.arglass.NativeBridge
import com.taowen.arglass.SessionFeature
import com.taowen.arglass.driver.DriverSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** GF/Gina/GS split transport. XRLinuxDriver currently exposes only the open TCP IMU path. */
internal class XrealOneFamilySession(
    private val connectivityManager: ConnectivityManager?,
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val model: GlassesModel,
    feature: SessionFeature,
    private val executor: Executor,
    private val listener: ArGlassesListener,
    private val displayModeProtocol: XrealOneDpDisplayModeProtocol,
) : DriverSession {
    private val running = AtomicBoolean(true)
    private val displayEnabled = (feature == SessionFeature.DISPLAY_MODE || feature == SessionFeature.ALL) &&
        GlassesCapability.DISPLAY_MODE in model.capabilities
    private val imuEnabled = feature == SessionFeature.IMU || feature == SessionFeature.ALL
    private val imuThread = if (imuEnabled) Thread(::readEthernetImu, "xreal-one-s-tcp-imu") else null

    init { imuThread?.start() }

    override fun queryDisplayMode(): DisplayMode? {
        if (!displayEnabled) {
            status("${model.displayName} 未声明 2D/3D 切换能力")
            return null
        }
        return readDpEdid()?.let { edid ->
            displayModeProtocol.decode(edid).also { mode ->
                status("${model.displayName} DP EDID=$edid，显示模式=${mode?.name ?: "未知"}")
            }
        }
    }

    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        if (!displayEnabled) {
            status("${model.displayName} 未声明 2D/3D 切换能力")
            return null
        }
        return readDpEdid()?.let { edid ->
            displayModeProtocol.decodeProfile(edid).also { profile ->
                status("${model.displayName} DP EDID=$edid，显示 profile=${profile?.let(::profileLabel) ?: "未知"}")
            }
        }
    }

    override fun setDisplayMode(mode: DisplayMode): Boolean {
        if (!displayEnabled) {
            status("${model.displayName} 未声明 2D/3D 切换能力")
            return false
        }
        val command = displayModeProtocol.encode(mode)
        if (command == null) {
            status("${model.displayName} 未开放 ${mode.name} 切换；仅真机验证 2D 与 Full SBS 3D")
            return false
        }
        val transportOk = runCatching {
            withXrealNcmNetwork {
                NativeBridge.xrealOneDpSetDisplayMode(DP_HOST, DP_PORT, command.edid, command.inputMode, 2_000, 1_200)
            }
        }.onFailure { error ->
            status("${model.displayName} DP ACK 未完成，继续读回 EDID 验证：${error.message}")
        }.getOrDefault(false)
        val verified = transportOk || verifyDpEdid(command.edid)
        if (verified) {
            status("${model.displayName} 已切换 DP 模式：${mode.name}，EDID=${command.edid}, input=${command.inputMode}")
        } else {
            status("${model.displayName} DP 模式切换未验证成功：${mode.name}")
        }
        return verified
    }

    override fun setDisplayProfile(profile: GlassesDisplayProfile): Boolean {
        if (!displayEnabled) {
            status("${model.displayName} 未声明显示模式切换能力")
            return false
        }
        val command = displayModeProtocol.encodeProfile(profile)
        if (command == null) {
            status("${model.displayName} 未开放 ${profileLabel(profile)} 切换")
            return false
        }
        val transportOk = runCatching {
            withXrealNcmNetwork {
                NativeBridge.xrealOneDpSetDisplayMode(DP_HOST, DP_PORT, command.edid, command.inputMode, 2_000, 1_200)
            }
        }.onFailure { error ->
            status("${model.displayName} DP ACK 未完成，继续读回 EDID 验证：${error.message}")
        }.getOrDefault(false)
        val verified = transportOk || verifyDpEdid(command.edid)
        if (verified) {
            status("${model.displayName} 已切换 DP profile：${profileLabel(profile)}")
        } else {
            status("${model.displayName} DP profile 切换未验证成功：${profileLabel(profile)}")
        }
        return verified
    }

    private fun profileLabel(profile: GlassesDisplayProfile): String =
        "${profile.width}×${profile.height}@${profile.refreshRateHz}Hz ${profile.layout}"

    private fun readEthernetImu() {
        var handle = 0L
        try {
            status("正在连接 ${model.displayName} USB Ethernet IMU")
            handle = withXrealNcmNetwork {
                NativeBridge.createXrealOneTcpImuSession(IMU_HOST, IMU_PORT, 2_000, 500)
            }
            status("${model.displayName} IMU 已连接 $IMU_HOST:$IMU_PORT")
            while (running.get()) {
                NativeBridge.xrealOneReadImu(handle)?.let(::decodeNativeImu)
                    ?.let { sample -> executor.execute { listener.onImuSample(sample) } }
            }
        } catch (error: Exception) {
            if (running.get()) status("${model.displayName} Ethernet IMU 不可达：${error.message}")
        } finally {
            if (handle != 0L) NativeBridge.closeXrealOneTcpImuSession(handle)
        }
    }

    private fun decodeNativeImu(sample: ByteArray): ImuSample? {
        if (sample.size < NATIVE_IMU_SAMPLE_SIZE) return null
        val buffer = ByteBuffer.wrap(sample).order(ByteOrder.LITTLE_ENDIAN)
        return ImuSample(
            buffer.getLong(0),
            floatArrayOf(buffer.getFloat(8), buffer.getFloat(12), buffer.getFloat(16)),
            floatArrayOf(buffer.getFloat(20), buffer.getFloat(24), buffer.getFloat(28)),
            null,
            Float.NaN,
            buffer.getInt(32),
        )
    }

    private fun readDpEdid(): Int? = runCatching {
        withXrealNcmNetwork {
            NativeBridge.xrealOneDpGetCurrentEdid(DP_HOST, DP_PORT, 2_000, 800)
        }
    }.onFailure { error ->
        status("${model.displayName} DP 模式读取失败：${error.message}")
    }.getOrNull()

    private fun verifyDpEdid(expected: Int): Boolean {
        repeat(6) { attempt ->
            val edid = readDpEdid()
            if (edid == expected) return true
            if (attempt < 5) Thread.sleep(250)
        }
        return false
    }

    private inline fun <T> withXrealNcmNetwork(block: () -> T): T {
        val manager = connectivityManager ?: return block()
        val network = findXrealNcmNetwork(manager)
        if (network == null) {
            status("${model.displayName} 未找到 $DP_LOCAL_HOST 所在的 Android Network，使用默认路由尝试连接")
            return block()
        }
        val previous = manager.boundNetworkForProcess
        if (!manager.bindProcessToNetwork(network)) {
            status("${model.displayName} 绑定 USB Ethernet Network 失败，使用默认路由尝试连接")
            return block()
        }
        return try {
            block()
        } finally {
            manager.bindProcessToNetwork(previous)
        }
    }

    @Suppress("DEPRECATION")
    private fun findXrealNcmNetwork(manager: ConnectivityManager): Network? = manager.allNetworks.firstOrNull { network ->
        val properties = manager.getLinkProperties(network) ?: return@firstOrNull false
        properties.linkAddresses.any { address -> address.address.hostAddress == DP_LOCAL_HOST }
    }

    private fun status(message: String) {
        Log.i(TAG, message)
        executor.execute { listener.onStatus(message) }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        imuThread?.interrupt(); if (Thread.currentThread() !== imuThread) imuThread?.join(1_200)
    }

    private companion object {
        const val TAG = "ArGlassXrealOne"
        const val DP_HOST = "169.254.2.1"
        const val DP_LOCAL_HOST = "169.254.2.10"
        const val DP_PORT = 52_999
        const val IMU_HOST = "169.254.2.1"
        const val IMU_PORT = 52_998
        const val NATIVE_IMU_SAMPLE_SIZE = 36
    }
}
