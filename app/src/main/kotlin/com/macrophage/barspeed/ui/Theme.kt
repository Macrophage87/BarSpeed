package com.macrophage.barspeed.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors =
    darkColorScheme(
        primary = BarColors.Volt,
        onPrimary = BarColors.Bg,
        primaryContainer = BarColors.HeroGreen,
        onPrimaryContainer = BarColors.Volt,
        secondary = BarColors.Blue,
        onSecondary = BarColors.Bg,
        tertiary = BarColors.Amber,
        onTertiary = BarColors.Bg,
        error = BarColors.Red,
        onError = BarColors.Bg,
        background = BarColors.Bg,
        onBackground = BarColors.Text,
        surface = BarColors.Bg,
        onSurface = BarColors.Text,
        surfaceVariant = BarColors.Surface,
        onSurfaceVariant = BarColors.Sub,
        surfaceContainer = BarColors.Surface,
        surfaceContainerHigh = BarColors.Surface,
        surfaceContainerHighest = BarColors.Track,
        surfaceContainerLow = BarColors.Surface,
        outline = BarColors.Ghost,
        outlineVariant = BarColors.Track,
    )

/** Tabular numerals so live metrics don't jitter as digits change. */
val MetricNumerals = TextStyle(fontFeatureSettings = "tnum")

private val BarTypography =
    Typography(
        displayLarge =
        TextStyle(
            fontSize = 64.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 68.sp,
            fontFeatureSettings = "tnum",
        ),
        displayMedium =
        TextStyle(
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 48.sp,
            fontFeatureSettings = "tnum",
        ),
        headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
        headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
        titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        labelMedium =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        ),
    )

@Composable
fun LiftingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = BarTypography,
        content = content,
    )
}
