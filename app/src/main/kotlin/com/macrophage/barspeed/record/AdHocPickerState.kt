package com.macrophage.barspeed.record

import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.model.AdHocPicker
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.OffPlanHold
import com.macrophage.barspeed.model.PlanFile
import com.macrophage.barspeed.model.RecordedLift
import com.macrophage.barspeed.model.SideChoicePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * How many of the latest sessions the picker reads for "recent" and "last
 * time". History is newest first, so this is the last few weeks of training
 * for a lifter on three or four sessions a week.
 */
private const val RECENT_SESSIONS = 10

/**
 * One `set_records` row as [AdHocPicker] reads it. The added load is
 * [AdHocPicker.addedKg]'s: `loadKg - bodyWeightKg` on a row that stored a
 * body weight (the entity's own KDoc), and unknown on a body-weight row from
 * before one was stored. The lifter's warm-up mark beats the plan's
 * declaration, which is the entity's stated precedence.
 */
private fun recordedLiftOf(row: SetRecordEntity): RecordedLift = RecordedLift(
    exerciseId = row.exerciseId,
    addedKg = AdHocPicker.addedKg(row.exerciseId, row.loadKg, row.bodyWeightKg),
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
 * One observed query per session over the latest [RECENT_SESSIONS], combined,
 * re-subscribed whenever the session list changes. Observed rather than read
 * once (review of batch1-adhoc-rel): Room re-emits each on any `set_records`
 * write, so a set voided or corrected in History, or recorded in the running
 * session, reaches "last time" without waiting for a session to start or end.
 * The session list alone does not re-emit on those writes. A row that fails
 * to map leaves the picker as it was, and a failed query ends this mirror
 * rather than the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun CoroutineScope.mirrorRecentLifts(repository: SessionRepository, state: MutableStateFlow<RecordState>) =
    launch {
        repository.sessions
            .flatMapLatest { sessions ->
                val recent = sessions.take(RECENT_SESSIONS)
                if (recent.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(recent.map { repository.observeSets(it.id) }) { lists -> lists.flatMap { it } }
                }
            }
            .catch { }
            .collect { rows ->
                val lifts = runCatching { rows.map(::recordedLiftOf) }.getOrNull() ?: return@collect
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
 *
 * What the lifter had said for the displaced slot -- a corrected load, reps,
 * hold, tempo or side -- is held aside rather than lost ([OffPlanHold],
 * whole-release review): the rest after the off-plan set, or removing it, gives
 * it back to that slot. Where the displaced slot is itself an added set the
 * hold already standing is kept, so two off-plan sets in a row still hand the
 * block's statements back.
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
        heldStatements = s.heldOnDisplacing(s.queue.getOrNull(at)),
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

/**
 * [queue] (with [removed] already taken out at [at]) with the "move the
 * sensor" flag of the slot that moved up recomputed, when [removed] was an
 * off-plan set (record-setup F2): [offPlanAddedState] set that flag against
 * the off-plan lift, and with it gone the slot follows whatever is before it
 * again. An appended set of a block is left alone; its removal changes no
 * neighbour's exercise.
 */
internal fun offPlanRemovalSensorMoves(removed: PlannedSlot, queue: List<PlannedSlot>, at: Int): List<PlannedSlot> {
    if (!removed.isAddedSet || removed.setIndexInExercise != 0) return queue
    val movedUp = queue.getOrNull(at) ?: return queue
    val previousId = queue.getOrNull(at - 1)?.exercise?.id
    val change = previousId != null && previousId != movedUp.exercise.id
    return queue.toMutableList().apply { set(at, movedUp.copy(isExerciseChange = change)) }
}
