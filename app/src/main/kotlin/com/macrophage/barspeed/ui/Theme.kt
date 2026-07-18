package com.macrophage.barspeed.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1565C0),
        secondary = Color(0xFF00838F),
        tertiary = Color(0xFFF9A825),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF90CAF9),
        secondary = Color(0xFF80DEEA),
        tertiary = Color(0xFFFFE082),
    )

@Composable
fun LiftingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
