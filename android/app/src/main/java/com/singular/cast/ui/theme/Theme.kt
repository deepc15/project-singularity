package com.singular.cast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4C9FFE)
private val AccentDark = Color(0xFF1E5FA8)
private val Surface = Color(0xFF11161C)
private val SurfaceHigh = Color(0xFF1A212A)
private val Background = Color(0xFF0D1014)
private val Ok = Color(0xFF3ECF8E)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF07131F),
    primaryContainer = AccentDark,
    onPrimaryContainer = Color.White,
    secondary = Ok,
    background = Background,
    onBackground = Color(0xFFE6EBF1),
    surface = Surface,
    onSurface = Color(0xFFE6EBF1),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFF9FAEBF),
    outline = Color(0xFF3A4653),
)

private val Light = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF1E8E63),
)

@Composable
fun SingularTheme(content: @Composable () -> Unit) {
    // The app is designed dark; light is a courtesy for forced-light devices.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
