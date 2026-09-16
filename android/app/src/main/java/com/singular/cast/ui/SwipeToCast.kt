package com.singular.cast.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.singular.cast.model.CastEdge
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Drag a row toward the configured edge to throw the app at the PC.
 *
 * Only motion in the configured direction counts; dragging the other way is
 * clamped so a stray flick can never fire a cast.
 */
@Composable
fun SwipeToCast(
    edge: CastEdge,
    threshold: Float,
    haptics: Boolean,
    enabled: Boolean,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val feedback = LocalHapticFeedback.current
    var extent by remember { mutableIntStateOf(1) }
    var armed by remember { mutableStateOf(false) }

    val progress = (abs(offset.value) / (extent * threshold).coerceAtLeast(1f)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { extent = if (edge.isHorizontal) it.width else it.height },
    ) {
        // Revealed behind the row as it slides away.
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f + 0.5f * progress)),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                // The gap opens up on the side the row slides away from.
                horizontalArrangement =
                    if (edge == CastEdge.Left) Arrangement.End else Arrangement.Start,
            ) {
                Text(
                    text = if (progress >= 1f) "Release to cast" else "Keep going…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(if (progress > 0.05f) 1f else 0f),
                )
            }
        }

        Box(
            Modifier
                .offset {
                    val value = offset.value.roundToInt()
                    if (edge.isHorizontal) IntOffset(value, 0) else IntOffset(0, value)
                }
                .draggable(
                    enabled = enabled,
                    orientation = if (edge.isHorizontal) Orientation.Horizontal else Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        // Clamp to the configured direction only, and compute
                        // the target here — snapTo is async, so reading
                        // offset.value back would be a frame stale.
                        val next =
                            ((offset.value + delta) * edge.sign).coerceAtLeast(0f) * edge.sign
                        scope.launch { offset.snapTo(next) }

                        val nowArmed = abs(next) >= extent * threshold
                        if (nowArmed != armed) {
                            armed = nowArmed
                            if (nowArmed && haptics) {
                                feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onDragStopped = {
                        val committed = abs(offset.value) >= extent * threshold
                        armed = false
                        if (committed) {
                            if (haptics) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCast()
                        }
                        offset.animateTo(0f)
                    },
                ),
        ) {
            content()
        }
    }
}
