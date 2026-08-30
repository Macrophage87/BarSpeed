package com.macrophage.barspeed.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.SettingsStore
import com.macrophage.barspeed.VoiceCounter
import com.macrophage.barspeed.ble.AutoConnectManager
import com.macrophage.barspeed.ble.DeviceRegistry
import com.macrophage.barspeed.ble.DeviceRole
import com.macrophage.barspeed.data.CompletedSet
import com.macrophage.barspeed.data.SecondaryCapture
import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.data.SetJournal
import com.macrophage.barspeed.data.SetJournalHeader
import com.macrophage.barspeed.data.SetJournalStore
import com.macrophage.barspeed.dsp.LiveSetState
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.SetEnd
import com.macrophage.barspeed.dsp.SetTargets
import com.macrophage.barspeed.dsp.StreamingSetTracker
import com.macrophage.barspeed.dsp.TempoSchedule
import com.macrophage.barspeed.dsp.TimedSetVoice
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.hrm.RrIngest
import com.macrophage.barspeed.model.BodyWeightPromptPolicy
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RecordedTimeZone
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.RestClockPolicy
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SensorRoster
import com.macrophage.barspeed.model.SessionCloseState
import com.macrophage.barspeed.model.SetClockPolicy
import com.macrophage.barspeed.model.SetCompletionPolicy
import com.macrophage.barspeed.model.SetEndKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetRepsPolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoAdjustPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.VoiceCue
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * How the set felt, tapped at the moment the set ends. The effort grid IS the
 * end-set control — one tap finishes the set and logs the rating, rather than
 * ending the set and then asking on a separate screen.
 */
/**
 * What the lifter said about a set: a rung of the effort scale, or the failure
 * tile, and never both.
 *
 * No warm-up member since #187. Warm-up is a property of the set rather than a
 * rating of it, declared by the plan and carried on the slot -- the tile that
 * used to set it here stored `rpe = null` alongside it, which discarded the
 * effort of every warm-up set by construction.
 */
data class SetRating(val rpe: Int?, val failed: Boolean)

/** One planned set, flattened from the plan into an ordered queue. */
data class PlannedSlot(
    val exercise: ExerciseDef,
    /** [exercise]'s direction and sensor mounting, with where each value came from. */
    val geometry: ResolvedGeometry,
    val setIndexInExercise: Int,
    val setsInExercise: Int,
    val reps: Int?,
    /** Hold/carry seconds for timed sets (plank, farmer's walk); null for rep sets. */
    val durationS: Int? = null,
    /**
     * The rep count the PLAN declared for this set, frozen and never written
     * back to, the way [plannedLoadKg] is for load and [plannedTempo] for tempo.
     *
     * [reps] carries the lifter's edit once `advancedState` has baked it in;
     * this does not. Three things turn on the difference. It is what lets
     * [SetRepsPolicy.standingStatedReps] tell "the plan prescribes 10 / 8 / 6"
     * from "the lifter changed the count and it is still standing", which is
     * the whole of #174's trap. It is what the export publishes as
     * `plannedReps`, so a coach can see the deviation -- until now the export
     * published the edit and called it the plan (#157). And it is what the
     * rest screen's deviation line compares against, so a count changed and
     * changed back no longer reads as no change (#170 item 6).
     *
     * Null on a slot the plan declared no count for, and on a slot built
     * before this field existed -- nothing persists a PlannedSlot, so the
     * second case lives only inside one running session.
     */
    val plannedReps: Int? = null,
    /** The same for a hold or carry's seconds, frozen beside [durationS]. */
    val plannedDurationS: Int? = null,
    val loadKg: Double?,
    val plannedLoadKg: Double?,
    val tempo: String?,
    /**
     * The tempo the PLAN declared for this set, frozen and never written back
     * to, the way [plannedLoadKg] is for load.
     *
     * [tempo] carries the lifter's adjustment once `advancedState` has baked it
     * in; this does not, which is what lets
     * [TempoAdjustPolicy.standingAdjustedTempo] tell "the plan prescribes a
     * tempo change for the next set" from "the lifter changed the tempo and it
     * is still standing". Comparing two `tempo` fields would compare a value
     * against itself.
     *
     * Null on a slot the plan declared no tempo for, and on a slot built before
     * this field existed -- nothing persists a PlannedSlot, so the second case
     * lives only inside one running session.
     */
    val plannedTempo: String? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /**
     * How many identical objects this exercise block is held with, as the plan
     * declared it. DISPLAY ONLY: [loadKg] and [plannedLoadKg] are the TOTAL
     * across all of them, and nothing divides either. See [ImplementLoad].
     */
    val implementCount: Int? = null,
    /**
     * The part of the plan's coaching cue shown with the set without the lifter
     * touching the phone: the exercise's `description` (or its older `notes`
     * when it declares no description), plus this set's own `note`. Built by
     * `PlanNoteDisplay.forSet`, which is where the precedence is pinned.
     */
    val exerciseNotes: String? = null,
    /**
     * The rest of that cue — the exercise's `additional_notes`, and its `notes`
     * when a `description` displaced them. Drawn only once the lifter expands
     * the note, so nothing that decides how the set is performed is in here.
     * Null when the plan wrote nothing beyond the visible line.
     */
    val exerciseNotesBehindTap: String? = null,
    val targetMeanConVelMps: Double? = null,
    val velocityLossStopPct: Double? = null,
    val restS: Int? = null,
    /**
     * Prep the plan declared for this exercise, in seconds; null when it
     * declared none. Resolved against the lifter's adjustment and the default by
     * [LeadInPolicy], never read raw.
     */
    val prepS: Int? = null,
    /**
     * How many accelerometers the plan declared for this set: the set's own
     * `sensors` where it has one, else the exercise block's, else null when
     * neither declared anything (#156).
     *
     * Resolved at flatten time because both levels are in hand there and only
     * one of them can be right for a given set; resolved against the lifter's
     * adjustment and the default by [SensorCapturePolicy], never read raw --
     * the same arrangement [prepS] has.
     */
    val sensors: Int? = null,
    val isExerciseChange: Boolean = false,
    /**
     * The plan declared this set preparatory -- a ramp set, a warm-up (#187).
     *
     * Frozen from the plan at flatten time and never written back to: it is a
     * declaration about what the set is FOR, and nothing the lifter does
     * during the set changes that. It is not a rating and does not touch one;
     * a warm-up set is rated on the same scale as any other set, which is the
     * whole reason it stopped being a tile.
     *
     * False on an ad-hoc set and on a set the lifter appended, because
     * neither has a plan to have declared it and the app offers no control
     * that says so. That is a gap rather than a statement, and it is named in
     * the commit body rather than papered over with a default that would
     * claim the lifter said something.
     */
    val warmup: Boolean = false,
    /**
     * True when the LIFTER appended this slot to the exercise mid-session and
     * the plan did not prescribe it (#177).
     *
     * An appended slot carries NO prescription at all -- [plannedLoadKg],
     * [plannedReps], [plannedDurationS] and [plannedTempo] are all null on it,
     * and that is a statement rather than a gap. Its [loadKg], [reps],
     * [durationS] and [tempo] are what the lifter was standing on at the moment
     * they added it: the corrected load, not the plan's, which is the whole
     * point of the control. Three things read the difference. The change-set
     * dialog's captions must not claim a prescription that does not exist
     * (#175). The frozen declarations must stay frozen for the sets that DO
     * have one, so a descending 10 / 8 / 6 keeps descending (#157, #174). And
     * the set write carries this flag through to the export, where it is what
     * stops an appended set occupying a prescribed slot and corrupting the
     * adherence a coach reads.
     */
    val isAddedSet: Boolean = false,
) {
    /**
     * Whether this slot is measured on the clock, which is a question about the
     * SET, not about the exercise. Agrees with [PlanSetDef.isTimed]
     * on PlanExerciseDef's sets, the same rule stated where the plan is parsed;
     * this used to add `|| exercise.isTimed` and so answered a different
     * question — what the movement usually is — with the result that a plan
     * prescribing reps of a hold got a stopwatch, a fabricated 60 s target from
     * the untouched duration field, no rep counter, and a rep count the lifter
     * could not correct afterwards.
     */
    val isTimed: Boolean get() = durationS != null
}

data class SetFeedback(
    val exerciseName: String,
    val loadKg: Double,
    /**
     * The ADDED load of the same set, which on body-weight work is a different
     * number from [loadKg]: that one is [SetLoadPolicy.totalKg], the lifter's
     * own mass included. Carried separately because it is the only one of the
     * two that may be divided across implements -- halving [loadKg] would
     * print "2 x 50 kg" for a 20 kg weighted dip at 80 kg body weight.
     */
    val addedKg: Double,
    /** The plan's declared implement count for the set just finished, if any. */
    val implementCount: Int?,
    val analysis: SetAnalysis,
    val plannedReps: Int?,
    val tempo: String?,
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    val side: String? = null,
    /** Olympic-lift style set: peak velocity is the headline metric. */
    val explosive: Boolean = false,
    /** Manually entered/corrected rep count; overrides the sensor count when set. */
    val repsOverride: Int? = null,
    /**
     * Seconds the lifter stated for a hold or a carry on the rest screen,
     * overriding what was recorded when the set ended (#168).
     *
     * Null is no correction, not zero seconds: a hold corrected DOWN to zero
     * is a different statement from one never corrected, and the display says
     * so.
     */
    val durationOverrideS: Int? = null,
) {
    val effectiveReps: Int get() = repsOverride ?: analysis.reps.size

    /**
     * Seconds this set stands at now, the lifter's correction ahead of the
     * recorded figure. Null on a set that is not timed at all.
     */
    val effectiveDurationS: Int? get() = durationOverrideS ?: actualDurationS
}

/** One pick in the "equipment busy — switch exercise" chooser. */
data class ExerciseChoice(val exerciseId: String, val displayName: String, val setsLeft: Int)

/**
 * Everything the set-end write needs, frozen at the moment the set ended.
 *
 * The point of freezing it is the retry. Half of these are read from live UI
 * state and two are read from the clock, so recomputing them on a second
 * attempt would store a different set from the one the lifter finished:
 * [actualDurationS] and [endedAtMs] would both have moved, and [orderIdx] reads
 * a counter the successful write increments.
 *
 * The sample lists are the buffers copied out, not the buffers themselves, so
 * they survive this ViewModel being destroyed while the write is still running.
 */
private data class PendingSetWrite(
    val exercise: ExerciseDef,
    /** [exercise]'s direction and mounting, frozen with everything else. */
    val geometry: ResolvedGeometry,
    val slot: PlannedSlot?,
    val isTimed: Boolean,
    val loadKg: Double,
    /** Paired with [loadKg] by [SetLoadPolicy.recordedPlannedLoadKg], same scale. */
    val plannedLoadKg: Double?,
    val addedKg: Double,
    /**
     * The rep count the PLAN prescribed, frozen from `PlannedSlot.plannedReps`
     * and never the lifter's edit. This is the figure the set is RECORDED
     * against and the one the export publishes as `plannedReps`; until #174 it
     * was the edit, so a coach reading the export could not see that the lifter
     * had deviated at all (#157).
     */
    val plannedReps: Int?,
    /**
     * The rep count the set was actually WORKING TO: the prescription unless
     * the lifter stated another, in which case theirs.
     *
     * Separate from [plannedReps] because the two answer different questions
     * and only one of them can be right per consumer. This one judges -- the
     * short-set derivation, the effort grid's gate, the guide's count, the
     * rest-screen feedback -- because a lifter who said "6 today" and did 6 did
     * not fail. [plannedReps] records, because what the plan asked for is not
     * changed by the lifter saying otherwise. Collapsing them is the defect
     * either way round.
     */
    val targetReps: Int?,
    val manualReps: Int?,
    val side: String?,
    val tempoText: String?,
    /** The hold seconds the PLAN prescribed, frozen. Recorded and exported. */
    val plannedDurationS: Int?,
    /** The hold seconds the set was working to. Judges, as [targetReps] does. */
    val targetDurationS: Int?,
    val actualDurationS: Int?,
    /** The prep prescribed and the prep that played, frozen too. */
    val plannedPrepS: Int?,
    val prepS: Int?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val orderIdx: Int,
    val samples: List<ImuSample>,
    val hrSamples: List<HrSample>,
    /** The rest window before this set, frozen with everything else. */
    val restHrSamples: List<HrSample>,
    val cues: List<VoiceCue>,
    /**
     * The instants a rep was counted this set, frozen with everything else
     * (#158).
     *
     * Taken from the journal rather than from a buffer of this class's own,
     * because the journal is already the one writer every mark goes through:
     * `addManualRep` and the guided runner's `onRepCounted` both call
     * [SetJournal.appendRepMark] and nothing else records a mark. A second
     * accumulator here would be a second thing to remember at each of those
     * sites, and the artifact it fed -- the stored set -- would then be able
     * to disagree with the recovered capture about which reps happened.
     *
     * The cost is stated rather than hidden: when no journal could be opened,
     * the marks of that set are lost. That is the same window in which the
     * interrupted-set capture does not exist either, and before this the
     * marks were discarded on every set regardless.
     */
    val repMarks: List<Long>,
    /**
     * What this set was armed with, and the capture from the accelerometer
     * that is not analysed -- both frozen with everything else (#156).
     *
     * [samples] keeps its meaning: the ANALYSED stream. [secondary] is null on
     * every one-sensor set, and non-null with an empty sample list when a role
     * was armed and its unit produced nothing, which is the state the
     * repository turns into "no row, and the declaration still names it".
     */
    val sensors: RecordedSensors?,
    val secondary: SecondaryCapture?,
    val rating: SetRating?,
    val planName: String?,
    val planSessionName: String?,
    val targets: SetTargets,
)

/**
 * The frozen set-write, in the shape the repository stores.
 *
 * A free function taking what it needs, rather than the inline construction
 * this replaces and for the same reason [openSession] is one: [RecordViewModel]
 * sits at detekt's LargeClass limit -- 599 of 600 logical lines before this
 * change -- so a thirty-line argument list living inside it means the next
 * field anywhere in the class reds `:app:detekt`, which is CI's first step.
 * Nothing else about it moved: every argument is the same expression over the
 * same three inputs, all of them already frozen.
 *
 * [failed] is passed rather than derived here. It is the OR of the lifter's own
 * tap and the app's derivation, computed at the call site from state this
 * function cannot see, and re-deriving it from [p] alone would silently drop
 * the half the lifter stated.
 */
private fun completedSetOf(p: PendingSetWrite, analysis: SetAnalysis, failed: Boolean) = CompletedSet(
    exerciseId = p.exercise.id,
    exerciseName = p.exercise.displayName,
    loadKg = p.loadKg,
    plannedLoadKg = p.plannedLoadKg,
    plannedReps = p.plannedReps,
    manualReps = p.manualReps,
    actualDurationS = p.actualDurationS,
    plannedDurationS = p.plannedDurationS,
    side = p.side,
    tempo = p.tempoText,
    targetMeanConVelMps = p.slot?.targetMeanConVelMps,
    velocityLossStopPct = p.slot?.velocityLossStopPct,
    plannedRestS = p.slot?.restS,
    plannedPrepS = p.plannedPrepS,
    prepS = p.prepS,
    startedAtMs = p.startedAtMs,
    endedAtMs = p.endedAtMs,
    analysis = analysis,
    geometry = p.geometry,
    imuSamples = p.samples,
    hrSamples = p.hrSamples,
    restHrSamples = p.restHrSamples,
    voiceCues = p.cues,
    repMarks = p.repMarks,
    sensors = p.sensors,
    secondary = p.secondary,
    rpe = p.rating?.rpe,
    failed = failed,
    // The plan's declaration, and nothing else can set it: #187 took warm-up
    // off the effort scale, so there is no tile left to OR in. An ad-hoc or
    // appended set is false because nothing declared it, which is a gap in
    // what the app can express rather than a claim that the set was work.
    warmup = p.slot?.warmup == true,
    // Off the FROZEN slot, never off live state: the queue has already moved on
    // by the time a retry runs, and the question this answers is about the set
    // that was performed. An ad-hoc set has no slot and is not appended to
    // anything -- it is its own thing, and false is the right answer for it
    // rather than a missing one (#177).
    added = p.slot?.isAddedSet == true,
)

/**
 * Open the session row the first set of a session hangs off.
 *
 * A free function taking what it needs, rather than a method, so that the one
 * platform call it makes is visible in isolation and so that the argument list
 * does not sit inside [RecordViewModel] -- a class detekt already measures as
 * being at its size limit, where every future addition competes for room.
 *
 * [ZoneId.systemDefault] is the only thing here that cannot be tested: it reads
 * the device setting, and no test in this repository runs on a device. What is
 * done WITH it -- resolving the offset that applied at this session's own start
 * instant, and refusing to report one at all when the id will not resolve --
 * is [RecordedTimeZone.resolve], which is pure and pinned in `:core:model`.
 *
 * Resolved against [PendingSetWrite.startedAtMs] rather than against the
 * current clock. The two are minutes apart in practice, but a zone's offset is
 * a function of the instant -- America/New_York is -04:00 in August and -05:00
 * in January -- so asking about the session's own instant is both free and the
 * only form that cannot be wrong for a set recorded across a transition.
 *
 * Read here, at the moment the session row is created, and never afterwards. A
 * zone read at export time would be the zone the phone is in then, which is a
 * different fact wearing the same name.
 */
private suspend fun openSession(repository: SessionRepository, p: PendingSetWrite): Long {
    return repository.startSession(
        planName = p.planName,
        planSessionName = p.planSessionName,
        startedAtMs = p.startedAtMs,
        timeZone = RecordedTimeZone.resolve(ZoneId.systemDefault().id, p.startedAtMs),
    )
}

