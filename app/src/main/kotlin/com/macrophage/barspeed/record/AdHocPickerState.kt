package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.model.AdHocPicker
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.PlanFile
import com.macrophage.barspeed.model.RecordedLift
import com.macrophage.barspeed.model.SideChoicePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * How many of the latest sessions the picker reads for "recent" and "last
 * time". History is newest first, so this is the last few weeks of training
 * for a lifter on three or four sessions a week.
 */
private const val RECENT_SESSIONS = 10

/**
 * One `set_records` row as [AdHocPicker] reads it. The row's load is
 * body-weight-inclusive on body-weight work, so the added load is
 * `loadKg - bodyWeightKg` (the entity's own KDoc); the lifter's warm-up mark
 * beats the plan's declaration, which is the entity's stated precedence.
 */
private fun recordedLiftOf(row: SetRecordEntity): RecordedLift = RecordedLift(
    exerciseId = row.exerciseId,
    addedKg = row.loadKg - (row.bodyWeightKg ?: 0.0),
    reps = row.actualReps,
    durationS = row.actualDurationS,
    tempo = row.tempo,
    side = row.side,
    warmup = row.warmupMark ?: row.warmup,
    voided = row.voided,
    endedAtMs = row.endedAtMs,
)

/**
 * Mirror the latest sessions' sets onto the state, and re-order the picker
 * from them (UX review 2026-11-06, record-setup F3, core-model F2).
 *
 * Re-read whenever the session list changes, which is a session starting or
 * ending; a set recorded inside the running session is not re-read until then.
 * A failed read leaves the picker as it was rather than ending the collector.
 */
internal fun CoroutineScope.mirrorRecentLifts(repository: SessionRepository, state: MutableStateFlow<RecordState>) =
    launch {
        repository.sessions.collectLatest { sessions ->
            val lifts =
                runCatching {
                    sessions.take(RECENT_SESSIONS).flatMap { repository.sets(it.id) }.map(::recordedLiftOf)
                }.getOrNull() ?: return@collectLatest
            state.value = withPickerOptions(state.value.copy(recentLifts = lifts))
        }
    }

/**
 * [s] with the picker's exercises rebuilt from what it now knows. The selected
 * exercise stays offered even when a plan change dropped it, so the chip that
 * is lit is always one on screen.
 */
internal fun withPickerOptions(s: RecordState): RecordState {
    val options = AdHocPicker.options(s.recentLifts, s.planSessions)
    val kept =
        if (options.any { it.id == s.selectedExerciseId }) {
            options
        } else {
            options + ExerciseDef.resolvedById(s.selectedExerciseId)
        }
    return s.copy(exerciseOptions = kept)
}

/** The active plan's name and sessions onto the state, and the picker re-ordered to put its lifts early. */
internal fun activePlanState(s: RecordState, plan: PlanFile?): RecordState =
    withPickerOptions(s.copy(planName = plan?.planName, planSessions = plan?.sessions ?: emptyList()))

/**
 * A tap on an exercise chip: nothing when it is already the one picked, so a
 * stray tap cannot wipe what was typed; otherwise [prefilledState].
 */
internal fun exerciseTappedState(s: RecordState, exerciseId: String): RecordState =
    if (exerciseId == s.selectedExerciseId) s else prefilledState(s, exerciseId)

/**
 * [exerciseId] picked, and the boxes filled from the last time the lifter did
 * it -- else [AdHocPicker.prefill]'s defaults, which open a body-weight lift
 * or a hold on 0 added rather than 60 (record-setup F3, core-model F3).
 *
 * The statements are cleared: a load or rep count typed for the lift the
 * lifter just left is not a statement about this one. The side selector keeps
 * its choice only for a lift that offers one.
 */
internal fun prefilledState(s: RecordState, exerciseId: String): RecordState {
    val exercise = s.exerciseOptions.firstOrNull { it.id == exerciseId } ?: ExerciseDef.resolvedById(exerciseId)
    val fill = AdHocPicker.prefill(exercise, AdHocPicker.lastLift(s.recentLifts, exerciseId), s.weightUnit)
    return s.copy(
        selectedExerciseId = exerciseId,
        loadInput = s.weightUnit.inputValue(fill.addedKg),
        statedLoadKg = null,
        repsInput = fill.reps.toString(),
        statedReps = null,
        durationInput = fill.durationS.toString(),
        statedDurationS = null,
        tempoInput = fill.tempo ?: "",
        sideInput = s.sideInput.takeIf { AdHocPicker.offersSide(exerciseId, s.recentLifts, s.planSessions) },
    )
}

/**
 * An OFF-PLAN set of [exerciseId] put next in a plan session, or null on an
 * ad-hoc session (record-setup F2).
 *
 * The set is marked [PlannedSlot.isAddedSet], so the export publishes it as
 * `added: true` with no prescription -- exactly what ADD SET's set is -- and
 * it takes no plan slot: every planned set is still queued behind it. It is
 * its own one-set block (`setIndexInExercise` 0 of 1), so it never joins the
 * block of the set before it or after it.
 *
 * Its values are the last time the lifter did it, else the picker's
 * defaults; its definition is [AdHocPicker.offPlanExercise]'s, so a lift the
 * plan declares elsewhere keeps the plan's geometry and prep. The boxes are
 * re-seeded to it for [appendedState]'s reason: the box and what the set would
 * record must not disagree.
 *
 * Inserted at `upcomingIndex`, which is the current slot on READY and the
 * next one during rest, and the end of the queue after the last planned set.
 * The slot it displaces has its "move the sensor" flag recomputed against it.
 */
internal fun offPlanAddedState(s: RecordState, exerciseId: String): RecordState? {
    if (s.adHoc) return null
    val at = s.upcomingIndex.coerceAtMost(s.queue.size)
    val planned = AdHocPicker.offPlanExercise(exerciseId, s.planSessions)
    val exercise = planned.exercise
    val last = AdHocPicker.lastLift(s.recentLifts, exerciseId)
    val fill = AdHocPicker.prefill(exercise, last, s.weightUnit)
    val previousId = s.queue.getOrNull(at - 1)?.exercise?.id
    val slot =
        PlannedSlot(
            exercise = exercise,
            geometry = planned.geometry,
            stackRuleApplied = planned.stackRuleApplied,
            setIndexInExercise = 0,
            setsInExercise = 1,
            reps = fill.reps.takeIf { !exercise.isTimed },
            durationS = fill.durationS.takeIf { exercise.isTimed },
            loadKg = fill.addedKg,
            plannedLoadKg = null,
            tempo = fill.tempo.takeIf { !exercise.isTimed },
            // The side he worked last time, so a one-sided lift gets the Side
            // row in the change dialog; nothing prescribed it.
            side = last?.side?.takeIf { SideChoicePolicy.offersChoice(it) },
            prepS = AdHocPicker.planDeclaration(exerciseId, s.planSessions)?.prepS,
            isAddedSet = true,
            warmup = false,
            isExerciseChange = previousId != null && previousId != exerciseId,
        )
    val queue = s.queue.toMutableList()
    queue.getOrNull(at)?.let { displaced ->
        queue[at] = displaced.copy(isExerciseChange = displaced.exercise.id != exerciseId)
    }
    queue.add(at, slot)
    return s.copy(
        queue = queue,
        loadInput = s.weightUnit.inputValue(fill.addedKg),
        statedLoadKg = null,
        statedTempo = null,
        statedReps = null,
        statedDurationS = null,
        statedSide = null,
        repsInput = fill.reps.toString(),
        durationInput = fill.durationS.toString(),
    )
}
