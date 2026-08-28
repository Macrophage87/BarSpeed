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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.PlanExerciseDef
import com.macrophage.barspeed.model.PlanNoteDisplay
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PlanSetDef
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.ChipTone
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
                    item { PlanHero(plan.planName, plan.notes, state.entity, viewModel, plan.sessions) }
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
}

@Composable
private fun PlanHero(
    name: String,
    notes: String?,
    entity: PlanEntity?,
    viewModel: PlanDetailViewModel,
    sessions: List<PlanSessionDef>,
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
        if (!active && entity != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = viewModel::activate, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Text("MAKE ACTIVE", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
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
            // Everything the EXERCISE declared, in full and behind no tap.
            // This is the approval gate -- the one screen whose job is to show
            // the lifter what they are about to accept -- and hiding a
            // paragraph here would hide it at the only moment it can still be
            // questioned. The split matters on the rest screen and is drawn
            // there.
            //
            // Not everything the plan wrote: a set's own `note` (PlanSetDef)
            // is drawn nowhere on this screen. `setNote` is passed as null
            // below, and the rest screen is the only place it reaches the
            // lifter.
            //
            // Ordered by the same function that orders it there, rather than by
            // a second copy of the precedence that could drift from it: what
            // shows without a tap, then what would have been behind one.
            val cue =
                PlanNoteDisplay.forSet(
                    description = exercise.description,
                    additionalNotes = exercise.additionalNotes,
                    notes = exercise.notes,
                    setNote = null,
                )
            listOfNotNull(cue.visible, cue.behindTap).forEach {
                Spacer(Modifier.height(2.dp))
                Text("“$it”", style = MaterialTheme.typography.bodySmall, color = BarColors.Amber)
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
                SetGroupRow(group, unit, kind, common)
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
private fun SetGroupRow(group: SetGroup, unit: WeightUnit, kind: ExerciseKind, common: List<String>) {
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
    val load = set.resolvedLoadKg?.takeIf { it > 0 }?.let { unit.format(it) } ?: "BW"

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
