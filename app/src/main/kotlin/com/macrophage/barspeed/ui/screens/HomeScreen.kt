package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.ble.ConnectionState
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.SensorDot
import com.macrophage.barspeed.ui.components.Sparkline
import com.macrophage.barspeed.ui.components.StatTile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KG_PER_TONNE = 1000.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BarSpeed") },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        SensorDot("IMU", imuState is ConnectionState.Connected)
                        SensorDot("HRM", hrmState is ConnectionState.Connected)
                        TextButton(onClick = viewModel::toggleWeightUnit) {
                            Text("${state.weightUnit.suffix} ⇄", color = BarColors.Sub)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            HeroCard(state) { navController.navigate("record") }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Week volume",
                    value = volumeValue(state.weekVolumeKg, state.weightUnit),
                    unit = volumeUnit(state.weightUnit),
                    sub = "last 7 days",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Sessions",
                    value = "${state.weekSessions}",
                    sub = "this week",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { navController.navigate("devices") }, modifier = Modifier.weight(1f)) {
                    Text("Devices", color = BarColors.Sub)
                }
                TextButton(onClick = { navController.navigate("plans") }, modifier = Modifier.weight(1f)) {
                    Text("Plans", color = BarColors.Sub)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionCaption("History")
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history) { row ->
                    HistoryCard(row) { navController.navigate("session/${row.session.id}") }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(state: HomeState, onStart: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(BarColors.HeroGreen, BarColors.Surface)),
                shape,
            )
            .border(1.dp, BarColors.Volt.copy(alpha = 0.2f), shape)
            .padding(14.dp),
    ) {
        if (state.planName != null) {
            SectionCaption("Active plan", color = BarColors.Volt)
            Text(
                state.planName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "${state.planSessionCount} sessions · ${state.planExerciseCount} exercises · " +
                    "${state.planSetCount} sets",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        } else {
            SectionCaption("No active plan", color = BarColors.Volt)
            Text(
                "Train ad-hoc or import a plan",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "Import a JSON plan from Claude on the Plans screen.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("START SESSION", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM")
    val started = Instant.ofEpochMilli(row.session.startedAtMs).atZone(ZoneId.systemDefault())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Column {
                Text(
                    row.session.planSessionName ?: "Ad-hoc session",
                    style = MaterialTheme.typography.titleSmall,
                )
                val parts =
                    listOfNotNull(
                        formatter.format(started),
                        "${row.setCount} sets",
                        row.session.hrAvgBpm?.let { "♥ $it avg" },
                    )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
            }
            if (row.sparkline.size >= 2) {
                Sparkline(
                    row.sparkline,
                    color = if (row.session.planSessionName != null) BarColors.Volt else BarColors.Blue,
                )
            }
        }
    }
}

private fun volumeValue(volumeKg: Double, unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> String.format(Locale.US, "%.1f", volumeKg / KG_PER_TONNE)
    WeightUnit.LB -> String.format(Locale.US, "%.1f", volumeKg * WeightUnit.LB_PER_KG / KG_PER_TONNE)
}

private fun volumeUnit(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "t"
    WeightUnit.LB -> "k lb"
}
