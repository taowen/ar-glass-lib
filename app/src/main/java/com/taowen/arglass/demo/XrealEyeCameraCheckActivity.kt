package com.taowen.arglass.demo

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taowen.arglass.XrealOneEyeHevcFrame
import com.taowen.arglass.XrealOneEyeCameraSession
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Lets the user explicitly test the open XREAL Eye camera implementation. */
class XrealEyeCameraCheckActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var preview: TextureView
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var latestSnapshotPath: String? = null
    @Volatile private var latestHevcPath: String? = null
    private var session: Closeable? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val snapshotInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = checkContent()
        content.addView(label("XREAL Eye 摄像头", 25f, true))
        status = label("开源读取，不依赖官方 SO。One + Eye 使用 USB Ethernet TCP/HEVC，不走 UVC。", 16f)
        content.addView(status, margins(top = 12, bottom = 12))
        content.addView(Button(this).apply {
            text = "XREAL One TCP/HEVC 读取"
            setOnClickListener { startOneFamilyTcpHevc() }
        }, margins(bottom = 12))
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    previewSurface = Surface(surfaceTexture)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    previewSurface?.release()
                    previewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(360),
        ).apply { bottomMargin = dp(12) })
        connectivityManager = getSystemService(ConnectivityManager::class.java)
    }

    private fun startOneFamilyTcpHevc() {
        stopCurrent()
        latestSnapshotPath = null
        latestHevcPath = null
        status.text = "One + Eye TCP/HEVC：正在连接 169.254.2.1:52995"
        running.set(true)
        readerThread = Thread({
            val surface = awaitPreviewSurface()
            if (surface == null) {
                if (running.get()) runOnUiThread { status.text = "One + Eye TCP/HEVC 启动失败：预览 Surface 未就绪" }
                return@Thread
            }
            runCatching { XrealOneEyeCameraSession(connectivityManager) }
                .onFailure { error ->
                    if (running.get()) runOnUiThread { status.text = "One + Eye TCP/HEVC 启动失败：${error.message}" }
                }
                .onSuccess { open ->
                    session = open
                    decodeHevcPreview(open, surface)
                }
        }, "xreal-one-eye-hevc-reader").also(Thread::start)
    }

    private fun awaitPreviewSurface(timeoutMs: Long = 2_000): Surface? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running.get() && System.currentTimeMillis() < deadline) {
            previewSurface?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(50)
        }
        return previewSurface?.takeIf { it.isValid }
    }

    private fun decodeHevcPreview(open: XrealOneEyeCameraSession, surface: Surface) {
        var codec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var rendered = 0
        var bytes = 0L
        var dumpedBytes = 0L
        var configFrames = 0
        var keyFrames = 0
        var presentationTimeUs = 0L
        var lastUpdate = 0L
        var startedOnKeyFrame = false
        val debugDir = getExternalFilesDir(null) ?: filesDir
        val hevcFile = File(debugDir, "xreal-one-eye-latest.h265")
        latestHevcPath = hevcFile.absolutePath
        val hevcDump = runCatching { FileOutputStream(hevcFile, false) }.getOrNull()
        try {
            while (running.get()) {
                codec?.let { drainDecoder(it, info).let { count -> rendered += count } }
                val frame = open.readHevcFrame() ?: continue
                frames += 1
                bytes += frame.hevcBytes.size.toLong()
                if (frame.codecConfig) configFrames += 1
                if (frame.keyFrame) keyFrames += 1
                if (hevcDump != null && dumpedBytes < HEVC_DUMP_LIMIT_BYTES) {
                    hevcDump.write(frame.hevcBytes)
                    dumpedBytes += frame.hevcBytes.size.toLong()
                }

                if (codec == null) {
                    if (frame.codecConfig) {
                        codec = createHevcDecoder(surface, frame.hevcBytes)
                    } else {
                        updateHevcStatus(frames, rendered, configFrames, keyFrames, bytes, dumpedBytes, frame, "等待 VPS/SPS/PPS")
                        continue
                    }
                }

                val hasSlice = frame.nalTypes.any { it in 0..31 }
                if (!startedOnKeyFrame) {
                    if (!frame.keyFrame) {
                        updateHevcStatus(frames, rendered, configFrames, keyFrames, bytes, dumpedBytes, frame, "等待关键帧")
                        continue
                    }
                    startedOnKeyFrame = true
                }
                if (hasSlice && queueHevcFrame(codec, frame, presentationTimeUs)) {
                    presentationTimeUs += 33_333L
                }
                codec?.let { drainDecoder(it, info).let { count -> rendered += count } }
                if (rendered > 0 && rendered % 30 == 0) savePreviewPng(rendered)
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 500) {
                    lastUpdate = now
                    updateHevcStatus(frames, rendered, configFrames, keyFrames, bytes, dumpedBytes, frame, "解码中")
                }
            }
        } catch (error: Exception) {
            if (running.get()) runOnUiThread { status.text = "One + Eye TCP/HEVC 解码失败：${error.message}" }
        } finally {
            runCatching { hevcDump?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun createHevcDecoder(surface: Surface, codecConfig: ByteArray): MediaCodec =
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_000_000)
                setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
            }
            configure(format, surface, null, 0)
            start()
        }

    private fun queueHevcFrame(codec: MediaCodec, frame: XrealOneEyeHevcFrame, presentationTimeUs: Long): Boolean {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return false
        val buffer = codec.getInputBuffer(inputIndex) ?: return false
        if (frame.hevcBytes.size > buffer.capacity()) {
            runOnUiThread { status.text = "One + Eye TCP/HEVC：跳过过大的 HEVC 帧 ${frame.hevcBytes.size}" }
            return false
        }
        buffer.clear()
        buffer.put(frame.hevcBytes)
        val flags = if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(inputIndex, 0, frame.hevcBytes.size, presentationTimeUs, flags)
        return true
    }

    private fun drainDecoder(codec: MediaCodec, info: MediaCodec.BufferInfo): Int {
        var rendered = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, info.size > 0)
                    if (info.size > 0) rendered += 1
                }
            }
        }
    }

    private fun updateHevcStatus(
        frames: Int,
        rendered: Int,
        configFrames: Int,
        keyFrames: Int,
        bytes: Long,
        dumpedBytes: Long,
        frame: XrealOneEyeHevcFrame,
        phase: String,
    ) {
        val nalSummary = frame.nalTypes.take(8).joinToString(prefix = "[", postfix = "]")
        val snapshot = latestSnapshotPath?.let { "\nPNG: $it" }.orEmpty()
        runOnUiThread {
            status.text = "One + Eye TCP/HEVC：$phase\n" +
                "读 ${frames} 帧，显示 ${rendered} 帧，VPS/SPS/PPS=${configFrames}，关键帧=${keyFrames}\n" +
                "${bytes / 1024} KiB，dump=${dumpedBytes / 1024} KiB，last=${frame.hevcBytes.size} bytes，NAL=$nalSummary$snapshot"
        }
    }

    private fun savePreviewPng(rendered: Int) {
        if (!snapshotInFlight.compareAndSet(false, true)) return
        runOnUiThread {
            try {
                val bitmap = preview.getBitmap(PREVIEW_SNAPSHOT_WIDTH, PREVIEW_SNAPSHOT_HEIGHT) ?: return@runOnUiThread
                val path = writePreviewPng(bitmap, rendered)
                latestSnapshotPath = path
            } finally {
                snapshotInFlight.set(false)
            }
        }
    }

    private fun writePreviewPng(bitmap: Bitmap, rendered: Int): String {
        val debugDir = getExternalFilesDir(null) ?: filesDir
        val file = File(debugDir, "xreal-one-eye-preview.png")
        FileOutputStream(file, false).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return "${file.absolutePath} @ frame $rendered"
    }

    private fun stopCurrent() {
        running.set(false)
        val current = session
        session = null
        current?.close()
        readerThread?.interrupt()
        if (readerThread != Thread.currentThread()) runCatching { readerThread?.join(1500) }
        readerThread = null
    }

    override fun onDestroy() {
        stopCurrent()
        super.onDestroy()
    }

    private companion object {
        const val HEVC_DUMP_LIMIT_BYTES = 32L * 1024L * 1024L
        const val PREVIEW_SNAPSHOT_WIDTH = 640
        const val PREVIEW_SNAPSHOT_HEIGHT = 360
    }
}
