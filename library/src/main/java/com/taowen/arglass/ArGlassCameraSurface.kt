package com.taowen.arglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ArGlassCameraSurfaceOptions @JvmOverloads constructor(
    val maxEmptyReads: Int = 3,
    val statusIntervalMs: Long = 500L,
)

enum class ArGlassCameraSource {
    BEST,
    XREAL_ONE_EYE,
    BEAST_UVC,
}

class ArGlassCameraSurfaceStatus(
    val phase: String,
    val sourceName: String?,
    val framesRead: Long,
    val framesRendered: Long,
    val codecConfigFrames: Long,
    val keyFrames: Long,
    val bytesRead: Long,
    val lastFrameBytes: Int,
    val detail: String?,
) {
    fun toDebugString(): String {
        val source = sourceName ?: "未打开摄像头"
        val extra = detail?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        return "$source：$phase\n" +
            "读 ${framesRead} 帧，显示 ${framesRendered} 帧，VPS/SPS/PPS=${codecConfigFrames}，关键帧=${keyFrames}\n" +
            "${bytesRead / 1024} KiB，last=${lastFrameBytes} bytes$extra"
    }
}

fun interface ArGlassCameraSurfaceStatusListener {
    fun onStatus(status: ArGlassCameraSurfaceStatus)
}

interface ArGlassCameraSurfaceWriter : Closeable {
    val sourceName: String
    fun writeFrame(): Boolean
}

interface ArGlassCameraSurfaceWriterFactory {
    fun open(surface: Surface): ArGlassCameraSurfaceWriter?
}

