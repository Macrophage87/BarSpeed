package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors

/** Compact metric tile for the home dashboard: label, big number, footnote. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sub: String? = null,
    subColor: Color = BarColors.Sub,
) {
    Column(
        modifier
            .background(BarColors.Surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            unit?.let {
                Text(
                    " $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = subColor) }
    }
}
