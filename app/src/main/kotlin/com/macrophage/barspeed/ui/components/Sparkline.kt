package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors

/** Tiny trend line for history rows (e.g. per-set mean velocity across a session). */
@Composable
fun Sparkline(
    points: List<Double>,
    color: Color = BarColors.Volt,
    modifier: Modifier = Modifier.size(width = 90.dp, height = 30.dp),
) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val pad = size.height * 0.15f
        val usable = size.height - pad * 2
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / (points.size - 1).toFloat()
            val y = pad + usable * (1f - ((v - min) / span).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.cornerPathEffect(6f)),
        )
        val lastX = size.width
        val lastY = pad + usable * (1f - ((points.last() - min) / span).toFloat())
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}
