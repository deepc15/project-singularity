package com.singular.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singular.cast.cast.ShizukuBridge
import com.singular.cast.model.CastEdge
import com.singular.cast.model.SingularSettings
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: SingularSettings,
    shizukuState: ShizukuBridge.State,
    shizukuDetail: String,
    accessibilityEnabled: Boolean,
    usageAccessGranted: Boolean,
    onEdge: (CastEdge) -> Unit,
    onThreshold: (Float) -> Unit,
    onBitrate: (Int) -> Unit,
    onFrameRate: (Int) -> Unit,
    onAutoKeyboard: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Swipe to cast") {
            Text(
                "Which way you throw an app at the PC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (edge in CastEdge.entries) {
                    FilterChip(
                        selected = settings.edge == edge,
                        onClick = { onEdge(edge) },
                        label = { Text(edge.label) },
                    )
                }
            }
            Text(
                "Distance before it fires: ${(settings.swipeThreshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.swipeThreshold,
                onValueChange = onThreshold,
                valueRange = 0.15f..0.8f,
                steps = 12,
            )
            Toggle("Vibrate when a swipe commits", settings.haptics, onHaptics)
        }

        Section("Video") {
            Text(
                "Bitrate: ${settings.bitrateKbps / 1000} Mbps",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.bitrateKbps.toFloat(),
                onValueChange = { onBitrate(it.roundToInt()) },
                valueRange = 1_000f..40_000f,
                steps = 38,
            )
            Text("Frame rate: ${settings.frameRate} fps", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.frameRate.toFloat(),
                onValueChange = { onFrameRate(it.roundToInt()) },
                valueRange = 24f..120f,
                steps = 7,
            )
            Text(
                "Changes apply to the next app you cast.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Keyboard") {
            Toggle(
                "Raise the keyboard automatically when a cast app asks for text",
                settings.autoKeyboard,
                onAutoKeyboard,
            )
            Text(
                "Needs the accessibility bridge below — that is how the app notices " +
                    "a text field taking focus on the PC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Device access") {
            PermissionRow(
                title = "Shizuku — per-app displays",
                detail = when (shizukuState) {
                    ShizukuBridge.State.Ready -> "Ready · $shizukuDetail"
                    ShizukuBridge.State.Connecting -> "Starting…"
                    ShizukuBridge.State.PermissionRequired -> "Tap to grant"
                    ShizukuBridge.State.Failed -> "Failed · $shizukuDetail"
                    ShizukuBridge.State.Unavailable -> shizukuDetail.ifEmpty { "Not running" }
                },
                ready = shizukuState == ShizukuBridge.State.Ready,
                actionLabel = "Grant",
                onAction = onRequestShizuku,
            )
            Text(
                "Without Shizuku, Singular Cast can only mirror the whole screen, and the " +
                    "phone cannot be used for anything else while casting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            PermissionRow(
                title = "Accessibility bridge",
                detail = if (accessibilityEnabled) "Enabled" else "Off — no automatic keyboard",
                ready = accessibilityEnabled,
                actionLabel = "Open settings",
                onAction = onOpenAccessibilitySettings,
            )
            HorizontalDivider()
            PermissionRow(
                title = "Usage access",
                detail = if (usageAccessGranted) {
                    "Granted — recent apps are marked"
                } else {
                    "Off — the list cannot show what is active"
                },
                ready = usageAccessGranted,
                actionLabel = "Open settings",
                onAction = onOpenUsageSettings,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(0.8f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.fillMaxWidth(0.66f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!ready) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