object ArGlassCameraSurfaceWriters {
    @JvmStatic
    @JvmOverloads
    fun open(
        context: Context,
        surface: Surface,
        source: ArGlassCameraSource = ArGlassCameraSource.BEST,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceWriter? {
        if (!surface.isValid) return null
        val reader = when (source) {
            ArGlassCameraSource.BEST -> ArGlassCameraFrameReaders.openBest(context)
            ArGlassCameraSource.XREAL_ONE_EYE -> ArGlassCameraFrameReaders.openXrealOneEye(context)
            ArGlassCameraSource.BEAST_UVC -> ArGlassCameraFrameReaders.openBeastUvc(context)
        } ?: return null
        return SurfaceWriter(surface, reader, options, listener)
    }

    @JvmStatic
    @JvmOverloads
    fun openBest(
        context: Context,
        surface: Surface,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceWriter? =
        open(context, surface, ArGlassCameraSource.BEST, options, listener)

    @JvmStatic
    @JvmOverloads
    fun leasedFactory(
        context: Context,
        source: ArGlassCameraSource = ArGlassCameraSource.BEST,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceWriterFactory =
        LeasedFactory(context.applicationContext, source, options, listener)

    @JvmStatic
    @JvmOverloads
    fun leasedBestFactory(
        context: Context,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceWriterFactory =
        leasedFactory(context, ArGlassCameraSource.BEST, options, listener)

    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        surface: Surface,
        source: ArGlassCameraSource = ArGlassCameraSource.BEST,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(maxEmptyReads = 0),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceStream =
        ArGlassCameraSurfaceStream(context.applicationContext, surface, source, options, listener).also {
            it.start()
        }

    @JvmStatic
    @JvmOverloads
    fun startBest(
        context: Context,
        surface: Surface,
        options: ArGlassCameraSurfaceOptions = ArGlassCameraSurfaceOptions(maxEmptyReads = 0),
        listener: ArGlassCameraSurfaceStatusListener? = null,
    ): ArGlassCameraSurfaceStream =
        start(context, surface, ArGlassCameraSource.BEST, options, listener)

    private class LeasedFactory(
        private val context: Context,
        private val source: ArGlassCameraSource,
        private val options: ArGlassCameraSurfaceOptions,
        private val listener: ArGlassCameraSurfaceStatusListener?,
    ) : ArGlassCameraSurfaceWriterFactory {
        private val lease = AtomicBoolean(false)

        override fun open(surface: Surface): ArGlassCameraSurfaceWriter? {
            if (!surface.isValid) return null
            if (!lease.compareAndSet(false, true)) return null
            var writer: ArGlassCameraSurfaceWriter? = null
            return try {
                writer = open(context, surface, source, options, listener)
                if (writer == null) {
                    lease.set(false)
                    null
                } else {
                    LeasedSurfaceWriter(writer, lease)
                }
            } catch (error: Throwable) {
                runCatching { writer?.close() }
                lease.set(false)
                null
            }
        }
    }

    private class LeasedSurfaceWriter(
        private val delegate: ArGlassCameraSurfaceWriter,
        private val lease: AtomicBoolean,
    ) : ArGlassCameraSurfaceWriter {
        override val sourceName: String get() = delegate.sourceName

        override fun writeFrame(): Boolean = delegate.writeFrame()

        override fun close() {
            try {
                delegate.close()
            } finally {
                lease.set(false)
            }
        }
    }

    private class SurfaceWriter(
        private val surface: Surface,
        private val reader: ArGlassCameraFrameReader,
        private val options: ArGlassCameraSurfaceOptions,
        private val listener: ArGlassCameraSurfaceStatusListener?,
    ) : ArGlassCameraSurfaceWriter {
        override val sourceName: String = reader.name
        private val bufferInfo = MediaCodec.BufferInfo()
        private val jpegOptions = BitmapFactory.Options()
        private var hevcDecoder: MediaCodec? = null
        private var reusableJpegBitmap: Bitmap? = null
        private var startedOnKeyFrame = false
        private var emptyReads = 0
        private var presentationTimeUs = 0L
        private var framesRead = 0L
        private var framesRendered = 0L
        private var codecConfigFrames = 0L
        private var keyFrames = 0L
        private var bytesRead = 0L
        private var lastFrameBytes = 0
        private var lastStatusAtMs = 0L

        override fun writeFrame(): Boolean {
            drainHevcDecoder()
            val frame = reader.readFrame()
            if (frame == null) {
                emptyReads += 1
                emitStatus("等待摄像头帧", force = false)
                return options.maxEmptyReads <= 0 || emptyReads < options.maxEmptyReads
            }
            emptyReads = 0
            framesRead += 1
            lastFrameBytes = frame.bytes.size
            bytesRead += lastFrameBytes.toLong()
            if (frame.codecConfig) codecConfigFrames += 1
            if (frame.keyFrame) keyFrames += 1
            return when (frame.format) {
                ArGlassCameraFrame.FORMAT_JPEG -> writeJpeg(frame.bytes)
                ArGlassCameraFrame.FORMAT_HEVC_ANNEX_B -> writeHevc(frame)
                else -> false
            }
        }

        override fun close() {
            try {
                closeHevcDecoder()
            } finally {
                recycleReusableBitmap()
                runCatching { reader.close() }
            }
        }

        private fun writeJpeg(bytes: ByteArray): Boolean {
            val bitmap = decodeJpeg(bytes) ?: return true
            var canvas: Canvas? = null
            return try {
                canvas = surface.lockCanvas(null)
                val src = Rect(0, 0, bitmap.width, bitmap.height)
                val dst = canvas.clipBounds
                canvas.drawBitmap(bitmap, src, dst, null)
                surface.unlockCanvasAndPost(canvas)
                canvas = null
                framesRendered += 1
                emitStatus("显示中", force = false)
                true
            } finally {
                canvas?.let { runCatching { surface.unlockCanvasAndPost(it) } }
            }
        }

        private fun decodeJpeg(bytes: ByteArray): Bitmap? {
            jpegOptions.inMutable = true
            jpegOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            val previous = reusableJpegBitmap
            jpegOptions.inBitmap = previous?.takeUnless { it.isRecycled }
            return try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, jpegOptions)
            } catch (_: IllegalArgumentException) {
                jpegOptions.inBitmap = null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, jpegOptions)
            }.also { bitmap ->
                rememberReusableBitmap(previous, bitmap)
            }
        }

        private fun rememberReusableBitmap(previous: Bitmap?, bitmap: Bitmap?) {
            if (bitmap == null) return
            if (previous != null && previous != bitmap && !previous.isRecycled) {
                previous.recycle()
            }
            reusableJpegBitmap = bitmap
        }

        private fun writeHevc(frame: ArGlassCameraFrame): Boolean {
            var decoder = hevcDecoder
            if (decoder == null) {
                if (!frame.codecConfig) {
                    emitStatus("等待 VPS/SPS/PPS", force = false)
                    return true
                }
                decoder = createHevcDecoder(frame)
                hevcDecoder = decoder
                emitStatus("已配置 HEVC 解码器", force = true)
                return true
            }
            if (frame.codecConfig) return true
            if (!startedOnKeyFrame) {
                if (!frame.keyFrame) {
                    emitStatus("等待关键帧", force = false)
                    return true
                }
                startedOnKeyFrame = true
            }
            queueHevcFrame(decoder, frame)
            drainHevcDecoder()
            emitStatus("显示中", force = false)
            return true
        }

        private fun createHevcDecoder(configFrame: ArGlassCameraFrame): MediaCodec {
            val width = configFrame.width.takeIf { it > 0 } ?: 1280
            val height = configFrame.height.takeIf { it > 0 } ?: 720
            val frameRate = configFrame.frameRate.takeIf { it > 0 } ?: 30
            return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    width,
                    height,
                ).apply {
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, HEVC_MAX_INPUT_SIZE)
                    setByteBuffer("csd-0", ByteBuffer.wrap(configFrame.bytes))
                }
                configure(format, surface, null, 0)
                start()
            }
        }

        private fun queueHevcFrame(decoder: MediaCodec, frame: ArGlassCameraFrame) {
            val inputIndex = decoder.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) return
            val buffer = decoder.getInputBuffer(inputIndex) ?: return
            if (frame.bytes.size > buffer.capacity()) return
            buffer.clear()
            buffer.put(frame.bytes)
            decoder.queueInputBuffer(inputIndex, 0, frame.bytes.size, presentationTimeUs, 0)
            val frameRate = frame.frameRate.takeIf { it > 0 } ?: 30
            presentationTimeUs += 1_000_000L / frameRate
        }

        private fun drainHevcDecoder() {
            val decoder = hevcDecoder ?: return
            while (true) {
                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        decoder.releaseOutputBuffer(outputIndex, bufferInfo.size > 0)
                        if (bufferInfo.size > 0) framesRendered += 1
                    }
                }
            }
        }

        private fun closeHevcDecoder() {
            val decoder = hevcDecoder ?: return
            hevcDecoder = null
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }

        private fun recycleReusableBitmap() {
            val bitmap = reusableJpegBitmap
            reusableJpegBitmap = null
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        private fun emitStatus(phase: String, force: Boolean) {
            val callback = listener ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastStatusAtMs < options.statusIntervalMs) return
            lastStatusAtMs = now
            callback.onStatus(
                ArGlassCameraSurfaceStatus(
                    phase = phase,
                    sourceName = sourceName,
                    framesRead = framesRead,
                    framesRendered = framesRendered,
                    codecConfigFrames = codecConfigFrames,
                    keyFrames = keyFrames,
                    bytesRead = bytesRead,
                    lastFrameBytes = lastFrameBytes,
                    detail = null,
                ),
            )
        }
    }

    private const val HEVC_MAX_INPUT_SIZE = 2_000_000
    private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
}

