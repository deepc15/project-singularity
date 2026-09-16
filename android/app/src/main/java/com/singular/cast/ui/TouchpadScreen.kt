package com.singular.cast.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.singular.cast.model.CastSession
import kotlin.math.abs

/** What the pad produces; coordinates are normalized 0..1 of the cast display. */
sealed interface TouchpadEvent {
    data class Touch(val action: Int, val x: Float, val y: Float) : TouchpadEvent

    /** [dy] is a 0..1 fraction of the display travelled since the last report. */
    data class Scroll(val x: Float, val y: Float, val dy: Float) : TouchpadEvent
}

/**
 * The automatic touchpad controller from the spec: while an app is on the PC,
 * this pad drives it from the phone.
 *
 * The pad maps 1:1 onto the cast display (it is given the same aspect ratio),
 * so it behaves like a remote touchscreen rather than a relative trackpad — a
 * relative pointer would need a visible cursor, which a virtual display has no
 * way to draw.
 */
@Composable
fun TouchpadScreen(
    session: CastSession,
    imeRequested: Boolean,
    onEvent: (TouchpadEvent) -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onRecall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrollMode by remember { mutableStateOf(false) }

    Column(
        modifier
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.fillMaxWidth(0.6f)) {
                Text(session.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${session.width}×${session.height} · " +
                        if (session.mode == CastSession.Mode.Display) "own display" else "screen mirror",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRecall) { Text("Bring back") }
        }

        TouchSurface(
            aspect = session.width.toFloat() / session.height.coerceAtLeast(1),
            scrollMode = scrollMode,
            onEvent = onEvent,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = scrollMode,
                onClick = { scrollMode = !scrollMode },
                label = { Text("Scroll mode") },
            )
            Text(
                "Two fingers scroll in either mode",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onKey(KeyEvent.KEYCODE_BACK) }) { Text("Back") }
            OutlinedButton(onClick = { onKey(KeyEvent.KEYCODE_HOME) }) { Text("Home") }
            OutlinedButton(onClick = { onKey(KeyEvent.KEYCODE_APP_SWITCH) }) { Text("Recents") }
        }

        KeyboardBridge(imeRequested = imeRequested, onText = onText, onKey = onKey)
    }
}

@Composable
private fun TouchSurface(
    aspect: Float,
    scrollMode: Boolean,
    onEvent: (TouchpadEvent) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .pointerInput(scrollMode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)

                    fun normalize(offset: Offset) = Offset(
                        (offset.x / w).coerceIn(0f, 1f),
                        (offset.y / h).coerceIn(0f, 1f),
                    )

                    var last = normalize(down.position)
                    // Scroll gestures must not plant a touch on the remote app,
                    // so ACTION_DOWN is withheld until we know the intent.
                    var touching = !scrollMode
                    if (touching) {
                        onEvent(TouchpadEvent.Touch(MotionEvent.ACTION_DOWN, last.x, last.y))
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break

                        val position = normalize(active.first().position)
                        // Two fingers means scroll, matching every trackpad the
                        // user already knows.
                        if (scrollMode || active.size >= 2) {
                            if (touching) {
                                // A second finger arrived mid-drag: cancel the
                                // touch rather than leaving it stuck down.
                                onEvent(
                                    TouchpadEvent.Touch(
                                        MotionEvent.ACTION_CANCEL,
                                        last.x,
                                        last.y,
                                    ),
                                )
                                touching = false
                            }
                            val dy = position.y - last.y
                            if (abs(dy) > SCROLL_EPSILON) {
                                onEvent(TouchpadEvent.Scroll(position.x, position.y, dy))
                            }
                        } else {
                            onEvent(
                                TouchpadEvent.Touch(MotionEvent.ACTION_MOVE, position.x, position.y),
                            )
                        }
                        last = position
                        event.changes.forEach { it.consume() }
                    }

                    if (touching) {
                        onEvent(TouchpadEvent.Touch(MotionEvent.ACTION_UP, last.x, last.y))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (scrollMode) "Drag to scroll the app on the PC" else "Touch here to control the app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A text field that grabs focus — and therefore raises the phone's keyboard —
 * the moment the cast app focuses an editable field. Characters are forwarded
 * as they are typed; this field is never the source of truth.
 */
@Composable
private fun KeyboardBridge(
    imeRequested: Boolean,
    onText: (String) -> Unit,
    onKey: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(imeRequested) {
        if (imeRequested) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (imeRequested) {
                    "The app is asking for text — type here"
                } else {
                    "Type to send text to the app on the PC"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (imeRequested) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    forwardEdit(value.text, next.text, onText, onKey)
                    value = next
                },
                singleLine = false,
                label = { Text("Text input") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onKey(KeyEvent.KEYCODE_ENTER) }) { Text("Enter") }
                OutlinedButton(onClick = { onKey(KeyEvent.KEYCODE_DEL) }) { Text("Backspace") }
                OutlinedButton(
                    onClick = {
                        value = TextFieldValue("")
                        keyboard?.hide()
                    },
                ) { Text("Done") }
            }
        }
    }
}

/**
 * Turn a local edit into remote input. Appends become text, deletions become
 * backspaces, and anything else (autocorrect, paste over a selection) is sent
 * as the whole replacement because it cannot be expressed as keystrokes.
 */
private fun forwardEdit(
    before: String,
    after: String,
    onText: (String) -> Unit,
    onKey: (Int) -> Unit,
) {
    when {
        after == before -> return
        after.length > before.length && after.startsWith(before) ->
            onText(after.substring(before.length))

        after.length < before.length && before.startsWith(after) ->
            repeat(before.length - after.length) { onKey(KeyEvent.KEYCODE_DEL) }

        else -> onText(after)
    }
}

private const val SCROLL_EPSILON = 0.002f
