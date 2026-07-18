package com.macrophage.barspeed.ui.screens

import android.app.Application
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.RepBars
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.TargetLineBars
import com.macrophage.barspeed.ui.components.VerdictChip
import com.macrophage.barspeed.ui.components.velocityLossColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val VEL_LOSS_WARN_PCT = 10.0
private const val VEL_LOSS_BAD_PCT = 20.0
private const val TEMPO_TOLERANCE_S = 0.5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(navController: NavController, sessionId: Long) {
    val context = LocalContext.current
    val viewModel: SessionDetailViewModel =
        viewModel(
            factory =
            SessionDetailViewModel.Factory(
                context.applicationContext as Application,
                sessionId,
            ),
        )
    val session by viewModel.session.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val sets by viewModel.sets.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.planSessionName ?: "Session") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            session?.let { s ->
                val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
                val started = Instant.ofEpochMilli(s.startedAtMs).atZone(ZoneId.systemDefault())
                val parts =
                    listOfNotNull(
                        formatter.format(started),
                        "${sets.size} sets",
                        s.hrAvgBpm?.let { "♥ $it avg / ${s.hrMaxBpm} max" },
                    )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.shareJson(false) }, modifier = Modifier.weight(1f)) {
                    Text("Share JSON")
                }
                OutlinedButton(onClick = { viewModel.shareJson(true) }, modifier = Modifier.weight(1f)) {
                    Text("Detailed")
                }
                OutlinedButton(onClick = { viewModel.shareRawZip() }, modifier = Modifier.weight(1f)) {
                    Text("Raw CSV")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Share JSON to Claude for analysis (see PROMPTS.md); Raw CSV exports the " +
                    "full sensor streams for Python/R.",
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sets) { record -> SetCard(record, viewModel, weightUnit) }
            }
        }
    }
}

@Composable
private fun SetCard(record: SetRecordEntity, viewModel: SessionDetailViewModel, unit: WeightUnit) {
    val analysis = viewModel.decodeAnalysis(record)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SetCardHeader(record, unit)
            analysis?.let { a ->
                SetChips(record, a)
                if (a.reps.isNotEmpty()) {
                    SetVelocityBars(record, a)
                    record.tempo?.let { Tempo.parseOrNull(it) }?.let { tempo ->
                        SetTempoChart(a, tempo.eccentricS)
                    }
                }
                a.verdicts.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
                }
            }
        }
    }
}

@Composable
private fun SetCardHeader(record: SetRecordEntity, unit: WeightUnit) {
    Text(
        "${record.exerciseName} — ${record.actualReps} × ${unit.format(record.loadKg)}",
        style = MaterialTheme.typography.titleMedium,
    )
    record.plannedLoadKg?.takeIf { it != record.loadKg }?.let {
        Text(
            "Deviation (planned ${unit.format(it)})",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Amber,
        )
    }
}

@Composable
private fun SetChips(record: SetRecordEntity, analysis: SetAnalysis) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        analysis.tempoCompliance?.let { compliance ->
            val ok = compliance.repsFullyCompliant == compliance.repsEvaluated
            VerdictChip(
                "Tempo ${compliance.repsFullyCompliant}/${compliance.repsEvaluated}" + if (ok) " ✓" else "",
                if (ok) ChipTone.OK else ChipTone.WARN,
            )
        }
        analysis.velocityLossPct?.let { loss ->
            VerdictChip(
                "−${trimNum(loss)}% vel",
                when {
                    loss >= (record.velocityLossStopPct ?: VEL_LOSS_BAD_PCT) -> ChipTone.BAD
                    loss >= VEL_LOSS_WARN_PCT -> ChipTone.WARN
                    else -> ChipTone.OK
                },
            )
        }
        record.hrAvgBpm?.let { VerdictChip("♥ $it", ChipTone.NEUTRAL) }
    }
}

@Composable
private fun SetVelocityBars(record: SetRecordEntity, analysis: SetAnalysis) {
    SectionCaption("Mean concentric velocity (m/s)")
    val velocities = analysis.reps.map { it.meanConVelMps }
    RepBars(
        values = velocities,
        plannedSlots = record.plannedReps,
        colorFor = { _, v -> velocityLossColor(v, velocities, record.velocityLossStopPct) },
        barHeight = 56,
    )
}

@Composable
private fun SetTempoChart(analysis: SetAnalysis, targetEccS: Double) {
    SectionCaption("Eccentric time vs ${trimNum(targetEccS)} s target")
    Spacer(Modifier.height(2.dp))
    TargetLineBars(
        values = analysis.reps.map { it.eccS },
        target = targetEccS,
        colorFor = { _, v -> if (abs(v - targetEccS) <= TEMPO_TOLERANCE_S) BarColors.Volt else BarColors.Amber },
        chartHeight = 48,
    )
}

private fun trimNum(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