/**
 * Apply one tap of the prep control: work out which exercise it changes and what
 * the new value is, clamped, then write it.
 *
 * A free function taking what it needs, for the reason [openSession] gives --
 * detekt measures [RecordViewModel] as at its size limit, and this addition took
 * it over. The behaviour is unchanged by the move.
 *
 * Written to the settings store and read back through its flow rather than
 * copied onto the state here. One source of truth: a state field written
 * alongside the store is a second copy that survives exactly until the next
 * emission disagrees with it.
 *
 * On [appScope] rather than the ViewModel's own scope, the same reason
 * `rateLastSet` gives -- the rest screen is where this is tapped, and the pop
 * that leaves it cancels anything still running on the ViewModel's scope. The
 * clamp is applied here as well as in the store, so a tap at either end of the
 * range moves nothing rather than storing a value the store then corrects.
 */
private fun applyPrepAdjustment(s: RecordState, deltaS: Int, appScope: CoroutineScope, settings: SettingsStore) {
    val slot = s.upcomingSlot
    val exerciseId = s.prepExerciseId(slot)
    val seconds = LeadInPolicy.clamp(s.prepSecondsFor(slot) + deltaS)
    appScope.launch { settings.setPrepS(exerciseId, seconds) }
}

/**
 * The four facts [mirrorSensorSettings] combines, carried out of the combine
 * transform so that the state read and the state write are one statement in
 * the collector.
 */
private data class SensorSettings(
    val roles: Map<String, SensorRole>,
    val counts: Map<String, Int>,
    val paired: List<String>,
    val preferred: String?,
)

/**
 * Mirror the stored body weight AND the moment it was written onto the state;
 * free for the same reason (#181).
 *
 * Two flows in one collector rather than two collectors, because the value and
 * its date are one fact: `BodyWeightPromptPolicy.stateOf` reads both together,
 * and a state carrying a new weight beside the previous weight's date -- which
 * two independent collectors can produce for one recomposition -- is the one
 * combination that classifies wrongly in the silencing direction.
 */
private fun CoroutineScope.mirrorBodyWeight(settings: SettingsStore, state: MutableStateFlow<RecordState>) = launch {
    settings.bodyWeightKg.combine(settings.bodyWeightSetAtMs) { kg, atMs -> kg to atMs }
        .collect { (kg, atMs) ->
            state.value = state.value.copy(bodyWeightKg = kg, bodyWeightSetAtMs = atMs)
        }
}

/** Mirror the stored prep adjustments onto the state; free for the same reason. */
private fun CoroutineScope.mirrorPrepOverrides(settings: SettingsStore, state: MutableStateFlow<RecordState>) =
    launch { settings.prepOverrides.collect { state.value = state.value.copy(prepOverrides = it) } }

/**
 * Mirror everything that decides which accelerometer is which onto the state,
 * and keep the second link pointed at the right device (#156).
 *
 * Four sources in one collector rather than four collectors, because the
 * roster is a function of all of them together: a role assigned to a device
 * that has since been forgotten arms nothing, and a second device paired
 * without a label arms nothing either. Emitting a state that is right about
 * three of the four and then correcting it is a window in which the READY
 * screen shows a dot for a sensor that will not be captured.
 *
 * [onSecondaryAddress] is called with the address the second link should
 * maintain, and it deliberately ignores the per-exercise COUNT: the link is
 * kept warm whenever two labelled units are paired, so arming dual for one
 * exercise does not have to wait out a BLE connect at the moment the lifter
 * taps START. What the count decides is whether the set CAPTURES from it,
 * which `beginSet` answers from the roster for its own slot.
 *
 * For a lifter with two labelled units that makes
 * up to three concurrent GATT links the steady state of EVERY set,
 * including one recorded at count 1, and the
 * second client still unlocks, sets 100 Hz and subscribes. Whether that costs
 * the analysed stream is unmeasured -- field item F1b -- and if it does, the
 * link has to be kept warm only when some exercise is armed for two rather
 * than whenever two units are labelled.
 *
 * The transform returns a [SensorSettings] and the state is read and written
 * in one statement inside `collect`, as `mirrorPrepOverrides` and all three
 * collectors of [mirrorLinkStates] do. Reading `state.value` in the transform
 * and assigning it in `collect` put the read and the write on either side of
 * `combine`'s channel hand-off. The three mirrors run as separate jobs on one
 * scope writing one `MutableStateFlow`, so anything [mirrorLinkStates] wrote
 * between the two was discarded by the write, and `imuConnected`,
 * `imuConnecting` and `imuState` re-emit only on a link-state CHANGE, so such
 * a loss does not heal itself. Read from source and reasoned about: nothing
 * in this repository can exercise [RecordViewModel], and this has not been
 * watched happen on a device.
 *
 * A free function for [openSession]'s reason: [RecordViewModel] is a class
 * detekt measures as being at its size limit.
 */
private fun CoroutineScope.mirrorSensorSettings(
    settings: SettingsStore,
    registry: DeviceRegistry,
    state: MutableStateFlow<RecordState>,
    onSecondaryAddress: (String?) -> Unit,
) = launch {
    combine(
        settings.sensorRoles,
        settings.sensorCounts,
        registry.knownDevices,
        registry.preferred(DeviceRole.IMU),
    ) { roles, counts, known, preferred ->
        SensorSettings(
            roles = roles,
            counts = counts,
            paired = known.filter { it.role == DeviceRole.IMU }.map { it.address },
            preferred = preferred?.address,
        )
    }.collect { next ->
        state.value =
            state.value.copy(
                sensorRoles = next.roles,
                sensorCountOverrides = next.counts,
                pairedImuAddresses = next.paired,
                preferredImuAddress = next.preferred,
            )
        onSecondaryAddress(
            SensorCapturePolicy.roster(
                pairedImuAddresses = next.paired,
                preferredAddress = next.preferred,
                roleByAddress = next.roles,
                requestedCount = SensorCapturePolicy.MAX_COUNT,
            ).secondaryAddress,
        )
    }
}

/**
 * Apply one tap of the sensor-count control: work out which exercise it
 * changes and write the new value.
 *
 * [applyPrepAdjustment]'s shape exactly, and for its reasons -- the value is
 * written to the store and read back through its flow rather than copied onto
 * the state.
 *
 * On [appScope] rather than the ViewModel's own scope, for
 * [applyPrepAdjustment]'s reason -- a pop that leaves the record screen
 * cancels anything still running on the ViewModel's scope, and a DataStore
 * write must not be lost to it. Unlike the prep control this one is drawn on
 * READY only, not on the rest screen, so `upcomingSlot` and `currentSlot` are
 * the same slot at every point it can be tapped.
 */
private fun applySensorCount(s: RecordState, count: Int, appScope: CoroutineScope, settings: SettingsStore) {
    val slot = s.upcomingSlot
    appScope.launch { settings.setSensorCount(s.sensorExerciseId(slot), SensorCapturePolicy.clamp(count)) }
}

/**
 * The collect job for the accelerometer that is NOT analysed.
 *
 * Deliberately not routed through `onSample`. That function feeds the tracker,
 * the live readout and the rep announcements, and every one of those is about
 * the stream the set is judged on -- a second stream reaching them would make
 * the bar appear to move twice. In the capture release the secondary reaches
 * the buffer and the journal and nothing else.
 *
 * The journal is passed as a lambda rather than a reference because the field
 * it reads is reassigned by `beginSet` and cleared when a set is stored; the
 * in-set HR collector reads the same field the same way.
 */
private fun CoroutineScope.openSecondaryCollector(
    samples: SharedFlow<ImuSample>,
    buffer: MutableList<ImuSample>,
    journal: () -> SetJournal?,
    role: SensorRole,
): Job = launch {
    samples.collect { sample ->
        buffer += sample
        journal()?.appendSecondaryImu(sample, role)
    }
}

/**
 * Mirror all three links' connection state onto the screen state.
 *
 * One free function rather than three collectors in `init`, and free for
 * [openSession]'s reason: [RecordViewModel] is a class detekt measures at its
 * size limit, and the third link took it over. The behaviour is unchanged by
 * the move -- three independent collectors, each copying one link's state onto
 * its own fields.
 *
 * `imuConnected`, `imuConnecting` and `imuState` still mean THE ANALYSED
 * SENSOR and nothing else. They have four consumers between them -- the dot,
 * the SETUP advice, whether an explosive lift is sensor-counted, and the set
 * journal's header -- and the correct answer for a second sensor differs at
 * each, so the second link gets its own fields rather than widening these
 * (#156).
 */
private fun CoroutineScope.mirrorLinkStates(autoConnect: AutoConnectManager, state: MutableStateFlow<RecordState>) {
    launch {
        autoConnect.imuState.collect { s ->
            state.value =
                state.value.copy(
                    imuConnected = s is ConnectionState.Connected,
                    imuConnecting = s is ConnectionState.Connecting,
                    imuState = s,
                )
        }
    }
    launch {
        autoConnect.imuStateB.collect { s ->
            state.value =
                state.value.copy(
                    imuConnectedB = s is ConnectionState.Connected,
                    imuStateB = s,
                )
        }
    }
    launch {
        autoConnect.hrmState.collect { s ->
            state.value =
                state.value.copy(
                    hrmConnected = s is ConnectionState.Connected,
                    hrmConnecting = s is ConnectionState.Connecting,
                    hrmState = s,
                )
        }
    }
}

/**
 * Open the durable capture for a set that is about to begin.
 *
 * A free function taking what it needs, for the reason [openSession] gives:
 * [RecordViewModel] is a class detekt measures as being at its size limit,
 * where every addition competes for room.
 *
 * Everything here is read at the moment the set starts, because that is the
 * only moment some of it is true. [RecordState.imuConnected] in particular is
 * what lets a capture holding zero samples be read correctly: zero with the
 * sensor connected is a failure, zero without one is the expected outcome of a
 * manually counted set, and no stream can tell those apart afterwards.
 *
 * [RecordState.sessionId] is null for the first set of a session and that null
 * is carried rather than smoothed over. No session row exists until the first
 * set has been durably written -- which is exactly why losing the first set
 * loses the session too, and why the capture has to name the session by the
 * clock instead.
 *
 * Returns null when the disk refuses, and the caller records the set anyway.
 * A journal that cannot be opened is the situation before this existed; it
 * must not also be a reason not to lift.
 */
private fun openJournal(
    store: SetJournalStore,
    s: RecordState,
    exercise: ExerciseDef,
    sessionStartedAtMs: Long,
    startedAtMs: Long,
    roster: SensorRoster,
): SetJournal? = store.open(
    SetJournalHeader(
        exerciseId = exercise.id,
        exerciseName = exercise.displayName,
        sessionId = s.sessionId,
        sessionStartedAtMs = sessionStartedAtMs,
        startedAtMs = startedAtMs,
        orderIdx = s.setsCompleted,
        imuConnected = s.imuConnected,
        planName = s.planName.takeIf { !s.adHoc },
        planSessionName = s.planSessionName.takeIf { !s.adHoc },
        // The roles this set was armed for, so a recovered capture holding one
        // stream can be told from one that was only ever armed for one -- the
        // same reason `imuConnected` is here. Empty on every one-sensor set.
        sensorRoles = roster.expected,
        analysedRole = roster.analysed,
        // The second link's state at that same moment, for the reading
        // `imuConnected` is here for -- but only on a set actually armed for
        // two. The link is kept warm whenever two units are labelled, so an
        // ungated write would record it on a count-1 set that never opened
        // `imu-b.csv`, and the interrupted-set card would then report a second
        // stream missing that nothing ever asked for.
        secondaryImuConnected = roster.secondary != null && s.imuConnectedB,
    ),
)

/**
 * A tap on a plan session: either raise the body-weight prompt, or start (#181).
 *
 * Free function taking the state flow and a start callback, for
 * [planSessionState]'s reason -- [RecordViewModel] is a class detekt measures
 * at its size limit, and the prompt's three entry points took it over.
 *
 * [BodyWeightPromptPolicy] decides whether to ASK, never whether the lifter may
 * train: both branches end in a started session, one of them after a dialog.
 * [nowMs] is read at the tap and passed in rather than read inside the
 * Composable, because a recomposing dialog re-deciding the staleness of the
 * stored weight is a decision that could change underneath a lifter already
 * typing into it.
 */
private fun askOrStartSession(
    state: MutableStateFlow<RecordState>,
    planSession: PlanSessionDef,
    nowMs: Long,
    onStart: (PlanSessionDef) -> Unit,
) {
    val s = state.value
    val ask =
        BodyWeightPromptPolicy.shouldPrompt(
            session = planSession,
            kg = s.bodyWeightKg,
            setAtMs = s.bodyWeightSetAtMs,
            nowMs = nowMs,
            skippedThisSession = s.bodyWeightPromptSkipped,
        )
    if (ask) state.value = s.copy(pendingBodyWeightSession = planSession) else onStart(planSession)
}

/**
 * The body-weight prompt has been answered. Either way the session starts (#181).
 *
 * [kg] null is the SKIP, and it is a real absence rather than a sentinel: a
 * skip writes nothing at all, not even a confirmation of the figure already
 * stored, because dating an unconfirmed number would buy fourteen days of
 * quiet for a value nobody looked at. What a skip does write is the
 * session-scoped flag that stops a second ask, cleared when the session closes.
 *
 * The write and the start are sequential inside one coroutine, so the stored
 * weight is durable before the session's first set can be set up. The recorded
 * load reads `bodyWeightKg` off the state at recordSet time, minutes later
 * either way, so the ordering is belt-and-braces rather than the thing that
 * makes the load right.
 *
 * Returns without doing anything when no prompt is pending, so a double tap on
 * SKIP or SAVE cannot start the session twice.
 */
private fun CoroutineScope.answerBodyWeight(
    state: MutableStateFlow<RecordState>,
    settings: SettingsStore,
    kg: Double?,
    onStart: (PlanSessionDef) -> Unit,
) {
    val pending = state.value.pendingBodyWeightSession ?: return
    state.value =
        state.value.copy(pendingBodyWeightSession = null, bodyWeightPromptSkipped = kg == null)
    launch {
        if (kg != null) settings.setBodyWeightKg(kg)
        onStart(pending)
    }
}

/**
 * The state opening a plan session leaves behind. Free function for
 * [openSession]'s reason: [RecordViewModel] is a class detekt measures as being
 * at its size limit, and the two seeds added below took it over.
 */
private fun planSessionState(s: RecordState, planSession: PlanSessionDef, queue: List<PlannedSlot>): RecordState =
    s.copy(
        stage = Stage.READY,
        planSessionName = planSession.name,
        queue = queue,
        queueIndex = 0,
        adHoc = false,
        // Empty, not the plan's number rendered through inputValue: that
        // render is lossy, and seeding the field with it would put a
        // display-quantised value one keystroke away from being recorded as
        // the lifter's own. Empty also stops the field opening on "60" -- or
        // on whatever the last session left -- under a card stating the plan's
        // load, which is #22's shape in an editable box.
        loadInput = "",
        statedLoadKg = null,
        // Seeded from the first slot the way every later rest transition seeds
        // them, and REQUIRED now rather than cosmetic. These two fields opened
        // on "5" and "60" -- the class defaults, or whatever the last session
        // left -- and nothing on READY read them, so a plan asking 8 reps was
        // recorded as 8 anyway. beginSet's bake reads them, so an unseeded
        // field would write 5 over the plan's 8 at set one, and the reps box
        // the change dialog now offers would have opened showing a number the
        // plan never wrote.
        repsInput = queue.firstOrNull()?.reps?.toString() ?: s.repsInput,
        durationInput = queue.firstOrNull()?.durationS?.toString() ?: s.durationInput,
        // Seeded text is not a statement, one target over from statedLoadKg
        // above: nothing has been typed for set one yet, so the plan's own
        // count is what the bake will carry.
        statedReps = null,
        statedDurationS = null,
    )

