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
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.macrophage.barspeed.model.BodyWeightPromptPolicy.BarColorRole
import com.macrophage.barspeed.model.PlanBodyWeightPolicy
import com.macrophage.barspeed.model.PlanLifecycle
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.model.PlanStartPolicy
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.RecordRoute
import com.macrophage.barspeed.ui.components.BodyWeightDialog
import com.macrophage.barspeed.ui.components.PlanWarnings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(navController: NavController, viewModel: PlansViewModel = viewModel()) {
    val plans by viewModel.planRows.collectAsState(emptyList())
    val importResult by viewModel.importResult.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val bodyWeight by viewModel.bodyWeight.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showBodyWeightDialog by remember { mutableStateOf(false) }
    // The id survives a rotation; the prompt is re-derived from the list, so a
    // plan deleted underneath the dialog takes the dialog with it rather than
    // leaving a confirm button pointed at a row that no longer exists.
    var confirmingPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Same shape as confirmingPlanId, for the same reason: a plan gone from the
    // list takes its delete prompt with it.
    var deletingPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFromUri(context, uri)
        }

    // Navigation is driven by the ViewModel rather than by the tap, because the
    // activation has to land first: see PlansViewModel.start.
    LaunchedEffect(Unit) {
        viewModel.recordRequests.collect { session ->
            // MAKE ACTIVE & START names the session to do next and opens its
            // preview, as Home's hero does; a row's START opens the picker.
            navController.navigate(if (session == null) RecordRoute.path() else RecordRoute.preview(session))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plans") },
                navigationIcon = {
                    // Kept a TextButton (#201): the app bar's navigation slot, the same act as the
                    // system back gesture.
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
            // No pointers to repo files the lifter cannot open on a phone
            // (UX review 2026-11-06, plans F10).
            Text(
                "Ask robocoach for a plan file and open it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            // Below the imports and their explanatory line, per the owner's own
            // placement instruction on #199: importing a plan is why a lifter
            // opens this screen, not changing their body weight. The button
            // shows ONE FIXED LABEL in every state and never the stored value
            // -- the owner's rule is that the number does not need to be in
            // front of him. The colour is the only signal, from the same
            // four-state mapping BodyWeightPromptPolicy already used to decide
            // whether RecordScreen should ask: red means nothing usable is
            // stored, amber means what is stored cannot be trusted (either no
            // recorded date, or dated 14+ days ago), volt means it is dated and
            // recent. This is NOT a reversal of #181 -- #181 demoted body
            // weight because an amber chip nagged from the HOME screen on
            // every cold launch; the colour is acceptable here because a
            // lifter reaches Plans deliberately, not on every cold launch.
            Button(
                onClick = { showBodyWeightDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = bodyWeightButtonColors(bodyWeight.colorRole),
            ) {
                Text("Change body weight")
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plans) { row ->
                    PlanCard(
                        row = row,
                        viewModel = viewModel,
                        onConfirmSwitch = { confirmingPlanId = row.entity.id },
                        onDelete = { deletingPlanId = row.entity.id },
                        onOpen = { navController.navigate("plan/${row.entity.id}") },
                    )
                }
            }
        }
    }

    confirmingPlanId
        ?.let { id -> plans.firstOrNull { it.entity.id == id } }
        ?.let { row -> ConfirmSwitchDialog(row, viewModel) { confirmingPlanId = null } }

    deletingPlanId
        ?.let { id -> plans.firstOrNull { it.entity.id == id } }
        ?.let { row -> ConfirmDeleteDialog(row, viewModel) { deletingPlanId = null } }

    if (showBodyWeightDialog) {
        BodyWeightDialog(
            current = bodyWeight.kg,
            unit = weightUnit,
            onDismiss = { showBodyWeightDialog = false },
            onSet = {
                viewModel.setBodyWeight(it)
                showBodyWeightDialog = false
            },
        )
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
            // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks
            // them as its answers.
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
                title = { Text("Make this plan active?") },
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
                        // Two tiers, every line, and the author's text on Copy
                        // (UX review 2026-11-06, plans F3): shared with Plan
                        // detail's approval so the two cannot drift apart.
                        PlanWarnings(
                            lines = summary.warningLines,
                            copyHeading = "BarSpeed flagged these while importing \"${summary.planName}\"",
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "New sessions will follow this plan.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                // "MAKE ACTIVE & START" is the filled primary (UX review 2026-11-06,
                // plans F2): a plan is usually imported to be lifted now, and
                // making it active alone left the lifter on this list hunting for
                // the card's START. Plain "Make active" stays, as a TextButton, for
                // a plan imported on a rest day. A plan with no sets has nothing to
                // start, so it gets "Make active" alone. Discard/Cancel are
                // unchanged. One verb for one act (copy F6): the cards, Plan
                // detail and this gate all say "Make active".
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.approve(summary.planId) }) { Text("Make active") }
                        if (summary.totalSets > 0) {
                            Button(onClick = { viewModel.approveAndStart(summary.planId) }) {
                                Text(PlanStartPolicy.MAKE_ACTIVE_AND_START)
                            }
                        }
                    }
                },
                dismissButton = {
                    // Reopened from "Make active" on a stored plan, the gate
                    // closes and keeps it; only a fresh import is discarded.
                    if (result.stored) {
                        TextButton(onClick = viewModel::dismissImportResult) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { viewModel.discard(summary.planId) }) { Text("Discard") }
                    }
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
                        // The same parse's warnings, below the errors (#58): a
                        // misspelt load key beside a real error used to be shown
                        // only after the error was fixed and the plan re-imported.
                        result.warnings.take(WARNINGS_SHOWN).forEach {
                            Spacer(Modifier.height(4.dp))
                            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
                        }
                        MoreLine(result.warnings.size, WARNINGS_SHOWN)
                        CopyForTheAuthor(
                            label = "Copy errors",
                            text = relayText("BarSpeed rejected this plan", result.errors + result.warnings),
                            clipboard = clipboard,
                        )
                    }
                },
                // Kept a TextButton (#201): this dialog's one action; the dialog's frame already
                // marks it as its answer.
                confirmButton = {
                    TextButton(onClick = viewModel::dismissImportResult) { Text("OK") }
                },
            )
        }
        null -> {}
    }
}

