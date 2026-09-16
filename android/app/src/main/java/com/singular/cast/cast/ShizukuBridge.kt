package com.singular.cast.cast

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.singular.cast.priv.ISingularService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Owns the connection to [SingularUserService] over Shizuku.
 *
 * Everything here degrades quietly: if Shizuku is missing, not running, or the
 * user declines, [state] settles on a non-ready value and the cast engine takes
 * the MediaProjection mirroring path instead.
 */
class ShizukuBridge(private val context: Context) {

    enum class State {
        /** Shizuku is not installed, or its binder is dead. */
        Unavailable,

        /** Shizuku is alive but has not granted us access. */
        PermissionRequired,

        /** Permission granted, user service starting. */
        Connecting,

        /** Ready to create displays and inject input. */
        Ready,

        /** Permission granted but the user service could not start. */
        Failed,
    }

    private val _state = MutableStateFlow(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    @Volatile
    private var service: ISingularService? = null
    private var pending: CompletableDeferred<ISingularService?>? = null

    val isReady: Boolean get() = service != null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, SingularUserService::class.java.name),
        )
            .processNameSuffix("priv")
            .debuggable(false)
            // Not a daemon: when Singular Cast goes away, so should the shell
            // process holding our virtual displays.
            .daemon(false)
            .version(SERVICE_VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                fail("user service returned a dead binder")
                return
            }
            val bound = ISingularService.Stub.asInterface(binder)
            service = bound
            _state.value = State.Ready
            _detail.value = runCatching { bound.describe() }.getOrElse { "shell" }
            Log.i(TAG, "privileged bridge ready: ${_detail.value}")
            pending?.complete(bound)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "privileged bridge disconnected")
            service = null
            if (_state.value == State.Ready) _state.value = State.Connecting
            pending?.complete(null)
            pending = null
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        _state.value = State.Unavailable
        _detail.value = "Shizuku stopped"
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
            else {
                _state.value = State.PermissionRequired
                _detail.value = "Permission denied"
            }
        }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        unbind()
    }

    private fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            service = null
            _state.value = State.Unavailable
            _detail.value = "Shizuku is not running"
            return
        }
        if (Shizuku.isPreV11()) {
            _state.value = State.Unavailable
            _detail.value = "Shizuku is too old — v11 or newer is required"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else {
            _state.value = State.PermissionRequired
            _detail.value = "Tap to grant Shizuku access"
        }
    }

    /** Called from the UI when the user taps the permission prompt. */
    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    private fun bind() {
        if (service != null) {
            _state.value = State.Ready
            return
        }
        _state.value = State.Connecting
        _detail.value = "Starting privileged helper…"
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun unbind() {
        service = null
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbind failed: ${e.message}")
        }
    }

    private fun fail(reason: String) {
        Log.e(TAG, "privileged bridge failed: $reason")
        service = null
        _state.value = State.Failed
        _detail.value = reason
        pending?.complete(null)
        pending = null
    }

    /** Await the user service, if one can plausibly be had. */
    suspend fun awaitService(timeoutMs: Long = BIND_TIMEOUT_MS): ISingularService? {
        service?.let { return it }
        if (_state.value == State.Unavailable || _state.value == State.PermissionRequired) return null

        val deferred = pending ?: CompletableDeferred<ISingularService?>().also { pending = it }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /**
     * Run [block] against the privileged service, returning null if it is
     * absent or the call died with the shell process.
     */
    fun <T> withService(block: (ISingularService) -> T): T? {
        val bound = service ?: return null
        return try {
            block(bound)
        } catch (e: Throwable) {
            Log.w(TAG, "privileged call failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SingularShizuku"
        private const val PERMISSION_REQUEST_CODE = 4201
        private const val SERVICE_VERSION = 1
        private const val BIND_TIMEOUT_MS = 8_000L
    }
}