/**
 * Add one more set of the exercise the lifter is on, at the values they are
 * standing on right now (#177).
 *
 * The owner's case is in his own session's capture twice. Sets 4 and 5 of
 * field-33 are a seated overhead press prescribed 8 at 40 lb, recorded 7 and
 * failed, then dropped to 30 lb for a completed 8 -- the load was wrong for
 * the day and correcting it cost a set. Sets 14 to 16 are a lat pulldown
 * whose 60 lb opener is marked warm-up and whose working weight, 75 lb, was
 * found only after it. When the first set reveals the load was wrong, the
 * plan's remaining set count is the wrong count, and what the lifter wants
 * is one more set of the exercise they are on -- without editing the plan,
 * without an ad-hoc session, and without the added set being
 * indistinguishable from a prescribed one afterwards.
 *
 * WHAT IT INHERITS. [carriedValues] -- the same function the bake uses, not
 * a second copy of it -- so the appended slot opens on the carried load, the
 * carried rep count or hold, and the carried tempo: what is STANDING, which
 * after #124, #174 and #148 may be something the lifter said several sets
 * ago. A set that reverted to the prescription would re-create the problem
 * it exists to solve, by offering back the 40 lb that just failed.
 *
 * WHAT IT DOES NOT INHERIT. Every frozen declaration is cleared. Nothing
 * prescribed this set, so `plannedLoadKg`, `plannedReps`, `plannedDurationS`
 * and `plannedTempo` are null and their absence is a statement. That is what
 * keeps #157's frozen figures honest for the sets that DO have one, and what
 * makes `PlanValueCaption` draw nothing rather than claiming a prescription
 * (#175).
 *
 * A CONSEQUENCE, STATED RATHER THAN HIDDEN. Because the appended slot
 * declares nothing, `SetLoadPolicy.standingStatedAddedKg` and its three
 * neighbours compare a declaration against a null and stop the carry AT it
 * -- so a load the lifter states AFTER appending, on some set in between,
 * does not reach the appended set. The values snapshotted here do. That is a
 * re-statement the lifter has to make, not silent data loss: the rest
 * screen's box shows the snapshot and the set records exactly what the box
 * shows, which is the property #45 and #124 are about. Making the carry
 * reach it would mean teaching four policies that an appended set prescribes
 * no change, and that is a wider change than this issue.
 *
 * A FREE FUNCTION taking what it needs, for [jumpedState]'s reason:
 * [RecordViewModel] is a class detekt measures as being AT its size limit, and
 * this method's body took it over -- measured, not guessed, by running detekt
 * with the body inline.
 *
 * WHERE IT GOES is [addedSetIndex]'s, which is pure and pinned in
 * `PlanQueueTest`. The block's remaining sets keep their place; the appended
 * set follows them.
 *
 * `setIndexInExercise` is the previous slot's plus one, which is both what
 * the heading counts from and what makes the NEXT append land after this one
 * -- `addedSetIndex` walks the block by that index, so an appended slot has
 * to read as a continuation of it. `setsInExercise` is set to match, so the
 * card cannot say "Set 4 of 3"; the prescribed sets keep the plan's own
 * count, because that is what the plan asked of them.
 *
 * `isExerciseChange` is false and no other slot's flag is recomputed. The
 * insertion point is inside the block or at its end, so the appended set's
 * predecessor is always the same exercise, and the slot after the insertion
 * point keeps whichever answer it already had.
 *
 * Repeatable, and removal is out of scope (#177 item 5): nothing here
 * shortens the queue.
 *
 * RUN ON A DEVICE, ONCE, and this is the only place that says so -- #177's own
 * commit body listed all of it as `[Field]` because it was written before the
 * bench run. On `barspeed-api35`, a three-set press block corrected 18.1 -> 13.6
 * kg with two appends taken on READY: the queue ran prescribed 2, prescribed 3,
 * then "Set 4 · you added this one" and "Set 5 · you added this one", both at
 * 13.6 kg, and the lat pulldown block that followed opened at its own declared
 * 27.2 kg. The appended sets' change dialog drew NO "Plan says" caption where
 * the prescribed sets drew one. `sqlite3` read five `set_records`: the three
 * prescribed carrying `plannedLoadKg` 18.14 and `added` 0, the two appended
 * carrying NULL and `added` 1. The saved export published `"added": true` on
 * exactly those two, omitted the key on the other three, and validated against
 * `docs/schemas/session-export.schema.json` under the same ajv invocation
 * `ci.yml` runs.
 *
 * That last line is what matters most here, because it covers the hop no JVM
 * test can: `completedSetOf`'s `added = p.slot?.isAddedSet == true` survived
 * mutation with the whole suite green, and the exported document is the
 * evidence that it is wired. WHAT THE RUN DID NOT COVER: the install was fresh,
 * so Room created v12 outright and `MIGRATION_11_12` did NOT execute. It is
 * still unexecuted, and the cluster's two-way exercise still owes 10 -> 11 -> 12
 * against real rows.
 */
private fun appendedQueue(s: RecordState): List<PlannedSlot>? {
    if (s.adHoc) return null
    val upcoming = s.upcomingSlot ?: return null
    val at = addedSetIndex(s.queue.map { QueueBlockKey(it.exercise.id, it.setIndexInExercise) }, s.upcomingIndex)
    val previous = s.queue[at - 1]
    val appended =
        carriedValues(upcoming, s).copy(
            plannedLoadKg = null,
            plannedReps = null,
            plannedDurationS = null,
            plannedTempo = null,
            isAddedSet = true,
            isExerciseChange = false,
            setIndexInExercise = previous.setIndexInExercise + 1,
            setsInExercise = previous.setIndexInExercise + 2,
        )
    return s.queue.toMutableList().apply { add(at, appended) }
}

/**
 * The state "Equipment busy? Switch exercise" leaves behind.
 *
 * A free function taking what it needs, rather than a method, for the reason
 * [openSession] gives: [RecordViewModel] is a class detekt measures as being at
 * its size limit, where every addition competes for room.
 *
 * [fixed] is non-empty because jumpToExercise returns before reaching here when
 * the chosen exercise has no sets left, so there is always a planned next set.
 * upcomingIndex is queueIndex + 1 from the rest screen and queueIndex from
 * READY: either way this slot has not been through the bake at startNextSet's
 * load carry, so its loadKg is still the plan's declaration rather than a value
 * the app wrote back.
 *
 * [RecordState.statedLoadKg] is cleared because the set being set up has just
 * changed. A load typed for the exercise the lifter was about to do is not a
 * statement about the one they switched to, and this route is reachable from
 * READY, where that typed value is the one the set would be recorded with.
 */
private fun jumpedState(s: RecordState, done: List<PlannedSlot>, fixed: List<PlannedSlot>): RecordState {
    val upcoming = fixed.first()
    val seedKg =
        SetLoadPolicy.seedAddedKg(
            hasPlannedNext = true,
            nextDeclaredAddedKg = upcoming.loadKg,
            lastAddedKg = null,
        )
    return s.copy(
        queue = done + fixed,
        // Refresh the editable inputs so they describe the new upcoming set.
        loadInput = seedKg?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
        statedLoadKg = null,
        // Cleared for [RecordState.statedLoadKg]'s reason, one field over: a
        // tempo set on the wheels for the exercise the lifter was about to do
        // is not a statement about the one they switched to.
        statedTempo = null,
        // Cleared for statedLoadKg's reason, two fields over: a rep count or
        // a hold set for the exercise the lifter was about to do is not a
        // statement about the one they switched to.
        statedReps = null,
        statedDurationS = null,
        repsInput = upcoming.reps?.toString() ?: s.repsInput,
        durationInput = upcoming.durationS?.toString() ?: s.durationInput,
        tempoInput = upcoming.tempo ?: "",
    )
}

/**
 * Equipment busy: the chosen exercise's remaining sets pulled forward so they
 * are done next, keeping everything else in order -- or null when nothing
 * moves. Deviating set order is fine; recorded sets keep their actual
 * timestamps.
 *
 * MOVED OUT OF [RecordViewModel] UNCHANGED by #177, and behaviour-preserving:
 * every expression is the one that was inside the method, the three early
 * returns became three nulls, and the caller writes what comes back. It moved
 * because that class is AT detekt's LargeClass threshold -- measured, not
 * guessed: adding #177's four-line entry point reds `:app:detekt`, which is
 * CI's first step. This is the same relief [jumpedState], [restingState] and
 * [advancedState] were each extracted for, and their KDocs say so.
 */
private fun jumpedToExerciseState(s: RecordState, exerciseId: String): RecordState? {
    if (s.adHoc) return null
    val done = s.queue.take(s.upcomingIndex)
    val remaining = s.queue.drop(s.upcomingIndex)
    if (remaining.firstOrNull()?.exercise?.id == exerciseId) return null
    val (target, others) = remaining.partition { it.exercise.id == exerciseId }
    if (target.isEmpty()) return null
    val reordered = target + others
    // Recompute "move the sensor" boundaries for the new order.
    val fixed =
        reordered.mapIndexed { i, slot ->
            val prevId = if (i == 0) done.lastOrNull()?.exercise?.id else reordered[i - 1].exercise.id
            slot.copy(isExerciseChange = prevId != null && prevId != slot.exercise.id)
        }
    return jumpedState(s, done, fixed)
}

/**
 * The state a rest-screen effort correction leaves behind.
 *
 * Free function for the reason [restingState] and [advancedState] are: it is a
 * pure `copy` over a state and some arguments, and `RecordViewModel` is at
 * detekt's `LargeClass` limit -- one more multi-line copy inside the class
 * pushes it over, which is not a reason to write the correction in fewer
 * fields.
 *
 * [tappedFailed] is what the lifter just said and [effectiveFailed] is the OR
 * `SetRatingTracker` returned. Both are stored, because the correction grid has
 * to attribute the verdict and the OR cannot say whose it was. #140.
 */
private fun ratedState(s: RecordState, rpe: Int?, tappedFailed: Boolean, effectiveFailed: Boolean): RecordState =
    s.copy(
        lastSetRpe = rpe,
        lastSetFailed = effectiveFailed,
        // SetRatingTracker overwrites its own tapped flag on every correction,
        // so this mirrors it exactly: a correction away from the failed tile
        // withdraws the tap and leaves the derived shortfall standing.
        lastSetTappedFailed = tappedFailed,
        // lastSetWarmup is deliberately NOT written. It is the plan's
        // declaration about the set, frozen when the set was recorded, and
        // re-rating the effort says nothing about whether it was a ramp (#187).
    )

/**
 * The state a set that has just begun recording leaves behind. Free function
 * for [ratedState]'s reason, and unchanged in content by the commit that moved
 * it out: every field it writes is written to the same value it was written to
 * inline.
 */
private fun inSetState(s: RecordState, manualSet: Boolean, guidedSet: Boolean, leadInRunning: Boolean): RecordState =
    s.copy(
        stage = Stage.IN_SET,
        setElapsedS = 0,
        live = LiveSetState(),
        manualSet = manualSet,
        manualReps = 0,
        guidedSet = guidedSet,
        guidedLabel = "",
        guidedCountdown = 0,
        guidedFinished = false,
        leadInRunning = leadInRunning,
    )

/**
 * The state a rest-screen duration correction leaves behind (#168). Free
 * function for [ratedState]'s reason, and it mirrors that one exactly:
 * `lastSetTappedFailed` is absent on purpose, because correcting how long a
 * hold lasted re-derives the shortfall and says nothing about whether the
 * lifter felt they failed.
 *
 * Why the correction is here at all, and post-set rather than mid-set: a hold
 * or a carry now ends when its clock reaches the seconds it was working to,
 * so the recorded
 * figure is the announced one and the walk back to the phone is no longer
 * inside it. The rare deliberate overage is stated on the rest screen, where
 * every other post-set correction already lives, because the owner does not
 * look at the phone while holding -- "There are rare instances I even look at
 * the phone mid set" -- so a mid-set affordance would be exercised never. The
 * delta moves the figure that currently stands, so repeated taps accumulate,
 * and `TimedSetEndPolicy.adjustedSeconds` floors the result at zero.
 */
private fun durationCorrectedState(s: RecordState, seconds: Int, effectiveFailed: Boolean): RecordState = s.copy(
    lastFeedback = s.lastFeedback?.copy(durationOverrideS = seconds),
    lastSetFailed = effectiveFailed,
)

/**
 * The seconds a finished timed set records, or null for a set that is not
 * timed at all.
 *
 * Free function for [ratedState]'s reason, and it is the join of the two rules
 * rather than either of them: [SetClockPolicy] says which instant the set is
 * measured from, [TimedSetEndPolicy] says whether the measurement or the
 * prescription is what gets written down. Both are pinned in `:core:model`;
 * what lives here is only the wiring, which nothing in this repository can
 * execute.
 */
private fun recordedTimedSeconds(
    isTimed: Boolean,
    prepCase: PrepCase,
    tappedAtMs: Long,
    clockStartedAtMs: Long?,
    endedAtMs: Long,
    targetS: Int?,
    autoEnded: Boolean,
): Int? = if (!isTimed) {
    null
} else {
    TimedSetEndPolicy.recordedSeconds(
        measuredS = SetClockPolicy.heldSeconds(prepCase, tappedAtMs, clockStartedAtMs, endedAtMs),
        targetS = targetS,
        autoEnded = autoEnded,
    )
}

/**
 * The rest-screen state the set just written leaves behind. Free function for
 * [openSession]'s reason.
 *
 * queue[queueIndex + 1] has not been through startNextSet's bake yet, so its
 * loadKg is still the plan's declaration. That is the field to seed from, not
 * plannedLoadKg: plannedLoadKg is frozen at the plan's number and would discard
 * an in-rest edit made to a set that then got postponed. Nothing else pins that
 * ordering, and the seam's unit tests cannot see it break.
 *
 * The write half stays outside the seam: startNextSet carries a load into this
 * same slot when the lifter taps through, so what is seeded below is read back
 * as a declaration one set later.
 *
 * [RecordState.statedLoadKg] is re-decided rather than cleared. What the lifter
 * said about the load holds for the rest of the exercise block, and
 * [SetLoadPolicy.standingStatedAddedKg] is what says whether it still holds
 * here -- at the exercise boundary it does not, nor where the plan declares a
 * different load for the set coming up, and in both cases the field is
 * re-seeded from the plan exactly as it always was. #124.
 *
 * [RecordState.statedTempo] is re-decided by the same rule on the same
 * boundaries, so a tempo the lifter set on the wheels holds for the rest of the
 * block and no further. #148.
 *
 * [restS] is the whole prescribed period and [restRemainingS] is what is left
 * of it at the moment this state is built. TWO arguments rather than one, and
 * the second is not derivable from the first here: how much of the rest has
 * already gone is a decision about instants, taken by
 * [com.macrophage.barspeed.model.RestClockPolicy] where a test can reach it,
 * and this function only places the answer. The progress ring reads the pair,
 * so a period seeded part-drained draws part-drained. #172.
 */
private fun restingState(
    s: RecordState,
    p: PendingSetWrite,
    analysis: SetAnalysis,
    failed: Boolean,
    restS: Int,
    restRemainingS: Int,
): RecordState {
    val nextSlot = s.nextSlot
    // Where the block ends. Hoisted to a local because the load and the tempo
    // are bounded by the SAME block -- asking twice would be two statements of
    // one rule, and the way they diverge is one of the two carrying past a
    // boundary the other stops at.
    val sameBlock =
        SetLoadPolicy.sameExerciseBlock(
            lastExerciseId = p.slot?.exercise?.id,
            nextExerciseId = nextSlot?.exercise?.id,
            nextSetIndexInExercise = nextSlot?.setIndexInExercise,
        )
    // What the lifter said about the load, and whether it still applies to the
    // set coming up. The decision is SetLoadPolicy's; this hands it the two
    // slots' FROZEN declarations -- never their loadKg, which the bake below
    // has already written a statement into -- and where the block ends.
    val standingKg =
        SetLoadPolicy.standingStatedAddedKg(
            statedAddedKg = s.statedLoadKg,
            sameExerciseBlock = sameBlock,
            lastDeclaredAddedKg = p.slot?.plannedLoadKg,
            nextDeclaredAddedKg = nextSlot?.plannedLoadKg,
        )
    // The same question about the tempo, decided by the same four boundaries.
    // plannedTempo on both sides, never tempo: the bake has already written the
    // adjustment into the slot's own tempo, so comparing those would compare a
    // value against itself and the carry would never stop.
    val standingTempo =
        TempoAdjustPolicy.standingAdjustedTempo(
            adjustedTempo = s.statedTempo,
            sameExerciseBlock = sameBlock,
            lastDeclaredTempo = p.slot?.plannedTempo,
            nextDeclaredTempo = nextSlot?.plannedTempo,
        )
    // The same question again for the rep count and the hold, bounded by the
    // same block and decided by the same rule. The two slots' FROZEN
    // declarations on both sides, never their live reps/durationS: the bake
    // writes the statement into those, so comparing them would compare a
    // number against itself and a descending 10 / 8 / 6 would flatten to
    // 12 / 12 / 12 the moment set one was changed. #174.
    val standingReps =
        SetRepsPolicy.standingStatedReps(
            statedReps = s.statedReps,
            sameExerciseBlock = sameBlock,
            lastDeclaredReps = p.slot?.plannedReps,
            nextDeclaredReps = nextSlot?.plannedReps,
        )
    val standingDurationS =
        SetRepsPolicy.standingStatedDurationS(
            statedDurationS = s.statedDurationS,
            sameExerciseBlock = sameBlock,
            lastDeclaredDurationS = p.slot?.plannedDurationS,
            nextDeclaredDurationS = nextSlot?.plannedDurationS,
        )
    val seedKg =
        SetLoadPolicy.seedAddedKg(
            hasPlannedNext = nextSlot != null,
            nextDeclaredAddedKg = nextSlot?.loadKg,
            // addedKg, never loadKg: the field holds what is ADDED, and seeding
            // it with the body-weight-inclusive total is what made a loadless
            // block climb set over set.
            lastAddedKg = p.addedKg,
        )
    return s.copy(
        stage = Stage.RESTING,
        setWrite = SetWriteState.NONE,
        restTotalS = restS,
        lastFeedback =
        SetFeedback(
            exerciseName = p.exercise.displayName,
            loadKg = p.loadKg,
            // Both frozen with the rest of the write. The count comes off the
            // slot rather than off live state, which has already moved on by
            // the time the rest screen draws.
            addedKg = p.addedKg,
            implementCount = p.slot?.implementCount,
            analysis = analysis,
            // The WORKING targets, not the plan's frozen prescription. This
            // feedback is about the set just finished -- what it was trying to
            // do -- and the hold verdict beside it reads the same pair: a
            // lifter who cut a 45 s plank to 30 and held 30 made their hold,
            // and grading them against the 45 the plan wrote would call it
            // short for a change they made deliberately. The prescription is
            // recorded separately and is what the export publishes.
            plannedReps = p.targetReps,
            tempo = p.tempoText,
            actualDurationS = p.actualDurationS,
            plannedDurationS = p.targetDurationS,
            side = p.side,
            explosive = p.exercise.kind == ExerciseKind.EXPLOSIVE,
            repsOverride = p.manualReps,
        ),
        lastSetRpe = p.rating?.rpe,
        lastSetFailed = failed,
        // The rating frozen with the write is the only tap there has been at
        // this point; [failed] above already carries the derived shortfall
        // OR-ed in, and that OR is what this field exists to see past.
        lastSetTappedFailed = p.rating?.failed == true,
        lastSetWarmup = p.slot?.warmup == true,
        restRemainingS = restRemainingS,
        // Set from the frozen index rather than incremented, so a retry cannot
        // count the same set twice.
        setsCompleted = p.orderIdx + 1,
        // Pre-fill next-set inputs so in-rest edits start from plan values --
        // except the load, where a statement that still stands is shown ahead
        // of the plan's number, so the box and what the set would record cannot
        // disagree.
        loadInput = (standingKg ?: seedKg)?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
        statedLoadKg = standingKg,
        // Same arrangement as the load one line up: a statement that still
        // stands is shown ahead of the plan's number, so the box and what the
        // set would record cannot disagree. The fallbacks are the WORKING
        // targets of the set just finished, not its frozen prescription --
        // they are reached only when the plan has run out, where what the
        // lifter last did is the only thing to go on.
        repsInput = (standingReps ?: nextSlot?.reps ?: p.targetReps ?: 5).toString(),
        statedReps = standingReps,
        durationInput =
        (standingDurationS ?: nextSlot?.durationS ?: p.targetDurationS)?.toString() ?: s.durationInput,
        statedDurationS = standingDurationS,
        statedTempo = standingTempo,
        // The AD-HOC field. On a plan session nothing draws or reads it -- the
        // wheels read statedTempo above, falling back to the slot's own
        // declaration -- and seedTempo no longer hands it the last tempo that
        // ran when a planned set is coming up, which is the inheritance this
        // change removes.
        tempoInput =
        TempoAdjustPolicy.seedTempo(
            hasPlannedNext = nextSlot != null,
            nextDeclaredTempo = nextSlot?.tempo,
            lastRanTempo = p.tempoText,
        ) ?: "",
    )
}

