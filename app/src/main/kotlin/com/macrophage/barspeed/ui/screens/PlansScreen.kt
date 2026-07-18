package com.macrophage.barspeed.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.data.PlanImportResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(navController: NavController, viewModel: PlansViewModel = viewModel()) {
    val plans by viewModel.plans.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plans") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Button(onClick = { showImportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Import plan (paste JSON)")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a plan from Claude or any LLM. See PROMPTS.md in the repo for " +
                    "ready-made prompts; plans must match plan.schema.json.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plans) { plan -> PlanCard(plan, viewModel) }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import plan JSON") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("Paste plan JSON here") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        viewModel.import(importText)
                        importText = ""
                    },
                ) { Text("Validate") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
    }

    when (val result = importResult) {
        is PlanImportResult.Staged -> {
            val summary = result.summary
            AlertDialog(
                onDismissRequest = viewModel::dismissImportResult,
                title = { Text("Approve plan?") },
                text = {
                    Column {
                        Text("\"${summary.planName}\"", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text("${summary.sessionCount} sessions, ${summary.totalSets} sets")
                        Text("Exercises: ${summary.exerciseNames.joinToString(", ")}")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Approving makes this the active plan for new sessions.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.approve(summary.planId) }) { Text("Approve") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.discard(summary.planId) }) { Text("Discard") }
                },
            )
        }
        is PlanImportResult.Invalid -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissImportResult,
                title = { Text("Plan rejected") },
                text = {
                    Column {
                        Text("Fix these and re-import (or paste the errors back to the LLM):")
                        Spacer(Modifier.height(6.dp))
                        result.errors.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissImportResult) { Text("OK") }
                },
            )
        }
        null -> {}
    }
}

@Composable
private fun PlanCard(plan: PlanEntity, viewModel: PlansViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleSmall)
            Text(plan.status, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.status != PlanEntity.STATUS_ACTIVE) {
                    TextButton(onClick = { viewModel.activate(plan.id) }) { Text("Make active") }
                }
                TextButton(onClick = { viewModel.delete(plan.id) }) { Text("Delete") }
            }
        }
    }
}
