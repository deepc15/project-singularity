package com.singular.cast.cast

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.singular.cast.priv.ISingularService
import java.lang.reflect.Method

/**
 * The privileged half of Singular Cast. Shizuku instantiates this class inside
 * the shell process (uid 2000), so calls made from here carry shell's
 * permissions rather than the app's.
 *
 * Everything the public SDK does not expose is reached by reflection, with a
 * fallback for each framework revision we care about.
 */
@Suppress("unused") // instantiated by Shizuku, by class name
class SingularUserService() : ISingularService.Stub() {

    /** Shizuku prefers a (Context) constructor when the service needs one. */
    constructor(context: Context) : this() {
        contextRef = context
    }

    private var contextRef: Context? = null
    private val displays = HashMap<Int, VirtualDisplay>()

    private val context: Context
        get() = contextRef ?: error("no context supplied to SingularUserService")

    override fun describe(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}"

    // ---------------------------------------------------------------- display

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return -1

        // TRUSTED is what allows arbitrary activities to be launched onto the
        // display; without it Android refuses and we have to fall back.
        val withTrusted = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or
            FLAG_SUPPORTS_TOUCH or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_ROTATES_WITH_CONTENT
        val withoutTrusted = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH

        for (flags in intArrayOf(withTrusted, withoutTrusted)) {
            try {
                val vd = dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
                    ?: continue
                val id = vd.display.displayId
                displays[id] = vd
                Log.i(TAG, "created virtual display $id ${width}x$height flags=0x${flags.toString(16)}")
                return id
            } catch (e: SecurityException) {
                Log.w(TAG, "createVirtualDisplay rejected for flags 0x${flags.toString(16)}: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "createVirtualDisplay failed: ${e.message}")
            }
        }
        return -1
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean {
        val vd = displays[displayId] ?: return false
        return try {
            vd.resize(width, height, densityDpi)
            true
        } catch (e: Exception) {
            Log.w(TAG, "resize failed: ${e.message}")
            false
        }
    }

    override fun setDisplaySurface(displayId: Int, surface: Surface): Boolean {
        val vd = displays[displayId] ?: return false
        return try {
            vd.surface = surface
            true
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed: ${e.message}")
            false
        }
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "release failed: ${e.message}")
            }
        }
    }

    // ----------------------------------------------------------------- launch

    @SuppressLint("QueryPermissionsNeeded") // shell can see every package
    override fun launchPackage(packageName: String, displayId: Int): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: run {
                Log.w(TAG, "$packageName has no launcher activity")
                return false
            }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch $packageName on display $displayId failed: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    override fun findTaskId(packageName: String): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return -1
        return try {
            // Shell holds REAL_GET_TASKS, so this really does list every task.
            am.getRunningTasks(MAX_TASKS)
                .firstOrNull { it.baseActivity?.packageName == packageName }
                ?.id ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "getRunningTasks failed: ${e.message}")
            -1
        }
    }

    @Suppress("DEPRECATION")
    override fun runningPackages(): Array<String> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyArray()
        return try {
            am.getRunningTasks(MAX_TASKS)
                .mapNotNull { it.baseActivity?.packageName }
                .distinct()
                .toTypedArray()
        } catch (e: Exception) {
            Log.w(TAG, "getRunningTasks failed: ${e.message}")
            emptyArray()
        }
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val service = activityTaskManager() ?: return false
        // Renamed in API 30; try the modern name first.
        for (name in arrayOf("moveRootTaskToDisplay", "moveStackToDisplay")) {
            try {
                val m = service.javaClass.getMethod(name, Int::class.java, Int::class.java)
                m.invoke(service, taskId, displayId)
                return true
            } catch (e: NoSuchMethodException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "$name($taskId, $displayId) failed: ${e.cause?.message ?: e.message}")
                return false
            }
        }
        Log.w(TAG, "no moveTaskToDisplay method on this platform")
        return false
    }

    // Reaching a hidden system service by reflection is the entire point of
    // this class; every call site is guarded and reports failure upward.
    @SuppressLint("PrivateApi")
    private fun activityTaskManager(): Any? = try {
        val cls = Class.forName("android.app.ActivityTaskManager")
        cls.getMethod("getService").invoke(null)
    } catch (e: Exception) {
        Log.w(TAG, "ActivityTaskManager.getService unavailable: ${e.message}")
        null
    }

    // ------------------------------------------------------------------ input

    override fun injectTouch(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTimeMs: Long,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val downTime = if (downTimeMs > 0) downTimeMs else now
        val event = MotionEvent.obtain(
            downTime,
            now,
            action,
            x,
            y,
            /* pressure = */ if (action == MotionEvent.ACTION_UP) 0f else 1f,
            /* size = */ 1f,
            /* metaState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(
        displayId: Int,
        action: Int,
        keyCode: Int,
        metaState: Int,
        downTimeMs: Long,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val downTime = if (downTimeMs > 0) downTimeMs else now
        val event = KeyEvent(
            downTime,
            now,
            action,
            keyCode,
            /* repeat = */ 0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            /* scancode = */ 0,
            /* flags = */ 0,
            InputDevice.SOURCE_KEYBOARD,
        )
        return inject(event, displayId)
    }

    override fun injectScroll(
        displayId: Int,
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            /* pointerCount = */ 1,
            props,
            coords,
            /* metaState = */ 0,
            /* buttonState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
            InputDevice.SOURCE_MOUSE,
            /* flags = */ 0,
        )
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    override fun injectText(displayId: Int, text: String): Boolean {
        // The virtual display has no IME, so text has to arrive as key events.
        // KeyCharacterMap covers everything reachable from a US layout; other
        // code points are dropped rather than silently mangled.
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray()) ?: run {
            Log.w(TAG, "no key sequence for ${text.length} chars")
            return false
        }
        var ok = true
        for (event in events) {
            ok = inject(event, displayId) && ok
        }
        return ok
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean {
        setDisplayId(event, displayId)
        val injector = injectMethod ?: return false
        val manager = inputManager ?: return false
        return try {
            injector.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "injectInputEvent failed: ${e.cause?.message ?: e.message}")
            false
        }
    }

    /** Hidden since forever, but the only way to target a non-default display. */
    private fun setDisplayId(event: InputEvent, displayId: Int) {
        try {
            InputEvent::class.java
                .getMethod("setDisplayId", Int::class.java)
                .invoke(event, displayId)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayId unavailable: ${e.message}")
        }
    }

    /**
     * `InputManager.getInstance()` was removed in API 34 in favour of
     * `InputManagerGlobal`; both expose the same `injectInputEvent`.
     */
    private val inputManager: Any? by lazy {
        val candidates = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager",
        )
        for (name in candidates) {
            try {
                return@lazy Class.forName(name).getMethod("getInstance").invoke(null)
            } catch (e: Exception) {
                continue
            }
        }
        Log.e(TAG, "no InputManager instance available")
        null
    }

    private val injectMethod: Method? by lazy {
        val manager = inputManager ?: return@lazy null
        try {
            manager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java,
            )
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent not found: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------- destroy

    override fun destroy() {
        for ((_, vd) in displays) {
            try {
                vd.release()
            } catch (_: Exception) {
                // Best effort — the process is going away anyway.
            }
        }
        displays.clear()
        System.exit(0)
    }

    private companion object {
        const val TAG = "SingularPriv"
        const val MAX_TASKS = 200
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        // android.hardware.display.DisplayManager virtual display flags. The
        // public constants stop at PRESENTATION, so the rest are inlined.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val FLAG_TRUSTED = 1 shl 10
    }
}
