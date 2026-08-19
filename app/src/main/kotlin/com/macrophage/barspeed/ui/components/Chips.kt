package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.ui.BarColors

private const val CHIP_BG_ALPHA = 0.15f

/** Small pill for set verdicts: tinted background, saturated text. */
@Composable
fun VerdictChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val color =
        when (tone) {
            ChipTone.OK -> BarColors.Volt
            ChipTone.WARN -> BarColors.Amber
            ChipTone.BAD -> BarColors.Red
            ChipTone.NEUTRAL -> BarColors.Sub
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier =
        modifier
            .background(color.copy(alpha = CHIP_BG_ALPHA), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Status-bar style connection dot: volt when live, amber while reconnecting, grey when off. */
@Composable
fun SensorDot(label: String, connected: Boolean, modifier: Modifier = Modifier, connecting: Boolean = false) {
    Text(
        "$label ●",
        style = MaterialTheme.typography.bodySmall,
        color =
        when {
            connected -> BarColors.Volt
            connecting -> BarColors.Amber
            else -> BarColors.Ghost
        },
        modifier = modifier,
    )
}

/**
 * Chip summarizing a device's link state, tinted by connection status.
 *
 * Failed and Disconnected used to collapse into one grey "✗": a sensor that
 * refused to connect looked exactly like one that was simply off, so the chip
 * could not say a permission had been denied. They are now separate tones, and
 * the branches are exhaustive over [ConnectionState] so a new state has to be
 * decided rather than falling into the grey. The reason string itself is
 * rendered by the caller — the pill is too narrow for a framework diagnostic.
 * [ConnectionState.Failed] has ten producers in `GattClients.kt` carrying
 * six distinct reasons, and exactly one of them interpolates a raw GATT status
 * integer (the service-discovery failure); an earlier version of this comment
 * said seven producers, before three more call sites gained a catch for the
 * same permission-revoked race `connect()` already guarded against, all three
 * reusing the existing reason string rather than adding a new one.
 */
@Composable
fun ConnectionChip(label: String, state: ConnectionState, modifier: Modifier = Modifier) {
    val (text, tone) =
        when (state) {
            is ConnectionState.Connected ->
                ("$label ✓" + (state.batteryPct?.let { " $it%" } ?: "")) to ChipTone.OK
            is ConnectionState.Connecting -> "$label …" to ChipTone.WARN
            is ConnectionState.Failed -> "$label ✗" to ChipTone.BAD
            is ConnectionState.Disconnected -> "$label ✗" to ChipTone.NEUTRAL
        }
    VerdictChip(text, tone, modifier)
}

/** Uppercase, letter-spaced section caption ("LAST SET", "UP NEXT · SET 4 OF 5"). */
@Composable
fun SectionCaption(text: String, color: Color = BarColors.Sub, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}