class ArGlassCameraSurfaceStream internal constructor(
    private val context: Context,
    private val surface: Surface,
    private val source: ArGlassCameraSource,
    private val options: ArGlassCameraSurfaceOptions,
    private val listener: ArGlassCameraSurfaceStatusListener?,
) : Closeable, Runnable {
    private val running = AtomicBoolean(false)
    @Volatile private var writer: ArGlassCameraSurfaceWriter? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(this, "ArGlassCameraSurfaceStream").also { it.start() }
    }

    override fun run() {
        val open = ArGlassCameraSurfaceWriters.open(context, surface, source, options, listener)
        if (open == null) {
            listener?.onStatus(
                ArGlassCameraSurfaceStatus(
                    phase = "没有可用摄像头",
                    sourceName = null,
                    framesRead = 0,
                    framesRendered = 0,
                    codecConfigFrames = 0,
                    keyFrames = 0,
                    bytesRead = 0,
                    lastFrameBytes = 0,
                    detail = ArGlassCameraFrameReaders.describeAvailability(context),
                ),
            )
            running.set(false)
            return
        }
        writer = open
        listener?.onStatus(
            ArGlassCameraSurfaceStatus(
                phase = "已打开摄像头",
                sourceName = open.sourceName,
                framesRead = 0,
                framesRendered = 0,
                codecConfigFrames = 0,
                keyFrames = 0,
                bytesRead = 0,
                lastFrameBytes = 0,
                detail = null,
            ),
        )
        try {
            while (running.get() && surface.isValid) {
                if (!open.writeFrame()) break
            }
        } catch (error: Throwable) {
            if (running.get()) {
                listener?.onStatus(
                    ArGlassCameraSurfaceStatus(
                        phase = "摄像头输出失败",
                        sourceName = open.sourceName,
                        framesRead = 0,
                        framesRendered = 0,
                        codecConfigFrames = 0,
                        keyFrames = 0,
                        bytesRead = 0,
                        lastFrameBytes = 0,
                        detail = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        } finally {
            runCatching { open.close() }
            writer = null
            running.set(false)
        }
    }

    override fun close() {
        running.set(false)
        runCatching { writer?.close() }
        val current = thread
        if (current != null && current != Thread.currentThread()) {
            current.interrupt()
            runCatching { current.join(1500) }
        }
        thread = null
    }
}
