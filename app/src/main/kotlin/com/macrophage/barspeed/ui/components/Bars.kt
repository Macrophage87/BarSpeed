package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors
import java.util.Locale

/** Height fraction floor so even a slow rep is visibly a bar, not a sliver. */
private const val MIN_BAR_FRACTION = 0.15f
private const val VEL_LOSS_DIM_PCT = 10.0
private const val VEL_LOSS_DEFAULT_STOP_PCT = 20.0

/**
 * Standard rep-bar coloring: velocity loss vs the best rep of the set —
 * volt when fresh, dimmer/amber as loss approaches the stop threshold, red past it.
 */
fun velocityLossColor(value: Double, all: List<Double>, stopPct: Double?): Color {
    val best = all.maxOrNull() ?: return BarColors.Volt
    if (best <= 0) return BarColors.Volt
    val lossPct = (1.0 - value / best) * 100.0
    val stop = stopPct ?: VEL_LOSS_DEFAULT_STOP_PCT
    return when {
        lossPct >= stop -> BarColors.Red
        lossPct >= VEL_LOSS_DIM_PCT -> if (lossPct >= stop * 0.75) BarColors.Amber else BarColors.VoltDim
        else -> BarColors.Volt
    }
}

/**
 * Per-rep bar chart: one bar per completed rep plus dashed placeholder slots
 * up to the planned rep count. Values are labelled beneath each bar.
 */
@Composable
fun RepBars(
    values: List<Double>,
    plannedSlots: Int?,
    colorFor: (index: Int, value: Double) -> Color,
    modifier: Modifier = Modifier,
    barHeight: Int = 74,
    valueLabel: (Double) -> String = { String.format(Locale.US, "%.2f", it) },
) {
    val slots = maxOf(plannedSlots ?: 0, values.size, 1)
    val peak = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().height(barHeight.dp),
        ) {
            repeat(slots) { i ->
                val value = values.getOrNull(i)
                if (value != null) {
                    val fraction = (value / peak).toFloat().coerceIn(MIN_BAR_FRACTION, 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .background(
                                colorFor(i, value),
                                RoundedCornerShape(
                                    topStart = 6.dp,
                                    topEnd = 6.dp,
                                    bottomStart = 2.dp,
                                    bottomEnd = 2.dp,
                                ),
                            ),
                    )
                } else {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(BarColors.Track, RoundedCornerShape(6.dp))
                            .border(1.dp, BarColors.Ghost, RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(slots) { i ->
                Text(
                    values.getOrNull(i)?.let(valueLabel) ?: "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Rest-screen rep quality chart: one bar per rep against a dashed horizontal
 * target line (e.g. eccentric seconds vs the prescribed tempo).
 */
@Composable
fun TargetLineBars(
    values: List<Double>,
    target: Double,
    colorFor: (index: Int, value: Double) -> Color,
    modifier: Modifier = Modifier,
    chartHeight: Int = 64,
) {
    val ceiling = maxOf(values.maxOrNull() ?: target, target) * 1.25
    Box(modifier.fillMaxWidth().height(chartHeight.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxSize(),
        ) {
            values.forEachIndexed { i, value ->
                val fraction = (value / ceiling).toFloat().coerceIn(MIN_BAR_FRACTION, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .background(colorFor(i, value), RoundedCornerShape(5.dp)),
                )
            }
        }
        val targetFromTop = (1.0 - target / ceiling).toFloat().coerceIn(0f, 1f)
        DashedLine(
            Modifier
                .fillMaxWidth()
                .padding(top = (chartHeight * targetFromTop).dp)
                .align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DashedLine(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.height(2.dp)) {
        val dash = 6.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = BarColors.Ghost,
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(minOf(x + dash, size.width), 0f),
                strokeWidth = 2.dp.toPx(),
            )
            x += dash * 2
        }
    }
}
