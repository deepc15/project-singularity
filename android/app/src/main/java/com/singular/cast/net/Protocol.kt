package com.singular.cast.net

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import org.json.JSONObject

/**
 * Singular Protocol v1 framing. See PROTOCOL.md at the repository root.
 */
object Protocol {
    const val VERSION = 1
    const val TCP_PORT = 8787
    const val UDP_PORT = 8788
    const val DISCOVERY_PROBE = "SINGULAR_PROBE/1"

    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val TYPE_CONTROL = 0x01
    const val TYPE_VIDEO_CONFIG = 0x02
    const val TYPE_VIDEO_FRAME = 0x03

    const val FLAG_KEYFRAME = 0x01

    /** `streamId` + `flags` + `ptsUs`. */
    const val VIDEO_FRAME_HEADER = 4 + 1 + 8
}

/** A decoded inbound frame. Only control frames are expected from the PC. */
sealed interface InboundFrame {
    data class Control(val json: JSONObject) : InboundFrame
    data class Unknown(val type: Int) : InboundFrame
}

/**
 * Writes frames onto the socket. Video and control frames are produced from
 * different threads (encoder vs. UI), so every write is serialised here and
 * the header is built in a reusable buffer to keep the encoder path allocation
 * free.
 */
class FrameWriter(private val out: OutputStream) {
    private val lock = Any()
    private val header = ByteArray(5)
    private val videoHeader = ByteArray(Protocol.VIDEO_FRAME_HEADER)

    fun writeControl(json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            putHeader(Protocol.TYPE_CONTROL, payload.size)
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }

    fun writeVideoConfig(streamId: Int, csd: ByteArray) {
        synchronized(lock) {
            putHeader(Protocol.TYPE_VIDEO_CONFIG, 4 + csd.size)
            out.write(header)
            out.write(intBytes(streamId))
            out.write(csd)
            out.flush()
        }
    }

    fun writeVideoFrame(
        streamId: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        keyframe: Boolean,
        ptsUs: Long,
    ) {
        synchronized(lock) {
            putHeader(Protocol.TYPE_VIDEO_FRAME, Protocol.VIDEO_FRAME_HEADER + length)
            putInt(videoHeader, 0, streamId)
            videoHeader[4] = if (keyframe) Protocol.FLAG_KEYFRAME.toByte() else 0
            putLong(videoHeader, 5, ptsUs)
            out.write(header)
            out.write(videoHeader)
            out.write(data, offset, length)
            out.flush()
        }
    }

    private fun putHeader(type: Int, length: Int) {
        header[0] = type.toByte()
        putInt(header, 1, length)
    }

    private fun intBytes(value: Int): ByteArray = ByteArray(4).also { putInt(it, 0, value) }

    private fun putInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value ushr 24).toByte()
        buf[at + 1] = (value ushr 16).toByte()
        buf[at + 2] = (value ushr 8).toByte()
        buf[at + 3] = value.toByte()
    }

    private fun putLong(buf: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) buf[at + i] = (value ushr (56 - 8 * i)).toByte()
    }
}

/** Blocking frame reader; run it on an IO dispatcher. */
class FrameReader(private val input: DataInputStream) {

    /** @throws EOFException when the peer closes the connection. */
    fun read(): InboundFrame {
        val type = input.read()
        if (type < 0) throw EOFException("stream closed")
        val length = input.readInt()
        if (length < 0 || length > Protocol.MAX_PAYLOAD) {
            throw IllegalStateException("frame payload $length out of range")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return when (type) {
            Protocol.TYPE_CONTROL -> InboundFrame.Control(
                JSONObject(String(payload, Charsets.UTF_8)),
            )
            else -> InboundFrame.Unknown(type)
        }
    }
}

/** Convenience builders so message construction reads like the protocol doc. */
fun controlMessage(type: String, build: JSONObject.() -> Unit = {}): JSONObject =
    JSONObject().put("t", type).apply(build)
