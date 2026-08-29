package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.model.BodyweightLoadDisplay
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanNoteDisplay
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
import com.macrophage.barspeed.ui.components.ExpandableNote
import com.macrophage.barspeed.ui.components.SectionCaption
import com.macrophage.barspeed.ui.components.VerdictChip
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(navController: NavController, planId: Long) {
    val context = LocalContext.current
    val viewModel: PlanDetailViewModel =
        viewModel(
            factory =
            PlanDetailViewModel.Factory(
                context.applicationContext as Application,
                planId,
            ),
        )
    val state by viewModel.state.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }

    // The ViewModel says when to navigate, not the tap: the activation has to
    // land before the record screen reads which plan is active.
    LaunchedEffect(Unit) {
        viewModel.recordRequests.collect { navController.navigate("record") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val plan = state.plan
        when {
            !state.loaded -> {}
            plan == null ->
                Text(
                    "This plan could not be read.",
                    Modifier.padding(padding).padding(16.dp),
                    color = BarColors.Red,
                )
            else ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        PlanHero(
                            name = plan.planName,
                            notes = plan.notes,
                            entity = state.entity,
                            viewModel = viewModel,
                            sessions = plan.sessions,
                            start = state.start,
                            onConfirmSwitch = { confirming = true },
                        )
                    }
                    plan.sessions.forEach { session ->
                        item { SessionHeader(session) }
                        items(session.exercises.size) { i ->
                            ExerciseCard(session.exercises[i], weightUnit)
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
        }
    }

    if (confirming) {
        ConfirmSwitchDialog(state.start, viewModel) { confirming = false }
    }
}

@Composable
private fun PlanHero(
    name: String,
    notes: String?,
    entity: PlanEntity?,
    viewModel: PlanDetailViewModel,
    sessions: List<PlanSessionDef>,
    start: PlanStartDecision?,
    onConfirmSwitch: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val active = entity?.status == PlanEntity.STATUS_ACTIVE
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BarColors.HeroGreen, BarColors.Surface)), shape)
            .border(1.dp, BarColors.Volt.copy(alpha = 0.2f), shape)
            .padding(14.dp),
    ) {
        SectionCaption(
            when (entity?.status) {
                PlanEntity.STATUS_ACTIVE -> "Active plan"
                PlanEntity.STATUS_STAGED -> "Staged — not yet active"
                PlanEntity.STATUS_ARCHIVED -> "Archived plan"
                else -> "Plan"
            },
            color = BarColors.Volt,
        )
        Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 4.dp))
        val exercises = sessions.sumOf { it.exercises.size }
        val sets = sessions.sumOf { s -> s.exercises.sumOf { it.sets.size } }
        Text(
            "${sessions.size} sessions · $exercises exercises · $sets sets",
            style = MaterialTheme.typography.bodySmall,
            color = BarColors.Sub,
        )
        notes?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        }
        // The start control belongs here as well as on the plans list, and this
        // is the better of the two places for it: the lifter has just read what
        // the plan prescribes, which is the moment they know whether they want
        // to do it. #182.
        //
        // MAKE ACTIVE stays beneath it and keeps its own job. Switching plans
        // without lifting today is a real thing to want, and folding it into
        // START would take it away.
        when (start) {
            null -> Unit
            is PlanStartDecision.Unstartable -> {
                Spacer(Modifier.height(12.dp))
                Text(start.reason, style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
            }
            is PlanStartDecision.Startable -> {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (start.switch == null) {
                            viewModel.start(activateFirst = false)
                        } else {
                            onConfirmSwitch()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("START WORKOUT", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (!active && entity != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::activate, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text("MAKE ACTIVE", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Said in words before the app starts following a different plan. The same
 * prompt the plans list shows, from the same decision, so the two screens
 * cannot describe one write differently.
 */
@Composable
private fun ConfirmSwitchDialog(start: PlanStartDecision?, viewModel: PlanDetailViewModel, onDismiss: () -> Unit) {
    val switch = (start as? PlanStartDecision.Startable)?.switch ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(switch.title) },
        text = { Text(switch.body, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.start(activateFirst = true)
                },
            ) { Text(switch.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SessionHeader(session: PlanSessionDef) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SectionCaption(session.name, color = BarColors.Volt)
            val sets = session.exercises.sumOf { it.sets.size }
            Text("$sets sets", style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        }
        session.notes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = BarColors.Sub)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseCard(exercise: PlanExerciseDef, unit: WeightUnit) {
    // What the app will actually track this as, which is what the plan should
    // show: a declaration, else the built-in, else the guess. Reading the seed
    // alone made a custom carry render as a hold.
    val kind = exercise.effectiveKind
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(displayName(exercise.exercise), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${exercise.sets.size} sets",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Sub,
                )
            }
            // Split by the same function the rest screen splits by, and now
            // drawn by the same component: what the lifter reads at a glance,
            // and what one labelled tap reveals. This screen used to draw both
            // halves in full, which is what #182 asked for and what the owner
            // meant by "only really show the shortened descriptions".
            //
            // The previous comment argued the opposite -- that the approval
            // gate must show everything behind no tap, because hiding a
            // paragraph hides it at the only moment it can be questioned. That
            // argument is retracted here rather than reworded: nothing is
            // hidden, it is one tap away and the control saying so is labelled,
            // and a plan whose exercises each carry a paragraph made the gate
            // itself unreadable, which hides more than a tap does.
            //
            // Reusing ExpandableNote also closes #170 item 4. `behindTap` is
            // `notes` and `additional_notes` joined by a blank line, and this
            // screen wrapped that whole join in ONE pair of quote marks, so two
            // separate statements read as one quotation with a hole in it.
            // ExpandableNote quotes the visible line only.
            //
            // Not everything the plan wrote: a set's own `note` (PlanSetDef)
            // is drawn nowhere on this screen. `setNote` is passed as null
            // below, and the rest screen is the only place it reaches the
            // lifter.
            val cue =
                PlanNoteDisplay.forSet(
                    description = exercise.description,
                    additionalNotes = exercise.additionalNotes,
                    notes = exercise.notes,
                    setNote = null,
                )
            if (cue.visible != null || cue.behindTap != null) {
                Spacer(Modifier.height(2.dp))
                ExpandableNote(cue.visible, cue.behindTap, BarColors.Amber)
            }
            // Stated ONCE, on the header, because the count is declared on the
            // exercise and not on a set. This screen is the approval gate --
            // the cheapest place a wrong count is caught, before any lifting
            // rather than afterwards in an export. The per-set loads below
            // stay the TOTAL, which is what they have always been and what is
            // recorded.
            exercise.implementCount?.takeIf { it > 1 }?.let { n ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "Held $n at a time — each load below is the TOTAL across all $n.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BarColors.Blue,
                )
            }

            val common = commonPrescriptions(exercise.sets)
            if (common.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    common.forEach { VerdictChip(it, ChipTone.NEUTRAL) }
                }
            }

            Spacer(Modifier.height(10.dp))
            val groups = groupSets(exercise.sets)
            groups.forEach { group ->
                SetGroupRow(group, unit, kind, exercise.bodyweight, common)
            }
        }
    }
}

/** Consecutive identical sets collapse into one row ("1–3"). */
private data class SetGroup(val firstSet: Int, val lastSet: Int, val set: PlanSetDef)

private fun groupSets(sets: List<PlanSetDef>): List<SetGroup> {
    val groups = mutableListOf<SetGroup>()
    sets.forEachIndexed { i, set ->
        val last = groups.lastOrNull()
        if (last != null && last.set == set) {
            groups[groups.lastIndex] = last.copy(lastSet = i + 1)
        } else {
            groups += SetGroup(i + 1, i + 1, set)
        }
    }
    return groups
}

/** Prescriptions shared by every set become chips instead of repeated row noise. */
private fun commonPrescriptions(sets: List<PlanSetDef>): List<String> {
    fun <T : Any> shared(pick: (PlanSetDef) -> T?): T? =
        pick(sets.first()).takeIf { first -> first != null && sets.all { pick(it) == first } }
    return listOfNotNull(
        shared { it.tempo }?.let { "tempo $it" },
        shared { it.targetMeanConcentricVelocityMps }?.let { String.format(Locale.US, "%.2f m/s", it) },
        shared { it.velocityLossStopPct }?.let { "stop −${trimNum(it)}%" },
        shared { it.restS }?.let { "rest ${formatMmSs(it)}" },
    )
}

@Composable
private fun SetGroupRow(
    group: SetGroup,
    unit: WeightUnit,
    kind: ExerciseKind,
    bodyweight: Boolean,
    common: List<String>,
) {
    val set = group.set
    val setLabel = if (group.firstSet == group.lastSet) "${group.firstSet}" else "${group.firstSet}–${group.lastSet}"
    val sidePrefix = set.side?.let { "${it.replaceFirstChar { c -> c.uppercase() }} · " } ?: ""
    val work =
        sidePrefix +
            (
                set.reps?.let { "$it reps" }
                    ?: set.durationS?.let { "${it}s ${if (kind == ExerciseKind.CARRY) "carry" else "hold"}" }
                    ?: "—"
                )
    // "BW" was already this row's answer for a set with no load, and on
    // body-weight work it is now the whole notation rather than the fallback:
    // an added plate reads "BW + 10 kg" instead of "10 kg", and an assisted
    // set reads "BW − 50 kg" instead of collapsing to bare BW beside an
    // unassisted one. This is the approval gate, so it is where a lifter is
    // most likely to notice the plan writer meant something else. #160.
    val load =
        if (bodyweight) {
            BodyweightLoadDisplay.label(set.resolvedLoadKg, unit)
        } else {
            set.resolvedLoadKg?.takeIf { it > 0 }?.let { unit.format(it) } ?: BodyweightLoadDisplay.BARE
        }

    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                setLabel,
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
                modifier = Modifier.width(40.dp),
            )
            Text(work, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(load, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        // Only what deviates from the shared chips shows up per row.
        val extras =
            listOfNotNull(
                set.tempo?.let { "tempo $it" },
                set.targetMeanConcentricVelocityMps?.let { String.format(Locale.US, "%.2f m/s", it) },
                set.velocityLossStopPct?.let { "stop −${trimNum(it)}%" },
                set.restS?.let { "rest ${formatMmSs(it)}" },
            ).filterNot { it in common }
        if (extras.isNotEmpty()) {
            Text(
                extras.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = BarColors.Sub,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
    }
}

private fun displayName(id: String): String = ExerciseDef.seedById(id)?.displayName
    ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun formatMmSs(totalS: Int): String = String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60)

private fun trimNum(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
