package com.singular.cast.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import com.singular.cast.apps.AppRepository
import com.singular.cast.ime.SingularAccessibilityService
import com.singular.cast.model.AppInfo
import com.singular.cast.model.CastSession
import com.singular.cast.model.SettingsStore
import com.singular.cast.model.SingularSettings
import com.singular.cast.net.ConnectionState
import com.singular.cast.net.Protocol
import com.singular.cast.net.SingularClient
import com.singular.cast.net.controlMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The heart of Singular Cast: turns "the user swiped an app at the PC" into a
 * virtual display, an encoder, a stream, and a two-way input channel.
 *
 * A process-wide singleton because the cast outlives every screen in the UI —
 * [CastService] keeps the process up and the Compose layer just observes the
 * flows exposed here.
 */
object CastEngine {

    private const val TAG = "SingularEngine"

    /** There is only one physical screen, so only one mirror can exist. */
    private const val MIRROR_STREAM_LIMIT = 1

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextStreamId = AtomicInteger(1)

    // These three hold a Context, which lint reads as a leak in a singleton.
    // Every one of them is constructed with the *application* context in
    // init(), so they live exactly as long as the process does.
    @SuppressLint("StaticFieldLeak")
    private lateinit var shizukuBridge: ShizukuBridge

    @SuppressLint("StaticFieldLeak")
    private lateinit var store: SettingsStore

    @SuppressLint("StaticFieldLeak")
    private lateinit var apps: AppRepository

    private lateinit var settingsFlow: StateFlow<SingularSettings>
    private lateinit var client: SingularClient

    val shizuku: ShizukuBridge get() = shizukuBridge
    val settingsStore: SettingsStore get() = store
    val settings: StateFlow<SingularSettings> get() = settingsFlow

    private val live = ConcurrentHashMap<Int, LiveSession>()

    // ------------------------------------------------------------------ state

    private val _sessions = MutableStateFlow<List<CastSession>>(emptyList())
    val sessions: StateFlow<List<CastSession>> = _sessions.asStateFlow()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _summary = MutableStateFlow("Idle")
    val summary: StateFlow<String> = _summary.asStateFlow()

    private val _events = MutableStateFlow<String?>(null)

    /** Latest user-facing message; the UI clears it once shown. */
    val events: StateFlow<String?> = _events.asStateFlow()

    /** Stream id whose app is asking for text input, driving the keyboard. */
    private val _imeTarget = MutableStateFlow<Int?>(null)
    val imeTarget: StateFlow<Int?> = _imeTarget.asStateFlow()

    /** Set when mirror mode needs the user to approve screen capture. */
    private val _projectionRequest = MutableStateFlow(false)
    val projectionRequest: StateFlow<Boolean> = _projectionRequest.asStateFlow()

    private var projectionConsent: CompletableDeferred<Intent?>? = null
    private var projection: MediaProjection? = null

    val connection: StateFlow<ConnectionState> get() = client.state

    @Volatile
    private var initialized = false

    // ------------------------------------------------------------------- setup

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        shizukuBridge = ShizukuBridge(appContext)
        store = SettingsStore(appContext)
        settingsFlow = store.flow.stateIn(scope, SharingStarted.Eagerly, SingularSettings())
        client = SingularClient(scope)
        apps = AppRepository(appContext, shizukuBridge)

