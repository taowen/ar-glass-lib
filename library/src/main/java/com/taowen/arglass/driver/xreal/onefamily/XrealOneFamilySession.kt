package com.taowen.arglass.driver.xreal.onefamily

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.SystemClock
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
            val inputMode = readDpInputMode(reportFailure = false)
            displayModeProtocol.decode(edid).also { mode ->
                status("${model.displayName} DP EDID=$edid，input=$inputMode，显示模式=${mode?.name ?: "未知"}")
            }
        }
    }

    override fun queryDisplayProfile(): GlassesDisplayProfile? {
        if (!displayEnabled) {
            status("${model.displayName} 未声明 2D/3D 切换能力")
            return null
        }
        return readDpEdid()?.let { edid ->
            val inputMode = readDpInputMode(reportFailure = false)
            displayModeProtocol.decodeProfile(edid).also { profile ->
                status("${model.displayName} DP EDID=$edid，input=$inputMode，显示 profile=${profile?.let(::profileLabel) ?: "未知"}")
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
            XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
                NativeBridge.xrealOneDpSetDisplayMode(
                    XrealOneNcmTransport.GLASSES_HOST,
                    XrealOneNcmTransport.CONTROL_PORT,
                    command.edid,
                    command.inputMode,
                    2_000,
                    1_200,
                )
            }
        }.onFailure { error ->
            status("${model.displayName} DP ACK 未完成，继续读回 EDID/input 验证：${error.message}")
        }.getOrDefault(false)
        val verified = verifyDpState(command.edid, command.inputMode)
        if (verified) {
            status("${model.displayName} 已切换 DP 模式：${mode.name}，EDID=${command.edid}, input=${command.inputMode}, ack=$transportOk")
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
            XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
                NativeBridge.xrealOneDpSetDisplayMode(
                    XrealOneNcmTransport.GLASSES_HOST,
                    XrealOneNcmTransport.CONTROL_PORT,
                    command.edid,
                    command.inputMode,
                    2_000,
                    1_200,
                )
            }
        }.onFailure { error ->
            status("${model.displayName} DP ACK 未完成，继续读回 EDID/input 验证：${error.message}")
        }.getOrDefault(false)
        val verified = verifyDpState(command.edid, command.inputMode)
        if (verified) {
            status("${model.displayName} 已切换 DP profile：${profileLabel(profile)}，ack=$transportOk")
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
            handle = XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
                NativeBridge.createXrealOneTcpImuSession(
                    XrealOneNcmTransport.GLASSES_HOST,
                    XrealOneNcmTransport.IMU_PORT,
                    2_000,
                    500,
                )
            }
            status("${model.displayName} IMU 已连接 ${XrealOneNcmTransport.GLASSES_HOST}:${XrealOneNcmTransport.IMU_PORT}")
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

    private fun readDpEdid(reportFailure: Boolean = true): Int? = runCatching {
        XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
            NativeBridge.xrealOneDpGetCurrentEdid(
                XrealOneNcmTransport.GLASSES_HOST,
                XrealOneNcmTransport.CONTROL_PORT,
                2_000,
                800,
            )
        }
    }.onFailure { error ->
        val message = "${model.displayName} DP 模式读取失败：${error.message}"
        if (reportFailure) {
            status(message)
        } else {
            Log.i(TAG, message)
        }
    }.getOrNull()

    private fun readDpInputMode(reportFailure: Boolean = true): Int? = runCatching {
        XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
            NativeBridge.xrealOneDpGetInputMode(
                XrealOneNcmTransport.GLASSES_HOST,
                XrealOneNcmTransport.CONTROL_PORT,
                2_000,
                800,
            )
        }
    }.onFailure { error ->
        val message = "${model.displayName} DP input mode 读取失败：${error.message}"
        if (reportFailure) {
            status(message)
        } else {
            Log.i(TAG, message)
        }
    }.getOrNull()

    private fun writeDpInputMode(inputMode: Int): Boolean = runCatching {
        XrealOneNcmTransport.withBoundNetwork(connectivityManager, ::status) {
            NativeBridge.xrealOneDpSetInputMode(
                XrealOneNcmTransport.GLASSES_HOST,
                XrealOneNcmTransport.CONTROL_PORT,
                inputMode,
                2_000,
                1_200,
            )
        }
    }.onFailure { error ->
        status("${model.displayName} DP input mode 补发失败：${error.message}")
    }.getOrDefault(false)

    private fun verifyDpState(expectedEdid: Int, expectedInputMode: Int): Boolean {
        // XREAL One family DP mode changes can briefly drop/re-enumerate the display.
        // During that window the control endpoint may answer with partial/malformed EDID data
        // even though the mode switch is already in progress. Wait for the hotplug to settle
        // before treating readback as a failure.
        Thread.sleep(900)
        val deadline = SystemClock.elapsedRealtime() + 7_000
        var lastEdid: Int? = null
        var lastInputMode: Int? = null
        var inputModeWriteCount = 0
        var inputModeWriteConfirmed = false
        while (SystemClock.elapsedRealtime() < deadline) {
            lastEdid = readDpEdid(reportFailure = false)
            lastInputMode = readDpInputMode(reportFailure = false)
            if (lastEdid == expectedEdid && lastInputMode == expectedInputMode) return true
            if (lastEdid == expectedEdid && lastInputMode != expectedInputMode &&
                inputModeWriteCount < displayModeProtocol.inputModeWriteAttempts
            ) {
                inputModeWriteCount += 1
                status(
                    "${model.displayName} EDID 已到位但 input=$lastInputMode，补发 input=$expectedInputMode" +
                        " ($inputModeWriteCount/${displayModeProtocol.inputModeWriteAttempts})",
                )
                inputModeWriteConfirmed = writeDpInputMode(expectedInputMode) || inputModeWriteConfirmed
                if (!displayModeProtocol.requireInputModeReadback && inputModeWriteConfirmed &&
                    inputModeWriteCount >= displayModeProtocol.inputModeWriteAttempts
                ) {
                    Thread.sleep(500)
                    lastEdid = readDpEdid(reportFailure = false)
                    if (lastEdid == expectedEdid) {
                        status("${model.displayName} EDID 已验证，inputMode 已补发；跳过不稳定 input 读回")
                        return true
                    }
                }
            }
            Thread.sleep(500)
        }
        lastEdid = lastEdid ?: readDpEdid(reportFailure = true)
        lastInputMode = lastInputMode ?: readDpInputMode(reportFailure = true)
        if (!displayModeProtocol.requireInputModeReadback && lastEdid == expectedEdid && inputModeWriteConfirmed) {
            status("${model.displayName} EDID 已验证，inputMode 已补发；跳过不稳定 input 读回")
            return true
        }
        status("${model.displayName} DP 状态未达预期：期望 EDID=$expectedEdid/input=$expectedInputMode，读回 EDID=$lastEdid/input=$lastInputMode")
        return false
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
        const val NATIVE_IMU_SAMPLE_SIZE = 36
    }
}
