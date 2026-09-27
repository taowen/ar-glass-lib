package com.taowen.arglass

import android.content.Context
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Device-side boundary for a host-rendered, double-buffered ATW consumer.
 * No EGL, pose prediction, Android Surface ownership, or X1 frame submission.
 * Open/close run on the consumer's control worker, never on the UI/IMU thread.
 * Samples run directly on the driver's receiver: no UI executor/backlog.
 */
class PhoneAtwSession private constructor(
    private val manager: ArGlassesManager,
    private val session: ArGlassesSession,
    val configuration: PhoneAtwConfiguration,
    private val accepting: AtomicBoolean,
    private val firstSample: CountDownLatch,
) : Closeable {
    private val closed = AtomicBoolean()

    /** Read after startup as needed: some drivers publish calibration before the query. */
    fun queryImuProtocolResponse(): ImuProtocolResponse? = session.queryImuProtocolResponse()

    /** Call only after the renderer has consumed calibration and initialized. */
    fun startSamples() {
        check(!closed.get())
        accepting.set(true)
        check(firstSample.await(3, TimeUnit.SECONDS)) { "No live IMU sample reached the renderer" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        accepting.set(false)
        manager.close() // invalidates callbacks, then stops/joins driver readers
    }

    companion object {
        @JvmStatic
        fun open(context: Context, glasses: ConnectedGlasses, sink: PhoneAtwSink): PhoneAtwSession {
            require(glasses.model.capabilities.contains(GlassesCapability.IMU)) {
                "${glasses.model.displayName} has no IMU source"
            }
            val ready = CountDownLatch(1)
            val receivedCalibration = AtomicReference<ImuCalibrationData?>()
            val accepting = AtomicBoolean(false)
            val firstSample = CountDownLatch(1)
            val callbacks = object : ArGlassesListener {
                override fun onStatus(message: String) = sink.onStatus(message)
                override fun onImuCalibration(calibration: ImuCalibrationData) {
                    // A driver can publish factory calibration, then a temperature
                    // table or a newly estimated magnetic calibration. Never keep
                    // only the first record; pair each sample with the latest one.
                    receivedCalibration.set(calibration)
                    ready.countDown()
                }
                override fun onImuSample(sample: ImuSample) {
                    if (accepting.get()) {
                        sink.onSample(sample, requireNotNull(receivedCalibration.get()))
                        firstSample.countDown()
                    }
                }
            }
            val manager = ArGlassesManager(context, Executor { it.run() }, callbacks)
            try {
                val session = manager.open(glasses, SessionFeature.ALL, callbacks)
                val model = session.model
                // Full profiles require real device readback. Layout-only
                // protocols (Air4) request mono separately; the application must
                // observe the Android output, never invent a profile/RPC ACK.
                val profile = if (GlassesCapability.DISPLAY_MODE in model.capabilities) {
                    val requested = model.supportedDisplayProfiles.filter {
                        it.layout == GlassesDisplayLayout.MONO_2D && it.refreshRateHz <= 120
                    }.maxByOrNull { it.refreshRateHz }
                        ?: error("${model.displayName}: no declared mono display profile")
                    check(session.setDisplayProfile(requested)) { "Display mode request rejected: ${requested.id}" }
                    val actual = session.queryDisplayProfile()
                    check(actual == requested) { "Display mode readback mismatch: requested=$requested actual=$actual" }
                    actual
                } else {
                    if (session.requestMonoLayout())
                        sink.onStatus("${model.displayName}: 已发送 2D 布局命令；完整模式未知，等待应用核对 Android 输出")
                    else sink.onStatus("${model.displayName}: 无显示模式控制协议，保留当前模式")
                    null
                }
                check(ready.await(20, TimeUnit.SECONDS)) { "Device calibration not received within 20 seconds" }
                val snapshot = requireNotNull(receivedCalibration.get())
                return PhoneAtwSession(manager, session,
                    PhoneAtwConfiguration(model, profile, snapshot, session.queryCenterTangentFov(),
                        session.queryImuProtocolResponse()), accepting, firstSample)
            } catch (error: Throwable) {
                accepting.set(false)
                manager.close()
                throw error
            }
        }
    }
}

/** Preserve calibration ownership/units/provenance instead of pretending every device is XREAL. */
data class PhoneAtwConfiguration(
    val model: GlassesModel,
    /** Null when the device protocol cannot control/read back its display mode. */
    val display: GlassesDisplayProfile?,
    val imuCalibration: ImuCalibrationData,
    val centerFov: GlassesTangentFov?,
    /** Startup observation, not an inferred ID. May be null if the driver query is still pending. */
    val imuProtocolResponse: ImuProtocolResponse? = null,
)

interface PhoneAtwSink {
    fun onStatus(message: String)
    fun onSample(sample: ImuSample, calibration: ImuCalibrationData)
}
