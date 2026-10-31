package com.voiceassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1B6EF3)
private val BlueDark = Color(0xFF3D8BFF)
private val Green = Color(0xFF34C759)
private val Amber = Color(0xFFFFB020)
private val Red = Color(0xFFFF453A)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF04101F),
    secondary = Green,
    tertiary = Amber,
    error = Red,
    background = Color(0xFF0E1116),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1E242D),
    onBackground = Color(0xFFE6EAF0),
    onSurface = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF9AA4B2)
)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Green,
    tertiary = Amber,
    error = Red
)

@Composable
fun VoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