/**
 * The owner's four-state colour mapping (issue #199), read off the theme's
 * own colour scheme rather than [BarColors] directly: `error`/`onError` are
 * already [BarColors.Red]/[BarColors.Bg] and `tertiary`/`onTertiary` are
 * already [BarColors.Amber]/[BarColors.Bg] (see [com.macrophage.barspeed.ui.LiftingTheme]'s
 * `DarkColors`), so this only has to choose a role rather than hand-pick a
 * contrasting text colour a second time. VOLT needs no override at all --
 * [ButtonDefaults.buttonColors]'s default IS `primary`/`onPrimary`, which the
 * same colour scheme already maps to [BarColors.Volt]/[BarColors.Bg].
 *
 * Exhaustive with no `else`, matching [com.macrophage.barspeed.ui.components.SensorDot]'s
 * own construction: a fifth [BarColorRole] must fail this file to compile
 * rather than silently drawing the wrong colour.
 */
@Composable
private fun bodyWeightButtonColors(role: BarColorRole): ButtonColors = when (role) {
    BarColorRole.RED ->
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    BarColorRole.AMBER ->
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        )
    BarColorRole.VOLT -> ButtonDefaults.buttonColors()
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
    OutlinedButton(
        onClick = { clipboard.setText(AnnotatedString(text)) },
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label)
    }
}

/** Every line, never the truncated view, with a heading that survives a paste. */
private fun relayText(heading: String, lines: List<String>): String =
    (listOf("$heading:") + lines.map { "- $it" }).joinToString("\n")

@Composable
private fun PlanCard(
    row: PlanRow,
    viewModel: PlansViewModel,
    onConfirmSwitch: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val plan = row.entity
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleSmall)
            // What the status means, never the database word (UX review
            // 2026-11-06, plans F6): "staged" was printed raw.
            val status = PlanLifecycle.of(plan.status).label
            Text(
                listOfNotNull(status, "tap to view").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
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
                        OutlinedButton(
                            onClick = {
                                if (start.switch == null) {
                                    viewModel.start(plan.id, activateFirst = false)
                                } else {
                                    onConfirmSwitch()
                                }
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) { Text("START", color = BarColors.Volt) }
                }
                if (plan.status != PlanEntity.STATUS_ACTIVE) {
                    OutlinedButton(
                        onClick = { viewModel.activate(plan.id) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text("Make active")
                    }
                }
                // Kept a TextButton (#201): it is never the card's next step. It
                // asks first (UX review 2026-11-06, plans F8): it sits in the
                // row beside Start, and one mis-tap used to lose the plan.
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * Deleting a plan removes only the plan. A recorded session keeps its own
 * copy of the plan's name and holds no reference to the plan row, so History
 * is untouched, and the prompt says so because that is what a lifter about
 * to delete an old plan wants to know.
 */
@Composable
private fun ConfirmDeleteDialog(row: PlanRow, viewModel: PlansViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${row.entity.name}?") },
        text = { Text("Its sessions in History stay.", style = MaterialTheme.typography.bodySmall) },
        // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks them
        // as its answers.
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.delete(row.entity.id)
                },
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
        // Kept TextButtons (#201): this dialog's two actions; the dialog's frame already marks them
        // as its answers.
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
