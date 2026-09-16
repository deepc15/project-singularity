package com.singular.cast.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Two jobs, both of which an ordinary app cannot do:
 *
 * 1. **Editable-focus watching.** When the cast app focuses a text field we
 *    report it, and the phone raises its own keyboard — the document's
 *    "when text interaction needed, automatically keyboard will appear on
 *    android device".
 * 2. **Fallback input.** With no Shizuku, gestures dispatched through
 *    accessibility are the only way to drive the screen. This only reaches the
 *    default display, which is exactly the mirror-mode case.
 */
class SingularAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        _editableFocus.value = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Nothing to interrupt — we produce no feedback of our own.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> reportFocus(event)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A new window very rarely opens straight into a text field;
                // clear the hint so a stale prompt doesn't linger on the PC.
                _editableFocus.value = null
            }

            else -> Unit
        }
    }

    private fun reportFocus(event: AccessibilityEvent) {
        // Our own text fields (the keyboard bridge) must not be mistaken for a
        // remote app asking for input — that would be a feedback loop.
        if (event.packageName?.toString() == packageName) return

        val source = event.source
        val editable = source?.isEditable == true
        _editableFocus.value = if (editable) {
            EditableFocus(
                displayId = displayIdOf(event.windowId),
                packageName = event.packageName?.toString().orEmpty(),
                existingText = source?.text?.toString().orEmpty(),
            )
        } else {
            null
        }
        // No recycle(): node pooling was removed in API 33 and the method is
        // deprecated, so these are ordinary garbage-collected objects now.
    }

    /**
     * Map a window to its display so the hint can be attributed to the right
     * cast tile. Multi-display window enumeration only exists from API 30.
     */
    private fun displayIdOf(windowId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return DEFAULT_DISPLAY
        return try {
            for (i in 0 until windowsOnAllDisplays.size()) {
                val displayId = windowsOnAllDisplays.keyAt(i)
                if (windowsOnAllDisplays.valueAt(i).any { it.id == windowId }) return displayId
            }
            DEFAULT_DISPLAY
        } catch (e: Exception) {
            DEFAULT_DISPLAY
        }
    }

    // ------------------------------------------------------------- fallback input

    /** Tap at absolute screen pixels on the default display. */
    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, TAP_DURATION_MS)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(x1, y1, x2, y2, durationMs)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return try {
            dispatchGesture(description, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed: ${e.message}")
            false
        }
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Append text to whatever field currently has input focus. Accessibility
     * has no way to synthesise a keystroke, so the field's value is rewritten;
     * fields that reject ACTION_SET_TEXT simply do not receive the text.
     */
    fun commitText(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            val existing = node.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    existing + text,
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "commitText failed: ${e.message}")
            false
        }
    }

    /** Backspace has no gesture equivalent; rewrite the field one char shorter. */
    fun backspace(): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = node.text?.toString().orEmpty()
        if (existing.isEmpty()) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing.dropLast(1),
            )
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "backspace failed: ${e.message}")
            false
        }
    }

    data class EditableFocus(
        val displayId: Int,
        val packageName: String,
        val existingText: String,
    )

    companion object {
        private const val TAG = "SingularA11y"
        private const val DEFAULT_DISPLAY = 0
        private const val TAP_DURATION_MS = 40L

        @Volatile
        var instance: SingularAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /** Whether the user has enabled the service in Settings. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _editableFocus = MutableStateFlow<EditableFocus?>(null)

        /** Non-null while a text field somewhere has input focus. */
        val editableFocus: StateFlow<EditableFocus?> = _editableFocus.asStateFlow()
    }
}