/**
 * The state one tap of a tempo stepper leaves behind. Free function for
 * [openSession]'s reason.
 *
 * The tempo the tapped digit belongs to is read from [RecordState.statedTempo]
 * falling back to the upcoming slot's own declaration -- the same expression
 * the control draws itself from, so what is on screen and what is written back
 * cannot disagree about which tempo is being edited.
 *
 * A tap that would not spell a tempo returns the state UNCHANGED rather than
 * clamping to something near it. [TempoAdjustPolicy.withDigit] is what decides,
 * and it refuses anything the control could not have drawn, so this cannot
 * write a prescription the metronome will not play or the exporter will not
 * recognise. In practice the control only offers values that pass; a refusal
 * here means the slot's tempo was not one four single-character digits can
 * show, and the control is not drawn for those at all.
 */
private fun tempoAdjustedState(s: RecordState, position: Int, value: String): RecordState {
    val editing = s.statedTempo ?: s.upcomingSlot?.tempo
    val turned = TempoAdjustPolicy.withDigit(editing, position, value) ?: return s
    return s.copy(statedTempo = turned)
}

/**
 * The state starting an ad-hoc session leaves behind. Free function for
 * [openSession]'s reason, and because the added argument would push the call
 * past 120 characters: the wrap costs five code lines against the size limit
 * where moving the whole expression out costs none.
 *
 * [RecordState.statedLoadKg] is cleared to keep one rule rather than to close a
 * reachable defect: resolve reads the typed field, never the stated one, on an
 * ad-hoc set.
 */
private fun adHocSessionState(s: RecordState): RecordState = s.copy(
    stage = Stage.READY,
    adHoc = true,
    queue = emptyList(),
    statedLoadKg = null,
    statedReps = null,
    statedDurationS = null,
)

/**
 * The state tapping through to the next planned set leaves behind, with any
 * in-rest edits applied. Free function for [openSession]'s reason.
 *
 * [RecordState.statedTempo] travels with [RecordState.statedLoadKg] through
 * both branches and for the same reasons, one field over.
 *
 * [RecordState.statedLoadKg] is cleared in the ad-hoc branch, which never reads
 * it, and SURVIVES the plan branch. The bake below consumes it into the slot,
 * and it is also the statement [restingState] puts back to
 * [SetLoadPolicy.standingStatedAddedKg] once the set that slot carries has been
 * written -- clearing it here would end a carry after one set from the other
 * side of the same rule. Between here and there nothing reads it that the bake
 * has not already given the same number to: [SetLoadPolicy.resolve] prefers it
 * over a declaration that now equals it, and the plate line prefers it over a
 * `loadKg` that now equals it.
 */
private fun advancedState(s: RecordState): RecordState {
    val next = s.nextSlot
    if (s.adHoc || next == null) {
        return s.copy(
            stage = Stage.READY,
            statedLoadKg = null,
            statedTempo = null,
            statedReps = null,
            statedDurationS = null,
        )
    }
    return bakedState(s, next, s.queueIndex + 1)
}

/**
 * The same bake, applied to the set already current, when START is tapped on
 * READY.
 *
 * READY is drawn once per session and it now carries the whole change-set
 * dialog (#152), because the owner's case is walking into a gym where every
 * squat rack is occupied: set one is exactly when rerouting matters. Three of
 * the four controls in that dialog wrote nothing that reached set one before
 * this function existed, and the failure was silent in the worst way -- the
 * control accepted the tap, the screen showed the new value, and `beginSet`
 * read past it:
 *
 *  - `beginSet` takes the rep target from `currentSlot.reps`, never
 *    `repsInput`, on a planned set.
 *  - It takes the tempo from `currentSlot.tempo`, never `statedTempo`, so the
 *    metronome, the voice guide and the compliance verdict all ran the plan's
 *    tempo while the screen showed the lifter's.
 *  - The hold seconds come from `currentSlot.durationS` through
 *    `currentTimedTargetS`.
 *
 * Only the load already worked, because `SetLoadPolicy.resolve` reads
 * `statedLoadKg` directly. From set two onwards all four have always worked,
 * because `startNextSet` runs [advancedState] first and the bake is what makes
 * them work. This is that same bake with the same arguments on the slot
 * `upcomingIndex` already names -- one rule reaching both screens, rather than
 * a second copy of it for the first set.
 *
 * Not merged into `beginSet`: `beginSet` is also the ad-hoc entry point, where
 * there is no slot to bake into and the typed fields are read directly.
 */
private fun startedFromReadyState(s: RecordState): RecordState {
    val slot = s.currentSlot
    if (s.adHoc || slot == null) return s
    return bakedState(s, slot, s.queueIndex)
}

/**
 * The lifter's edits written into the slot the next START will run.
 *
 * One function for both entry points, because two copies of "which of the
 * plan's numbers does a statement displace" is two rules. [index] is
 * `upcomingIndex` at both call sites -- `queueIndex + 1` during rest,
 * `queueIndex` on READY -- and the stage becomes READY either way.
 */
private fun bakedState(s: RecordState, next: PlannedSlot, index: Int): RecordState {
    val edited = carriedValues(next, s)
    val queue = s.queue.toMutableList()
    queue[index] = edited
    return s.copy(queue = queue, queueIndex = index, stage = Stage.READY)
}

/**
 * [slot] with the lifter's standing statements written into the four values a
 * set is run with, and its frozen declarations untouched.
 *
 * Extracted from [bakedState] verbatim -- every argument is the same expression
 * over the same inputs, and nothing about what it computes moved. It is a
 * function of its own because a SECOND caller arrives with #177: an appended
 * set starts from what the lifter is standing on, which is exactly this, and
 * "what is standing" stated in two places is two rules that can disagree about
 * a load carried across a warm-up.
 */
private fun carriedValues(slot: PlannedSlot, s: RecordState): PlannedSlot {
    val next = slot
    return next.copy(
        loadKg =
        SetLoadPolicy.carriedIntoNextSet(
            declaredAddedKg = next.loadKg,
            statedAddedKg = s.statedLoadKg,
        ),
        // NOT behaviour-identical to the two `?:` expressions on main,
        // which read `s.repsInput.toIntOrNull() ?: next.reps` and
        // `s.durationInput.toIntOrNull() ?: next.durationS`. c1 moved those
        // verbatim into SetRepsPolicy; this commit changes what is handed
        // to them. The outcome coincides on a validated plan only because
        // the boxes are re-seeded from the very declaration they fall back
        // to, and PlanSetDef.validate requires exactly one of reps /
        // duration_s, so no slot the bake reads is countless.
        // The rule is SetRepsPolicy's now for the reason the load's is
        // SetLoadPolicy's: it is about to grow a boundary, and a rule that
        // grows in :app grows where no test on the CI path can reach it.
        // statedReps / statedDurationS, not the text boxes. The boxes are
        // re-seeded on every rest transition and hold a number whether or
        // not the lifter typed one; reading them here is the same mistake
        // #45 was for the load, where a seeded field was read back as a
        // statement the lifter never made. What displaces the plan's
        // declaration is a count the lifter actually gave, and nothing
        // else.
        //
        // plannedReps and plannedDurationS are deliberately not in this
        // copy: they stay frozen at the plan's declaration so restingState
        // can tell a prescribed change from a standing statement one set
        // later, and so the export can publish what was prescribed.
        reps =
        if (next.isTimed) {
            next.reps
        } else {
            SetRepsPolicy.carriedIntoNextSet(declaredReps = next.reps, statedReps = s.statedReps)
        },
        durationS =
        if (next.isTimed) {
            SetRepsPolicy.carriedDurationIntoNextSet(
                declaredDurationS = next.durationS,
                statedDurationS = s.statedDurationS,
            )
        } else {
            next.durationS
        },
        // statedTempo, not tempoInput. The text field is the ad-hoc
        // control and reading it here is how one exercise's tempo reached
        // the next: restingState seeded it from the set just finished
        // whenever the coming one declared none. What displaces the plan's
        // declaration now is a tempo the lifter actually set on the wheels
        // for THIS block, and nothing else.
        //
        // plannedTempo is deliberately not in this copy: it stays frozen at
        // the plan's declaration so restingState can tell a prescribed
        // tempo change from a standing adjustment one set later.
        tempo =
        TempoAdjustPolicy.carriedIntoNextSet(
            declaredTempo = next.tempo,
            adjustedTempo = s.statedTempo,
        ),
    )
}

