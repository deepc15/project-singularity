package com.singular.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singular.cast.cast.CastEngine
import com.singular.cast.ime.SingularAccessibilityService
import com.singular.cast.model.CastSession
import com.singular.cast.net.ConnectionState
import kotlinx.coroutines.launch

private enum class Tab(val label: String) { Connect("PC"), Apps("Apps"), Control("Control"), Settings("Settings") }

/**
 * The whole phone UI: pick a PC, swipe apps at it, drive them, tune behaviour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingularUi(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
) {
    val engine = CastEngine
    val connection by engine.connection.collectAsState()
    val sessions by engine.sessions.collectAsState()
    val apps by engine.appList.collectAsState()
    val settings by engine.settings.collectAsState()
    val event by engine.events.collectAsState()
    val imeTarget by engine.imeTarget.collectAsState()
    val shizukuState by engine.shizuku.state.collectAsState()
    val shizukuDetail by engine.shizuku.detail.collectAsState()
    val accessibilityOn by SingularAccessibilityService.connected.collectAsState()

    var tab by remember { mutableStateOf(Tab.Connect) }
    var selectedSession by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Land on the app list as soon as there is somewhere to cast to.
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected && tab == Tab.Connect) tab = Tab.Apps
    }

    // Following the cast: a new session becomes the control target.
    LaunchedEffect(sessions) {
        if (sessions.none { it.id == selectedSession }) {
            selectedSession = sessions.lastOrNull()?.id
        }
    }

    // The app asking for text is the one the user wants to type into.
    LaunchedEffect(imeTarget) {
        imeTarget?.let {
            selectedSession = it
            tab = Tab.Control
        }
    }

    LaunchedEffect(event) {
        event?.let {
            snackbar.showSnackbar(it)
            engine.consumeEvent()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Singular Cast") },
                actions = {
                    ConnectionChip(connection)
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                when (entry) {
                                    Tab.Connect -> Icons.Filled.Wifi
                                    Tab.Apps -> Icons.Filled.Cast
                                    Tab.Control -> Icons.Filled.TouchApp
                                    Tab.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            if (sessions.isNotEmpty()) {
                ActiveCasts(
                    sessions = sessions,
                    selected = selectedSession,
                    onSelect = {
                        selectedSession = it
                        tab = Tab.Control
                    },
                    onRecall = engine::recall,
                )
            }

            when (tab) {
                Tab.Connect -> ConnectScreen(
                    state = connection,
                    lastHost = settings.lastHost,
                    lastPort = settings.lastPort,
                    autoConnect = settings.autoConnect,
                    onConnect = engine::connect,
                    onDisconnect = engine::disconnect,
                    onAutoConnectChange = {
                        scope.launch { engine.settingsStore.setAutoConnect(it) }
                    },
                )

                Tab.Apps -> AppListScreen(
                    apps = apps,
                    edge = settings.edge,
                    threshold = settings.swipeThreshold,
                    haptics = settings.haptics,
                    castable = connection is ConnectionState.Connected,
                    castingPackages = sessions.map { it.app.pkg }.toSet(),
                    onCast = engine::castApp,
                    modifier = Modifier.fillMaxSize(),
                )

                Tab.Control -> {
                    val session = sessions.firstOrNull { it.id == selectedSession }
                    if (session == null) {
                        EmptyControl()
                    } else {
                        TouchpadScreen(
                            session = session,
                            imeRequested = imeTarget == session.id,
                            onEvent = { event ->
                                when (event) {
                                    is TouchpadEvent.Touch ->
                                        engine.injectTouch(session.id, event.action, event.x, event.y)

                                    is TouchpadEvent.Scroll ->
                                        engine.injectScroll(session.id, event.x, event.y, event.dy)
                                }
                            },
                            onKey = { engine.sendKey(session.id, it) },
                            onText = { engine.sendText(session.id, it) },
                            onRecall = { engine.recall(session.id) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Tab.Settings -> SettingsScreen(
                    settings = settings,
                    shizukuState = shizukuState,
                    shizukuDetail = shizukuDetail,
                    accessibilityEnabled = accessibilityOn,
                    usageAccessGranted = engine.hasUsageAccess(),
                    onEdge = { scope.launch { engine.settingsStore.setEdge(it) } },
                    onThreshold = { scope.launch { engine.settingsStore.setThreshold(it) } },
                    onBitrate = { scope.launch { engine.settingsStore.setBitrate(it) } },
                    onFrameRate = { scope.launch { engine.settingsStore.setFrameRate(it) } },
                    onAutoKeyboard = { scope.launch { engine.settingsStore.setAutoKeyboard(it) } },
                    onHaptics = { scope.launch { engine.settingsStore.setHaptics(it) } },
                    onRequestShizuku = engine.shizuku::requestPermission,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenUsageSettings = onOpenUsageSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ConnectionChip(state: ConnectionState) {
    val (label, ready) = when (state) {
        is ConnectionState.Connected -> state.pcName to true
        is ConnectionState.Connecting -> "Connecting…" to false
        is ConnectionState.Failed -> "Offline" to false
        ConnectionState.Idle -> "Offline" to false
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (ready) Icons.Filled.Cast else Icons.Filled.Wifi,
                contentDescription = null,
            )
        },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** Strip of apps currently on the PC, each with the "bring back" button. */
@Composable
private fun ActiveCasts(
    sessions: List<CastSession>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onRecall: (Int) -> Unit,
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { onSelect(session.id) },
                    label = {
                        Text(
                            session.app.label,
                            style = if (session.id == selected) {
                                MaterialTheme.typography.labelLarge
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Cast, contentDescription = null) },
                )
                AssistChip(
                    onClick = { onRecall(session.id) },
                    label = { Text("Bring back") },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyControl() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Nothing is on the PC yet.\nSwipe an app from the Apps tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}
