package com.singular.cast.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    data class Connected(val host: String, val port: Int, val pcName: String) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * TCP client for Singular Desk.
 *
 * Reading happens on a dedicated IO coroutine; writing is synchronised inside
 * [FrameWriter] so the encoder thread can push video without hopping
 * dispatchers on every frame.
 */
class SingularClient(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val inbound: SharedFlow<JSONObject> = _inbound.asSharedFlow()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: FrameWriter? = null

    private var readJob: Job? = null

    /** Geometry the PC prefers for new virtual displays, from `hello.ack`. */
    @Volatile
    var preferredTile: Triple<Int, Int, Int> = Triple(1280, 800, 200)
        private set

    val isConnected: Boolean get() = writer != null

    fun connect(host: String, port: Int, hello: JSONObject) {
        disconnect("reconnecting")
        _state.value = ConnectionState.Connecting(host, port)

        readJob = scope.launch(Dispatchers.IO) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                socket = sock

                val out = FrameWriter(BufferedOutputStream(sock.getOutputStream(), WRITE_BUFFER))
                writer = out
                out.writeControl(hello)

                val reader = FrameReader(
                    DataInputStream(BufferedInputStream(sock.getInputStream(), READ_BUFFER)),
                )
                readLoop(reader)
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "connection to $host:$port ended: ${e.message}")
                    _state.value = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                writer = null
                socket = null
                runCatching { sock.close() }
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Idle
                }
            }
        }
    }

    private suspend fun readLoop(reader: FrameReader) {
        while (true) {
            val frame = try {
                reader.read()
            } catch (e: EOFException) {
                Log.i(TAG, "peer closed the connection")
                return
            }

            if (frame !is InboundFrame.Control) continue
            val json = frame.json

            when (json.optString("t")) {
                "hello.ack" -> {
                    val tile = json.optJSONObject("tile")
                    if (tile != null) {
                        preferredTile = Triple(
                            tile.optInt("w", 1280),
                            tile.optInt("h", 800),
                            tile.optInt("dpi", 200),
                        )
                    }
                    val host = (socket?.inetAddress?.hostAddress).orEmpty()
                    _state.value = ConnectionState.Connected(
                        host = host,
                        port = socket?.port ?: Protocol.TCP_PORT,
                        pcName = json.optString("name", "PC"),
                    )
                }
                // Answered here so liveness never depends on the UI being alive.
                "ping" -> send(controlMessage("pong") { put("ts", json.optLong("ts")) })
            }

            // Re-emit everything, including hello.ack and ping, so the engine
            // can observe the full conversation.
            _inbound.emit(json)
        }
    }

    fun send(json: JSONObject) {
        val out = writer ?: return
        // Control messages originate on the main thread; keep them off it.
        scope.launch(Dispatchers.IO) {
            runCatching { out.writeControl(json) }
                .onFailure { Log.w(TAG, "control write failed: ${it.message}") }
        }
    }

    /** Called from the encoder thread — must not suspend. */
    fun sendVideoConfig(streamId: Int, csd: ByteArray) {
        val out = writer ?: return
        runCatching { out.writeVideoConfig(streamId, csd) }
            .onFailure { Log.w(TAG, "csd write failed: ${it.message}") }
    }

    /** Called from the encoder thread — must not suspend. */
    fun sendVideoFrame(
        streamId: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        keyframe: Boolean,
        ptsUs: Long,
    ) {
        val out = writer ?: return
        runCatching { out.writeVideoFrame(streamId, data, offset, length, keyframe, ptsUs) }
            .onFailure { Log.w(TAG, "frame write failed: ${it.message}") }
    }

    fun disconnect(reason: String) {
        readJob?.cancel()
        readJob = null
        writer = null
        socket?.let { sock -> runCatching { sock.close() } }
        socket = null
        if (_state.value !is ConnectionState.Failed) _state.value = ConnectionState.Idle
        Log.i(TAG, "disconnected: $reason")
    }

    suspend fun awaitClose() = withContext(Dispatchers.IO) { readJob?.join() }

    private companion object {
        const val TAG = "SingularClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_BUFFER = 32 * 1024
        const val WRITE_BUFFER = 256 * 1024
    }
}
