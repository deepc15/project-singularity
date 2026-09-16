package com.singular.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.singular.cast.net.ConnectionState
import com.singular.cast.net.DiscoveredPc
import com.singular.cast.net.Discovery
import kotlinx.coroutines.launch

/**
 * PC picker: broadcast discovery with a manual-IP escape hatch for networks
 * that drop UDP broadcast (most corporate Wi-Fi).
 */
@Composable
fun ConnectScreen(
    state: ConnectionState,
    lastHost: String,
    lastPort: Int,
    autoConnect: Boolean,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }
    var manual by remember { mutableStateOf(lastHost) }
    var manualError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            found = Discovery.scan()
            scanning = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    // The remembered host arrives a moment after first composition, once
    // DataStore has been read.
    LaunchedEffect(lastHost) { if (manual.isBlank()) manual = lastHost }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionCard(state, onDisconnect)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PCs on this network", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = ::scan, enabled = !scanning) {
                Text(if (scanning) "Scanning…" else "Scan again")
            }
        }

        if (scanning && found.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Looking for Singular Desk…", style = MaterialTheme.typography.bodySmall)
            }
        }

        for (pc in found) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(pc.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${pc.host}:${pc.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onConnect(pc.host, pc.port) }) { Text("Connect") }
                }
            }
        }

        if (!scanning && found.isEmpty()) {
            Text(
                "No PC answered. Make sure Singular Desk is open, both devices are on the " +
                    "same network, and Windows Firewall allows it — otherwise type the IP below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Connect by address", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = manual,
            onValueChange = {
                manual = it
                manualError = null
            },
            label = { Text("192.168.1.20 or 192.168.1.20:8787") },
            singleLine = true,
            isError = manualError != null,
            supportingText = { manualError?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val parsed = Discovery.parseManual(manual)
                    if (parsed == null) manualError = "That is not a valid address"
                    else onConnect(parsed.hostName, parsed.port)
                },
            ) { Text("Connect") }

            OutlinedButton(onClick = { onAutoConnectChange(!autoConnect) }) {
                Text(if (autoConnect) "Auto-connect on" else "Auto-connect off")
            }
        }

        if (lastHost.isNotEmpty()) {
            Text(
                "Last used: $lastHost:$lastPort",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionCard(state: ConnectionState, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth(0.7f)) {
                Text(
                    when (state) {
                        is ConnectionState.Connected -> "Connected"
                        is ConnectionState.Connecting -> "Connecting"
                        is ConnectionState.Failed -> "Not connected"
                        ConnectionState.Idle -> "Not connected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (state) {
                        is ConnectionState.Connected -> "${state.pcName} · ${state.host}:${state.port}"
                        is ConnectionState.Connecting -> "${state.host}:${state.port}"
                        is ConnectionState.Failed -> state.reason
                        ConnectionState.Idle -> "Pick a PC below"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is ConnectionState.Connected) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}
