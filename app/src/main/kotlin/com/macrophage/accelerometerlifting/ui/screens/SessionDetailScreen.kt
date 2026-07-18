package com.macrophage.accelerometerlifting.ui.screens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.accelerometerlifting.LiftingApp
import com.macrophage.accelerometerlifting.data.SetRecordEntity
import com.macrophage.accelerometerlifting.model.WeightUnit
import com.macrophage.accelerometerlifting.ui.ShareUtil
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class SessionDetailViewModel(app: Application, private val sessionId: Long) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.sessionRepository

    val session =
        repository.observeSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sets =
        repository.observeSets(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun decodeAnalysis(record: SetRecordEntity) = repository.decodeAnalysis(record)

    val weightUnit =
        container.settings.weightUnit
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KG)

    fun shareJson(includeDetail: Boolean) {
        viewModelScope.launch {
            val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
            val name = if (includeDetail) "session-$sessionId-detailed.json" else "session-$sessionId.json"
            ShareUtil.shareJson(getApplication(), name, json)
        }
    }

    fun shareRawZip() {
        viewModelScope.launch {
            val zip = container.rawExporter.buildZip(sessionId) ?: return@launch
            ShareUtil.shareFile(getApplication(), "session-$sessionId-raw.zip", zip, "application/zip")
        }
    }

    class Factory(private val app: Application, private val sessionId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionDetailViewModel(app, sessionId) as T
    }
}

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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${record.exerciseName} — ${record.actualReps}×${unit.format(record.loadKg)}",
                style = MaterialTheme.typography.titleSmall,
            )
            val deviation =
                record.plannedLoadKg?.takeIf { it != record.loadKg }?.let { " (planned ${unit.format(it)})" } ?: ""
            if (deviation.isNotEmpty()) Text("Deviation$deviation", style = MaterialTheme.typography.bodySmall)
            analysis?.let { a ->
                a.velocityLossPct?.let { Text("Velocity loss $it%") }
                a.tempoCompliance?.let {
                    Text("Tempo ${it.prescribed.notation()}: ${it.repsFullyCompliant}/${it.repsEvaluated} on tempo")
                }
                if (a.reps.isNotEmpty()) {
                    Text(
                        "rep  ecc   con   mean m/s",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    a.reps.forEach { rep ->
                        Text(
                            String.format(
                                Locale.US,
                                "%2d  %4.1f  %4.1f  %.2f",
                                rep.index + 1,
                                rep.eccS,
                                rep.conS,
                                rep.meanConVelMps,
                            ),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                a.verdicts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
