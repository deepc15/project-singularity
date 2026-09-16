package com.singular.cast.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 encoder fed by a [Surface].
 *
 * The surface is handed to whoever produces pixels — a privileged virtual
 * display, or a MediaProjection mirror — so this class never touches capture
 * itself. Output is pushed to [onConfig]/[onFrame] from the encoder's own
 * thread; both callbacks must be cheap and non-blocking.
 */
class ScreenEncoder(
    private val label: String,
    private var width: Int,
    private var height: Int,
    private var bitrate: Int,
    private val frameRate: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, offset: Int, length: Int, keyframe: Boolean, ptsUs: Long) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** Valid only between [start] and [stop]. */
    var inputSurface: Surface? = null
        private set

    fun start() {
        check(codec == null) { "encoder already started" }

        val format = MediaFormat.createVideoFormat(MIME, evenize(width), evenize(height)).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // A static screen produces no frames at all; repeating the last one
            // keeps the decoder on the PC from stalling on a still app.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // One frame of encoder latency: this is interactive, not a file.
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MIME)
        } catch (e: Exception) {
            onFatal("no H.264 encoder available: ${e.message}")
            return
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
        } catch (e: Exception) {
            runCatching { encoder.release() }
            onFatal("encoder configure failed: ${e.message}")
            return
        }

        codec = encoder
        running.set(true)
        thread = Thread({ drainLoop(encoder) }, "singular-enc-$label").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "$label encoder started ${width}x$height @${frameRate}fps ${bitrate / 1000}kbps")
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                if (running.get()) onFatal("encoder died: ${e.message}")
                return
            }

            if (index < 0) continue

            val buffer = encoder.getOutputBuffer(index)
            if (buffer == null) {
                encoder.releaseOutputBuffer(index, false)
                continue
            }

            if (info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.get(bytes)

                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) {
                    onConfig(bytes)
                } else {
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onFrame(bytes, 0, bytes.size, keyframe, info.presentationTimeUs)
                }
            }

            encoder.releaseOutputBuffer(index, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** Ask for an immediate IDR — used when the PC's decoder had to reset. */
    fun requestKeyFrame() {
        val encoder = codec ?: return
        runCatching {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }
    }

    fun setBitrate(bps: Int) {
        val encoder = codec ?: return
        bitrate = bps
        runCatching {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) },
            )
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(THREAD_JOIN_MS)
        thread = null
        val encoder = codec
        codec = null
        inputSurface = null
        encoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        Log.i(TAG, "$label encoder stopped")
    }

    companion object {
        private const val TAG = "SingularEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL_SEC = 2
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        private const val REPEAT_AFTER_US = 200_000L
        private const val THREAD_JOIN_MS = 1_500L

        /** H.264 chroma subsampling needs even dimensions. */
        fun evenize(value: Int): Int = if (value % 2 == 0) value else value - 1
    }
}