data class RecordState(
    val stage: Stage = Stage.SETUP,
    val planName: String? = null,
    val planSessionName: String? = null,
    val planSessions: List<PlanSessionDef> = emptyList(),
    val queue: List<PlannedSlot> = emptyList(),
    val queueIndex: Int = 0,
    val adHoc: Boolean = false,
    val exerciseOptions: List<ExerciseDef> = ExerciseDef.SEED,
    val selectedExerciseId: String = ExerciseDef.SEED.first().id,
    val loadInput: String = "60",
    /**
     * The added load the lifter has stated for the set now being set up, in kg,
     * and null when they have stated nothing for it.
     *
     * A different fact from [loadInput], which is one string reused across
     * every set of a session and holds a value from an earlier set until
     * something re-seeds it. This has no default that means anything and is
     * written only by [RecordViewModel.updateLoadInput] -- a keystroke -- or by
     * [SetLoadPolicy.standingStatedAddedKg] ruling on a rest transition that an
     * earlier keystroke still applies. Seeding the text does NOT set it, and
     * that separation is what makes a forgotten clear cost a stale string on
     * screen rather than a stale recorded load.
     */
    val statedLoadKg: Double? = null,
    val repsInput: String = "5",
    /**
     * The rep count the lifter has stated for the set now being set up, and
     * null when they have stated nothing for it.
     *
     * A different fact from [repsInput], exactly as [statedLoadKg] is a
     * different fact from [loadInput]: the text is one string reused across
     * every set of a session and re-seeded on every rest transition, while this
     * is written only by [RecordViewModel.updateRepsInput] -- a keystroke -- or
     * by [SetRepsPolicy.standingStatedReps] ruling that an earlier keystroke
     * still applies. Seeding the text does NOT set it. #174.
     */
    val statedReps: Int? = null,
    val durationInput: String = "60",
    /** The hold analogue of [statedReps], one target over. */
    val statedDurationS: Int? = null,
    /** Ad-hoc unilateral side: null (bilateral), "left", or "right". */
    val sideInput: String? = null,
    /**
     * The AD-HOC tempo text field. A plan session neither draws it nor reads
     * it: its control is the digit steppers, and what they produce is
     * [statedTempo].
     */
    val tempoInput: String = "",
    /**
     * The tempo the lifter has set on the steppers for the set now being set up,
     * and null when they have set none.
     *
     * The tempo analogue of [statedLoadKg] and separate from [tempoInput] for
     * the reason given there: this has no default that means anything, is
     * written only by a tap of a stepper or by
     * [TempoAdjustPolicy.standingAdjustedTempo] ruling that an earlier tap
     * still applies, and cannot outlive the block it was made in. Seeding a
     * text field does not set it.
     *
     * It cannot hold a tempo the app will not run. Every value that reaches it
     * comes from [TempoAdjustPolicy.withDigit], which refuses anything four
     * single-character digits could not have drawn, and no value a digit takes
     * clears it.
     */
    val statedTempo: String? = null,
    val live: LiveSetState = LiveSetState(),
    /** Sensorless rep set: the lifter taps to count reps. */
    val manualSet: Boolean = false,
    val manualReps: Int = 0,
    /** Active guided-cadence set: the app calls the tempo and counts the reps. */
    val guidedSet: Boolean = false,
    val guidedLabel: String = "",
    val guidedCountdown: Int = 0,
    val guidedPhaseTotal: Int = 1,
    /**
     * True while the lead-in before the set's own work is playing -- the prep
     * before a hold or a carry, and the identical prep before a guided
     * cadence.
     *
     * The set is recording and nothing it will be measured on has started:
     * [setElapsedS] is 0 and stays there until a hold's prep ends, and the
     * guide has called no stroke. A different question from [guidedSet], which
     * says whether a CADENCE follows the lead-in; a hold has none.
     *
     * One flag for both, and not two that must agree: the two windows are the
     * same fact about the set -- it has not begun -- and [setStarted] is the
     * only thing that reads it apart from the ring the prep draws.
     */
    val leadInRunning: Boolean = false,

    /** True once the voice guide has called the prescription all the way through. */
    val guidedFinished: Boolean = false,
    val setElapsedS: Int = 0,
    val hrBpm: Int? = null,
    /** Rolling HRV (RMSSD, ms) over the last ~2 minutes of beats. */
    val hrvMs: Int? = null,
    val lastFeedback: SetFeedback? = null,
    val restRemainingS: Int = 0,
    val restTotalS: Int = 0,
    /** RPE the lifter picked for the just-finished set (rest screen), if any. */
    val lastSetRpe: Int? = null,
    /**
     * The effective failed verdict: what the lifter tapped OR the shortfall
     * derived from the count, exactly as it was stored with the set row.
     */
    val lastSetFailed: Boolean = false,
    /**
     * The lifter's own failure tap, kept apart from [lastSetFailed] so the rest
     * screen can tell a verdict the lifter gave from one the app derived.
     *
     * `SetRatingTracker` holds both facts and ORs them privately, so without
     * this field the two are indistinguishable downstream by construction, and
     * a screen that has to attribute one of them cannot. #140.
     */
    val lastSetTappedFailed: Boolean = false,
    val lastSetWarmup: Boolean = false,
    val audioCues: Boolean = true,
    val imuConnected: Boolean = false,
    val imuConnecting: Boolean = false,
    val hrmConnected: Boolean = false,
    val hrmConnecting: Boolean = false,
    /** The dot and the SETUP advice both need the whole state, not just these booleans. */
    val imuState: ConnectionState = ConnectionState.Disconnected,
    val hrmState: ConnectionState = ConnectionState.Disconnected,
    val demoMode: Boolean = false,
    val sessionId: Long? = null,
    val setsCompleted: Int = 0,
    /**
     * Where the set-end durable write has got to.
     *
     * Not the same fact as `endingSet`, which is set when the set ends and is
     * cleared only by the next `beginSet`, so it stays true for the whole of
     * RESTING. This one goes back to NONE the moment the write lands, which is
     * what Back needs to know: the stage is IN_SET for all three values.
     */
    val setWrite: SetWriteState = SetWriteState.NONE,
    /**
     * Where the close of the session has got to.
     *
     * Independent of [setWrite], not a fourth value on it. Both can be
     * outstanding at once in principle, and the record exit gate answers them
     * with separate parameters so that pair stays representable.
     *
     * Nothing sets this to anything but NONE yet; the writer arrives with the
     * commit that moves the close off `viewModelScope`.
     */
    val sessionClose: SessionCloseState = SessionCloseState.NONE,
    /**
     * True once the lifter has tapped Finish session and before they have
     * answered or skipped the session rating (#159).
     *
     * The lifter's intent and nothing else. It does not gate the close, does
     * not gate any write, and is not a fourth value on [sessionClose]: what may
     * be drawn while it is true is [RestControlPolicy]'s decision, taken from
     * this and the close state together, so that the rating panel can only ever
     * appear where the finish control would have. Cleared by answering, by
     * skipping and by starting another set.
     */
    val askingSessionRpe: Boolean = false,
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Lifter body weight, the base load for pull-ups and dips; null until set. */
    val bodyWeightKg: Double? = null,
    /**
     * When [bodyWeightKg] was written, epoch millis, or null when the app does
     * not know -- a value carried over from a build that stored no date. Read
     * only by the start-of-session prompt (#181); nothing that records a set
     * consults it.
     */
    val bodyWeightSetAtMs: Long? = null,
    /**
     * The plan session waiting on the body-weight prompt, or null when nothing
     * is being asked.
     *
     * The session itself rather than a boolean, because the answer has to
     * start THAT session and the picker the tap came from is a list. Holding a
     * flag and re-reading a selection would be two facts about one intent.
     */
    val pendingBodyWeightSession: PlanSessionDef? = null,
    /**
     * The lifter has said "not now" to the body-weight prompt since the last
     * session closed. Nothing asks again until then (#181).
     */
    val bodyWeightPromptSkipped: Boolean = false,
    /**
     * The lifter's prep adjustments, exercise id to seconds. Empty until the
     * settings flow first emits, which is why nothing here reads it directly --
     * [prepSecondsFor] falls back through the plan's declaration to the default.
     */
    val prepOverrides: Map<String, Int> = emptyMap(),
    /**
     * The second accelerometer's link, issue #156.
     *
     * [imuConnected] and [imuState] are NOT redefined to mean "any IMU" or
     * "all IMUs". They have four consumers between them -- the dot, the SETUP
     * advice, whether an explosive lift is sensor-counted, and the set
     * journal's header, which exists so that a capture of zero samples can be
     * read correctly -- and the right answer differs per consumer, so one flag
     * cannot serve both sensors. These are separate fields and every existing
     * reader is untouched.
     */
    val imuStateB: ConnectionState = ConnectionState.Disconnected,
    val imuConnectedB: Boolean = false,
    /** Device address to role, as the lifter labelled them; see `SettingsStore.sensorRoles`. */
    val sensorRoles: Map<String, SensorRole> = emptyMap(),
    /** Exercise id to the count the lifter chose, where they chose one. */
    val sensorCountOverrides: Map<String, Int> = emptyMap(),
    /** Every paired IMU address, and which of them the analysed link maintains. */
    val pairedImuAddresses: List<String> = emptyList(),
    val preferredImuAddress: String? = null,
) {
    val currentSlot: PlannedSlot? get() = queue.getOrNull(queueIndex)
    val nextSlot: PlannedSlot? get() = queue.getOrNull(queueIndex + 1)

    /**
     * The exercise the set being set up or recorded runs against.
     *
     * On the state rather than in [RecordViewModel] because the live readout
     * needs it too: the in-set tempo ring resolves which digit it is charging a
     * phase against, and it has to resolve that against the SAME lift
     * [RecordViewModel.beginSet] handed the tracker. Two statements of this
     * rule is one more than can be kept in agreement.
     */
    val currentExercise: ExerciseDef
        get() = currentSlot?.exercise
            ?: ExerciseDef.seedById(selectedExerciseId)
            ?: ExerciseDef(selectedExerciseId, selectedExerciseId)

    /** Index of the first not-yet-done slot: during rating/rest the current one is already complete. */
    val upcomingIndex: Int
        get() = if (stage == Stage.RESTING) queueIndex + 1 else queueIndex

    /** The slot the next START will run: the current one, or the next during rest. */
    val upcomingSlot: PlannedSlot? get() = queue.getOrNull(upcomingIndex)

    /**
     * The slot AFTER the one being set up, and null at the end of the queue.
     *
     * Read by the change-set dialog's captions and by nothing else: whether a
     * statement the lifter is making right now will outlive this set is a
     * question about the pair of slots the carry policies compare, so the
     * caption asks those policies about this slot and this one rather than
     * asserting a reach of its own (#174, #175).
     */
    val slotAfterUpcoming: PlannedSlot? get() = queue.getOrNull(upcomingIndex + 1)

    /** Which exercise's prep a control on screen is editing. */
    fun prepExerciseId(slot: PlannedSlot?): String = slot?.exercise?.id ?: currentExercise.id

    /**
     * The prep a set of [slot] will play: the lifter's stored adjustment, else
     * the plan's declaration, else the default.
     *
     * A function taking the slot rather than a property, because the screen and
     * [RecordViewModel.beginSet] ask about different slots -- the next one during
     * rest, the current one at START. The RULE is stated once, in
     * [LeadInPolicy.resolve]; only the subject differs.
     */
    fun prepSecondsFor(slot: PlannedSlot?): Int = LeadInPolicy.resolve(slot?.prepS, prepOverrides[prepExerciseId(slot)])

    /** What the plan prescribed for [slot]: its declaration, or the default. */
    fun plannedPrepSecondsFor(slot: PlannedSlot?): Int = LeadInPolicy.planned(slot?.prepS)

    /** Which exercise's sensor count a control on screen is editing. */
    fun sensorExerciseId(slot: PlannedSlot?): String = slot?.exercise?.id ?: currentExercise.id

    /**
     * How many accelerometers a set of [slot] will run with: the lifter's
     * stored choice, else the plan's declaration, else one.
     *
     * A function taking the slot rather than a property, for
     * [prepSecondsFor]'s reason -- the screen and [RecordViewModel.beginSet]
     * ask about different slots. The RULE is stated once, in
     * [SensorCapturePolicy.resolve].
     */
    fun sensorCountFor(slot: PlannedSlot?): Int =
        SensorCapturePolicy.resolve(slot?.sensors, sensorCountOverrides[sensorExerciseId(slot)])

    /** What the PLAN prescribed for [slot], which is what the export pairs the actual against. */
    fun plannedSensorCountFor(slot: PlannedSlot?): Int = SensorCapturePolicy.planned(slot?.sensors)

    /**
     * What a set of [slot] would be armed with right now.
     *
     * Everything about which physical unit is which is decided by
     * [SensorCapturePolicy.roster] in `:core:model`, where a test runs on it.
     * This only hands it what it needs; the screen reads the answer to draw
     * the count chip and the second dot, and `beginSet` reads it again at the
     * moment the set starts, which is the only moment it is true.
     */
    fun rosterFor(slot: PlannedSlot?): SensorRoster = SensorCapturePolicy.roster(
        pairedImuAddresses = pairedImuAddresses,
        preferredAddress = preferredImuAddress,
        roleByAddress = sensorRoles,
        requestedCount = sensorCountFor(slot),
    )

    /**
     * True when the set coming up will play a prep -- a tempo'd lift, or a hold
     * or a carry. [LeadInPolicy.playsPrep], asked of the upcoming slot rather
     * than the current one, so the control on the rest screen is about the set
     * it sits above.
     */
    val upcomingPlaysPrep: Boolean
        get() {
            val slot = upcomingSlot
            val tempo = if (adHoc) tempoInput.ifBlank { null } else slot?.tempo
            return LeadInPolicy.playsPrep(
                hasTempo = tempo?.let { Tempo.parseOrNull(it) } != null,
                isTimed = slot?.isTimed ?: currentIsTimed,
                kind = (slot?.exercise ?: currentExercise).kind,
            )
        }

    /** Other exercises with sets still to do — offered when equipment is busy. */
    val exerciseChoices: List<ExerciseChoice>
        get() {
            if (adHoc) return emptyList()
            val remaining = queue.drop(upcomingIndex)
            val upcomingId = remaining.firstOrNull()?.exercise?.id ?: return emptyList()
            return remaining
                .groupBy { it.exercise.id }
                .filterKeys { it != upcomingId }
                .map { (id, slots) -> ExerciseChoice(id, slots.first().exercise.displayName, slots.size) }
        }

    /** True when the set being set up / recorded is duration-based (hold or carry). */
    val currentIsTimed: Boolean
        get() = currentSlot?.isTimed
            ?: (adHoc && exerciseOptions.firstOrNull { it.id == selectedExerciseId }?.isTimed == true)

    /** Target seconds for the current timed set. */
    val currentTimedTargetS: Int?
        get() = if (!currentIsTimed) null else currentSlot?.durationS ?: durationInput.toIntOrNull()

    /** Reps asked of the set in progress; ad-hoc sets use the typed target. */
    val currentTargetReps: Int?
        get() = if (adHoc) repsInput.toIntOrNull() else currentSlot?.reps

    /**
     * True once the set in progress has delivered what it was asked for — the
     * point at which rating the effort makes sense. Before it, the only honest
     * way out is to stop early, which is a failed set.
     *
     * Judged exactly where the count can be trusted, matching the auto-fail rule
     * in [RecordViewModel.endSet]: timed sets against the clock, voice-guided
     * sets against the guide, and hand-counted sets against the app's own rep
     * count. A sensor total is never trusted here — a low miscount would
     * otherwise leave a lifter who finished every rep with no way to end the set
     * except by logging it as a failure — and a set with no target has nothing
     * to fall short of.
     */
    val setTargetMet: Boolean
        get() = when {
            // The same rule the set write applies, asked of the same function
            // rather than written out a second time here (#168): a threshold
            // stated twice is a threshold that can disagree with itself, and
            // this one decides which controls the lifter is offered while the
            // write decides whether the set is recorded as failed.
            currentIsTimed -> !TimedSetEndPolicy.fellShort(setElapsedS, currentTimedTargetS)
            // The guide finishing IS the set being done. Its rep count lands one
            // stroke early, before the closing cue is even spoken, and a guided
            // set given no rep target never finishes on its own at all.
            guidedSet -> guidedFinished || currentTargetReps == null
            manualSet -> currentTargetReps?.let { manualReps >= it } ?: true
            else -> true
        }

    /**
     * Whether the app KNOWS the set in progress has delivered its
     * prescription -- and null where it cannot know at all (#186).
     *
     * A different question from [setTargetMet], and the difference is the
     * whole of why this exists. That one answers `true` wherever there is
     * nothing to fall short of, because its job is to decide which way OUT
     * sits beside the grid and a set with no target must not be pushed down
     * the failure path. This one has to distinguish "not finished yet" from
     * "nothing here can ever say it finished": the second is an ad-hoc hold
     * with no target, or a guided set the plan gave no rep count, and
     * `GuidedCadenceRunner` never calls `onFinished` on one of those at all.
     * Rendering that absence as `false` would withhold the effort grid for the
     * whole set and leave a tapped failure as the only exit.
     *
     * Timed sets read the same [TimedSetEndPolicy.fellShort] the write and
     * [setTargetMet] read, so the clock cannot say one thing here and another
     * there.
     */
    val setComplete: Boolean?
        get() = SetCompletionPolicy.complete(
            timed = currentIsTimed,
            timedTargetS = currentTimedTargetS,
            elapsedS = setElapsedS,
            guided = guidedSet,
            targetReps = currentTargetReps,
            guidedFinished = guidedFinished,
        )

    /**
     * Whether the set's own clock or cadence has begun.
     *
     * False for the whole lead-in, which is a window in which the set is
     * recording, nothing has been measured, and the question of how it went
     * has no answer at all -- not even "not yet".
     */
    val setStarted: Boolean
        get() = !leadInRunning

    /**
     * Which completion signal, if any, the set in progress has -- the decision
     * [SetEndControlPolicy] gates on, made in `:core:model` where a test runs
     * on it every push rather than inside a composable nothing can reach.
     */
    val setEndKind: SetEndKind
        get() = SetEndKind.of(
            timed = currentIsTimed,
            explosive = currentExerciseKind == ExerciseKind.EXPLOSIVE,
            guided = guidedSet,
        )

    /**
     * What the movement in front of the lifter IS, for the set being set up or
     * recorded.
     *
     * The queue's slot where there is one, the ad-hoc picker's selection
     * otherwise, and DYNAMIC where neither has resolved yet. Read by
     * [setEndKind] and by the screen, which used to carry its own copy of
     * these three lines; two statements of "which exercise is this" can
     * disagree, and the one in the screen was unreachable by any test.
     *
     * NOT [currentExercise], whose fallback is a seed lookup by id: that one
     * answers a question about the exercise DEFINITION and invents an empty
     * definition rather than returning nothing, which would call a custom
     * timed exercise DYNAMIC.
     */
    val currentExerciseKind: ExerciseKind
        get() = currentSlot?.exercise?.kind
            ?: exerciseOptions.firstOrNull { it.id == selectedExerciseId }?.kind
            ?: ExerciseKind.DYNAMIC
}

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val autoConnect = container.autoConnect
    private val sessionRepository = container.sessionRepository

    private val stateFlow = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = stateFlow

    private val imuBuffer = mutableListOf<ImuSample>()

    /**
     * The capture from the accelerometer that is NOT analysed (#156).
     *
     * [imuBuffer] keeps its meaning -- the analysed stream -- so nothing that
     * already reads it changes. This one is filled by a collector that reaches
     * the buffer and the journal and nothing else.
     */
    private val imuBufferB = mutableListOf<ImuSample>()
    private val hrBuffer = mutableListOf<HrSample>()

    /**
     * Heart rate arriving while no set is running -- the READY window before
     * the first set, and every rest window after one. Issue #90.
     *
     * RAW. These are the notifications as received, duplicates and all, taken
     * from the sample the collector was handed rather than from the
     * de-duplicated beats it derives beside them. Issue #81 removes re-sent
     * intervals from the two ANALYSIS accumulators and deliberately leaves
     * every raw capture untouched; this is a raw capture. Storing the
     * de-duplicated form here would look entirely plausible and would destroy
     * the only data anyone could use to measure #81's cost at resting rates.
     *
     * Cleared when the set that carries it is frozen for writing, not when the
     * next one begins: the window belongs to the set that FOLLOWS it, so it
     * has to survive from the end of one set to the freeze of the next.
     */
    private val restHrBuffer = mutableListOf<HrSample>()

    /** Spoken cues this set, epoch-ms stamped for IMU cross-reference in exports. */
    private val cueBuffer = mutableListOf<VoiceCue>()
    private var tracker: StreamingSetTracker? = null
    private var collectJob: Job? = null
    private var collectJobB: Job? = null

    /**
     * What the set in progress was armed with, frozen at [beginSet].
     *
     * Read at the moment the set starts and not again, because that is the
     * only moment it is true: the lifter can label a device or forget one
     * mid-session, and a set already recording must be stored as the thing it
     * was armed as. Null between sets.
     */
    private var armedSensors: RecordedSensors? = null
    private var armedSecondaryRole: SensorRole? = null
    private var hrJob: Job? = null
    private var tickJob: Job? = null
    private var restJob: Job? = null
    private var demoJob: Job? = null
    private var guidedCadence: GuidedCadenceRunner? = null
    private var setStartedAtMs = 0L

    /**
     * When the set's own clock started, or null while it has not started.
     *
     * Distinct from [setStartedAtMs], which is when RECORDING started and is the
     * set journal's t0. Which of the two a figure is measured from is
     * [SetClockPolicy]'s decision, not this file's.
     */
    private var clockStartedAtMs: Long? = null

    /** Which prep the set in progress played; frozen at [beginSet] for [endSet]. */
    private var prepCaseForSet: PrepCase = PrepCase.NONE

    /**
     * The set in progress is ending because its clock reached the planned
     * duration, not because the lifter ended it.
     *
     * Set by [startSetTimer] on the tick [TimedSetEndPolicy.endsNow] answers
     * for, immediately before it calls [endSet], and cleared at [beginSet].
     * [endSet] reads it to decide which figure the set records: the
     * prescription for an end the app timed, the measurement for one the
     * lifter made. Written from the tick coroutine and read in [endSet], both
     * on the main dispatcher, as every other field here is.
     */
    private var autoEndedSet = false

    /** When this session began, for grouping a session's captures on disk. */
    private var sessionStartedAtMs = 0L

    /**
     * The set being performed right now, on disk.
     *
     * Null when no set is running, or when the disk refused to open one. It
     * outlives [endSet] deliberately: the set being over and the set being
     * stored are a whole durable write apart, and this is what covers that gap.
     */
    private var journal: SetJournal? = null
    private val ratings = SetRatingTracker(sessionRepository)

    /**
     * The effort grid is seven separately clickable tiles, and finishing a set
     * takes a few hundred ms (analysis, then gzipping the whole sample stream).
     * Two fingers landing on two tiles would otherwise run endSet twice, which
     * starts two sessions and writes the same set twice.
     */
    private var endingSet = false

    /**
     * The set-end write's frozen input, kept until that write lands so a failed
     * one can be retried from exactly what the lifter finished.
     */
    private var pendingWrite: PendingSetWrite? = null

    /**
     * The prep prescribed for the set in progress, and the prep that played.
     * Both null on a set that played none.
     *
     * Held here for the same reason `plannedRepsForSet` is: the value is decided
     * at [beginSet], when the set's guided-ness is known, and is needed at
     * [endSet], which is a different call with different state in front of it.
     */
    private var plannedPrepSForSet: Int? = null
    private var prepSForSet: Int? = null

    /**
     * The row id the set-end write already obtained, or null if it has not got
     * that far. A retry that ignored this would insert the set a second time,
     * under the same orderIdx, with its own copy of the gzipped stream, and
     * nothing in the app can delete a set row.
     */
    private var writtenSetId: Long? = null

    /**
     * Why the foreground service is running, for the whole process.
     *
     * Not a field of this ViewModel's own, and that is the point: the question
     * it answers is asked in [onCleared], as this instance is being destroyed,
     * about work that outlives it.
     */
    private val holds = container.recordingHolds

    /** Closing the session, on the scope this screen going away cannot cancel. */
    private val closer = SessionCloser(sessionRepository, container.appScope, holds)

    /** All R-R intervals seen during the active session (sets + rests) for session HRV. */
    private val sessionRrMs = mutableListOf<Double>()

    /** Recent beats only, for the live rolling HRV readout. */
    private val recentRrMs = ArrayDeque<Double>()

    /**
     * The last heart-rate notification that carried R-R intervals, at any stage.
     *
     * [RrIngest] needs it to tell a notification that brought a new beat from
     * one re-sending the last. Deliberately NOT cleared when a session starts:
     * a beat that completed before the session began is not new merely because
     * [sessionRrMs] is.
     *
     * Nor cleared across a dropout, and that is right rather than merely
     * tolerable. A strap that has lost contact holds its last value and keeps
     * re-sending it -- which is why the unworn capture collapses from 46, 91
     * and 91 intervals to 1, 1 and 6. Resetting on reconnect would let the
     * first notification back count a beat the strap had already reported.
     *
     * Advanced through [RrIngest.nextPrevious] rather than assigned here, so
     * the one rule about when the reference moves has one implementation. This
     * file has no test source set behind it; that function does.
     */
    private var lastHrSample: HrSample? = null
    private var voice: VoiceCounter? = null
    private var lastCountedPhase: Phase = Phase.IDLE
    private var lastSpokenSecond = 0
    private var lastAnnouncedRep = 0
    private var plannedRepsForSet: Int? = null
    private var announceReps = false

    init {
        viewModelScope.mirrorLinkStates(autoConnect, stateFlow)
        viewModelScope.launch {
            container.planRepository.activePlan.collect { entity ->
                val plan = entity?.let { container.planRepository.decode(it) }
                stateFlow.value =
                    stateFlow.value.copy(
                        planName = plan?.planName,
                        planSessions = plan?.sessions ?: emptyList(),
                    )
            }
        }
        // Passive HR display even outside sets; R-R intervals feed the HRV readouts.
        viewModelScope.launch {
            autoConnect.hrSamples.collect { hr ->
                // Which beats this notification brought, decided in :core:hrm
                // where a test can reach the rule. This strap re-sends its last
                // completed R-R when no beat has arrived (#81), and counting
                // those again deflates every HRV the app publishes.
                val beats = RrIngest.newBeats(lastHrSample, hr)
                lastHrSample = RrIngest.nextPrevious(lastHrSample, hr)
                // The raw notification, not `beats`. This buffer is a capture,
                // not an analysis, and the de-duplication above must not reach
                // it -- see restHrBuffer's own note. This collector runs at
                // every stage; the in-set collector below owns IN_SET, so the
                // guard here is what keeps the two captures disjoint.
                if (stateFlow.value.stage in setOf(Stage.READY, Stage.RESTING)) restHrBuffer += hr
                if (beats.isNotEmpty()) {
                    recentRrMs.addAll(beats)
                    while (recentRrMs.size > ROLLING_HRV_BEATS) recentRrMs.removeFirst()
                    val inSession =
                        stateFlow.value.stage in setOf(Stage.READY, Stage.IN_SET, Stage.RESTING)
                    if (inSession) sessionRrMs += beats
                }
                stateFlow.value =
                    stateFlow.value.copy(
                        hrBpm = hr.bpm,
                        hrvMs = Hrv.rmssdMs(recentRrMs.toList())?.toInt(),
                    )
            }
        }
        viewModelScope.launch {
            container.settings.weightUnit.collect { unit ->
                stateFlow.value = stateFlow.value.copy(weightUnit = unit)
            }
        }
        viewModelScope.mirrorBodyWeight(container.settings, stateFlow)
        viewModelScope.mirrorPrepOverrides(container.settings, stateFlow)
        viewModelScope.mirrorSensorSettings(container.settings, container.deviceRegistry, stateFlow) { address ->
            autoConnect.setSecondaryImuAddress(address)
        }
        viewModelScope.launch {
            container.settings.audioCues.collect { enabled ->
                stateFlow.value = stateFlow.value.copy(audioCues = enabled)
                if (enabled && voice == null) voice = VoiceCounter(getApplication())
            }
        }
    }

    fun toggleAudioCues() {
        viewModelScope.launch { container.settings.setAudioCues(!stateFlow.value.audioCues) }
    }

    fun toggleWeightUnit() {
        viewModelScope.launch {
            container.settings.setWeightUnit(stateFlow.value.weightUnit.other())
        }
    }

    fun toggleDemoMode() {
        stateFlow.value = stateFlow.value.copy(demoMode = !stateFlow.value.demoMode)
    }

    /** The tap on a plan session in the picker; [askOrStartSession] decides. */
    fun requestPlanSession(planSession: PlanSessionDef) =
        askOrStartSession(stateFlow, planSession, System.currentTimeMillis(), ::startPlanSession)

    /** The body-weight prompt's answer, null meaning skip; [answerBodyWeight] decides. */
    fun answerBodyWeightPrompt(kg: Double?) =
        viewModelScope.answerBodyWeight(stateFlow, container.settings, kg, ::startPlanSession)

    /** Start a session following the given plan session. */
    private fun startPlanSession(planSession: PlanSessionDef) {
        viewModelScope.launch {
            sessionRrMs.clear()
            // A new session does not inherit the last one's trailing rest
            // window. That window belongs to no set of either session and is
            // the one gap this design leaves open, tracked separately; letting
            // it drift forward would attach it to the wrong session entirely.
            restHrBuffer.clear()
            sessionStartedAtMs = System.currentTimeMillis()
            val queue = sessionRepository.flattenPlan(planSession)
            stateFlow.value = planSessionState(stateFlow.value, planSession, queue)
        }
    }

    /** Equipment busy: every decision is [jumpedToExerciseState]'s; this is the tap. */
    fun jumpToExercise(exerciseId: String) {
        stateFlow.value = jumpedToExerciseState(stateFlow.value, exerciseId) ?: return
    }

    /** One more set of the current exercise (#177); every decision is [appendedQueue]'s. */
    fun addSetOfCurrentExercise() {
        stateFlow.value = stateFlow.value.copy(queue = appendedQueue(stateFlow.value) ?: return)
    }

    fun startAdHocSession() {
        sessionRrMs.clear()
        restHrBuffer.clear()
        sessionStartedAtMs = System.currentTimeMillis()
        stateFlow.value = adHocSessionState(stateFlow.value)
    }

    fun selectExercise(id: String) {
        stateFlow.value = stateFlow.value.copy(selectedExerciseId = id)
    }

    fun updateLoadInput(text: String) {
        val s = stateFlow.value
        stateFlow.value = s.copy(loadInput = text, statedLoadKg = s.weightUnit.parseToKg(text))
    }

    // The keystroke is the only thing that makes a count a STATEMENT, which is
    // updateLoadInput's arrangement one target over: the text and the statement
    // are written together here and nowhere else, so a re-seed of the text
    // cannot manufacture one. An unparseable box states nothing rather than
    // stating zero -- a lifter mid-retype has not asked for a zero-rep set.
    fun updateRepsInput(text: String) {
        stateFlow.value = stateFlow.value.copy(repsInput = text, statedReps = text.trim().toIntOrNull())
    }

    fun updateDurationInput(text: String) {
        stateFlow.value = stateFlow.value.copy(durationInput = text, statedDurationS = text.trim().toIntOrNull())
    }

    fun selectSide(side: String?) {
        stateFlow.value = stateFlow.value.copy(sideInput = side)
    }

    fun updateTempoInput(text: String) {
        stateFlow.value = stateFlow.value.copy(tempoInput = text)
    }

    /** One tap of a tempo digit's stepper between sets; every decision is [tempoAdjustedState]'s. */
    fun adjustTempoDigit(position: Int, value: String) {
        stateFlow.value = tempoAdjustedState(stateFlow.value, position, value)
    }

    /**
     * Begin recording the current set.
     *
     * The first statement is the bake, and it is here rather than beside the
     * START button because this is the one door every set goes through. See
     * [startedFromReadyState] for what silently failed to reach set one
     * without it. On the rest-screen path `startNextSet` has already run the
     * same bake through `advancedState`, and running it again is a no-op:
     * every field it writes has already been given the value it would write,
     * and `upcomingIndex` is `queueIndex` once the stage is READY. Ad-hoc sets
     * pass straight through it untouched.
     */
    fun beginSet() {
        // The rating panel goes with it. Starting another set is how a lifter
        // backs out of a mistapped Finish, and a flag left set here would
        // reopen the panel the next time they reach the rest screen -- asking
        // about a workout they have carried on with.
        stateFlow.value = startedFromReadyState(stateFlow.value).copy(askingSessionRpe = false)
        val s = stateFlow.value
        val exercise = s.currentExercise
        val tracker = StreamingSetTracker.forLift(exercise.liftDirection())
        this.tracker = tracker
        imuBuffer.clear()
        imuBufferB.clear()
        hrBuffer.clear()
        cueBuffer.clear()
        // What this set is armed with, read once, here. The lifter can label a
        // device or forget one mid-session, so a set already recording has to
        // be stored as the thing it was armed as rather than as whatever the
        // settings say when it ends. `recorded` returns null on the ordinary
        // one-sensor set, which is what keeps that set's row and both export
        // documents exactly what they were.
        val roster = s.rosterFor(s.currentSlot)
        armedSensors = SensorCapturePolicy.recorded(s.plannedSensorCountFor(s.currentSlot), roster)
        armedSecondaryRole = roster.secondary
        endingSet = false
        lastCountedPhase = Phase.IDLE
        lastSpokenSecond = 0
        lastAnnouncedRep = 0
        plannedRepsForSet =
            if (s.currentIsTimed) {
                null
            } else if (s.adHoc) {
                s.repsInput.toIntOrNull()
            } else {
                s.currentSlot?.reps
            }
        // Never announce reps on timed sets: a carry's gait can trip the rep detector.
        announceReps = !s.currentIsTimed
        // The bar sensor is RECORD-ONLY for standard lifts: the lifter (or the
        // voice guide) counts the reps, while sensor data feeds velocity/power
        // analysis. Explosive lifts stay sensor-counted (single drives, peak
        // velocity is the point) unless no sensor is present. Demo mode keeps
        // sensor counting to showcase the live tracking.
        var manualSet = !s.currentIsTimed && !s.demoMode &&
            (exercise.kind != ExerciseKind.EXPLOSIVE || !s.imuConnected)
        // Guided cadence: the app calls the tempo out loud and counts the reps
        // itself — the DEFAULT for all tempo work. A missed phase switch in
        // sensor counting corrupts the whole set, so the app's own count wins;
        // the sensor still records for velocity/power metrics. Explosive lifts
        // (concentric is the metric) stay sensor-counted.
        val guidedTempo =
            (if (s.adHoc) s.tempoInput.ifBlank { null } else s.currentSlot?.tempo)?.let { Tempo.parseOrNull(it) }
        // The same rule the import gate warns an inert prep_s against and the
        // same one the screen offers the prep control on. Stated once, in a
        // module with tests, because three statements of it would be three rules.
        val prepCase = LeadInPolicy.prepCase(guidedTempo != null, s.currentIsTimed, exercise.kind)
        // A cadence runner runs on exactly the sets whose prep runs into one,
        // read from the case rather than restated, so it cannot drift from it.
        val guidedSet = prepCase == PrepCase.CUED
        if (guidedSet) manualSet = true
        // The word the prep of a hold or a carry ends on, at the instant the
        // set's clock starts. Non-null on every TIMED prep and on nothing else:
        // LeadInPolicy pairs the case with the word, so this is one decision
        // read twice rather than two decisions that can disagree.
        val timedStartWord = LeadInPolicy.timedStartWord(exercise.kind).takeIf { prepCase == PrepCase.TIMED }
        // The prep, decided once here and used twice: handed to the runner
        // below, and frozen onto the set record at endSet. One expression,
        // because a constant read by the player and a second statement of it at
        // the write site are two facts that can disagree -- and the way they
        // disagree leaves every capture claiming a prep nobody heard.
        //
        // Recorded only when the set played a prep. A set that played none has
        // no prep, and writing 0 for it would be absence rendered as a value:
        // 0 is a real prep, the one where nothing is spoken before the set
        // begins.
        //
        // The PLANNED half is what the plan prescribed, which is its declaration
        // or the default -- not what was played. Whenever the two differ the
        // lifter adjusted the prep; they are equal both when no adjustment
        // exists and when it happens to equal what the plan prescribed.
        val prepS = s.prepSecondsFor(s.currentSlot)
        plannedPrepSForSet = s.plannedPrepSecondsFor(s.currentSlot).takeIf { prepCase != PrepCase.NONE }
        prepSForSet = prepS.takeIf { prepCase != PrepCase.NONE }
        prepCaseForSet = prepCase
        clockStartedAtMs = null
        autoEndedSet = false
        setStartedAtMs = System.currentTimeMillis()
        if (sessionStartedAtMs == 0L) sessionStartedAtMs = setStartedAtMs
        // Opened before the collectors below start, so no sample can arrive
        // with nowhere durable to go. A null here is the disk refusing, which
        // is the state this whole mechanism replaces -- it must not also stop
        // the set being recorded in memory as it always was.
        journal = openJournal(container.setJournals, s, exercise, sessionStartedAtMs, setStartedAtMs, roster)
        // Every set, not just the first. All three catch clauses in
        // RecordingService.onStartCommand end in stopSelf(startId), so a start
        // that was refused leaves nothing running and this is the only retry.
        holds.acquire(RecordingHold.SESSION)

        val timedTargetS = s.currentTimedTargetS
        collectJob =
            viewModelScope.launch {
                autoConnect.imuSamples.collect { sample -> onSample(sample) }
            }
        // Opened after the journal and only when a role was armed, so no
        // sample can arrive with nowhere durable to go and no unlabelled
        // stream can be captured. Null role means one sensor, and this job
        // does not exist.
        collectJobB =
            roster.secondary?.let { role ->
                viewModelScope.openSecondaryCollector(autoConnect.imuSamplesB, imuBufferB, { journal }, role)
            }
        if (s.demoMode && !s.currentIsTimed) startDemoStream(s, exercise)
        hrJob =
            viewModelScope.launch {
                autoConnect.hrSamples.collect { hr ->
                    hrBuffer += hr
                    journal?.appendHr(hr)
                    stateFlow.value = stateFlow.value.copy(hrBpm = hr.bpm)
                }
            }
        // A timed prep starts the clock itself, when it ends. Every other set
        // is measured from here, exactly as before.
        if (timedStartWord == null) startSetTimer(timedTargetS)
        // Both lead-ins in one expression, read from the two branches below:
        // set here rather than at the runner's first push, so no frame of a
        // lead-in renders as a set already under way.
        stateFlow.value = inSetState(s, manualSet, guidedSet, leadInRunning = timedStartWord != null || guidedSet)
        if (timedStartWord != null) {
            val speaks = LeadInPolicy.speaks(prepCase, s.audioCues)
            startTimedPrep(prepS, timedStartWord, speaks) { startSetTimer(timedTargetS) }
        } else if (guidedSet && guidedTempo != null) {
            startGuidedCadence(TempoSchedule.of(guidedTempo, exercise.liftDirection()), plannedRepsForSet, prepS)
        }
    }

    private fun onSample(sample: ImuSample) {
        imuBuffer += sample
        journal?.appendImu(sample)
        val live = tracker?.feed(sample) ?: return
        stateFlow.value = stateFlow.value.copy(live = live)
        // Manual/guided sets: the app (or the lifter) is the counter — the
        // sensor keeps recording for velocity metrics, but its phase counts and
        // rep calls must stay silent or two voices count over each other.
        if (!stateFlow.value.manualSet) {
            countPhaseSeconds(live.phase, live.currentPhaseElapsedS)
            announceRepMilestones(live.repCount)
        }
    }

    /**
     * Start the set's own clock: the seconds on screen, and what a timed set
     * says as its target approaches.
     *
     * A function rather than four lines inside [beginSet] because WHEN it runs
     * is a decision. The instant it runs is the instant the set is measured
     * from -- [endSet] reads [clockStartedAtMs] and nothing else -- so a caller
     * that runs it too early folds whatever came before into the set's figure.
     *
     * What is said comes from [TimedSetVoice] in `:core:dsp`, where every case
     * of it is a literal in a test. This walks the seconds and hands each cue to
     * [speakCue], which writes it to the cue track as the tempo calls are
     * written.
     *
     * ## Why the loop ends the set (#168)
     *
     * A hold used to run until the lifter ended it by hand. The voice already
     * announced the planned end, so the bar went down on the word and
     * everything from there until the phone was back out was recorded as hold
     * time -- inflation in one direction, on every timed set, indistinguishable
     * in the record from a longer hold. The clock ends the set now, and the
     * rare genuine overage is stated afterwards on the rest screen where every
     * other post-set correction lives.
     *
     * The remainder is computed ONCE per tick, by
     * [TimedSetEndPolicy.remainingS], and the same value is handed to the voice
     * and to [TimedSetEndPolicy.endsNow]. That is the whole guard against the
     * word and the end landing on different seconds; a second expression of
     * "has it reached the target" anywhere in this function is the defect.
     *
     * The order within the tick is load-bearing. The cue is spoken and written
     * BEFORE [endSet] runs, because [endSet] freezes `cueBuffer` into the
     * pending write -- speaking after it would drop the terminal word from the
     * set's own cue track, which is the boundary the exporter's rep window is
     * cut at. `break` after it, because [endSet] cancels [tickJob] from inside
     * the job's own coroutine and the cancellation is not observed until the
     * next suspension point.
     */
    private fun startSetTimer(timedTargetS: Int?) {
        clockStartedAtMs = System.currentTimeMillis()
        stateFlow.value = stateFlow.value.copy(leadInRunning = false)
        tickJob =
            viewModelScope.launch {
                var seconds = 0
                while (true) {
                    delay(1_000)
                    seconds++
                    stateFlow.value = stateFlow.value.copy(setElapsedS = seconds)
                    val remainingS = TimedSetEndPolicy.remainingS(seconds, timedTargetS)
                    if (remainingS != null && LeadInPolicy.speaks(prepCaseForSet, stateFlow.value.audioCues)) {
                        TimedSetVoice.cueFor(remainingS)?.let { speakCue(it) }
                    }
                    if (TimedSetEndPolicy.endsNow(remainingS)) {
                        autoEndedSet = true
                        endSet()
                        break
                    }
                }
            }
    }

    /**
     * A [GuidedCadenceRunner] wired to this view model's voice, journal and
     * state.
     *
     * One construction site rather than one per thing a runner can be asked to
     * play. The `speak` split is load-bearing and easy to get subtly wrong: the
     * FIRST argument is the cue that goes on the record, the second is what is
     * spoken, and a NULL cue means speak it and write nothing down. A second
     * copy of that line is a second chance to record a countdown digit that
     * `LeadInPlan.RECORDED` says must not be recorded.
     *
     * [RecordState.guidedLabel], [RecordState.guidedCountdown] and
     * [RecordState.guidedPhaseTotal] carry whatever a running voice guide is
     * saying. `guidedSet` is a different question -- whether a CADENCE follows
     * the prep -- and is answered in [beginSet].
     */
    private fun newVoiceRunner(): GuidedCadenceRunner {
        if (voice == null) voice = VoiceCounter(getApplication())
        return GuidedCadenceRunner(
            scope = viewModelScope,
            speak = { cues, utterance -> if (cues.isEmpty()) speakOnly(utterance) else speakCues(cues, utterance) },
            // `leadInRunning` off the runner's OWN constant, not a copy of the
            // string: its first beat pushes a label before it sleeps, so this
            // clears on the instant the lead-in ends. A hold's prep pushes
            // nothing after it, so [startSetTimer] clears that one.
            update = { label, remaining, total ->
                stateFlow.value = stateFlow.value.copy(
                    guidedLabel = label, guidedCountdown = remaining, guidedPhaseTotal = total,
                    leadInRunning = label == LEAD_IN_LABEL,
                )
            },
            onRepCounted = { rep ->
                journal?.appendRepMark(System.currentTimeMillis())
                stateFlow.value = stateFlow.value.copy(manualReps = rep)
            },
            onFinished = { stateFlow.value = stateFlow.value.copy(guidedFinished = true) },
        )
    }

    /** See [GuidedCadenceRunner]; the runner speaks and counts, the VM just mirrors state. */
    private fun startGuidedCadence(schedule: TempoSchedule, plannedReps: Int?, prepS: Int) {
        guidedCadence = newVoiceRunner().also { it.start(schedule, plannedReps, prepS) }
    }

    /**
     * Play the prep before a hold or a carry, and start the set's clock when it
     * ends rather than when the lifter tapped START.
     *
     * [onStarted] is [startSetTimer], handed through the runner instead of
     * being called here. Called here, a 45 s hang with a 10 s prep would record
     * 55 s: `duration_s` and `plannedDuration_s` would agree with each other,
     * `startedAt` is not a per-set key in `session.json`, and the auto-fail rule
     * compares that same inflated figure against the prescription. The witness
     * in the gym is audible -- `TimedSetVoice` counts against the same figure,
     * so "15 seconds" would arrive with 25 seconds of holding left to go; see
     * [SetClockPolicy] for the silent one at the desk.
     *
     * [speaks] false plays the same seconds in silence. The clock still starts
     * at the end of them, so the toggle changes what the lifter hears and what
     * reaches the cue track, and no figure the set records.
     */
    private fun startTimedPrep(prepS: Int, startWord: String, speaks: Boolean, onStarted: () -> Unit) {
        guidedCadence = newVoiceRunner().also { it.startPrep(prepS, startWord, speaks, onStarted) }
    }

    /**
     * Speak an in-set cue and log it on the sample clock (see VoiceCue).
     *
     * [cueText] is what goes on the record: the phase that was called. It is a
     * persisted format -- every cue-track fixture and parser matches these
     * strings exactly -- so it must not pick up whatever else the voice happens
     * to say at the same moment. [utterance] is what is spoken, and may carry a
     * rep announcement alongside the cue.
     */
    private fun speakCue(cueText: String, utterance: String = cueText) {
        speakCues(listOf(cueText), utterance)
    }

    /**
     * Speak one utterance and log every word of it that belongs on the record.
     *
     * One utterance, several rows: the guide merges a rep call into a stroke's
     * own word -- "Down, Rep 3" -- because TTS speaks with QUEUE_FLUSH and a
     * second utterance would cancel the first. Both words were said, at the
     * same instant, so both are written at ONE timestamp read once. Reading the
     * clock per row would let two words of a single utterance straddle a
     * millisecond boundary and appear as two things the app said in sequence.
     */
    private fun speakCues(cueTexts: List<String>, utterance: String) {
        val at = System.currentTimeMillis()
        for (text in cueTexts) {
            val cue = VoiceCue(at, text)
            cueBuffer += cue
            journal?.appendCue(cue)
        }
        voice?.speak(utterance)
    }

    /**
     * Speak without recording: touches neither [cueBuffer] nor the journal.
     *
     * The lead-in's countdown digits and its `"N seconds"` opener come here.
     * `LeadInPlan.RECORDED` is the canonical statement of which lead-in words
     * are written down and why the digits are not; this function is only the
     * half that obeys it.
     */
    private fun speakOnly(utterance: String) {
        voice?.speak(utterance)
    }

    /** Tap-to-count for sensorless sets; announces milestones like sensor reps. */
    fun addManualRep() {
        val s = stateFlow.value
        if (!s.manualSet) return
        // Ending a set takes a few hundred ms of analysis and gzipping, and the
        // in-set screen stays up for all of it. A tap landing in that window
        // would count a rep onto a set already written at the old count, and
        // could swap the effort grid back in for a set that is over.
        if (endingSet) return
        val count = s.manualReps + 1
        // The one fact in a set that no reprocessing of any stream can rebuild.
        // The sensor records what the bar did; it never records what the lifter
        // decided a rep was worth.
        journal?.appendRepMark(System.currentTimeMillis())
        stateFlow.value = s.copy(manualReps = count)
        announceRepMilestones(count)
    }

    /** Rest-screen correction when the sensor miscounted (or the set was manual). */
    fun overrideLastSetReps(reps: Int) {
        if (reps < 0) return
        // appScope, for the reason launchSetWrite is: a correction tapped on the
        // rest screen and then abandoned by Back is a correction the pop
        // cancels, and nothing anywhere can edit a stored set once this screen
        // is gone. Main.immediate keeps it ordered against the other writers and
        // keeps SetRatingTracker's fields on the thread that already reads them.
        container.appScope.launch(Dispatchers.Main.immediate) {
            val s = stateFlow.value
            val failed = ratings.correctReps(reps, rpe = s.lastSetRpe, warmup = s.lastSetWarmup) ?: return@launch
            stateFlow.value =
                stateFlow.value.copy(
                    lastFeedback = stateFlow.value.lastFeedback?.copy(repsOverride = reps),
                    lastSetFailed = failed,
                    // lastSetTappedFailed is deliberately NOT written here.
                    // correctReps re-derives only the shortfall; the lifter's
                    // own tap is untouched by a rep correction, so carrying it
                    // unchanged is what keeps the two facts two facts.
                )
        }
    }

    /**
     * Rest-screen correction of a hold or a carry's recorded seconds (#168):
     * the only way a genuine overage is entered, and it is entered after the
     * set. See [durationCorrectedState] for why it is not offered mid-set, and
     * [overrideLastSetReps] for why this runs on appScope.
     */
    fun addLastSetSeconds(deltaS: Int) {
        val current = stateFlow.value.lastFeedback?.effectiveDurationS ?: return
        val seconds = TimedSetEndPolicy.adjustedSeconds(current, deltaS)
        container.appScope.launch(Dispatchers.Main.immediate) {
            val s = stateFlow.value
            val failed = ratings.correctDuration(seconds, rpe = s.lastSetRpe, warmup = s.lastSetWarmup) ?: return@launch
            stateFlow.value = durationCorrectedState(stateFlow.value, seconds, failed)
        }
    }

    /**
     * Voice at each lockout: "Rep N" as reps complete, "Last rep" going into the
     * final planned rep, and "Done" when the count is hit.
     */
    private fun announceRepMilestones(repCount: Int) {
        if (!announceReps || !stateFlow.value.audioCues) return
        if (repCount == lastAnnouncedRep || repCount == 0) return
        lastAnnouncedRep = repCount
        val planned = plannedRepsForSet
        when {
            planned != null && repCount == planned -> speakCue("Done")
            planned != null && repCount == planned - 1 && planned > 1 -> speakCue("Last rep")
            else -> speakCue("Rep $repCount")
        }
    }

    /** Voice tempo count: speaks 1, 2, 3… through each moving phase (spec: audible 4-s eccentric). */
    private fun countPhaseSeconds(phase: Phase, elapsedS: Double) {
        if (!stateFlow.value.audioCues) return
        if (phase != lastCountedPhase) {
            lastCountedPhase = phase
            lastSpokenSecond = 0
        }
        if (phase != Phase.ECCENTRIC && phase != Phase.CONCENTRIC) return
        val second = elapsedS.toInt()
        if (second >= 1 && second != lastSpokenSecond) {
            lastSpokenSecond = second
            speakCue(second.toString())
        }
    }

    /**
     * Finish the set, logging [rating] as part of the same tap: freeze
     * everything the durable write needs, then run that write.
     *
     * Split in two on purpose. Everything above the launch is read from live
     * state and from the clock, so it is captured once, here, and the write
     * consumes the frozen copy. A retry that recomputed instead would re-read
     * the clock: a 60 s plank ended at 45 s and retried 20 s later would store
     * a 65 s hold that never happened, and would flip from a failed set to one
     * that met its target, because the shortfall test compares against 90% of
     * the prescription.
     *
     * [endingSet] is never cleared here, including when the write fails, and it
     * still has two jobs once the set is over.
     *
     * It guards re-entry to this function: the effort grid is seven separately
     * clickable tiles, and two fingers landing on two of them would otherwise
     * run this twice and store the set twice.
     *
     * It also guards [addManualRep] across the frame in which the set ends.
     * `+1 REP` is no longer drawn once the write starts — the screen gates that
     * button on the write state — but the gate only takes effect at the next
     * recomposition, and the button from the previous composition is on screen
     * and hittable until then. A tap landing in that frame would count a rep
     * onto a set whose count is already frozen into the write.
     */
    fun endSet(rating: SetRating? = null) {
        if (endingSet) return
        endingSet = true
        collectJob?.cancel()
        collectJobB?.cancel()
        hrJob?.cancel()
        tickJob?.cancel()
        demoJob?.cancel()
        guidedCadence?.cancel()
        val s = stateFlow.value
        val exercise = s.currentExercise
        val slot = s.currentSlot
        val isTimed = s.currentIsTimed
        val addedKg =
            SetLoadPolicy.resolve(
                adHoc = s.adHoc,
                plannedAddedKg = slot?.loadKg,
                typedAddedKg = s.weightUnit.parseToKg(s.loadInput),
                statedAddedKg = s.statedLoadKg,
            )
        // Pull-ups and dips move the lifter: the plan's number is what was ADDED
        // (negative when a band or machine assists), so the load that actually
        // travelled is body weight plus that.
        val loadKg = SetLoadPolicy.totalKg(exercise.bodyweight, s.bodyWeightKg, addedKg)
        // Paired on the same scale as loadKg above, from the same bodyWeightKg
        // reading, so a compliant set cannot disagree with itself: #25.
        val plannedLoadKg =
            SetLoadPolicy.recordedPlannedLoadKg(exercise.bodyweight, s.bodyWeightKg, slot?.plannedLoadKg)
        // THREE numbers, and this is where they part. The plan's PRESCRIPTION
        // comes off the frozen declaration and is what the set is recorded and
        // exported against; the WORKING target is the slot's live count, which
        // carries the lifter's standing statement, and is what every judgment
        // reads. An ad-hoc set has no plan, so the typed box is both.
        val plannedReps = if (s.adHoc) s.repsInput.toIntOrNull() else slot?.plannedReps
        val targetReps = s.currentTargetReps
        val side = if (s.adHoc) s.sideInput else slot?.side
        val plannedDurationS =
            when {
                !isTimed -> null
                s.adHoc -> s.durationInput.toIntOrNull()
                else -> slot?.plannedDurationS
            }
        val targetDurationS = if (isTimed) s.currentTimedTargetS else null
        // Measured from the instant the set's CLOCK started, which is not the
        // instant recording started once a prep sits in front of a hold. The
        // rule is SetClockPolicy's, in a module with tests; this hands it both
        // instants and the case that decides between them. One reading of the
        // clock, shared with the row's own endedAt, so the two cannot disagree.
        val endedAtMs = System.currentTimeMillis()
        // Then, for a hold the CLOCK ended rather than the lifter, the figure
        // the set records is the seconds it was working to and not the
        // measurement (#168) -- plannedDurationS unless the lifter changed the
        // hold in the change-set dialog, in which case theirs.
        // The two disagree by whatever the dispatcher did -- delay(1_000)
        // drifts positive, so a sixty-tick hold measures 60 or 61 -- and 61
        // against a 60 s target reads as a hold carried past target on every
        // set, for a reason that is nothing to do with the lifter. A set the
        // lifter ended is never touched: it records what it lasted.
        val actualDurationS =
            recordedTimedSeconds(
                isTimed = isTimed,
                prepCase = prepCaseForSet,
                tappedAtMs = setStartedAtMs,
                clockStartedAtMs = clockStartedAtMs,
                endedAtMs = endedAtMs,
                // The target the CLOCK ran to, which is the lifter's if they
                // set one: #168 replaces a measured 61 with the target it was
                // counting down, and the countdown ran to what was on screen.
                targetS = targetDurationS,
                autoEnded = autoEndedSet,
            )
        val tempoText =
            when {
                isTimed -> null
                s.adHoc -> s.tempoInput.ifBlank { null }
                else -> slot?.tempo
            }
        val manualReps = if (s.manualSet) s.manualReps else null
        pendingWrite =
            PendingSetWrite(
                exercise = exercise,
                // The slot's own description when the set came from a plan, so
                // what is stored is what flattenPlan resolved and what
                // SetAnalyzer was handed. An ad-hoc set has no plan to
                // describe, so its geometry is the built-in definition's.
                geometry = slot?.geometry ?: SetGeometryPolicy.describe(exercise, null),
                slot = slot,
                isTimed = isTimed,
                loadKg = loadKg,
                plannedLoadKg = plannedLoadKg,
                addedKg = addedKg,
                plannedReps = plannedReps,
                targetReps = targetReps,
                manualReps = manualReps,
                side = side,
                tempoText = tempoText,
                plannedDurationS = plannedDurationS,
                targetDurationS = targetDurationS,
                actualDurationS = actualDurationS,
                plannedPrepS = plannedPrepSForSet,
                prepS = prepSForSet,
                startedAtMs = setStartedAtMs,
                endedAtMs = endedAtMs,
                orderIdx = s.setsCompleted,
                samples = imuBuffer.toList(),
                hrSamples = hrBuffer.toList(),
                restHrSamples = restHrBuffer.toList(),
                cues = cueBuffer.toList(),
                repMarks = journal?.repMarks.orEmpty(),
                sensors = armedSensors,
                // A role and its samples together, so a full stream cannot
                // exist without a label. Non-null with an EMPTY list when the
                // role was armed and its unit produced nothing -- which the
                // repository turns into no row and a declaration that still
                // names the role, the state that makes a flat battery readable
                // afterwards rather than invisible.
                secondary = armedSecondaryRole?.let { SecondaryCapture(it, imuBufferB.toList()) },
                rating = rating,
                planName = s.planName.takeIf { !s.adHoc },
                planSessionName = s.planSessionName.takeIf { !s.adHoc },
                targets =
                SetTargets(
                    // The working target: SetAnalyzer grades the reps it
                    // detected against what the set was trying to do.
                    plannedReps = targetReps,
                    countedReps = if (s.manualSet) s.manualReps else null,
                    tempo = tempoText?.let { Tempo.parseOrNull(it) },
                    targetMeanConcentricVelocityMps = slot?.targetMeanConVelMps,
                    velocityLossStopPct = slot?.velocityLossStopPct,
                ),
            )
        // Cleared once it is FROZEN into the pending write, not when the next
        // set begins. The window belongs to the set that follows it, so it has
        // to survive from one set ending to the next one being written -- and
        // clearing it here rather than at beginSet means a retry replays the
        // frozen copy rather than a buffer that kept filling behind it.
        restHrBuffer.clear()
        launchSetWrite()
    }

    /**
     * Store the set that failed to store, from the copy frozen when it ended.
     * Nothing is recomputed and nothing is re-read from the clock.
     */
    fun retrySetWrite() {
        if (stateFlow.value.setWrite != SetWriteState.FAILED) return
        launchSetWrite()
    }

    /**
     * Run the durable write on a scope the record screen cannot take with it.
     *
     * On `appScope` rather than `viewModelScope`, because the pop that destroys
     * this ViewModel cancels `viewModelScope` at whichever suspension point the
     * write has reached: inside the analysis, between starting the session and
     * inserting the set, or between the set row and its gzipped IMU stream.
     * `appScope` is created once per process in `AppContainer` and is never
     * cancelled.
     *
     * Dispatched on `Main.immediate`, which is the load-bearing half. `appScope`
     * is `SupervisorJob() + Dispatchers.Default`, so launching unqualified would
     * put every `stateFlow.value = stateFlow.value.copy(...)` below on a
     * background thread. Those are non-atomic read-modify-writes, there are
     * dozens of them in this file, and demo mode already writes to that flow off
     * the main thread through `launchDemoStream`; adding a second off-main
     * writer is how the RESTING transition gets lost and the screen strands on a
     * set that was in fact written. `Main.immediate` keeps every one of them
     * exactly where it is today, and keeps this write, [rateLastSet] and
     * [overrideLastSetReps] in tap order now that all three have left
     * `viewModelScope`.
     *
     * The catch is not optional. `appScope` has no `CoroutineExceptionHandler`,
     * so anything escaping reaches the default uncaught handler and kills the
     * process. That is what happens today on `viewModelScope` too, but it can
     * now arrive after the screen is gone. Swallowing it silently would be worse
     * than the crash, so the failure becomes a state the screen shows and a
     * retry the lifter can tap, with the buffers still in memory behind it.
     *
     * The hold is what keeps the process worth as much to Android as the work
     * is worth to the lifter. Surviving the pop was only half the problem: the
     * write can outlive the screen and still be running when [onCleared] gives
     * the foreground service up, and everything it is carrying — the samples,
     * the rating, the wall times — exists in this process and nowhere else
     * until the insert lands.
     */
    private fun launchSetWrite() {
        val pending = pendingWrite ?: return
        stateFlow.value = stateFlow.value.copy(setWrite = SetWriteState.IN_FLIGHT)
        holds.acquire(RecordingHold.SET_WRITE)
        container.appScope.launch(Dispatchers.Main.immediate) {
            try {
                runSetWrite(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Deliberately broad. Whatever failed, the set is still only in
                // memory, and the one thing that must not happen is the screen
                // moving on as though it had been stored.
                stateFlow.value = stateFlow.value.copy(setWrite = SetWriteState.FAILED)
            } finally {
                // Both terminal branches. A write that failed is still a write
                // that is over: if the lifter is still here their own hold keeps
                // the service up behind SAVE THIS SET AGAIN, and if they have
                // gone the buffers went with the ViewModel and there is nothing
                // left for the service to protect.
                holds.release(RecordingHold.SET_WRITE)
            }
        }
    }

    private suspend fun runSetWrite(p: PendingSetWrite) {
        // The set is over, so nothing more will be appended: release the file
        // handles and let everything queued reach the filesystem. Deliberately
        // NOT a delete. The capture has to outlive this write and die only when
        // the row lands, because if this write is what fails the capture is the
        // only copy left -- and on a retry the lifter may well have walked away
        // between the two.
        journal?.close()
        // Sensor data is analyzed even on manually-counted sets — the manual
        // count overrides the rep COUNT, but velocity/power metrics still come
        // from the bar sensor when it was recording.
        val analysis =
            withContext(Dispatchers.Default) {
                when {
                    p.isTimed -> SetAnalysis(
                        emptyList(),
                        0.0,
                        null,
                        null,
                        // The working target, the same figure the rest
                        // screen's hold verdict reads: "Held 30s -- full 30s
                        // target" on a hold the lifter shortened to 30, not
                        // "30s of 45s -- just short".
                        timedVerdicts(p.actualDurationS, p.targetDurationS),
                    )
                    p.samples.size >= 8 ->
                        // The set's own cue track, frozen into the pending
                        // write alongside the samples. It carries the Done cue,
                        // which is the only thing that says when the app
                        // stopped prescribing -- and the stream runs on past it
                        // with the sensor being handled, 4.3 to 13.7 s on the
                        // eleven sets of session 32 that carry both a cue and a
                        // stream. See SetEnd.
                        SetAnalyzer.analyze(p.samples, p.exercise.liftDirection(), p.loadKg, p.targets, cues = p.cues)
                    p.manualReps != null ->
                        SetAnalysis(emptyList(), 0.0, null, null, listOf("Reps counted manually — no bar sensor."))
                    else -> SetAnalysis(emptyList(), 0.0, null, null, listOf("No sensor data recorded."))
                }
            }
        // A session row created here and then left behind by a failed insert
        // stays open with endedAtMs null. A retry reclaims it, because sessionId
        // is read back from state; nothing reclaims it if the lifter never
        // retries. Named in the commit body rather than covered by the word
        // atomic: the transaction spans the set and its streams, not this.
        val sessionId =
            stateFlow.value.sessionId
                ?: openSession(sessionRepository, p).also { stateFlow.value = stateFlow.value.copy(sessionId = it) }

        sessionRepository.ensureExerciseExists(p.exercise.id)

        // Stopped early = failed. Judged only where the count is trustworthy:
        // timed sets against the clock, manual/guided sets against the app's
        // own rep count — never against a possibly-miscounted sensor total.
        val stoppedEarly =
            when {
                // #168: the same function the in-set control gate asks, so the
                // screen and the record cannot draw the boundary in different
                // places. The `?: 0` this replaces was unreachable -- a timed
                // set's actualDurationS is an Int by construction a few lines
                // up -- so nothing observable changes here; fellShort simply
                // declines to grade an absent figure rather than reading it as
                // a zero-second hold.
                // Judged against the WORKING target, never the frozen
                // prescription. A lifter who states 6 and does 6 has not
                // stopped early; judging them against the plan's 8 would
                // record a failed set for a change they made deliberately,
                // and after #137 that reaches the RPE record. The deviation
                // is still visible, because plannedReps is stored beside it.
                p.isTimed && p.targetDurationS != null ->
                    TimedSetEndPolicy.fellShort(p.actualDurationS, p.targetDurationS)
                p.manualReps != null && p.targetReps != null -> p.manualReps < p.targetReps
                else -> false
            }
        // The lifter's tap is authoritative for effort, but a set that ended
        // short of its target is still a failed set — both facts are recorded.
        val failed = ratings.onSetRecorded(p.targetReps, p.targetDurationS, stoppedEarly, p.rating)

        // Reusing an id already returned is what keeps a retry from writing the
        // set twice. The rating travels with the row rather than following it as
        // an update, so there is no second statement left to fail after the row
        // has landed.
        val setId =
            writtenSetId ?: sessionRepository.recordSet(
                sessionId = sessionId,
                orderIdx = p.orderIdx,
                set = completedSetOf(p, analysis, failed),
            ).also { writtenSetId = it }
        ratings.attachTo(setId)
        // The row and every stream belonging to it are in one transactional
        // call above, so reaching here is the first moment the capture is
        // genuinely redundant. Discarded rather than left to accumulate: an
        // orphan that outlives the set it duplicates would offer the lifter a
        // recovery for a set they already have.
        journal?.discard()
        journal = null

        val restS = p.slot?.restS ?: DEFAULT_REST_S
        // Where the rest period started, and how much of it is left now. The
        // instant comes off the set's own frozen cue track -- the same `Done`
        // stamp SetEnd bounds the rep window at -- so there is one instant and
        // nothing recomputes it. A set nothing called over falls back to the
        // instant this write froze, which is every hold and every set recorded
        // with the voice off. #172.
        val restStartedAtMs =
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = (SetEnd.of(p.cues) as? SetEnd.Cued)?.atMs,
                endedAtMs = p.endedAtMs,
            )
        val restRemainingS = RestClockPolicy.remainingS(restS, restStartedAtMs, System.currentTimeMillis())
        stateFlow.value = restingState(stateFlow.value, p, analysis, failed, restS, restRemainingS)
        pendingWrite = null
        writtenSetId = null
        startRestCountdown()
    }

    private fun startRestCountdown() {
        restJob?.cancel()
        restJob =
            viewModelScope.launch {
                while (stateFlow.value.restRemainingS > 0) {
                    delay(1_000)
                    val remaining = stateFlow.value.restRemainingS - 1
                    stateFlow.value = stateFlow.value.copy(restRemainingS = remaining)
                    if (stateFlow.value.audioCues) {
                        when (remaining) {
                            in 1..REST_COUNTDOWN_FROM_S -> voice?.speak(remaining.toString())
                            0 -> voice?.speak("Rest over")
                        }
                    }
                }
            }
    }

    /**
     * Correct a mistapped effort rating from the rest screen. The tap can change
     * how the set FELT, but it cannot erase the fact that the set ended short of
     * its target — that verdict comes from the rep count, not from an opinion.
     */
    fun rateLastSet(rpe: Int?, failed: Boolean) {
        // appScope, as overrideLastSetReps: the rest screen is the only place a
        // set's effort can be corrected, and the pop that leaves it cancelled
        // the correction on the way out.
        container.appScope.launch(Dispatchers.Main.immediate) {
            // The stored warm-up flag is handed back unchanged rather than
            // taken from the correction: `updateRpe` writes the column on every
            // correction, so passing anything else here would let re-rating a
            // ramp set silently turn it into work (#187).
            val warmup = stateFlow.value.lastSetWarmup
            val effectiveFailed = ratings.rate(rpe, failed, warmup) ?: return@launch
            stateFlow.value = ratedState(stateFlow.value, rpe, failed, effectiveFailed)
        }
    }

    /** One tap of the prep control; the work is in the free [applyPrepAdjustment]. */
    fun adjustPrep(deltaS: Int) = applyPrepAdjustment(stateFlow.value, deltaS, container.appScope, container.settings)

    /** One tap of the sensor-count control; the work is in the free [applySensorCount]. */
    fun setSensorCount(count: Int) = applySensorCount(stateFlow.value, count, container.appScope, container.settings)

    /** Advance to the next planned set, applying any in-rest load/rep edits. */
    fun startNextSet() {
        restJob?.cancel()
        stateFlow.value = advancedState(stateFlow.value)
        beginSet()
    }

    /**
     * Close the session the lifter just asked to finish.
     *
     * This was the last durable writer on this screen still running on
     * `viewModelScope`, and the pop cancels that scope wherever the write has
     * got to: inside `sessionById`, inside the read of every set row, or inside
     * the update itself. What that costs is not the timestamp. `endedAtMs`,
     * `hrAvgBpm` and `hrMaxBpm` are all rebuildable later from rows that are
     * already durable — the set rows carry their own end times and their own
     * heart-rate columns. `hrvRmssdMs` is not. Its input is [sessionRrMs],
     * accumulated across READY, IN_SET and RESTING, and the only R-R that
     * reaches storage is the per-set `hrm` stream covering IN_SET; the rest
     * windows are held here and nowhere else. A cancelled close is the
     * difference between the lifter having that number and never having it.
     *
     * So the work moves to `appScope`, joining the set write and the two rest
     * screen corrections, and on `Dispatchers.Main.immediate` for the reason
     * [launchSetWrite] documents: `appScope` is `SupervisorJob() +
     * Dispatchers.Default`, and every `stateFlow.value = stateFlow.value.copy()`
     * in this file is a non-atomic read-modify-write.
     *
     * Everything the close needs is frozen here, above the launch, rather than
     * read inside it. That is the freeze [PendingSetWrite] performs and it is
     * what makes the retry a retry.
     *
     * The consequence to weigh, named rather than buried: a lifter who taps
     * Finish and then leaves before it lands now gets the finish, where
     * cancellation used to win. Cancellation winning was never a decision — it
     * was an artefact of the scope. Honouring the finish writes an end time that
     * was measured when they asked, a summary over the sets that exist, and an
     * HRV that is otherwise lost; nothing false is written. The screen no longer
     * offers "Leave without finishing" during that window at all, which is what
     * makes this a completed instruction rather than a contradicted one.
     *
     * [sessionRpe] is the lifter's own 1-to-10 answer for the whole session,
     * or null where they skipped it or were never asked (#159). It is frozen
     * with everything else, so a retry after a failed close writes the answer
     * they actually gave rather than nothing. Null is a real outcome and not a
     * fallback: an unrated session records an absence, and no midpoint is
     * substituted for it anywhere between here and the export.
     */
    fun finishSession(sessionRpe: Int?) {
        restJob?.cancel()
        stateFlow.value = stateFlow.value.copy(askingSessionRpe = false)
        closer.close(
            sessionId = stateFlow.value.sessionId,
            endedAtMs = System.currentTimeMillis(),
            // Snapshotted, not handed the live list: the passive HR collector
            // keeps appending to it for as long as this ViewModel lives.
            rrMs = sessionRrMs.toList(),
            sessionRpe = sessionRpe,
            onState = ::onSessionCloseState,
            onClosed = ::onSessionClosed,
        )
    }

    /**
     * Ask how the session felt, before closing it (#159).
     *
     * The rest and ready screens' Finish session control lands here rather
     * than on [finishSession], so the rating is asked for at the one moment the
     * lifter has finished and is still holding the phone. There is no other
     * moment: the close writes the column once and nothing corrects it
     * afterwards.
     *
     * NO PARAMETER AND NO DEFAULT ON [finishSession], deliberately. Every route
     * that closes a session now states its own answer at its own call site --
     * a number, or null for the skip, or null for the exit dialog's Finish
     * session, which closes immediately and does not ask. A default would let a
     * new caller record an absence without deciding to.
     */
    fun askSessionRpe() {
        if (stateFlow.value.sessionClose != SessionCloseState.NONE) return
        stateFlow.value = stateFlow.value.copy(askingSessionRpe = true)
    }

    /**
     * Close the session again after a close that failed, from the copy frozen
     * when the lifter first asked. Nothing is recomputed and nothing is re-read
     * from the clock.
     */
    fun retrySessionClose() {
        if (stateFlow.value.sessionClose != SessionCloseState.FAILED) return
        closer.retry(::onSessionCloseState, ::onSessionClosed)
    }

    private fun onSessionCloseState(close: SessionCloseState) {
        stateFlow.value = stateFlow.value.copy(sessionClose = close)
    }

    /**
     * The session is closed. The screen has no further use for the service.
     *
     * [RecordingHold.SESSION] rather than the close's own hold, and getting that
     * wrong is a real hazard rather than a naming quibble: this runs inside
     * [SessionCloser]'s try, so its `finally` releases
     * [RecordingHold.SESSION_CLOSE] a moment later and that release is what
     * emits the stop. Releasing the close's hold here instead would leave the
     * screen's hold held with nothing able to give it up, and `FinishedStage`
     * navigates on a tap and never on its own — so the lifter would sit on the
     * finished-session screen with a "Recording session" notification that
     * nothing takes down.
     */
    private fun onSessionClosed() {
        holds.release(RecordingHold.SESSION)
        stateFlow.value =
            stateFlow.value.copy(
                stage = Stage.FINISHED,
                sessionClose = SessionCloseState.NONE,
                // The skip lasted the session out and no longer applies (#181).
                // Cleared here rather than at the next start so the flag names
                // one session and cannot be read as a standing preference.
                bodyWeightPromptSkipped = false,
            )
    }

    fun abandonSetup() {
        stateFlow.value = stateFlow.value.copy(stage = Stage.SETUP, queue = emptyList(), queueIndex = 0)
    }

    /** Demo/replay mode (spec 5): synthesizes a realistic set through the full pipeline. */
    private fun startDemoStream(s: RecordState, exercise: ExerciseDef) {
        val slot = s.currentSlot
        demoJob =
            viewModelScope.launchDemoStream(
                reps = (if (s.adHoc) s.repsInput.toIntOrNull() else slot?.reps) ?: DEMO_REPS,
                tempo = (if (s.adHoc) s.tempoInput else slot?.tempo)?.let { Tempo.parseOrNull(it) },
                eccentricFirst = exercise.startsWith == StartPhase.ECCENTRIC,
                onSample = ::onSample,
            )
    }

    /**
     * The record flow's owner is going away: the "record" nav entry was popped
     * — RecordScreen draws a Back button in the top bar in every stage — or the
     * Activity is finishing.
     *
     * `viewModelScope` is ALREADY cancelled by the time this runs, so cleanup
     * launched there would compile, run nothing, and report nothing.
     * `ViewModel.clear()` closes the scope closeable and only then calls this;
     * in lifecycle 2.8.7 that is `ViewModelImpl.clear()` walking
     * `keyToCloseables`, where `viewModelScope` is registered under
     * `...ViewModelCoroutineScope.JOB_KEY`, and `CloseableCoroutineScope.close`
     * is a bare cancel of the context. Everything here is therefore called
     * inline.
     *
     * This gives up the screen's reason to run the foreground service. It no
     * longer stops it. `RecordingService.start` fires on every [beginSet], and
     * the stop was once reachable only from [finishSession] — so leaving by any
     * other route left the service running with nothing else able to stop it —
     * but the stop now comes from whichever reason is the last to go away, and
     * on this path that is usually the set write, a moment later.
     *
     * The history is kept because both halves of it were true in turn. This
     * function used to explain that stopping here was safe because no recording
     * survives the instance: both sample collectors are `viewModelScope`-bound
     * and `imuBuffer`/`hrBuffer` go with it. That stopped being true when
     * [endSet] began copying those buffers into a [PendingSetWrite] BEFORE
     * launching a write that runs on `appScope` — a set can be in the middle of
     * being stored at the moment this runs, and its samples are in this process
     * and nowhere else until the insert lands. The correction was recorded and
     * the stop left unconditional anyway, deferred to a field measurement. What
     * changed is not that the measurement arrived. It is that the same window
     * opened over the session close, which the screen advertises as safe to
     * leave in as many words, over an `hrvRmssdMs` that no later reprocessing
     * can rebuild.
     *
     * Deferred, never skipped. The two writes that can outlive this screen give
     * their reasons up in a `finally`, so a write that failed releases exactly
     * as a write that landed, and the "Recording session" notification cannot
     * be stranded by anything short of the process dying.
     *
     * This writes nothing to the database, and that is now a settled ruling
     * rather than deferred work. The session row is left open with `endedAtMs`
     * null, which is the honest state and the only signal anywhere that the
     * session was abandoned rather than finished. `SessionEntity.endedAtMs` has
     * exactly one reader — `Exporters`, which builds the export under
     * `explicitNulls = false` and so omits the key entirely while it is null —
     * and the published schema does not require it, so nothing downstream
     * breaks on the absence.
     *
     * Four reasons, against closing it here:
     *
     *  - It would override a choice the lifter just made. The RESTING exit
     *    prompt offers "Leave without finishing" and says in as many words that
     *    the session stays open and nothing can finish it later. A close on this
     *    path makes both branches of that prompt do the same thing.
     *  - The stages that reach here with a session row are IN_SET and RESTING,
     *    and RESTING is exactly the deliberate-abandonment case. There is no
     *    third bucket to close.
     *  - There is no timestamp to write. `System.currentTimeMillis()` here
     *    measures when the screen was destroyed, which can be long after the
     *    last rep; the honest measured alternative, the last set's own
     *    `endedAtMs`, means something different from what a deliberate finish
     *    writes into the same column.
     *  - It could not be complete. This function is not called on a task swipe
     *    or a process kill, so `endedAtMs == null` would stop meaning
     *    "abandoned" and start meaning "abandoned in one of the ways that
     *    happen to run this". Today it means one thing.
     *
     * What piece 4 does instead is make the close the lifter DOES ask for
     * survive this screen going away; see [finishSession].
     */
    override fun onCleared() {
        voice?.shutdown()
        voice = null
        holds.release(RecordingHold.SESSION)
        super.onCleared()
    }

    companion object {
        const val DEFAULT_REST_S = 150
        const val REST_COUNTDOWN_FROM_S = 3

        /** Reps synthesized for a demo set when nothing planned one. */
        const val DEMO_REPS = 5

        /** ~2 minutes of beats at typical training heart rates. */
        const val ROLLING_HRV_BEATS = 150
    }
}
