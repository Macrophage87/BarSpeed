package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors

private const val START_ANGLE_DEG = -90f
private const val FULL_SWEEP_DEG = 360f

/**
 * Circular progress ring with an optional "ghost" arc marking a target
 * position (e.g. the prescribed tempo duration) behind the live arc.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 230.dp,
    strokeWidth: Dp = 14.dp,
    color: Color = BarColors.Volt,
    trackColor: Color = BarColors.Track,
    ghostProgress: Float? = null,
    ghostColor: Color = BarColors.Ghost,
    content: @Composable () -> Unit = {},
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val arcSize = Size(size.width - strokeWidth.toPx(), size.height - strokeWidth.toPx())
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = FULL_SWEEP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            ghostProgress?.let { ghost ->
                drawArc(
                    color = ghostColor,
                    startAngle = START_ANGLE_DEG,
                    sweepAngle = FULL_SWEEP_DEG * ghost.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = START_ANGLE_DEG,
                    sweepAngle = FULL_SWEEP_DEG * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        content()
    }
}
