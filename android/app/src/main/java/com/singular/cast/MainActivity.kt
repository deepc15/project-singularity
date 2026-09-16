package com.singular.cast

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.singular.cast.cast.CastEngine
import com.singular.cast.net.ConnectionState
import com.singular.cast.ui.SingularUi
import com.singular.cast.ui.theme.SingularTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Screen-capture consent for the mirroring fallback. The engine suspends on
     * a deferred until this returns, so the answer is routed straight back.
     */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        CastEngine.onProjectionResult(
            if (result.resultCode == RESULT_OK) result.data else null,
        )
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The cast works either way; the notification is just nicer. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CastEngine.init(application)
        askForNotifications()
        observeProjectionRequests()
        autoConnect()

        setContent {
            SingularTheme {
                SingularUi(
                    onOpenAccessibilitySettings = {
                        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    },
                    onOpenUsageSettings = {
                        openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and Shizuku state can change while we were away, and the
        // running-app markers go stale fast.
        lifecycleScope.launch { CastEngine.refreshApps() }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun observeProjectionRequests() {
        lifecycleScope.launch {
            CastEngine.projectionRequest.collect { wanted ->
                if (!wanted) return@collect
                val manager = getSystemService(MediaProjectionManager::class.java)
                // A PC-initiated cast can arrive while the activity is not in
                // the foreground, where launching the consent dialog throws.
                runCatching { projectionLauncher.launch(manager.createScreenCaptureIntent()) }
                    .onFailure { CastEngine.onProjectionResult(null) }
            }
        }
    }

    private fun autoConnect() {
        lifecycleScope.launch {
            // Read the store directly: the shared StateFlow starts on defaults,
            // so `settings.first()` would race the first DataStore read.
            val settings = CastEngine.settingsStore.flow.first()
            if (!settings.autoConnect || settings.lastHost.isEmpty()) return@launch
            if (CastEngine.connection.value is ConnectionState.Connected) return@launch
            CastEngine.connect(settings.lastHost, settings.lastPort)
        }
    }

    private fun openSettings(action: String) {
        runCatching {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
