package com.macrophage.barspeed.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.data.PlanImportResult
import com.macrophage.barspeed.model.PlanBodyWeightPolicy
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.ui.BarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(navController: NavController, viewModel: PlansViewModel = viewModel()) {
    val plans by viewModel.planRows.collectAsState(emptyList())
    val importResult by viewModel.importResult.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    // The id survives a rotation; the prompt is re-derived from the list, so a
    // plan deleted underneath the dialog takes the dialog with it rather than
    // leaving a confirm button pointed at a row that no longer exists.
    var confirmingPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFromUri(context, uri)
        }

    // Navigation is driven by the ViewModel rather than by the tap, because the
    // activation has to land first: see PlansViewModel.start.
    LaunchedEffect(Unit) {
        viewModel.recordRequests.collect { navController.navigate("record") }
    }

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
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { filePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import plan from file")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste or pick a .json plan from Claude or any LLM. See PROMPTS.md in the repo " +
                    "for ready-made prompts; plans must match plan.schema.json.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plans) { row ->
                    PlanCard(
                        row = row,
                        viewModel = viewModel,
                        onConfirmSwitch = { confirmingPlanId = row.entity.id },
                        onOpen = { navController.navigate("plan/${row.entity.id}") },
                    )
                }
            }
        }
    }

    confirmingPlanId
        ?.let { id -> plans.firstOrNull { it.entity.id == id } }
        ?.let { row -> ConfirmSwitchDialog(row, viewModel) { confirmingPlanId = null } }

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
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("\"${summary.planName}\"", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text("${summary.sessionCount} sessions, ${summary.totalSets} sets")
                        Text("Exercises: ${summary.exerciseNames.joinToString(", ")}")
                        if (summary.optionalExercises.isNotEmpty()) {
                            Text(
                                "Droppable if short on time: ${summary.optionalExercises.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BarColors.Blue,
                            )
                        }
                        // Above the warnings and in its own colour: this is not
                        // a complaint about the document, it is the one thing
                        // the import changed OUTSIDE the plan, and the lifter
                        // should read the number rather than discover it in a
                        // recorded load next week.
                        summary.bodyWeightKg?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                PlanBodyWeightPolicy.appliedLine(it, weightUnit),
                                style = MaterialTheme.typography.bodySmall,
                                color = BarColors.Volt,
                            )
                        }
                        summary.warnings.take(WARNINGS_SHOWN).forEach {
                            Spacer(Modifier.height(4.dp))
                            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
                        }
                        MoreLine(summary.warnings.size, WARNINGS_SHOWN)
                        if (summary.warnings.isNotEmpty()) {
                            CopyForTheAuthor(
                                label = "Copy warnings",
                                text = relayText(
                                    "BarSpeed flagged these while importing \"${summary.planName}\"",
                                    summary.warnings,
                                ),
                                clipboard = clipboard,
                            )
                        }
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
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Fix these and re-import (or paste the errors back to the LLM):")
                        Spacer(Modifier.height(6.dp))
                        result.errors.take(ERRORS_SHOWN).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        MoreLine(result.errors.size, ERRORS_SHOWN)
                        CopyForTheAuthor(
                            label = "Copy errors",
                            text = relayText("BarSpeed rejected this plan", result.errors),
                            clipboard = clipboard,
                        )
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

/**
 * How many lines either dialog shows before deferring to the clipboard. The
 * list itself is never truncated on the way out — [relayText] always carries
 * all of it — so this only decides how much is readable without scrolling.
 */
private const val WARNINGS_SHOWN = 12
private const val ERRORS_SHOWN = 12

/** Says how many lines were not drawn, so a cut list never looks like a whole one. */
@Composable
private fun MoreLine(total: Int, shown: Int) {
    if (total <= shown) return
    Spacer(Modifier.height(4.dp))
    Text(
        "…and ${total - shown} more — use the copy button to get all $total.",
        style = MaterialTheme.typography.bodySmall,
        color = BarColors.Sub,
    )
}

/**
 * The point of showing any of this is that the lifter can go back to whoever
 * wrote the plan and ask. That means the text has to leave the phone, and a
 * dialog that is read-only, clipped and discarded on dismiss cannot do it —
 * these lines exist nowhere else, since only the plan JSON is stored.
 */
@Composable
private fun CopyForTheAuthor(label: String, text: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(label) }
}

/** Every line, never the truncated view, with a heading that survives a paste. */
private fun relayText(heading: String, lines: List<String>): String =
    (listOf("$heading:") + lines.map { "- $it" }).joinToString("\n")

@Composable
private fun PlanCard(row: PlanRow, viewModel: PlansViewModel, onConfirmSwitch: () -> Unit, onOpen: () -> Unit) {
    val plan = row.entity
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleSmall)
            Text("${plan.status} · tap to view", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val start = row.start) {
                    // The refusal is drawn where the control would have been. A
                    // card that simply has no START button looks like a screen
                    // that forgot to draw one.
                    is PlanStartDecision.Unstartable ->
                        Text(
                            start.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = BarColors.Amber,
                        )
                    // A null switch means this plan is already active, so
                    // starting writes nothing and there is nothing to agree to.
                    // Anything else changes which plan the app follows, and the
                    // dialog is where that is said in words.
                    is PlanStartDecision.Startable ->
                        TextButton(
                            onClick = {
                                if (start.switch == null) {
                                    viewModel.start(plan.id, activateFirst = false)
                                } else {
                                    onConfirmSwitch()
                                }
                            },
                        ) { Text("Start", color = BarColors.Volt) }
                }
                if (plan.status != PlanEntity.STATUS_ACTIVE) {
                    TextButton(onClick = { viewModel.activate(plan.id) }) { Text("Make active") }
                }
                TextButton(onClick = { viewModel.delete(plan.id) }) { Text("Delete") }
            }
        }
    }
}

/**
 * The one place a start from this screen can change which plan the app
 * follows, said in words before it happens.
 *
 * Dismissing is a refusal: nothing is written and nothing is started. That is
 * the whole reason the activation is deferred to the confirm button rather than
 * done on the tap that opens this.
 */
@Composable
private fun ConfirmSwitchDialog(row: PlanRow, viewModel: PlansViewModel, onDismiss: () -> Unit) {
    val switch = (row.start as? PlanStartDecision.Startable)?.switch ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(switch.title) },
        text = { Text(switch.body, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.start(row.entity.id, activateFirst = true)
                },
            ) { Text(switch.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