        shizukuBridge.start()
        scope.launch { client.inbound.collect { handleInbound(it) } }
        scope.launch { observeConnection() }
        scope.launch { observeEditableFocus() }
        scope.launch {
            // Emits immediately, which doubles as the initial load. The list
            // also changes meaning once the privileged bridge is up: "running"
            // becomes authoritative instead of a usage-stats guess.
            shizuku.state.collect { refreshApps() }
        }
    }

    private suspend fun observeConnection() {
        client.state.collect { state ->
            _summary.value = when (state) {
                is ConnectionState.Idle -> "Not connected"
                is ConnectionState.Connecting -> "Connecting to ${state.host}…"
                is ConnectionState.Connected -> "Connected to ${state.pcName}"
                is ConnectionState.Failed -> "Connection failed: ${state.reason}"
            }
            if (state is ConnectionState.Connected) {
                settingsStore.rememberPc(state.host, state.port)
                sendAppInventory()
            }
            if (state is ConnectionState.Idle || state is ConnectionState.Failed) {
                stopAllSessions("disconnected")
            }
        }
    }

    private suspend fun observeEditableFocus() {
        SingularAccessibilityService.editableFocus.collect { focus ->
            if (focus == null) {
                _imeTarget.value?.let { id ->
                    client.send(controlMessage("ime.hide") { put("id", id) })
                }
                _imeTarget.value = null
                return@collect
            }
            if (!settings.value.autoKeyboard) return@collect

            // Attribute the focus to the session on that display. A mirror
            // session covers the default display, so anything focused there
            // belongs to it; a virtual display must match exactly, otherwise a
            // text field the user tapped on the phone would prompt on the PC.
            val session = live.values.firstOrNull {
                it.meta.mode == CastSession.Mode.Display && it.meta.displayId == focus.displayId
            } ?: live.values.firstOrNull {
                it.meta.mode == CastSession.Mode.Mirror
            } ?: return@collect
            _imeTarget.value = session.meta.id
            client.send(controlMessage("ime.show") { put("id", session.meta.id) })
        }
    }

    // -------------------------------------------------------------- connection

    fun connect(host: String, port: Int) {
        CastService.start(appContext)
        client.connect(host, port, helloMessage())
    }

    fun disconnect() {
        stopAllSessions("user disconnected")
        client.disconnect("user disconnected")
        CastService.stop(appContext)
    }

    fun shutdown(reason: String) {
        stopAllSessions(reason)
        client.disconnect(reason)
    }

    private fun helloMessage(): JSONObject {
        val metrics = displayMetrics()
        return controlMessage("hello") {
            put("proto", Protocol.VERSION)
            put(
                "device",
                JSONObject().apply {
                    put("name", Build.MODEL)
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("sdk", Build.VERSION.SDK_INT)
                    put("w", metrics.widthPixels)
                    put("h", metrics.heightPixels)
                    put("dpi", metrics.densityDpi)
                },
            )
            put(
                "caps",
                JSONObject().apply {
                    put("privileged", shizuku.isReady)
                    put("accessibility", SingularAccessibilityService.connected.value)
                    put("mirror", true)
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val wm = appContext.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    // --------------------------------------------------------------- app list

    suspend fun refreshApps() {
        val loaded = apps.load()
        _appList.value = loaded
        if (client.isConnected) sendAppInventory()
    }

    fun hasUsageAccess(): Boolean = apps.hasUsageAccess()

    private fun sendAppInventory() {
        val array = JSONArray()
        for (app in _appList.value) {
            array.put(
                JSONObject().apply {
                    put("pkg", app.pkg)
                    put("label", app.label)
                    put("running", app.running)
                    put("recent", app.recent)
                    app.iconPng?.let { put("iconPng", it) }
                },
            )
        }
        client.send(controlMessage("apps") { put("list", array) })
    }

    // ------------------------------------------------------------------- cast

    /**
     * The swipe landed: get this app onto the PC.
     *
     * With the privileged bridge the app gets its own virtual display sized to
     * the PC's tile, which is what makes the phone stay usable. Without it, the
     * only capture path is whole-screen mirroring.
     */
    fun castApp(app: AppInfo) {
        if (!client.isConnected) {
            emit("Connect to a PC first")
            return
        }
        if (live.values.any { it.meta.app.pkg == app.pkg }) {
            emit("${app.label} is already on the PC")
            return
        }
        scope.launch {
            if (shizuku.isReady || shizuku.awaitService() != null) startOnVirtualDisplay(app)
            else startMirror(app)
        }
    }

    private suspend fun startOnVirtualDisplay(app: AppInfo) {
        val (tileW, tileH, tileDpi) = client.preferredTile
        val width = ScreenEncoder.evenize(tileW.coerceIn(320, 3840))
        val height = ScreenEncoder.evenize(tileH.coerceIn(320, 2160))
        val streamId = nextStreamId.getAndIncrement()
        val cfg = settings.value

        val encoder = newEncoder(streamId, "vd$streamId", width, height, cfg)
        encoder.start()
        val surface = encoder.inputSurface ?: run {
            encoder.stop()
            emit("Could not start the video encoder")
            return
        }

        val displayId = shizuku.withService { service ->
            service.createDisplay("Singular-$streamId", width, height, tileDpi, surface)
        } ?: -1

        if (displayId < 0) {
            encoder.stop()
            emit("Shizuku could not create a display — falling back to mirroring")
            startMirror(app)
            return
        }

        val launched = shizuku.withService { it.launchPackage(app.pkg, displayId) } ?: false
        if (!launched) {
            shizuku.withService { it.releaseDisplay(displayId) }
            encoder.stop()
            emit("Could not launch ${app.label} on the PC display")
            return
        }

        // The task only exists after the launch, and we need it to bring the
        // app back to the phone later.
        val taskId = withTimeoutOrNull(TASK_LOOKUP_TIMEOUT_MS) {
            var found = -1
            while (found < 0) {
                found = shizuku.withService { it.findTaskId(app.pkg) } ?: -1
                if (found < 0) kotlinx.coroutines.delay(100)
            }
            found
        } ?: -1

        val meta = CastSession(
            id = streamId,
            app = app,
            mode = CastSession.Mode.Display,
            width = width,
            height = height,
            dpi = tileDpi,
            displayId = displayId,
            taskId = taskId,
        )
        live[streamId] = LiveSession(meta, encoder)
        publishSessions()
        announceStart(meta)
        Log.i(TAG, "casting ${app.pkg} on display $displayId task $taskId")
    }

    private suspend fun startMirror(app: AppInfo) {
        if (live.values.count { it.meta.mode == CastSession.Mode.Mirror } >= MIRROR_STREAM_LIMIT) {
            emit("Only one screen mirror at a time")
            return
        }

        val consent = requestProjectionConsent() ?: run {
            emit("Screen capture was not allowed")
            return
        }

        CastService.instance?.promoteToMediaProjection()
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(android.app.Activity.RESULT_OK, consent)
        } catch (e: Exception) {
            emit("Screen capture unavailable: ${e.message}")
            null
        } ?: return

        projection = mp
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    live.values
                        .filter { it.meta.mode == CastSession.Mode.Mirror }
                        .forEach { stopSession(it.meta.id, "capture stopped") }
                }
            },
            // Explicit looper: this runs on a worker dispatcher, and a null
            // handler would make MediaProjection look for one that isn't there.
            Handler(Looper.getMainLooper()),
        )

        val metrics = displayMetrics()
        // Mirroring reproduces the phone's own screen, so the aspect ratio is
        // the phone's; scale it down to keep the bitrate sane.
        val scale = MIRROR_MAX_HEIGHT.toFloat() / metrics.heightPixels.coerceAtLeast(1)
        val width = ScreenEncoder.evenize(
            if (scale < 1f) (metrics.widthPixels * scale).toInt() else metrics.widthPixels,
        )
        val height = ScreenEncoder.evenize(
            if (scale < 1f) MIRROR_MAX_HEIGHT else metrics.heightPixels,
        )

        val streamId = nextStreamId.getAndIncrement()
        val encoder = newEncoder(streamId, "mirror$streamId", width, height, settings.value)
        encoder.start()
        val surface = encoder.inputSurface ?: run {
            encoder.stop()
            emit("Could not start the video encoder")
            return
        }

        val vd: VirtualDisplay? = try {
            mp.createVirtualDisplay(
                "Singular-mirror-$streamId",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "mirror display failed: ${e.message}")
            null
        }

        if (vd == null) {
            encoder.stop()
            emit("Could not start screen mirroring")
            return
        }

        val meta = CastSession(
            id = streamId,
            app = app,
            mode = CastSession.Mode.Mirror,
            width = width,
            height = height,
            dpi = metrics.densityDpi,
            displayId = -1,
            taskId = -1,
        )
        live[streamId] = LiveSession(meta, encoder, mirrorDisplay = vd)
        publishSessions()
        announceStart(meta)

        // Nothing actually moved — the PC is watching the phone's own screen —
        // so bring the requested app to the front to get as close as possible
        // to "that exact application appeared on the PC".
        launchLocally(app.pkg)
        emit("Mirroring the whole screen — the phone stays on ${app.label}.")
    }

    private fun newEncoder(
        streamId: Int,
        label: String,
        width: Int,
        height: Int,
        cfg: SingularSettings,
    ): ScreenEncoder = ScreenEncoder(
        label = label,
        width = width,
        height = height,
        bitrate = cfg.bitrateKbps * 1000,
        frameRate = cfg.frameRate,
        onConfig = { csd -> client.sendVideoConfig(streamId, csd) },
        onFrame = { data, offset, length, keyframe, pts ->
            client.sendVideoFrame(streamId, data, offset, length, keyframe, pts)
        },
        onFatal = { reason ->
            Log.e(TAG, "encoder $label fatal: $reason")
            stopSession(streamId, "encoder error")
            emit("Video encoder failed: $reason")
        },
    )

    private fun announceStart(meta: CastSession) {
        client.send(
            controlMessage("stream.start") {
                put("id", meta.id)
                put(
                    "app",
                    JSONObject().apply {
                        put("pkg", meta.app.pkg)
                        put("label", meta.app.label)
                    },
                )
                put("w", meta.width)
                put("h", meta.height)
                put("dpi", meta.dpi)
                put("mode", meta.modeWire)
            },
        )
    }

    // ----------------------------------------------------------------- recall

    /** The document's "button on android to get back that casted application". */
    fun recall(streamId: Int) {
        val session = live[streamId] ?: return
        scope.launch {
            if (session.meta.mode == CastSession.Mode.Display && session.meta.taskId >= 0) {
                val moved = shizuku.withService {
                    it.moveTaskToDisplay(session.meta.taskId, DEFAULT_DISPLAY)
                } ?: false
                if (!moved) {
                    // The display is about to be destroyed; at least put the app
                    // back in front of the user rather than silently killing it.
                    launchLocally(session.meta.app.pkg)
                }
            }
            stopSession(streamId, "recalled")
            emit("${session.meta.app.label} is back on the phone")
        }
    }

    private fun launchLocally(pkg: String) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    fun stopSession(streamId: Int, reason: String) {
        val session = live.remove(streamId) ?: return
        session.encoder.stop()
        session.mirrorDisplay?.let { runCatching { it.release() } }
        if (session.meta.displayId >= 0) {
            shizuku.withService { it.releaseDisplay(session.meta.displayId) }
        }
        if (live.values.none { it.meta.mode == CastSession.Mode.Mirror }) {
            projection?.let { runCatching { it.stop() } }
            projection = null
        }
        if (_imeTarget.value == streamId) _imeTarget.value = null
        publishSessions()
        client.send(
            controlMessage("stream.stop") {
                put("id", streamId)
                put("reason", reason)
            },
        )
        Log.i(TAG, "stream $streamId stopped: $reason")
    }

    private fun stopAllSessions(reason: String) {
        for (id in live.keys.toList()) stopSession(id, reason)
    }

    private fun publishSessions() {
        _sessions.value = live.values.map { it.meta }.sortedBy { it.id }
        _summary.value = when (val count = live.size) {
            0 -> if (client.isConnected) "Connected — nothing cast" else "Not connected"
            1 -> "Casting ${live.values.first().meta.app.label}"
            else -> "Casting $count apps"
        }
    }

    // --------------------------------------------------------- inbound control

    private suspend fun handleInbound(json: JSONObject) {
        when (json.optString("t")) {
            "apps.refresh" -> refreshApps()

            "stream.request" -> {
                val pkg = json.optString("pkg")
                val app = _appList.value.firstOrNull { it.pkg == pkg }
                if (app != null) castApp(app) else emit("PC asked for an unknown app: $pkg")
            }

            "stream.recall" -> recall(json.optInt("id"))

            "stream.geometry" -> resizeStream(
                json.optInt("id"),
                json.optInt("w"),
                json.optInt("h"),
                json.optInt("dpi"),
            )

            "stream.bitrate" -> live[json.optInt("id")]?.encoder?.setBitrate(json.optInt("bps"))

            "stream.keyframe" -> live[json.optInt("id")]?.encoder?.requestKeyFrame()

            "input.touch" -> onTouch(json)
            "input.scroll" -> onScroll(json)
            "input.key" -> onKey(json)
            "input.text" -> onText(json)
        }
    }

    /**
     * Re-cut the virtual display to the tile's new size. The display survives —
     * only the encoder is swapped — so the app is re-laid out rather than
     * restarted.
     */
    private fun resizeStream(streamId: Int, width: Int, height: Int, dpi: Int) {
        val session = live[streamId] ?: return
        if (session.meta.mode != CastSession.Mode.Display) return

        val w = ScreenEncoder.evenize(width.coerceIn(320, 3840))
        val h = ScreenEncoder.evenize(height.coerceIn(320, 2160))
        if (w == session.meta.width && h == session.meta.height) return

        val fresh = newEncoder(streamId, "vd$streamId", w, h, settings.value)
        fresh.start()
        val surface = fresh.inputSurface ?: run {
            fresh.stop()
            return
        }

        val ok = shizuku.withService { service ->
            service.resizeDisplay(session.meta.displayId, w, h, dpi) &&
                service.setDisplaySurface(session.meta.displayId, surface)
        } ?: false

        if (!ok) {
            fresh.stop()
            Log.w(TAG, "resize of stream $streamId rejected")
            return
        }

        val old = session.encoder
        session.encoder = fresh
        session.meta = session.meta.copy(width = w, height = h, dpi = dpi)
        old.stop()
        publishSessions()
        client.send(
            controlMessage("stream.resized") {
                put("id", streamId)
                put("w", w)
                put("h", h)
                put("dpi", dpi)
            },
        )
    }

    // ------------------------------------------------------------------ input

    private fun onTouch(json: JSONObject) {
        val session = live[json.optInt("id")] ?: return
        val action = when (json.optString("action")) {
            "down" -> MotionEvent.ACTION_DOWN
            "move" -> MotionEvent.ACTION_MOVE
            "up" -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_CANCEL
        }
        injectTouch(session, action, json.optDouble("x").toFloat(), json.optDouble("y").toFloat())
    }

    /**
     * Normalized coordinates in, real pixels out. Called both from the PC's
     * mouse and from the phone's touchpad, so the two paths cannot diverge.
     */
    fun injectTouch(streamId: Int, action: Int, normX: Float, normY: Float) {
        live[streamId]?.let { injectTouch(it, action, normX, normY) }
    }

    private fun injectTouch(session: LiveSession, action: Int, normX: Float, normY: Float) {
        val x = normX.coerceIn(0f, 1f) * session.meta.width
        val y = normY.coerceIn(0f, 1f) * session.meta.height

        if (session.meta.mode == CastSession.Mode.Display) {
            if (action == MotionEvent.ACTION_DOWN) {
                session.touchDownTime = android.os.SystemClock.uptimeMillis()
            }
            shizuku.withService {
                it.injectTouch(session.meta.displayId, action, x, y, session.touchDownTime)
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                session.touchDownTime = 0L
            }
            return
        }

        // Mirror fallback: accessibility cannot stream a pointer, so the drag is
        // buffered and replayed as one tap or swipe when the pointer lifts.
        val a11y = SingularAccessibilityService.instance ?: return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                session.mirrorDownX = x
                session.mirrorDownY = y
                session.touchDownTime = android.os.SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = android.os.SystemClock.uptimeMillis() - session.touchDownTime
                val dx = x - session.mirrorDownX
                val dy = y - session.mirrorDownY
                val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                if (distance < TAP_SLOP_PX) {
                    a11y.tap(session.mirrorDownX, session.mirrorDownY)
                } else {
                    a11y.swipe(
                        session.mirrorDownX,
                        session.mirrorDownY,
                        x,
                        y,
                        elapsed.coerceIn(60L, 1_200L),
                    )
                }
                session.touchDownTime = 0L
            }
        }
    }

    /**
     * Scroll from the phone's touchpad. [dyFraction] is how far the finger
     * travelled as a fraction of the display; dragging down scrolls the content
     * with the finger, which is a positive `AXIS_VSCROLL` for Android.
     */
    fun injectScroll(streamId: Int, normX: Float, normY: Float, dyFraction: Float) {
        val session = live[streamId] ?: return
        val x = normX.coerceIn(0f, 1f) * session.meta.width
        val y = normY.coerceIn(0f, 1f) * session.meta.height

        if (session.meta.mode == CastSession.Mode.Display) {
            shizuku.withService {
                it.injectScroll(session.meta.displayId, x, y, 0f, dyFraction * TOUCHPAD_SCROLL_GAIN)
            }
            return
        }
        val a11y = SingularAccessibilityService.instance ?: return
        val travel = dyFraction * session.meta.height
        a11y.swipe(x, y, x, (y + travel).coerceIn(0f, session.meta.height.toFloat()), 90L)
    }

    private fun onScroll(json: JSONObject) {
        val session = live[json.optInt("id")] ?: return
        val x = json.optDouble("x").toFloat().coerceIn(0f, 1f) * session.meta.width
        val y = json.optDouble("y").toFloat().coerceIn(0f, 1f) * session.meta.height
        val dx = json.optDouble("dx").toFloat()
        val dy = json.optDouble("dy").toFloat()

        if (session.meta.mode == CastSession.Mode.Display) {
            shizuku.withService { it.injectScroll(session.meta.displayId, x, y, dx, dy) }
            return
        }
        // No scroll event without privileges — approximate with a flick.
        val a11y = SingularAccessibilityService.instance ?: return
        val travel = (dy * SCROLL_TO_SWIPE_PX).coerceIn(-600f, 600f)
        a11y.swipe(x, y, x, (y + travel).coerceIn(0f, session.meta.height.toFloat()), 120L)
    }

    private fun onKey(json: JSONObject) {
        val session = live[json.optInt("id")] ?: return
        val keyCode = json.optInt("keyCode")
        val action = if (json.optString("action") == "down") {
            android.view.KeyEvent.ACTION_DOWN
        } else {
            android.view.KeyEvent.ACTION_UP
        }

        if (session.meta.mode == CastSession.Mode.Display) {
            shizuku.withService {
                it.injectKey(session.meta.displayId, action, keyCode, json.optInt("meta"), 0L)
            }
            return
        }
        // Only the keys accessibility can express map onto the mirror path.
        if (action != android.view.KeyEvent.ACTION_DOWN) return
        val a11y = SingularAccessibilityService.instance ?: return
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> a11y.globalBack()
            android.view.KeyEvent.KEYCODE_HOME -> a11y.globalHome()
            android.view.KeyEvent.KEYCODE_APP_SWITCH -> a11y.globalRecents()
            android.view.KeyEvent.KEYCODE_DEL -> a11y.backspace()
        }
    }

    private fun onText(json: JSONObject) {
        val session = live[json.optInt("id")] ?: return
        val text = json.optString("text")
        if (text.isEmpty()) return
        sendText(session.meta.id, text)
    }

    /** Also used by the phone's own keyboard bridge on the touchpad screen. */
    fun sendText(streamId: Int, text: String) {
        val session = live[streamId] ?: return
        if (session.meta.mode == CastSession.Mode.Display) {
            shizuku.withService { it.injectText(session.meta.displayId, text) }
        } else {
            SingularAccessibilityService.instance?.commitText(text)
        }
    }

    fun sendKey(streamId: Int, keyCode: Int) {
        val session = live[streamId] ?: return
        if (session.meta.mode == CastSession.Mode.Display) {
            shizuku.withService {
                it.injectKey(session.meta.displayId, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, 0L)
                it.injectKey(session.meta.displayId, android.view.KeyEvent.ACTION_UP, keyCode, 0, 0L)
            }
        } else {
            val a11y = SingularAccessibilityService.instance ?: return
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> a11y.globalBack()
                android.view.KeyEvent.KEYCODE_HOME -> a11y.globalHome()
                android.view.KeyEvent.KEYCODE_APP_SWITCH -> a11y.globalRecents()
                android.view.KeyEvent.KEYCODE_DEL -> a11y.backspace()
            }
        }
    }

    // ------------------------------------------------- media projection consent

    private suspend fun requestProjectionConsent(): Intent? {
        val deferred = CompletableDeferred<Intent?>()
        projectionConsent = deferred
        _projectionRequest.value = true
        return withTimeoutOrNull(CONSENT_TIMEOUT_MS) { deferred.await() }
            .also { _projectionRequest.value = false }
    }

    /** Called by [com.singular.cast.MainActivity] with the consent result. */
    fun onProjectionResult(data: Intent?) {
        _projectionRequest.value = false
        projectionConsent?.complete(data)
        projectionConsent = null
    }

    // ----------------------------------------------------------------- events

    private fun emit(message: String) {
        _events.value = message
        client.send(
            controlMessage("toast") {
                put("text", message)
                put("level", "info")
            },
        )
    }

    fun consumeEvent() {
        _events.value = null
    }

    /**
     * Mutated from the network reader, the encoder callback and the UI, so
     * every field that changes after construction is volatile.
     */
    private class LiveSession(
        meta: CastSession,
        encoder: ScreenEncoder,
        val mirrorDisplay: VirtualDisplay? = null,
    ) {
        @Volatile
        var meta: CastSession = meta

        @Volatile
        var encoder: ScreenEncoder = encoder

        @Volatile
        var touchDownTime: Long = 0L

        @Volatile
        var mirrorDownX: Float = 0f

        @Volatile
        var mirrorDownY: Float = 0f
    }

    private const val DEFAULT_DISPLAY = 0
    private const val MIRROR_MAX_HEIGHT = 1280
    private const val TAP_SLOP_PX = 18.0
    private const val SCROLL_TO_SWIPE_PX = 120f

    /** Notches of wheel scroll per full-display finger travel. */
    private const val TOUCHPAD_SCROLL_GAIN = 12f
    private const val TASK_LOOKUP_TIMEOUT_MS = 4_000L
    private const val CONSENT_TIMEOUT_MS = 60_000L
}
