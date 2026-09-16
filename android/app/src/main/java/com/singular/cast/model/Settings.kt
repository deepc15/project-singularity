package com.singular.cast.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which way the user drags an app row to throw it at the PC. The document
 * calls for the direction to be user-configurable.
 */
enum class CastEdge(val label: String) {
    Right("Swipe right"),
    Left("Swipe left"),
    Up("Swipe up"),
    Down("Swipe down"),
    ;

    val isHorizontal: Boolean get() = this == Right || this == Left

    /** Sign of the drag that counts as "toward the PC". */
    val sign: Int get() = if (this == Right || this == Down) 1 else -1
}

data class SingularSettings(
    val edge: CastEdge = CastEdge.Right,
    /** Fraction of the row that must be dragged before the cast fires. */
    val swipeThreshold: Float = 0.35f,
    val bitrateKbps: Int = 8_000,
    val frameRate: Int = 60,
    /** Raise the phone's keyboard automatically on remote editable focus. */
    val autoKeyboard: Boolean = true,
    /** Vibrate when a swipe commits. */
    val haptics: Boolean = true,
    val lastHost: String = "",
    val lastPort: Int = 8787,
    /** Reconnect to [lastHost] on launch. */
    val autoConnect: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("singular")

class SettingsStore(private val context: Context) {

    val flow: Flow<SingularSettings> = context.dataStore.data.map { prefs ->
        SingularSettings(
            edge = prefs[KEY_EDGE]?.let { name ->
                CastEdge.entries.firstOrNull { it.name == name }
            } ?: CastEdge.Right,
            swipeThreshold = (prefs[KEY_THRESHOLD] ?: 35).coerceIn(15, 80) / 100f,
            bitrateKbps = prefs[KEY_BITRATE] ?: 8_000,
            frameRate = prefs[KEY_FPS] ?: 60,
            autoKeyboard = prefs[KEY_AUTO_KEYBOARD] ?: true,
            haptics = prefs[KEY_HAPTICS] ?: true,
            lastHost = prefs[KEY_HOST].orEmpty(),
            lastPort = prefs[KEY_PORT] ?: 8787,
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true,
        )
    }

    suspend fun setEdge(edge: CastEdge) = edit { it[KEY_EDGE] = edge.name }

    /** Stored as a percentage so the preference file stays readable. */
    suspend fun setThreshold(fraction: Float) =
        edit { it[KEY_THRESHOLD] = (fraction * 100).toInt().coerceIn(15, 80) }

    suspend fun setBitrate(kbps: Int) = edit { it[KEY_BITRATE] = kbps.coerceIn(1_000, 40_000) }

    suspend fun setFrameRate(fps: Int) = edit { it[KEY_FPS] = fps.coerceIn(24, 120) }

    suspend fun setAutoKeyboard(enabled: Boolean) = edit { it[KEY_AUTO_KEYBOARD] = enabled }

    suspend fun setHaptics(enabled: Boolean) = edit { it[KEY_HAPTICS] = enabled }

    suspend fun setAutoConnect(enabled: Boolean) = edit { it[KEY_AUTO_CONNECT] = enabled }

    suspend fun rememberPc(host: String, port: Int) = edit {
        it[KEY_HOST] = host
        it[KEY_PORT] = port
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_EDGE = stringPreferencesKey("edge")
        val KEY_THRESHOLD = intPreferencesKey("threshold_pct")
        val KEY_BITRATE = intPreferencesKey("bitrate_kbps")
        val KEY_FPS = intPreferencesKey("frame_rate")
        val KEY_AUTO_KEYBOARD = booleanPreferencesKey("auto_keyboard")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_HOST = stringPreferencesKey("last_host")
        val KEY_PORT = intPreferencesKey("last_port")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    }
}
