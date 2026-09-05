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
import com.macrophage.barspeed.model.AbandonedSetPolicy
import com.macrophage.barspeed.model.AddSetControl
import com.macrophage.barspeed.model.AddSetSlotKey
import com.macrophage.barspeed.model.ArmedCapture
import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ArmedLinks
import com.macrophage.barspeed.model.ArmedSilencePolicy
import com.macrophage.barspeed.model.BodyWeightPromptPolicy
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.EffortAsk
import com.macrophage.barspeed.model.EffortScale
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.LiveFeed
import com.macrophage.barspeed.model.LiveFeedPolicy
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.PrepCase
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.PrepWindowPolicy
import com.macrophage.barspeed.model.PreviewSet
import com.macrophage.barspeed.model.ProgressionKind
import com.macrophage.barspeed.model.RecordedSensors
import com.macrophage.barspeed.model.RecordedTimeZone
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RemoveSetControl
import com.macrophage.barspeed.model.RemoveSetTarget
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.RestClockPolicy
import com.macrophage.barspeed.model.RestControl
import com.macrophage.barspeed.model.RestControlPolicy
import com.macrophage.barspeed.model.SecondaryCapture
import com.macrophage.barspeed.model.SensorCapturePolicy
import com.macrophage.barspeed.model.SensorRole
import com.macrophage.barspeed.model.SensorRoster
import com.macrophage.barspeed.model.SessionCloseState
import com.macrophage.barspeed.model.SetClockPolicy
import com.macrophage.barspeed.model.SetCompletionPolicy
import com.macrophage.barspeed.model.SetEndKind
import com.macrophage.barspeed.model.SetGeometryPolicy
import com.macrophage.barspeed.model.SetLimiter
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.SetRepsPolicy
import com.macrophage.barspeed.model.SetVoicePolicy
import com.macrophage.barspeed.model.SetWriteState
import com.macrophage.barspeed.model.SideChoicePolicy
import com.macrophage.barspeed.model.Stage
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoAdjustPolicy
import com.macrophage.barspeed.model.TimedSetEndPolicy
import com.macrophage.barspeed.model.VelocityLossRegime
import com.macrophage.barspeed.model.VoiceCue
import com.macrophage.barspeed.model.VoiceMilestonePolicy
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.model.armedCaptureOf
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
     * rest screen's card strikes the changed count against, so a count
     * changed and changed back no longer reads as no change (#170 item 6).
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
     * The side the PLAN declared for this set, frozen and never written back
     * to, the way [plannedTempo] is for tempo and [plannedReps] for the count.
     *
     * [side] carries the lifter's choice once `advancedState` has baked it in;
     * this does not. It is what the export publishes as `plannedSide`, so a
     * coach can see that the lifter swapped arm order, and it is what the
     * rest-screen card strikes the changed side against. Until #215 there was
     * no second field at all and `side` was a copy of the prescription with
     * nowhere for a worked side to go -- issue #144.
     *
     * Null on a bilateral slot, on an APPENDED set -- prescribed by nothing,
     * so every frozen field on it is null -- and on a slot built before this
     * field existed; nothing persists a PlannedSlot, so the third case lives
     * only inside one running session.
     */
    val plannedSide: String? = null,
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
     * What the plan declared for this set: the set's own `sensors` where it has
     * one, else the exercise block's, else null when neither declared anything
     * (#156).
     *
     * SINCE #198 NOTHING READS IT. Capture is decided by the hardware -- see
     * [SensorCapturePolicy.roster], which takes no count -- and there is no
     * lifter adjustment and no resolution left: `SettingsStore.sensorCounts`,
     * `setSensorCount` and `SENSOR_COUNT_KEY_PREFIX` are gone, and
     * `SensorCapturePolicy` no longer declares `resolve`, `planned` or `clamp`.
     * It is carried here only so a slot still states what its plan said, and it
     * decides nothing; a reader must not take its presence as evidence that a
     * count reaches the record flow.
     *
     * KEPT rather than deleted, and the reason is a pin rather than sentiment:
     * `AppendedSlotTest` asserts field by field which values an appended set
     * inherits, and `sensors` is one of them. Deleting the field deletes that
     * coverage of the append rule in a round whose whole subject is prose, so
     * the false KDoc is deleted instead and the field is labelled dead.
     * Retiring it is #198's remainder, not its fix.
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
    /**
     * Which dimension the post-set grid raises on this exercise, read from the
     * plan's `progression` key at flatten time (#214, schema 1.11).
     *
     * [ProgressionKind.WEIGHT] on a slot the plan said nothing about, which is
     * exactly what an omitted key means, so no session recorded before 1.11
     * behaves differently. It is carried on the SLOT rather than looked up
     * again at the rest screen because the exercise the grid is about is the
     * one that just finished, and by the time that screen draws
     * `currentExercise` is already the movement coming up -- the same reason
     * [SetFeedback] freezes its own copy of the exercise.
     *
     * An appended slot inherits it through the `copy` in `appendedState`: one
     * more set of an exercise progresses the way that exercise progresses.
     * An ad-hoc set has no slot at all and so is never offered the grid.
     */
    val progression: ProgressionKind = ProgressionKind.WEIGHT,
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

/**
 * This slot as the session preview reads it (#202).
 *
 * A PROJECTION, not a re-reading of the plan: every field is copied straight
 * off the slot the record flow is going to run, with no arithmetic and no
 * second consultation of `PlanSessionDef`. That is what stops the preview and
 * the first set's "Up next" card ever disagreeing -- they are the same queue,
 * and their text has one source: the card draws `SetCardValues.of` with the
 * plan's displaced figures struck, and `SessionPreviewPolicy.setLine` is that
 * same `of` rendered plain.
 *
 * It takes [loadKg], [reps], [durationS] and [tempo] -- the STANDING values --
 * rather than their frozen `planned*` twins, because the preview answers "what
 * am I about to lift", which is the question the record flow answers too.
 */
fun PlannedSlot.previewSet(): PreviewSet = PreviewSet(
    exerciseName = exercise.displayName,
    kind = exercise.kind,
    bodyweight = exercise.bodyweight,
    setIndexInExercise = setIndexInExercise,
    setsInExercise = setsInExercise,
    reps = reps,
    durationS = durationS,
    loadKg = loadKg,
    tempo = tempo,
    side = side,
    implementCount = implementCount,
    restS = restS,
    warmup = warmup,
    // Which dimension this exercise steps up on (#235), copied off the slot
    // like every other field here. ProgressionKind.ofPlan already resolved the
    // plan's key at flatten time, so this is the same value the post-set grid
    // will read -- one reading of the plan, two surfaces.
    progression = progression,
)

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
    /**
     * Whether the movement puts the lifter's own mass in the path -- the
     * exercise's `bodyweight` declaration, frozen with the rest of the write.
     *
     * Carried so the rest screen's load correction can apply the right rule
     * without asking the live queue, which has already moved on to the set
     * coming up by the time that row draws. It decides two things:
     * [addedKg] renders in #160's "BW + 10 kg" notation rather than as a bare
     * number, and a correction may take it below zero for band or machine
     * assistance.
     */
    val bodyweight: Boolean,
    /** The plan's declared implement count for the set just finished, if any. */
    val implementCount: Int?,
    /**
     * What kind of movement the set was, frozen with the rest of the write
     * (#237).
     *
     * Carried so the rest screen's record line can say "45s carry" for a
     * farmer's walk and "30s hold" for a plank -- the same two words the "Up
     * next" card uses, which is the whole point of the two reading as one
     * another. Nothing on this screen could tell the two apart before, and the
     * hold correction's "Held" label still cannot; that label is a near
     * neighbour of this change and is left alone rather than folded in.
     *
     * [explosive] is the SAME fact narrowed to one member and is deliberately
     * left standing rather than re-derived here: it has four call sites on this
     * screen and turning it into a computed property is a refactor #237 did not
     * ask for. That the two can drift is named, not fixed.
     */
    val kind: ExerciseKind,
    val analysis: SetAnalysis,
    /**
     * The count the row was WRITTEN with: the tally the app kept where it kept
     * one, and the DSP's own count where it did not (#237 round 2).
     *
     * Frozen here because NEITHER of the two fields the box read before can
     * still say it once the set is over. [repsOverride] is seeded with the
     * tally at set end -- see `restingState` -- so on a manual set it is not a
     * correction at all; and [analysis] carries an EMPTY rep list for a set no
     * sensor counted, so `analysis.reps.size` is 0. Striking one against the
     * other drew "0 reps" through on every guided and every sensorless set,
     * against a figure nobody had corrected.
     *
     * This is the left-hand operand of that pair, and it is EQUAL to
     * [repsOverride] until the lifter actually corrects the count -- which is
     * what makes an uncorrected set draw one figure rather than two.
     */
    val recordedReps: Int,
    val plannedReps: Int?,
    val tempo: String?,
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    val side: String? = null,
    /** Olympic-lift style set: peak velocity is the headline metric. */
    val explosive: Boolean = false,
    /**
     * Which question this set's velocity loss answers, and therefore which
     * figure the rest screen leads with (#250).
     *
     * Resolved once, from the geometry FROZEN on the pending write -- the same
     * object the row stores and the export later re-derives this word from --
     * rather than from live state or from the exercise definition as it stands
     * when the rest screen draws. `kind` and `explosive` beside it read the
     * pending write's ExerciseDef and could in principle disagree with the
     * resolved geometry; that they are two facts is named at `kind` already
     * and is not fixed here.
     *
     * Null where the regime is not decidable, and null draws the chips exactly
     * as they drew before this existed.
     */
    val velocityLossRegime: VelocityLossRegime? = null,
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
    /**
     * The ADDED load the lifter stated for this set on the rest screen,
     * overriding what it was recorded with (#205).
     *
     * Null is no correction, not a zero load: a set corrected DOWN to an empty
     * bar is a different statement from one never corrected, and the row's own
     * label says which of the two it is showing.
     *
     * The ADDED load and never the total, for [addedKg]'s reason: the total is
     * derived from it by [SetLoadPolicy.correctedTotalKg] at the write, from
     * the body-weight term this set was actually recorded with.
     */
    val loadOverrideAddedKg: Double? = null,
    /**
     * Which scale this set was RATED on, frozen with everything else (#244).
     *
     * The rest screen re-opens the same grid to correct a rating, and it must
     * ask in the dimension the set was rated in rather than in whatever the
     * plan says now. Frozen for [bodyWeightKg]'s reason and a sharper one: a
     * plan is editable and deletable, so an exercise's `progression` can move
     * -- or the whole plan can be gone -- while the set that was rated under
     * it is still on the rest screen. This is the same value
     * `completedSetOf` writes into the row's `rpeScale`, resolved by TWO
     * calls to `EffortScale.askFor` on the same frozen [PendingSetWrite] --
     * one in `completedSetOf` and one here in `restingState` -- so they
     * cannot differ today, and nothing in this repository would catch it if
     * one call's arguments moved and the other's did not.
     */
    val rpeAsk: EffortAsk,
) {
    // Against [recordedReps] rather than the analysis, so this and the box's
    // struck pair read the same left-hand figure. Behaviour-identical at this
    // commit and pinned by nothing: the ONE construction site sets both
    // `recordedReps = p.manualReps ?: analysis.reps.size` and
    // `repsOverride = p.manualReps`, so a null override implies a null
    // manualReps implies `recordedReps == analysis.reps.size`, and no copy
    // site writes the override back to null.
    val effectiveReps: Int get() = repsOverride ?: recordedReps

    /** The added load this set stands at now, the correction ahead of the record. */
    val effectiveAddedKg: Double get() = loadOverrideAddedKg ?: addedKg

    /**
     * The body-weight-inclusive load this set stands at now.
     *
     * Computed by the same function the write uses, from the same pair, so
     * what the rest screen shows and what the row holds cannot disagree.
     */
    val effectiveLoadKg: Double get() = SetLoadPolicy.correctedTotalKg(loadKg, addedKg, effectiveAddedKg)

    /**
     * Seconds this set stands at now, the lifter's correction ahead of the
     * recorded figure. Null on a set that is not timed at all.
     */
    val effectiveDurationS: Int? get() = durationOverrideS ?: actualDurationS
}

/** One pick in the chooser SWITCH EXERCISE opens. */
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
     * The body weight [loadKg] was computed with, frozen at the moment the set
     * ended, or null on a set with no body-weight term (#220).
     *
     * Frozen with everything else and never re-read: the app holds ONE body
     * weight and the lifter can change it between sets, so a figure read at
     * write time or at export time would be attributed to arithmetic it took
     * no part in.
     */
    val bodyWeightKg: Double?,
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
    /**
     * The arm the PLAN prescribed, frozen beside [side] the way [plannedReps]
     * is frozen beside [targetReps]. Null on an ad-hoc set, which no plan
     * prescribed anything for.
     */
    val plannedSide: String?,
    val tempoText: String?,
    /** The hold seconds the PLAN prescribed, frozen. Recorded and exported. */
    val plannedDurationS: Int?,
    /** The hold seconds the set was working to. Judges, as [targetReps] does. */
    val targetDurationS: Int?,
    val actualDurationS: Int?,
    /** The prep prescribed and the prep that played, frozen too. */
    val plannedPrepS: Int?,
    val prepS: Int?,
    /**
     * Where the prep was, or null where this set has no window to state
     * (#185).
     *
     * Built by `PrepWindowPolicy` and frozen here with everything else. The
     * seconds are already carried by [prepS]; this is the INTERVAL, on the
     * clock the raw samples are stamped with, which is what an analysis needs
     * to know which rows precede the set rather than how many of them there
     * were.
     */
    val prepWindow: PrepWindow?,
    /**
     * Whether this set's WORK PHASE began, frozen with everything else (#216).
     *
     * The companion of [prepWindow] and a different question: that one says
     * whether the prep INTERVAL can be stated, this says whether the work
     * happened at all. They differ on a set with no prep, where there is no
     * interval and the work began at the tap, and on a set whose two instants
     * inverted, where the window is refused and the work did begin.
     * `AbandonedSetPolicy.workBegan` owns the rule.
     */
    val workBegan: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /**
     * The instant the rest AFTER this set runs from, frozen with everything
     * else (#178).
     *
     * `RestClockPolicy.startedAtMs` owns the rule and is asked once, at the
     * freeze, because two readers now take this instant: the countdown the
     * lifter watches and the heart-rate window the archive publishes as the
     * next set's `rest_before_hrm`. Computing it twice is how those two come
     * to disagree, which is the defect #178 measured at 53.06 s.
     */
    val restStartedAtMs: Long,
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
     * every one-sensor set, and non-null when a role was armed and the
     * analysis was not pointed at it -- with an empty sample list where that
     * unit produced nothing, which is the state the repository turns into "no
     * row, and the declaration still names it", and with its handful of rows
     * where it delivered too few frames to analyse (#209), which the
     * repository archives like any other capture.
     */
    val sensors: RecordedSensors?,
    val secondary: SecondaryCapture?,
    val rating: SetRating?,
    val planName: String?,
    val planSessionName: String?,
    val targets: SetTargets,
)

/**
 * Which stream the live readout follows right now, from what has arrived so
 * far (#210).
 *
 * A free function taking what it needs rather than a member: detekt's
 * `LargeClass` counts [RecordViewModel]'s lines against a default of 600, and
 * this branch put it over -- `:app:detekt` is CI's first step. The three below say "for
 * [liveFeedOf]'s reason" and mean this paragraph; they used to name
 * `armedCaptureOf`, which since #212 lives in `:core:model` and no longer
 * carries it.
 *
 * THE LATCH. Once the readout has moved off the armed stream it stays moved,
 * for the whole set. That is [LiveFeedPolicy]'s rule and not this function's,
 * and it is carried across frames by RecordViewModel.liveFedBy, which this
 * takes as [fedBy] and whose next value is the answer's own role.
 *
 * BOTH COLLECTORS RUN ON viewModelScope, whose dispatcher is the main one, so
 * the two calls to this are serialised by the same thread that writes the
 * screen state. That is what makes the latch a plain field.
 *
 * The DECISION is [LiveFeedPolicy.liveFeed]'s, in `:core:model` where a test
 * runs on it. What is left here is the same lookup [armedCaptureOf] does --
 * roles as keys, frame counts as values -- so the live half and the set-end
 * half read one another's vocabulary rather than two spellings of it.
 *
 * COUNTS, NOT BUFFERS. [armedCaptureOf] hands the analysis a list of samples
 * and must therefore hold both; this decides nothing about what is recorded,
 * so it takes the sizes and cannot reach a sample at all.
 */
internal fun liveFeedOf(
    armed: RecordedSensors?,
    secondaryRole: SensorRole?,
    fedBy: SensorRole?,
    analysedFrames: Int,
    secondaryFrames: Int,
): LiveFeed {
    val framesByRole = buildMap {
        armed?.analysed?.let { put(it, analysedFrames) }
        secondaryRole?.let { put(it, secondaryFrames) }
    }
    return LiveFeedPolicy.liveFeed(
        armed = armed?.analysed,
        fedBy = fedBy,
        expected = armed?.expected.orEmpty(),
        framesByRole = framesByRole,
    )
}

/**
 * Everything frozen onto a set that is ending: which buffer the DSP is pointed
 * at, what the row says about that choice (#207), and which armed links were
 * silent across the whole set with what the app could see of each (#213).
 *
 * A free function taking what it needs, for [liveFeedOf]'s reason:
 * [RecordViewModel] is the class detekt's `LargeClass` counts against a
 * default of 600, and this issue's two added lines put it over. So the two answers are composed
 * here rather than at the call site, which keeps that site the single
 * statement it already was.
 *
 * The buffers are copied HERE, which is the one place the freeze happens now
 * rather than at every call. The set's start and its end are what is passed
 * down, and a role reading as delivering while its buffer is empty is a
 * contradiction [armedCaptureOf] declines to publish rather than resolve. On a
 * set shorter than `ArmedSilencePolicy.SILENT_AFTER_MS` that reading can be
 * driven by a frame from before the set began, which is where the
 * contradiction comes from and why the declining direction is silence -- see
 * [armedCaptureOf].
 */
internal fun RecordState.captureAt(
    armed: RecordedSensors?,
    secondaryRole: SensorRole?,
    analysedBuffer: List<ImuSample>,
    secondaryBuffer: List<ImuSample>,
    startedAtMs: Long,
    endedAtMs: Long,
): ArmedCapture = armedCaptureOf(
    armed,
    secondaryRole,
    analysedBuffer.toList(),
    secondaryBuffer.toList(),
    ArmedSilencePolicy.storedDeliveryByRole(
        analysed = armed?.analysed,
        secondary = secondaryRole,
        links = armedLinks(),
        setStartedAtMs = startedAtMs,
        setEndedAtMs = endedAtMs,
    ),
    ArmedSilencePolicy.storedSoleSilence(
        roster = roster,
        pairedImuAddresses = pairedImuAddresses,
        links = armedLinks(),
        setStartedAtMs = startedAtMs,
        setEndedAtMs = endedAtMs,
    ),
)

/**
 * The four link fields THIS STATE is holding, as `:core:model` wants them.
 *
 * The one place they are bundled, so the two readings -- the card on READY and
 * the rest screen, asked at the moment they draw, and `endSet`, asked at the
 * moment the set ended -- cannot pair one link's state with the other's frame
 * instant in different ways. An extension rather than a member of
 * [RecordViewModel] for [liveFeedOf]'s reason: that class is the one detekt's
 * `LargeClass` counts.
 */
internal fun RecordState.armedLinks(): ArmedLinks =
    ArmedLinks(imuState, imuFrameAtMs, imuArmedAtMs, imuStateB, imuFrameAtMsB, imuArmedAtMsB)

/**
 * What the ONE armed link is doing on a set whose stream carries no role
 * (#224), or null when this set arms roles, arms nothing, or that link is
 * delivering.
 *
 * The role-keyed LIVE reading's sibling for the configuration this app is used
 * in most, and an extension rather than a member of [RecordViewModel] for
 * [liveFeedOf]'s reason. The DECISION is
 * [ArmedSilencePolicy.soleSilence]'s, in `:core:model` where a test runs on it,
 * including which sets it applies to; what is left here is handing it the one
 * link's state and frame instant, which are [imuState] and [imuFrameAtMs] --
 * the analysed link, and the only bar-sensor client still holding a device
 * when the roster names no second address -- `setSecondaryImuAddress(null)`
 * disconnects the second one and its loop then idles on the no-address
 * branch.
 *
 * THE CARD'S READING ONLY. `endSet` asks the same question through
 * `ArmedSilencePolicy.storedSoleSilence`, which takes the whole [armedLinks]
 * bundle and the set's two instants; this one is passed
 * `RecordState.imuArmedAtMs` while the card is drawing.
 *
 * IT IS WHEN THIS LINK WAS LAST DELIBERATELY POINTED SOMEWHERE, since #225
 * item 9. `AutoConnectManager` rewrites the analysed link's instant wherever
 * it re-points that link -- `pairAndConnect`, and the two drops in
 * `setPreferredAndConnect` and `forgetAndDrop` -- as it already did for the
 * second link. Before that only `imuArmedAtB` ever moved, so this was the
 * PROCESS's start instant, the grace floor was spent seconds into the
 * process, and a unit paired an hour in was accused while it was still
 * connecting. What is still not covered is stated in that property's own
 * KDoc: a `stop()`/`start()` pair writes neither instant, and `maintain` can
 * re-point a link on its own when the preferred address moves underneath it.
 *
 * IT READS THE LIVE ROSTER. Pairing a second unit MID-SET does NOT silence
 * this: `SensorRoster.isDual` is `secondary != null`, so an unlabelled second
 * unit, or one whose label collides with the first, leaves the roster
 * non-dual and the word is still stored -- beside the shortfall that says why
 * the app recorded one stream. What moves this answer to null is the roster
 * becoming DUAL, which is two paired units carrying two different labels.
 */
internal fun RecordState.soleSilenceOver(sinceMs: Long, nowMs: Long): ArmedDelivery? = ArmedSilencePolicy
    .soleSilence(
        roster = roster,
        pairedImuAddresses = pairedImuAddresses,
        state = imuState,
        lastFrameAtMs = imuFrameAtMs,
        armedAtMs = sinceMs,
        nowMs = nowMs,
    )

/**
 * The frozen set-write, in the shape the repository stores.
 *
 * A free function taking what it needs, rather than the inline construction
 * this replaces and for the same reason [openSession] is one: it keeps a
 * thirty-line argument list out of [RecordViewModel].
 * Nothing else about it moved: every argument is the same expression over the
 * same three inputs, all of them already frozen.
 *
 * [failed] is passed rather than derived here. It is the OR of the lifter's own
 * tap and the app's derivation, computed at the call site from state this
 * function cannot see, and re-deriving it from [p] alone would silently drop
 * the half the lifter stated.
 */
private fun completedSetOf(p: PendingSetWrite, analysis: SetAnalysis, failed: Boolean, failedByLifter: Boolean) =
    CompletedSet(
        exerciseId = p.exercise.id,
        exerciseName = p.exercise.displayName,
        loadKg = p.loadKg,
        plannedLoadKg = p.plannedLoadKg,
        bodyWeightKg = p.bodyWeightKg,
        // WHICH QUESTION the lifter was shown, resolved from the FROZEN pair;
        // the tiles were worded by a DIFFERENT askFor call in RecordScreen,
        // off state.currentIsTimed/state.currentSlot -- they agree only
        // because endSet builds this pair from that same state in the same
        // action (#244).
        //
        // Not the raw declaration: the stored word is a capture-time fact
        // about what was asked, and a later change to how a declaration maps
        // onto a question must not restate what a past lifter saw.
        //
        // `p.slot` is null on an ad-hoc set, which no plan declared anything
        // for; `askFor` resolves that as WEIGHT and the set's own kind then
        // decides, so an ad-hoc rep set stores `load` and an ad-hoc hold
        // stores `time`. #244's brief said an ad-hoc set is written as `load`
        // outright; that is right for a dynamic one and WRONG for a hold,
        // which is asked in seconds on screen, and writing `load` there would
        // record a question nobody was shown.
        rpeScale = EffortScale.askFor(p.isTimed, p.slot?.progression).word,
        plannedReps = p.plannedReps,
        manualReps = p.manualReps,
        actualDurationS = p.actualDurationS,
        plannedDurationS = p.plannedDurationS,
        side = p.side,
        plannedSide = p.plannedSide,
        tempo = p.tempoText,
        targetMeanConVelMps = p.slot?.targetMeanConVelMps,
        velocityLossStopPct = p.slot?.velocityLossStopPct,
        plannedRestS = p.slot?.restS,
        plannedPrepS = p.plannedPrepS,
        prepS = p.prepS,
        prepWindow = p.prepWindow,
        workBegan = p.workBegan,
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
        // The OR's two halves, stored apart for the first time (#216). [failed] is
        // still the OR and nothing about it moves; this says whether the lifter
        // said so, which the row has never carried and which no export could
        // therefore publish.
        failedByLifter = failedByLifter,
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
 * does not sit inside [RecordViewModel]. Keeping state transitions out of that
 * class is this file's convention against detekt's `LargeClass`; #208 split the
 * correction seam out of it and left 24 lines of growth under the default
 * 600, measurable by bisecting `LargeClass.threshold` on `:app:detekt` until
 * the finding appears.
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
 * A free function taking what it needs, for the reason [openSession] gives.
 * The behaviour is unchanged by the move.
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
 * The three facts [mirrorSensorSettings] combines, carried out of the combine
 * transform so that the state read and the state write are one statement in
 * the collector.
 */
private data class SensorSettings(
    val roles: Map<String, SensorRole>,
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
 * Three sources in one collector rather than three collectors, because the
 * roster is a function of all of them together: a role assigned to a device
 * that has since been forgotten arms nothing, and a second device paired
 * without a label arms nothing either. It was four until #198 retired the
 * per-exercise count. Emitting a state that is right about
 * three of the four and then correcting it is a window in which the READY
 * screen shows a dot for a sensor that will not be captured.
 *
 * [onSecondaryAddress] is called with the address the second link should
 * maintain. It reads no count, because since #198 nothing does: two PAIRED and
 * labelled units arm two streams on every set, so the link the roster names is
 * the link every set captures from. Paired, not connected -- what the roster
 * names is a link to bring up, and whether it comes up is the link's answer.
 *
 * For a lifter with two labelled units that makes up to three concurrent GATT
 * links the steady state of every set, and the second client unlocks, sets
 * 100 Hz and subscribes. That was field item F1b and the owner has MEASURED
 * it: on field-34's dual-sensor session the second sensor cost 0.000 Hz on the
 * analysed stream. The capture is not in this repository -- there is no
 * `field-34` fixture among `core/dsp/src/test/resources/field-*.csv` -- so
 * nothing here re-checks the figure, and the session was two sets rather than
 * a whole one. It is not a reason to keep a count; it is not a discharge
 * either, and calling it one was this branch's own overstatement.
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
 * A free function for [openSession]'s reason.
 */
private fun CoroutineScope.mirrorSensorSettings(
    settings: SettingsStore,
    registry: DeviceRegistry,
    state: MutableStateFlow<RecordState>,
    onSecondaryAddress: (String?) -> Unit,
) = launch {
    combine(
        settings.sensorRoles,
        registry.knownDevices,
        registry.preferred(DeviceRole.IMU),
    ) { roles, known, preferred ->
        SensorSettings(
            roles = roles,
            paired = known.filter { it.role == DeviceRole.IMU }.map { it.address },
            preferred = preferred?.address,
        )
    }.collect { next ->
        state.value =
            state.value.copy(
                sensorRoles = next.roles,
                pairedImuAddresses = next.paired,
                preferredImuAddress = next.preferred,
            )
        onSecondaryAddress(
            SensorCapturePolicy.roster(
                pairedImuAddresses = next.paired,
                preferredAddress = next.preferred,
                roleByAddress = next.roles,
            ).secondaryAddress,
        )
    }
}

/**
 * The collect job for the accelerometer that is not the ARMED one.
 *
 * Deliberately not routed through `onSample`. That function asks
 * [LiveFeedPolicy] the same question about the ARMED unit's frames and, when
 * the answer is yes, feeds the tracker, the live readout and the rep
 * announcements; a second stream reaching them unconditionally would make the
 * bar appear to move twice. So this sample is OFFERED to [onLive] rather than
 * fed: whether the tracker takes it is [LiveFeedPolicy]'s answer and never
 * this collector's, and once the armed unit has delivered
 * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] frames first, the answer is no
 * for the rest of the set. AN EARLIER VERSION SAID "on every set where the
 * armed unit is delivering the answer is no", which is wrong about the start
 * of a set -- before either unit has eight frames nothing is analysable and
 * the armed role holds the readout by default, not because it is delivering.
 *
 * The buffer is written BEFORE the offer, because the decision is taken over
 * frame counts and this frame is one of them.
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
    onLive: (ImuSample) -> Unit,
): Job = launch {
    samples.collect { sample ->
        buffer += sample
        journal()?.appendSecondaryImu(sample, role)
        onLive(sample)
    }
}

/**
 * The four instants [mirrorLinkStates] mirrors in one write (#213).
 *
 * A named carrier rather than a `List<Any>` out of `combine`, for
 * `SensorSettings`' reason: the four are the same primitive type and an
 * argument order swapped at the lambda would compile and be wrong about which
 * unit is silent.
 */
private data class ArmedLiveness(
    val analysedFrameAtMs: Long?,
    val secondaryFrameAtMs: Long?,
    val analysedArmedAtMs: Long,
    val secondaryArmedAtMs: Long,
)

/**
 * Mirror all three links' connection state onto the screen state.
 *
 * One free function rather than three collectors in `init`, and free for
 * [openSession]'s reason. The behaviour is unchanged by the move -- three
 * independent collectors, each copying one link's state onto its own fields.
 *
 * `imuConnected`, `imuConnecting` and `imuState` still mean THE ARMED SENSOR
 * -- the link to the preferred address -- and nothing else; since #207 that is
 * not always the stream the set's figures come from. They have four consumers
 * between them -- the dot, the SETUP advice, whether an explosive lift is
 * sensor-counted, and the set journal's header -- and the correct answer for a
 * second sensor differs at each, so the second link gets its own fields rather
 * than widening these (#156).
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
    // The two links' DELIVERY, beside their connection state and deliberately
    // not folded into it (#213). Connected says the app issued a subscribe;
    // these say something came back. Four flows in one collector rather than
    // four collectors, because the SETUP card and the dots read them together
    // and a state right about three of four is a window in which the screen
    // names the wrong unit.
    //
    // `AutoConnectManager` coalesces these to at most one write per half
    // second per link, so this is nothing like a per-sample write onto the
    // screen state -- `feedTracker` already does one of those at 100 Hz during
    // a set, from whichever collector the live feed is pointed at (#210).
    launch {
        combine(
            autoConnect.imuFrameAtMs,
            autoConnect.imuFrameAtMsB,
            autoConnect.imuArmedAtMs,
            autoConnect.imuArmedAtMsB,
        ) { frameA, frameB, armedA, armedB ->
            ArmedLiveness(frameA, frameB, armedA, armedB)
        }.collect { next ->
            state.value =
                state.value.copy(
                    imuFrameAtMs = next.analysedFrameAtMs,
                    imuFrameAtMsB = next.secondaryFrameAtMs,
                    imuArmedAtMs = next.analysedArmedAtMs,
                    imuArmedAtMsB = next.secondaryArmedAtMs,
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
 * A free function taking what it needs, for the reason [openSession] gives.
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
        armedRole = roster.analysed,
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
 * The START on the session preview: either raise the body-weight prompt, or
 * start (#181, #202). A tap on the plan session card no longer reaches here --
 * it calls openPreview.
 *
 * Free function taking the state flow and a start callback, for
 * [planSessionState]'s reason.
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
 * A refused body-weight set has been answered (#61). [kg] null is CANCEL, and
 * a cancel is not a skip: no set starts, nothing is written, and the next
 * START asks again. There is nothing to skip to -- the app cannot record the
 * set without the number.
 *
 * On an answer the settings write is AWAITED and the state is then updated
 * from [kg] on the same continuation rather than waited for. `bodyWeightKg`
 * reaches [RecordState] through a collector on the settings flow, which is a
 * separate coroutine, so calling [onBegin] on the strength of the durable
 * write alone would race that collector and hit the refusal a second time
 * with the figure already stored.
 *
 * THE REFUSAL IS CLEARED BEFORE THE LAUNCH, not inside it. A second answer
 * arriving while the settings write is still in flight reads the flag,
 * finds it already false and returns, so one refusal cannot start two sets.
 * Clearing it after the awaited write left a window the second tap fell
 * into, and `answerBodyWeight` below has always cleared its own flag
 * synchronously for this reason. The state written inside the launch is
 * therefore `bodyWeightKg` alone; `state.value` is re-read there rather than
 * copied from `s`, so the clear above is not undone.
 *
 * [writeBodyWeightKg] is the durable write, taken as a function rather than
 * as the [SettingsStore] that provides it: `SettingsStore` is a concrete
 * class over an Android `Context`, and a `:app` unit test cannot build one.
 * `RecordViewModel` passes `container.settings::setBodyWeightKg`.
 *
 * Free function taking the state flow, for [askOrStartSession]'s reason, and
 * `internal` for `RefusedSetAnswerTest`'s -- the same arrangement
 * `advancedState` and `unitChangedState` are in.
 */
internal fun CoroutineScope.answerRefusedSet(
    state: MutableStateFlow<RecordState>,
    writeBodyWeightKg: suspend (Double) -> Unit,
    kg: Double?,
    onBegin: () -> Unit,
) {
    val s = state.value
    if (!s.bodyWeightRequiredForSet) return
    state.value = s.copy(bodyWeightRequiredForSet = false)
    if (kg == null) return
    launch {
        writeBodyWeightKg(kg)
        state.value = state.value.copy(bodyWeightKg = kg)
        onBegin()
    }
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
 * What starting a session resets, stated once for both starts, and the one
 * thing it deliberately leaves alone: a new session does not inherit the last
 * one's trailing rest window -- the previous session's close wrote it onto
 * that session's last set where there was one and the write landed (#109),
 * and it belongs to neither set here.
 *
 * Returns the start instant rather than writing it, because the field it
 * belongs in is the ViewModel's and a free function has no business owning it.
 */
private fun openedSessionClocks(rr: MutableList<Double>, restHr: MutableList<HrSample>): Long {
    rr.clear()
    restHr.clear()
    return System.currentTimeMillis()
}

/**
 * The state a tap on a plan session card leaves behind: the queue BUILT and
 * being read, and nothing started (#202).
 *
 * The whole of the guarantee "looking starts nothing" is the shortness of this
 * function. It writes a stage, the session being read, and the queue. It does
 * not stamp [RecordViewModel.sessionStartedAtMs], clear the R-R or rest-HR
 * buffers, create a session row -- nothing does that until the first set is
 * recorded -- or start the foreground service, which `beginSet` starts. Every
 * one of those happens in `startPlanSession` and `beginSet` instead, on the far
 * side of the Start press.
 */
private fun previewState(s: RecordState, planSession: PlanSessionDef, queue: List<PlannedSlot>): RecordState =
    s.copy(stage = Stage.PREVIEW, previewSession = planSession, queue = queue, queueIndex = 0)

/**
 * Back to the session picker, dropping the queue. `RecordViewModel.abandonSetup`
 * is the only caller, and the preview's "Choose another session" is in turn
 * the only thing that calls it (#202), which is why [RecordState.previewSession]
 * is cleared here. The queue goes with it rather than being kept, so a second
 * look re-flattens rather than redrawing a list the plan may no longer say.
 */
private fun previewCancelledState(s: RecordState): RecordState =
    s.copy(stage = Stage.SETUP, previewSession = null, queue = emptyList(), queueIndex = 0)

/**
 * The state opening a plan session leaves behind. Free function for
 * [openSession]'s reason.
 */
private fun planSessionState(s: RecordState, planSession: PlanSessionDef, queue: List<PlannedSlot>): RecordState =
    s.copy(
        stage = Stage.READY,
        // Cleared as the session starts: from here on it is running, not being
        // read, and leaving the name behind would leave a second answer to
        // "which session is this" that nothing keeps up to date.
        previewSession = null,
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
 * Add one more set of the exercise just finished, at the values standing for
 * it (#177, #188).
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
 * EVERY FIELD, AND WHICH SLOT IT COMES FROM. Moving the anchor from the
 * upcoming slot to the finished one changed the source of every field the
 * copy does not reset, so all twenty-four are stated here rather than the one
 * a reviewer happened to name. `AppendedSlotTest.APPEND_DECISIONS` holds the
 * same table and is asserted against the class BOTH ways: a new field cannot
 * be added without an answer, and a wrongly-grouped answer reds on the entry
 * itself in each of the six directions an entry can be moved between the
 * three groups. Six directions, six mutations, one build each -- the list and
 * the failing test per direction are in that file's header. An earlier
 * version of this sentence claimed the same thing with three of the six run,
 * and one of the unrun three, CARRIED mislabelled RESET, passed until a
 * fourth sweep was added for it (#188 round 3).
 *
 *  - From the anchor, because the appended set IS one more set of that
 *    exercise: `exercise`, `geometry`, `side`, `implementCount`,
 *    `exerciseNotes`, `exerciseNotesBehindTap`, `targetMeanConVelMps`,
 *    `velocityLossStopPct`, `restS`, `prepS`, `sensors`. `side` follows the
 *    set just DONE, so a unilateral block appends another set on the side
 *    whose load was wrong rather than the side coming up.
 *  - From what is standing for that exercise, via [carriedValues]:
 *    `loadKg`, `reps`, `durationS`, `tempo`.
 *  - Cleared or recomputed: `plannedLoadKg`, `plannedReps`,
 *    `plannedDurationS`, `plannedTempo`, `setIndexInExercise`,
 *    `setsInExercise`, `isExerciseChange`, `isAddedSet`, and `warmup`.
 *
 * `warmup` is the one that describes the ANCHOR SET'S PURPOSE rather than
 * the exercise, which is why it is the one that had to move groups. `side`
 * is a statement about the set, as the bullet above says; the remaining ten
 * inherited fields all answer "how is this exercise performed", and the
 * appended set performs it the same way.
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
 * WHICH EXERCISE, AND WHEN THE TWO DIFFER. The anchor is the slot at
 * `queueIndex` -- the set just finished during rest, the set being set up on
 * READY -- and not `upcomingSlot`, which is the set that has not happened.
 * "The load was wrong" is a statement about a set already done. At a block
 * boundary the two are different exercises, and reading the upcoming one made
 * this build a slot of the NEXT exercise and queue it after the next block;
 * after the session's final set it made the control refuse outright, since the
 * upcoming index is one past the end (#188).
 *
 * `internal` RATHER THAN `private`, and that is the whole of the test seam
 * this function has. `AddSetControl.placement` decides WHERE the slot goes and
 * is pinned in `:core:model`; which slot each FIELD of the appended
 * [PlannedSlot] is copied from is decided here, on a type that lives in `:app`
 * and cannot be moved without dragging `ExerciseDef`, `ResolvedGeometry` and
 * the whole queue with it. Widening the visibility is what lets
 * `AppendedSlotTest` ask this function directly. It is called from one place,
 * [RecordViewModel.addSetOfCurrentExercise], and nothing else may call it.
 *
 * A FREE FUNCTION taking what it needs, for [jumpedState]'s reason.
 *
 * WHERE IT GOES is [AddSetControl.placement]'s, which is pure and pinned in
 * `AddSetControlTest`. The block's remaining sets keep their place; the
 * appended set follows them.
 *
 * `setIndexInExercise` is the previous slot's plus one, which is both what
 * the heading counts from and what makes the NEXT append land after this one
 * -- the placement rule walks the block by that index, so an appended slot has
 * to read as a continuation of it. `setsInExercise` is set to match, so the
 * card cannot say "Set 4 of 3"; the prescribed sets keep the plan's own
 * count, because that is what the plan asked of them.
 *
 * `isExerciseChange` is false and no other slot's flag is recomputed. The
 * insertion point is inside the block or at its end, so the appended set's
 * predecessor is always the same exercise, and the slot after the insertion
 * point keeps whichever answer it already had.
 *
 * Repeatable, and reversible since #206: nothing HERE shortens the queue,
 * and [removedState] takes the last appended set of the block back out
 * again while it is still queued.
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
 * RUN ON A DEVICE AGAIN FOR #188, at the boundary #177's run never reached.
 * On `barspeed-api35`, a two-set overhead press block followed by a one-set lat
 * pulldown block: after the LAST press set the control read "Load was wrong?
 * Add another Overhead Press set before Lat pulldown", and the appended set
 * drew as "Set 3 · you added this one · Overhead Press — 2 reps · 44.1 lb",
 * ahead of the pulldown, with the move-the-sensor card gone. `sqlite3` read
 * five `set_records` for that session: three `overhead_press` at 20.0 kg
 * (orderIdx 0, 1, 2) with the third carrying `plannedLoadKg` NULL and `added`
 * 1, then `lat_pulldown` at 30.0 kg prescribed, then a second `lat_pulldown`
 * with `added` 1 -- added from the "That was the last planned set" screen,
 * which had no control at all before #188. The appended press row carries the
 * PRESS load, not the pulldown's: what the defect used to write there was a
 * `lat_pulldown` row at 30.0 kg queued after the pulldown block.
 *
 * That last line is what matters most here, because it covers the hop no JVM
 * test can: `completedSetOf`'s `added = p.slot?.isAddedSet == true` survived
 * mutation with the whole suite green, and the exported document is the
 * evidence that it is wired. WHAT THE RUN DID NOT COVER: the install was fresh,
 * so Room created v12 outright and `MIGRATION_11_12` did NOT execute. It is
 * still unexecuted, and the cluster's two-way exercise still owes 10 -> 11 -> 12
 * against real rows.
 *
 * RUN ON A DEVICE A THIRD TIME, for the `warmup` fix, and this is what
 * discharges it. On `barspeed-api35`, a plan whose lat pulldown block declares
 * its 27.2 kg opener `warmup: true` and its 34 kg working set not. TWO
 * sessions, one per half of the leak: session 1 appended from the REST screen
 * after the warm-up ran, session 2 appended from READY before the warm-up set
 * was started. Both then ran all three sets. `sqlite3` read the same three
 * rows for each session -- `warmup` 1 / `added` 0 on the opener, 0 / 0 on the
 * working set, and 0 / 1 on the APPENDED set, with its `plannedLoadKg` and
 * `plannedReps` NULL. Before this fix that last row read `warmup` 1. The
 * session detail screen drew a WARM-UP chip on the opener and ADDED, with no
 * WARM-UP, on the appended set; the saved export published `"warmup": true`
 * on the opener, `"added": true` on the appended set, and no `warmup` key on
 * it at all. Logcat scoped to the app's own pid carried no `SQLiteException`,
 * no `FATAL` and no crash across both sessions. Same limits as the run above:
 * no sensor, so nothing about BLE, sample capture or the DSP, and a fresh
 * install, so no migration executed.
 */
internal fun appendedState(s: RecordState): RecordState? {
    if (s.adHoc) return null
    val placement =
        AddSetControl.placement(
            s.queue.map { AddSetSlotKey(it.exercise.id, it.setIndexInExercise, it.isAddedSet) },
            queueIndex = s.queueIndex,
            upcomingIndex = s.upcomingIndex,
        ) ?: return null
    val at = placement.insertAt
    val anchor = s.queue[placement.anchorIndex]
    val previous = s.queue[at - 1]
    // The anchor as it stands is what that exercise last RAN with: the bake
    // wrote the lifter's statements into it before the set started. So the
    // carry adds something only while those statements are still about this
    // exercise, and across a block boundary it must not run at all -- a load
    // typed for the exercise the lifter is about to do is not a statement
    // about the one they just finished, which is [jumpedState]'s rule one
    // control over.
    val base = if (placement.carriesStandingStatements) carriedValues(anchor, s) else anchor
    val appended =
        base.copy(
            plannedLoadKg = null,
            plannedReps = null,
            plannedDurationS = null,
            plannedTempo = null,
            // Nothing prescribed this set, so there is no prescribed side to
            // freeze either. RESET rather than inherited, with the other four
            // frozen declarations, and pinned as RESET in AppendedSlotTest.
            plannedSide = null,
            // The anchor's own side, EXPLICITLY: `base` is carriedValues(anchor)
            // whenever the lifter's statements still apply to this exercise,
            // and since #215 that function writes a stated side into the copy.
            // A side stated for the set this append DISPLACES is not a
            // statement about the appended set, and the table records `side`
            // as INHERITED. Without this line the two would disagree the
            // moment a lifter appended a set with an arm chosen.
            side = anchor.side,
            isAddedSet = true,
            // Never inherited. The anchor may be a plan-declared warm-up --
            // the 60 lb pulldown opener this KDoc names -- and an appended
            // set has no plan to have declared it. PlannedSlot.warmup and
            // completedSetOf both say so in as many words; without this line
            // they were false, and the set added BECAUSE the load was wrong
            // was written to set_records as a warm-up, which PLAN_PROMPT
            // tells the coach to drop from volume and progression.
            warmup = false,
            isExerciseChange = false,
            setIndexInExercise = previous.setIndexInExercise + 1,
            setsInExercise = previous.setIndexInExercise + 2,
        )
    val queue = s.queue.toMutableList().apply { add(at, appended) }
    if (!placement.becomesNextSet) return s.copy(queue = queue)
    // The appended set has displaced the one the rest screen was set up for,
    // and every editable box on that screen was seeded from the displaced
    // slot. Re-seeded here rather than left to drift, [jumpedState]'s
    // arrangement for the same event: the box and what the set would record
    // cannot be allowed to disagree, which is what #45 and #124 are about.
    // Unreachable from READY -- there the block always has the set being set
    // up still to run, so the appended set never lands next -- so #177's
    // behaviour on that screen is untouched.
    val seedKg =
        SetLoadPolicy.seedAddedKg(
            hasPlannedNext = true,
            nextDeclaredAddedKg = appended.loadKg,
            lastAddedKg = null,
        )
    return s.copy(
        queue = queue,
        loadInput = seedKg?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
        // Cleared for [jumpedState]'s reason: these were statements about the
        // exercise that is no longer coming up.
        statedLoadKg = null,
        statedTempo = null,
        statedReps = null,
        statedDurationS = null,
        // Cleared for the same reason, and it would have been cleared on the
        // next rest transition anyway: a side stated for the set that has just
        // been displaced is not a statement about the one now coming up.
        statedSide = null,
        repsInput = appended.reps?.toString() ?: s.repsInput,
        durationInput = appended.durationS?.toString() ?: s.durationInput,
    )
}

/**
 * The state taking back an appended set leaves behind, or null when there is
 * none to take (#206).
 *
 * The mirror of [appendedState], and the same division of labour:
 * [RemoveSetControl.target] decides WHICH slot goes and whether the boxes have
 * to follow, on a projection of the queue that a `:core:model` test can build;
 * this function does the removal and the re-seed, on a type that lives in
 * `:app`.
 *
 * `internal` for [appendedState]'s reason, and called from one place,
 * [RecordViewModel.removeAddedSetOfCurrentExercise].
 *
 * WHAT THE LIFTER IS STANDING ON IS LEFT STANDING (#206 requirement 4). A
 * load or a rep count corrected for the EXERCISE is not a statement about the
 * one appended set, so taking that set back out does not roll it back; the
 * removal touches the queue and nothing else.
 *
 * THE ONE EXCEPTION IS NOT AN EXCEPTION TO THAT. Where the removed slot was
 * the set the next START would have run, every editable box on the rest
 * screen was seeded FROM IT, and the slot that comes up in its place is
 * outside the block -- the removal takes the last appended set, so nothing of
 * that exercise follows it. Leaving the boxes would show the finished
 * exercise's load on the next exercise's set: the box disagreeing with what
 * the set would record, which is what #45 and #124 are about. They are
 * re-seeded from the new upcoming slot, and the stated values cleared,
 * exactly as [jumpedState] and [appendedState] already do for the same event.
 * Nothing is reverted; the boxes follow the set that is now coming.
 *
 * At the very end of the queue there is no slot to re-seed from and nothing
 * to run, so the boxes are left alone: the screen becomes "That was the last
 * planned set".
 *
 * NOTHING IS WRITTEN TO ROOM AND NOTHING NEEDS TO BE. The slot being removed
 * has not run, so no `set_records` row, no raw stream and no export entry
 * exists for it. A set that HAS run is unreachable from here --
 * `RemoveSetControl.target` refuses every index below `upcomingIndex` -- which
 * is the boundary #206 draws and the reason this change carries no schema or
 * export move.
 */
internal fun removedState(s: RecordState): RecordState? {
    if (s.adHoc) return null
    val target = s.removeSetTarget ?: return null
    val at = target.removeAt
    val queue = s.queue.toMutableList().apply { removeAt(at) }
    if (!target.wasUpcoming) return s.copy(queue = queue)
    val upcoming = queue.getOrNull(at) ?: return s.copy(queue = queue)
    val seedKg =
        SetLoadPolicy.seedAddedKg(
            hasPlannedNext = true,
            nextDeclaredAddedKg = upcoming.loadKg,
            lastAddedKg = null,
        )
    return s.copy(
        queue = queue,
        loadInput = seedKg?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
        statedLoadKg = null,
        statedTempo = null,
        statedReps = null,
        statedDurationS = null,
        // Cleared with the four above it: a side stated for the set that has
        // just been REMOVED is not a statement about the one that moved up.
        statedSide = null,
        repsInput = upcoming.reps?.toString() ?: s.repsInput,
        durationInput = upcoming.durationS?.toString() ?: s.durationInput,
    )
}

/**
 * The state SWITCH EXERCISE leaves behind.
 *
 * A free function taking what it needs, rather than a method, for the reason
 * [openSession] gives.
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
        // Cleared for statedLoadKg's reason, three fields over: an arm chosen
        // for the exercise the lifter was about to do is not a statement about
        // the one they switched to.
        statedSide = null,
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
 * out to make room for #177's entry point. This is the same relief
 * [jumpedState], [restingState] and [advancedState] were each extracted
 * for, and their KDocs say so.
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
 * here. At the exercise boundary it does not, and the field is re-seeded from
 * the plan exactly as it always was (#124). Where the plan declares a
 * DIFFERENT load for the set coming up it now holds as a distance rather than
 * as a number -- the coming set's own declaration shifted by the correction --
 * so a progressive block keeps stepping and keeps the correction (#143). The
 * sentence that stood here said the statement was dropped in that case; it was
 * true when written and is deleted rather than reworded.
 *
 * [RecordState.statedTempo] is re-decided on the same boundaries but, since
 * #143, NOT by the same rule: [TempoAdjustPolicy.standingAdjustedTempo] drops
 * the adjustment wherever the two declarations differ, where the load carries
 * it as a distance, and it has no warm-up guard. A tempo the lifter set on the
 * wheels holds for the rest of the block, and no further. #148.
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
            // The COMING slot's movement, because that is the set the answer
            // is offered for. Inside a block the two are the same exercise by
            // construction -- sameExerciseBlock compared their ids -- so this
            // differs from the finished set's only where the answer is null
            // anyway.
            bodyweight = nextSlot?.exercise?.bodyweight ?: false,
            // The plan's own warmup declaration on each side of the pair --
            // never s.lastSetWarmup, which this same function wrote into the
            // RETURNED state on the transition before this one, so read here
            // it still describes the set BEFORE p.slot rather than p.slot
            // itself; p.slot?.warmup is asked directly instead.
            // lastSetWarmupMark is excluded for the other reason: it is the
            // lifter's own live statement, not the plan's declaration.
            finishedWarmup = p.slot?.warmup == true,
            nextWarmup = nextSlot?.warmup == true,
        )
    // The same question about the tempo, and since #143 NOT by the same rule:
    // TempoAdjustPolicy.standingAdjustedTempo drops the adjustment wherever the
    // two declarations differ, where the load carries it as a distance, and it
    // has no warm-up guard.
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
    // same block and decided by SetRepsPolicy's rule, which is the tempo's:
    // standingStatedReps and standingStatedDurationS both drop the statement
    // wherever the two declarations differ, and neither has a warm-up guard.
    // The two slots' FROZEN declarations on both sides, never their live
    // reps/durationS: the bake writes the statement into those, so comparing
    // them would compare a number against itself and a descending 10 / 8 / 6
    // would flatten to 12 / 12 / 12 the moment set one was changed. #174.
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
            // The exercise's own declaration, frozen here with the load it
            // qualifies. Read off the pending write rather than off
            // s.currentExercise, which by the time the rest screen draws is
            // already the movement coming up.
            bodyweight = p.exercise.bodyweight,
            implementCount = p.slot?.implementCount,
            // Off the pending write's own exercise, beside `explosive` one
            // field below, which reads the same declaration.
            kind = p.exercise.kind,
            analysis = analysis,
            // What the row was written with, and the reason it cannot be read
            // back off the two fields beside it: `repsOverride` one field down
            // is seeded from the SAME tally, so on a manual set the two are
            // equal and neither is a correction, while `analysis.reps.size` is
            // 0 for every set no sensor counted.
            recordedReps = p.manualReps ?: analysis.reps.size,
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
            // Off the FROZEN geometry, which is what the row stores and what
            // the exporter re-derives the published word from -- so the chip
            // the lifter reads between sets and the word the coach reads in
            // the export are one decision applied to one object (#250).
            velocityLossRegime =
            VelocityLossRegime.of(p.tempoText, p.geometry.concentricUp, p.geometry.horizontal, p.geometry.kind),
            repsOverride = p.manualReps,
            // Resolved from the FROZEN pair, not from live state, and by the
            // same call `completedSetOf` makes -- one decision, two readers
            // (#244).
            rpeAsk = EffortScale.askFor(p.isTimed, p.slot?.progression),
        ),
        lastSetRpe = p.rating?.rpe,
        lastSetFailed = failed,
        // The rating frozen with the write is the only tap there has been at
        // this point; [failed] above already carries the derived shortfall
        // OR-ed in, and that OR is what this field exists to see past.
        lastSetTappedFailed = p.rating?.failed == true,
        lastSetWarmup = p.slot?.warmup == true,
        // A new set arrives unmarked, whatever the last one carried, for the
        // reason stated at the reason fields below.
        lastSetWarmupMark = null,
        // A new set arrives carrying no reason and having never been asked,
        // whatever the last one carried. Written explicitly rather than left
        // to a default, because this is a copy of the previous state and a
        // field omitted here keeps the FINISHED SET BEFORE'S answer -- which
        // is how a reason ends up published against the wrong set.
        lastSetLimiter = null,
        lastSetLimiterNote = null,
        restRemainingS = restRemainingS,
        // Set from the frozen index rather than incremented, so a retry cannot
        // count the same set twice.
        setsCompleted = p.orderIdx + 1,
        // Off the pending write, never off s.currentExercise, which by the time
        // the rest screen draws is already the movement coming up.
        lastSetExerciseId = p.exercise.id,
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
        // NOT carried, and this is the field that differs from the four above
        // it. A stated side expires with the set it was made for: the plan
        // writes unilateral work one set per side, so its own order is the
        // alternation, and a choice that stood would put every remaining set of
        // the block on one arm. Written explicitly rather than left to the
        // copy: a field omitted here keeps the finished set's answer.
        statedSide = null,
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
    // The upcoming slot is what says which stroke is the DRIVE, and the digit
    // carries that answer with it: TempoAdjustPolicy.withDigit takes a
    // TempoDigit rather than a position so a caller cannot ask about a digit
    // without saying which lift it belongs to. #251. The same guard the
    // control draws itself behind -- TempoAdjuster returns early with no slot
    // -- so no tap the screen can produce reaches the null branch.
    val exercise = s.upcomingSlot?.exercise ?: return s
    val digit =
        TempoAdjustPolicy.digits(exercise.concentricUp, exercise.horizontal)
            .firstOrNull { it.position == position } ?: return s
    val editing = s.statedTempo ?: s.upcomingSlot?.tempo
    val turned = TempoAdjustPolicy.withDigit(editing, digit, value) ?: return s
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
    // An ad-hoc session has no prescription to deviate from; its side is
    // sideInput, which the Both/Left/Right chips write.
    statedSide = null,
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
 *
 * `internal` rather than private for `appendedState`'s reason: it is a pure
 * function over [RecordState], and `LastPlannedSetTest` is the only thing on
 * the CI path that can reach what tapping START does to the queue.
 */
internal fun advancedState(s: RecordState): RecordState {
    if (s.adHoc) {
        return s.copy(
            stage = Stage.READY,
            statedLoadKg = null,
            statedTempo = null,
            statedReps = null,
            statedDurationS = null,
            statedSide = null,
        )
    }
    // Nothing to advance to, so advancing does nothing (#195). This branch
    // used to share the ad-hoc one: it wrote READY and left `queueIndex`
    // where it was, so the slot just recorded became the current slot again
    // and `startNextSet` -- which calls `beginSet` in the same frame -- ran
    // the finished set a second time. The row it wrote carried the plan's
    // prescription with `added` false, so nothing in the export could tell it
    // from the planned set it duplicated.
    //
    // The state is returned UNTOUCHED rather than moved to a close state: the
    // lifter is on the rest screen and finishing is a decision they take, not
    // one taken for them while the phone is on the floor. The screen no
    // longer offers a control that reaches here -- `RestControlPolicy
    // .restScreen` withholds START with no next slot -- so this is the
    // second half of one rule rather than the only guard.
    val next = s.nextSlot ?: return s
    return bakedState(s, next, s.queueIndex + 1)
}

/**
 * The state starting another set leaves behind, or null where starting one is
 * not a thing that may be done (#195).
 *
 * A free function for [advancedState]'s reason. Returning null rather than a
 * boolean is what keeps the call site the three lines it already was, and it
 * is [appendedState]'s and [jumpedToExerciseState]'s idiom besides.
 *
 * THE CANCEL NOW FOLLOWS THE WRITE at the call site, which it did not before.
 * Safe because both run on the main dispatcher inside one non-suspending
 * function, so the countdown cannot resume between them; and harmless even if
 * it could, because each tick copies whatever `stateFlow.value` holds at that
 * moment and changes only `restRemainingS`, so a tick landing after the write
 * cannot put the stage back.
 *
 * The SAME projection `RecordScreen`'s `restControls` makes, so the button
 * and the tap cannot disagree about the state they are looking at.
 * `hasNextSlot` is `nextSlot != null` -- the slot START would run, null after
 * the last planned set and non-null again the moment the lifter appends one.
 *
 * Withheld means withheld: after the last planned set `startNextSet` returns
 * without cancelling the rest clock or touching the queue, and the lifter
 * either appends a set or finishes. The other case this refuses -- a close in
 * flight -- is one the screen already stopped drawing, so the guard closes a
 * tap nothing can currently deliver rather than removing a control.
 */
internal fun startedNextSetState(s: RecordState): RecordState? {
    val screen =
        RestControlPolicy.restScreen(
            close = s.sessionClose,
            askedToFinish = s.askingSessionRpe,
            hasNextSlot = s.nextSlot != null,
            adHoc = s.adHoc,
        )
    if (RestControl.START_NEXT_SET !in screen.controls) return null
    return advancedState(s)
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
        // The lifter's choice of arm for THIS set, displacing the plan's
        // prescription where they made one. plannedSide is deliberately not in
        // this copy, for the reason every other frozen declaration is not: it
        // stays at what the plan asked for so the card can strike the change
        // and the export can publish both.
        side =
        SideChoicePolicy.carriedIntoNextSet(
            declaredSide = next.side,
            statedSide = s.statedSide,
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
     * The side the lifter has said the set now being set up will work, and null
     * when they have said nothing about it (#215).
     *
     * A different fact from [sideInput], which is the AD-HOC selector: that one
     * says which limb an unplanned set is for and is never read on a planned
     * set. This is a deviation from a prescription that exists, written only by
     * a tap of the change-next-set control.
     *
     * IT EXPIRES WITH THE SET IT WAS MADE FOR, which is where it parts company
     * with [statedLoadKg], [statedReps] and [statedTempo]: those stand across
     * the exercise block until the plan prescribes otherwise, and this is
     * cleared on every rest transition. A plan writes unilateral work one set
     * per side, so the plan's own order IS the alternation; a statement that
     * stood would flip every remaining set of the block onto one arm, which is
     * the opposite of what a lifter swapping arm order for ONE set asked for.
     */
    val statedSide: String? = null,
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
    /**
     * The exercise the set just finished ran against, frozen with the rest of
     * the write.
     *
     * The id, not the display name [SetFeedback.exerciseName] carries: it is
     * compared against [selectedExerciseId] to decide whether a load corrected
     * on the rest screen may reach the set coming up on an ad-hoc session,
     * where there is no slot to compare against.
     */
    val lastSetExerciseId: String? = null,
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
    /**
     * Why the just-finished set ended, or null where nothing has been said
     * (#189).
     *
     * Null is the state a skip leaves behind as well as the state a set nobody
     * was asked about carries, and the two are deliberately not distinguished
     * on this screen: both mean there is no answer, and the row says so in
     * words either way. What IS distinguished is whether the page has already
     * been offered for this set, which is composable state in RecordScreen's
     * RestingStage and is deliberately not a field here: a skip stores
     * nothing, so the record has nothing to remember.
     */
    val lastSetLimiter: SetLimiter? = null,
    /** The lifter's own words, where [lastSetLimiter] is [SetLimiter.OTHER]. */
    val lastSetLimiterNote: String? = null,
    /**
     * The lifter's own statement about the just-finished set's purpose, or
     * null where they have not made one (#194).
     *
     * Beside [lastSetWarmup] and never instead of it: that field is what the
     * PLAN declared, frozen at the write, and `WarmupMarkPolicy` composes the
     * two. Null is silence and is not a quiet false -- it is the ordinary
     * state of every set on a declared plan.
     */
    val lastSetWarmupMark: Boolean? = null,
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
     * The plan session being READ on [Stage.PREVIEW], before anything has been
     * started (#202). Null in every other stage.
     *
     * Held separately from [planSessionName], which is written by
     * `planSessionState` at the Start press and is what the record screen's
     * title and the stored session row read. Keeping the two apart is what
     * makes "a session is being looked at" and "a session is running"
     * different facts: nothing downstream of the preview can mistake a
     * previewed session for a started one by reading the name.
     */
    val previewSession: PlanSessionDef? = null,
    /**
     * The plan session waiting on the body-weight prompt, or null when nothing
     * is being asked.
     *
     * The session itself rather than a boolean, because the answer has to
     * start THAT session. Holding a flag and re-reading a selection would be
     * two facts about one intent.
     */
    val pendingBodyWeightSession: PlanSessionDef? = null,
    /**
     * The lifter has said "not now" to the body-weight prompt since the last
     * session closed. Nothing asks again until then (#181).
     */
    val bodyWeightPromptSkipped: Boolean = false,
    /**
     * A set was asked to start, the lifter's own body is its load, and no body
     * weight is stored -- so it did NOT start and the screen is asking for one
     * (#61).
     *
     * A refusal, not a prompt, and the two are deliberately different fields.
     * [bodyWeightPromptSkipped] silences the start-of-session ASK; nothing
     * silences this, because there is no answer it could take that would let
     * the app record a load it does not have. `RecordViewModel.beginSet`
     * writes it and refuses; `answerBodyWeightForSet` clears it.
     */
    val bodyWeightRequiredForSet: Boolean = false,
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
    /**
     * When the analysed bar sensor last delivered a frame, or null while it
     * never has, and when its link was armed (#213).
     *
     * Beside [imuState] rather than folded into it, for the reason the second
     * link got its own fields rather than widening the first's:
     * [ConnectionState.Connected] means this app ISSUED a notification
     * subscribe, and these four are the only evidence anything came back. The
     * armed instants are a grace floor -- see `ArmedSilencePolicy` -- and not
     * a claim that a link was up at them.
     *
     * Read together with [imuStateB] and [imuFrameAtMsB] by
     * `ArmedSilencePolicy`'s two readings through [armedLinks], and never one
     * without the others.
     */
    val imuFrameAtMs: Long? = null,
    val imuFrameAtMsB: Long? = null,
    val imuArmedAtMs: Long = 0L,
    val imuArmedAtMsB: Long = 0L,
    /** Device address to role, as the lifter labelled them; see `SettingsStore.sensorRoles`. */
    val sensorRoles: Map<String, SensorRole> = emptyMap(),
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
        get() = currentSlot?.exercise ?: ExerciseDef.resolvedById(selectedExerciseId)

    /** Index of the first not-yet-done slot: during rating/rest the current one is already complete. */
    val upcomingIndex: Int
        get() = if (stage == Stage.RESTING) queueIndex + 1 else queueIndex

    /** The slot the next START will run: the current one, or the next during rest. */
    val upcomingSlot: PlannedSlot? get() = queue.getOrNull(upcomingIndex)

    /**
     * The appended set "Remove the set you added" would take, or null when
     * there is none (#206).
     *
     * On the state rather than in [RecordViewModel] because the SCREEN needs
     * it too: the control is drawn only when there is something to remove, and
     * it names the set it will take. Two statements of which set that is would
     * be one more than can be kept in agreement -- a button naming set 5 while
     * the tap removes set 4 is the same class of defect as #45.
     *
     * `isAddedSet` is projected explicitly here, and [AddSetSlotKey] takes it
     * without a default so this line cannot be forgotten.
     */
    val removeSetTarget: RemoveSetTarget?
        get() = if (adHoc) {
            null
        } else {
            RemoveSetControl.target(
                queue.map { AddSetSlotKey(it.exercise.id, it.setIndexInExercise, it.isAddedSet) },
                queueIndex = queueIndex,
                upcomingIndex = upcomingIndex,
            )
        }

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

    /**
     * What a set beginning right now would be armed with.
     *
     * A property rather than a function taking a slot, since #198: the roster
     * is a fact about which units are PAIRED, how the lifter has labelled
     * them and which address is preferred -- it is handed those three and no
     * link state -- and no slot enters into it. Whether an armed link then
     * produces a stream is a
     * separate question this property does not answer.
     * `sensorCountFor`, `plannedSensorCountFor` and
     * `sensorExerciseId` stood beside a `rosterFor(slot)` here and are gone
     * with the count they resolved.
     *
     * Everything about which physical unit is which is decided by
     * [SensorCapturePolicy.roster] in `:core:model`, where a test runs on it.
     * This only hands it what it needs; the screen reads the answer to draw
     * the capture line and the second dot, and `beginSet` reads it again at
     * the moment the set starts, which is the only moment it is true.
     */
    /**
     * What each of this set's ARMED units is doing at [nowMs], with the
     * TOO_SOON grace floor taken from when its link was armed (#213).
     *
     * A function taking `nowMs` rather than a property, and that is forced:
     * the answer changes as time passes with no state change to drive a
     * recomposition, so the SCREEN ticks and passes the instant in. A property
     * reading the clock itself would answer correctly once and then hold a
     * stale answer for as long as nothing else moved.
     *
     * Empty on every one-sensor set, because [SensorRoster.analysed] is null
     * there and there is no armed role to report against. That is not the same
     * as nothing being said about such a set: [soleSilenceOver] answers the
     * same question about the one link, without a role (#224), and the card
     * draws from both.
     */
    fun armedDelivery(nowMs: Long): Map<SensorRole, ArmedDelivery> = ArmedSilencePolicy.liveDeliveryByRole(
        analysed = roster.analysed,
        secondary = roster.secondary,
        links = armedLinks(),
        nowMs = nowMs,
    )

    val roster: SensorRoster get() = SensorCapturePolicy.roster(
        pairedImuAddresses = pairedImuAddresses,
        preferredAddress = preferredImuAddress,
        roleByAddress = sensorRoles,
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
     * Whether the thing the set is JUDGED on has begun -- a hold's clock, a
     * guided set's cadence. On a guided set `setElapsedS` is already ticking
     * here, because `beginSet` starts the tick loop before the cadence's
     * lead-in; nothing records that figure on a guided set, and it is not what
     * this asks about.
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

/**
 * The three latches the in-set voice keeps, and the two calls that move them.
 *
 * At file scope rather than inside [RecordViewModel] for [liveFeedOf]'s
 * reason: detekt's `LargeClass` counts that class against a default of 600 and
 * this branch put it over. Moving these five declarations out is what brought
 * it back under.
 *
 * The DECISION is [VoiceMilestonePolicy]'s, in `:core:model` where a test runs
 * on it. What is left here is the latches and the call to [speak].
 *
 * THE AUDIO-CUES GATE IS THE CALLER'S. Both entry points below are asked only
 * where cues are on, so a set recorded with the voice off leaves every latch
 * where it was -- which is what the two functions in [RecordViewModel] did
 * before this class took them.
 */
private class VoiceMilestones(private val speak: (String) -> Unit) {
    private var announceReps = false
    private var plannedReps: Int? = null
    private var countedPhase: Phase = Phase.IDLE
    private var spokenSecond = 0
    private var announcedRep = 0

    /** Clear every latch for a new set, and take the set's own two facts. */
    fun startSet(announceReps: Boolean, plannedReps: Int?) {
        this.announceReps = announceReps
        this.plannedReps = plannedReps
        countedPhase = Phase.IDLE
        spokenSecond = 0
        announcedRep = 0
    }

    /** One live frame: the tempo count, then the rep call. */
    fun onLive(phase: Phase, elapsedS: Double, repCount: Int) {
        val next = VoiceMilestonePolicy.phaseCount(phase, elapsedS, countedPhase, spokenSecond)
        countedPhase = next.phase
        spokenSecond = next.second
        next.speak?.let(speak)
        announceRep(repCount)
    }

    /**
     * Voice at each lockout: "Rep N" as reps complete, "Last rep" going into
     * the final planned rep, and "Done" when the count is hit. Reached from
     * the live frame above and from the manual tap.
     */
    fun announceRep(repCount: Int) {
        if (!announceReps) return
        val cue = VoiceMilestonePolicy.repMilestone(repCount, announcedRep, plannedReps) ?: return
        announcedRep = repCount
        speak(cue)
    }
}

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val autoConnect = container.autoConnect
    private val sessionRepository = container.sessionRepository

    private val stateFlow = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = stateFlow

    private val imuBuffer = mutableListOf<ImuSample>()

    /**
     * The capture from the accelerometer that is not the ARMED one (#156).
     *
     * [imuBuffer] is the ARMED unit's capture -- the stream of whichever
     * address the preference names -- and since #207 that is not always the
     * stream the figures come from: where the armed unit delivered too few
     * frames to analyse and this one delivered enough, [armedCaptureOf] points the analysis here and
     * [RecordedSensors.analysedFellBack] records the move. This one is filled
     * by a collector that reaches the buffer and the journal and nothing else.
     */
    private val imuBufferB = mutableListOf<ImuSample>()
    private val hrBuffer = mutableListOf<HrSample>()

    /**
     * Heart rate belonging to a REST rather than to a set: the READY window
     * before the first set, and every rest window after one. Issue #90.
     *
     * Not "while no set is running", which is what this said until #178 and is
     * no longer true. A rest starts when the set was CALLED OVER, and a guided
     * set goes on recording after that -- 53.06 s after it on one measured set
     * -- so at each freeze this buffer is seeded with the tail of the set's own
     * capture from that instant, by `RestClockPolicy.restWindowSeed`. Those
     * samples arrived while a set WAS running and are rest all the same. They
     * are COPIED, not moved: the set's `hrm` stream still holds them.
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
     * next one begins: a window belongs to the set that FOLLOWS it, except the
     * last, which the session close writes onto the set BEFORE it (#109).
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

    /** Which role's samples are reaching the tracker, this set: [liveFeedOf] (#210). */
    private var liveFedBy: SensorRole? = null
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

    /**
     * When this set's WORK began -- the instant its prep ended -- or null
     * where the set has none (#185).
     *
     * A second field beside [clockStartedAtMs] rather than a reuse of it,
     * because the two are the same instant on a TIMED set and different ones
     * everywhere else: a cued set starts its clock at the tap and begins its
     * work when the cadence's first stroke is called. Folding them would make
     * "the set was ended while its prep was still running" unrepresentable on a
     * cued set -- the clock instant would equal the tap, and a zero-length
     * window would be published for a prep that in fact never finished.
     *
     * Reset per set in [beginSet]. `PrepWindowPolicy` refuses a value earlier
     * than the tap as well, so a stale one cannot become a window; that rule is
     * in `:core:model` where a test reaches it, and this reset is not it.
     */
    private var workStartedAtMs: Long? = null

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
     * one is reached by no test on the CI path.
     */
    private var lastHrSample: HrSample? = null
    private var voice: VoiceCounter? = null
    private val milestones = VoiceMilestones { speakCue(it) }
    private var plannedRepsForSet: Int? = null

    /**
     * Whether the SENSOR-driven counter may speak on the set in progress.
     *
     * `SetVoicePolicy.guidesFor` in `:core:model` decides it, frozen here at
     * [beginSet] as everything else about a set's arming is. It used to be
     * `!manualSet`, read out of the state at every arriving sample -- and that
     * flag also picks the UI branch, the counter completion is judged against
     * and whether a rep tap is accepted, so one variable answered four
     * questions and was wrong for this one on every timed set (#217).
     */
    private var sensorVoiceRuns = false

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
                // Every decision is [unitChangedState]'s, including the load
                // field's -- the chip is a display action and must not change
                // what the set records (#77).
                stateFlow.value = unitChangedState(stateFlow.value, unit)
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

    // Expression bodies. Both were a `viewModelScope.launch` whose Job nobody
    // read, and both still are; what changed is the declared return type,
    // which no caller uses.
    fun toggleAudioCues() = viewModelScope.launch { container.settings.setAudioCues(!stateFlow.value.audioCues) }

    fun toggleWeightUnit() =
        viewModelScope.launch { container.settings.setWeightUnit(stateFlow.value.weightUnit.other()) }

    fun toggleDemoMode() {
        stateFlow.value = stateFlow.value.copy(demoMode = !stateFlow.value.demoMode)
    }

    /** The START on the preview (#202); [askOrStartSession] decides. */
    fun requestPlanSession(planSession: PlanSessionDef) =
        askOrStartSession(stateFlow, planSession, System.currentTimeMillis(), ::startPlanSession)

    /** A tap on a plan session card: build the queue and SHOW it; [previewState] starts nothing (#202). */
    fun openPreview(planSession: PlanSessionDef) = viewModelScope.launch {
        stateFlow.value = previewState(stateFlow.value, planSession, sessionRepository.flattenPlan(planSession))
    }

    /** The body-weight prompt's answer, null meaning skip; [answerBodyWeight] decides. */
    fun answerBodyWeightPrompt(kg: Double?) =
        viewModelScope.answerBodyWeight(stateFlow, container.settings, kg, ::startPlanSession)

    /** The refused set's answer, null meaning cancel; [answerRefusedSet] decides (#61). */
    fun answerBodyWeightForSet(kg: Double?) =
        viewModelScope.answerRefusedSet(stateFlow, container.settings::setBodyWeightKg, kg, ::beginSet)

    /**
     * Start on the queue the lifter has just READ -- the same list `openPreview`
     * built, never a second flatten of the same plan (#202).
     */
    private fun startPlanSession(planSession: PlanSessionDef) {
        sessionStartedAtMs = openedSessionClocks(sessionRrMs, restHrBuffer)
        stateFlow.value = planSessionState(stateFlow.value, planSession, stateFlow.value.queue)
    }

    /** Equipment busy: every decision is [jumpedToExerciseState]'s; this is the tap. */
    fun jumpToExercise(exerciseId: String) {
        stateFlow.value = jumpedToExerciseState(stateFlow.value, exerciseId) ?: return
    }

    /** One more set of the exercise just finished (#177, #188); every decision is [appendedState]'s. */
    fun addSetOfCurrentExercise() {
        stateFlow.value = appendedState(stateFlow.value) ?: return
    }

    /**
     * Take back a set the lifter appended (#206); every decision is
     * [removedState]'s.
     *
     * Nothing is written to Room here and nothing needs to be: the slot being
     * removed has not run, so no row, no raw stream and no export entry exists
     * for it. A set that HAS run is not reachable from this control at all --
     * `RemoveSetControl.target` refuses every index below `upcomingIndex`.
     */
    fun removeAddedSetOfCurrentExercise() {
        stateFlow.value = removedState(stateFlow.value) ?: return
    }

    fun startAdHocSession() {
        sessionStartedAtMs = openedSessionClocks(sessionRrMs, restHrBuffer)
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

    /**
     * The lifter's statement that the NEXT set works this arm (#215).
     *
     * Deliberately not [selectSide], which writes [RecordState.sideInput] --
     * the ad-hoc selector, read only where there is no plan. This is a
     * deviation from a prescription that exists, it applies to one set, and
     * `restingState` clears it once that set has been written.
     *
     * Stored raw and judged by [SideChoicePolicy] at every reader, rather than
     * validated here: one rule, in the module a test runs in.
     */
    fun stateNextSetSide(side: String?) {
        stateFlow.value = stateFlow.value.copy(statedSide = side)
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
        // Before anything this function arms. NOT before the bake on the
        // rest-screen door: startNextSet has already run advancedState and
        // cancelled the rest clock, so a refusal there is reached with the
        // planned queue already advanced -- or, on an ad-hoc session, with
        // statedLoadKg, statedTempo, statedReps, statedDurationS and
        // statedSide already cleared -- and the rest clock already stopped.
        // Nothing durable is written on either branch. Why it refuses at all
        // is SetLoadPolicy.blocksSetStart's KDoc (#61).
        //
        // Here rather than on the START button, because the button is not the
        // only door: `startNextSet` writes READY and calls this in the same
        // frame for every set after the first, so a gate on the tap would
        // cover set one and nothing else.
        val before = stateFlow.value
        if (SetLoadPolicy.blocksSetStart(before.currentExercise.bodyweight, before.bodyWeightKg)) {
            stateFlow.value = before.copy(bodyWeightRequiredForSet = true)
            return
        }
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
        val roster = s.roster
        armedSensors = SensorCapturePolicy.recorded(roster)
        armedSecondaryRole = roster.secondary
        // The readout starts on the armed unit; nothing has arrived yet.
        liveFedBy = armedSensors?.analysed
        endingSet = false
        plannedRepsForSet =
            if (s.currentIsTimed) {
                null
            } else if (s.adHoc) {
                s.repsInput.toIntOrNull()
            } else {
                s.currentSlot?.reps
            }
        // Never announce reps on timed sets: a carry's gait can trip the rep detector.
        milestones.startSet(announceReps = !s.currentIsTimed, plannedReps = plannedRepsForSet)
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
        // Who speaks while the work is under way, decided in `:core:model` and
        // pinned there. `manualSet` above still answers its other three
        // questions and is deliberately not derived from this: they agreed
        // only by accident, and on a timed set they disagreed (#217).
        sensorVoiceRuns = SetVoicePolicy.sensorCounts(
            hasTempo = guidedTempo != null,
            isTimed = s.currentIsTimed,
            kind = exercise.kind,
            demoMode = s.demoMode,
            imuConnected = s.imuConnected,
        )
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
        workStartedAtMs = null
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
                viewModelScope.openSecondaryCollector(
                    autoConnect.imuSamplesB, imuBufferB, { journal }, role, ::onSecondarySample,
                )
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
        if (LiveFeedPolicy.feedsTracker(liveFeedNow(), armedSensors?.analysed)) feedTracker(sample)
    }

    /** A frame from the unit that is NOT the armed one; see [liveFeedOf] (#210). */
    private fun onSecondarySample(sample: ImuSample) {
        if (LiveFeedPolicy.feedsTracker(liveFeedNow(), armedSecondaryRole ?: return)) feedTracker(sample)
    }

    /** Which stream feeds the readout, and the latch with it: [liveFeedOf]. */
    private fun liveFeedNow(): LiveFeed =
        liveFeedOf(armedSensors, armedSecondaryRole, liveFedBy, imuBuffer.size, imuBufferB.size)
            .also { liveFedBy = it.role }

    /** The body [onSample] carried until #210, now reachable from either collector. */
    private fun feedTracker(sample: ImuSample) {
        val live = tracker?.feed(sample) ?: return
        stateFlow.value = stateFlow.value.copy(live = live)
        // Manual/guided sets: the app (or the lifter) is the counter — the
        // sensor keeps recording for velocity metrics, but its phase counts and
        // rep calls must stay silent or two voices count over each other.
        if (!sensorVoiceRuns || !stateFlow.value.audioCues) return
        milestones.onLive(live.phase, live.currentPhaseElapsedS, live.repCount)
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
        // On a TIMED set this function is reached only from the prep's own
        // callback -- beginSet skips it while a timed start word exists -- so
        // the instant the clock starts is the instant the prep ended, which is
        // what #168 moved the clock here for. One read of the wall clock rather
        // than a second one a statement later, because two reads are two facts
        // that can disagree. On any other case the work start is not this
        // instant and is not written here.
        if (prepCaseForSet == PrepCase.TIMED) workStartedAtMs = clockStartedAtMs
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
        // The runner says when its lead-in ends, which on a cued set is where
        // the work begins: the set's own clock started at the tap and says
        // nothing about it, and the cue track cannot answer it either, because
        // LeadInPlan fixes the launch phrase a prescribed distance from the
        // first stroke call whatever the prep was (#185).
        val onWorkStarted = { workStartedAtMs = System.currentTimeMillis() }
        guidedCadence = newVoiceRunner().also { it.start(schedule, plannedReps, prepS, onWorkStarted) }
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
    // An expression body for the reason toggleAudioCues above is one: detekt
    // counts this class's lines of code against a LargeClass default of 600,
    // so #109's one new argument to closer.close had to be paid for here.
    private fun speakCue(cueText: String, utterance: String = cueText) = speakCues(listOf(cueText), utterance)

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
        if (s.audioCues) milestones.announceRep(count)
    }

    /** Rest-screen correction when the sensor miscounted (or the set was manual). */
    fun overrideLastSetReps(reps: Int) = applyRepCorrection(stateFlow, reps, ratings, container.appScope)

    /**
     * Rest-screen correction of a hold or a carry's recorded seconds (#168):
     * the only way a genuine overage is entered, and it is entered after the
     * set. See [durationCorrectedState] for why it is not offered mid-set, and
     * [overrideLastSetReps] for why this runs on appScope.
     */
    fun addLastSetSeconds(deltaS: Int) = applyDurationCorrection(stateFlow, deltaS, ratings, container.appScope)

    /**
     * Rest-screen correction of the load the just-finished set was recorded at
     * (#205), the one value in a set nothing in the app can observe. See
     * [overrideLastSetReps] for why this runs on appScope, and
     * [applyLoadCorrection] for what it does to the load standing for the set
     * coming up.
     */
    fun addLastSetLoad(deltaKg: Double) = applyLoadCorrection(stateFlow, deltaKg, ratings, container.appScope)

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
        // The set has to SAY it is over, or nothing on the record does. A
        // guided set the lifter ends early never reaches the runner's `Done`,
        // so until #141 its cue track stopped on a stroke and `SetEnd.of`
        // returned NotCued: the rep list was unbounded, `detectionsAfter`
        // reported null rather than a count, and the rest clock had no instant
        // to start from. Spoken as well as written, because the archive is a
        // record of what the app SAID (#176) and because the lifter gets no
        // confirmation the set is over on exactly the sets that ended badly.
        //
        // BEFORE the cue buffer is frozen below, which is the whole of the
        // ordering constraint: `speakCues` appends to `cueBuffer`, and the
        // pending write copies it. The word is chosen by `SetEnd.terminalCall`
        // in :core:dsp -- which sets get one, and whether the record already
        // carries a boundary, are its decisions and not this function's.
        SetEnd.terminalCall(guided = s.guidedSet, spoken = cueBuffer)?.let { speakCues(it.recorded, it.utterance) }
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
        // The slot's FROZEN declaration, never its live `side`, which the bake
        // has already written the lifter's choice into. Comparing those two
        // would compare a value against itself and the export could never show
        // a deviation. An ad-hoc set has no plan to have prescribed one.
        val plannedSide = if (s.adHoc) null else slot?.plannedSide
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
        val tempoText = when {
            isTimed -> null
            s.adHoc -> s.tempoInput.ifBlank { null }
            else -> slot?.tempo
        }
        val manualReps = if (s.manualSet) s.manualReps else null
        // Which buffer the DSP is pointed at, and what the row says about the
        // choice (#207). Frozen here with everything else, from the buffers as
        // they stand at the end of the set.
        // Frozen here with everything else -- see captureAt (#207, #213).
        val capture = s.captureAt(armedSensors, armedSecondaryRole, imuBuffer, imuBufferB, setStartedAtMs, endedAtMs)
        // Where the rest after this set runs from. Asked here, once, and
        // carried on the frozen write: the countdown reads it below and the
        // rest-HR window is seeded from it a few lines down, so the two
        // readers cannot take different instants (#178). The rule and the
        // fallback for a set nothing called over are RestClockPolicy's.
        val restStartedAtMs =
            RestClockPolicy.startedAtMs(
                setOverCueAtMs = (SetEnd.of(cueBuffer.toList()) as? SetEnd.Cued)?.atMs,
                endedAtMs = endedAtMs,
            )
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
                // The term totalKg above actually added, and null where it
                // added none -- on loaded work there is no body in the load
                // path, and on a body-weight set recorded before any body
                // weight was entered totalKg used 0 kg (#61) with nothing
                // observed to record. Absence rather than a 0, which would
                // read as a lifter who weighs nothing.
                bodyWeightKg = s.bodyWeightKg?.takeIf { exercise.bodyweight },
                plannedReps = plannedReps,
                targetReps = targetReps,
                manualReps = manualReps,
                side = side,
                plannedSide = plannedSide,
                tempoText = tempoText,
                plannedDurationS = plannedDurationS,
                targetDurationS = targetDurationS,
                actualDurationS = actualDurationS,
                plannedPrepS = plannedPrepSForSet,
                prepS = prepSForSet,
                // The rule is PrepWindowPolicy's, in a module with tests; this
                // hands it the case and both instants. It refuses rather than
                // inventing a window -- no prep, a prep the set was ended
                // during, and an inverted pair each state nothing.
                prepWindow = PrepWindowPolicy.of(prepCaseForSet, setStartedAtMs, workStartedAtMs),
                // The same two facts, asked the other question (#216): did the
                // work start at all. A set with no prep began at the tap; a
                // prepped set began when :app captured the instant, and did not
                // when it never did.
                workBegan = AbandonedSetPolicy.workBegan(prepCaseForSet, workStartedAtMs),
                startedAtMs = setStartedAtMs,
                endedAtMs = endedAtMs,
                restStartedAtMs = restStartedAtMs,
                orderIdx = s.setsCompleted,
                samples = capture.samples,
                hrSamples = hrBuffer.toList(),
                restHrSamples = restHrBuffer.toList(),
                cues = cueBuffer.toList(),
                repMarks = journal?.repMarks.orEmpty(),
                sensors = capture.sensors,
                // A role and its samples together, so a full stream cannot
                // exist without a label. Non-null with an EMPTY list when the
                // role was armed and its unit produced nothing -- which the
                // repository turns into no row and a declaration that still
                // names the role, the state that makes a flat battery readable
                // afterwards rather than invisible -- and non-null with the
                // rows it did send where a unit delivered too few frames to
                // analyse (#209), which are archived like any other capture.
                secondary = capture.secondary,
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
        // set begins. A window belongs to the set that FOLLOWS it, except the
        // last, which the session close writes onto the set BEFORE it (#109) --
        // and clearing it here rather than at beginSet means a retry replays
        // the frozen copy rather than a buffer that kept filling behind it.
        restHrBuffer.clear()
        // Then seeded with whatever of this set's own capture belongs to the
        // rest rather than to the set, by the same instant the countdown runs
        // from (#178). The samples stay in the set's `hrm` stream as well --
        // this reads the frozen buffer and moves nothing -- so no published
        // figure computed over that stream changes, and a window that begins
        // before the set stopped recording is stated by both documents rather
        // than by neither.
        restHrBuffer += RestClockPolicy.restWindowSeed(hrBuffer.toList(), restStartedAtMs)
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
                    p.samples.size >= SensorCapturePolicy.MIN_ANALYSABLE_FRAMES ->
                        // The set's own cue track, frozen into the pending
                        // write alongside the samples. It carries the set's
                        // terminal cue -- Done where the guide called the
                        // prescription through, Set ended where it did not
                        // -- which is the only thing that says when the app
                        // stopped prescribing, and the stream runs on past it
                        // with the sensor being handled, 4.3 to 13.7 s on the
                        // eleven sets of session 32 that carry both a cue and a
                        // stream. See SetEnd.
                        SetAnalyzer.analyze(
                            p.samples,
                            p.exercise.liftDirection(),
                            p.loadKg,
                            p.targets,
                            cues = p.cues,
                            // The instant the prep ended, so movement made
                            // during the countdown is not a rep of the set
                            // (#245). Already on the pending write, built by
                            // PrepWindowPolicy, and null on every set that has
                            // no window -- WorkStart is where that means
                            // "bound nothing".
                            workStartedAtMs = p.prepWindow?.workStartedAtMs,
                        )
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
                set = completedSetOf(p, analysis, failed, ratings.lifterCalledFailure),
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
        // How much of the rest is left now, against the instant frozen with
        // the write. That instant comes off the set's own frozen cue track --
        // the same terminal stamp SetEnd bounds the rep window at -- and a set
        // nothing called over falls back to the instant the write froze, which
        // is every hold and every set recorded with the voice off. #172. Since
        // #141 a guided set the lifter ended early is no longer in that group:
        // `endSet` speaks `SetEnd.STOPPED` before freezing the buffer, so the
        // stamp exists. It is READ here rather than worked out again, because
        // #178 gave it a second reader in the rest-HR window and two
        // computations of one instant is how they came to disagree.
        val restRemainingS = RestClockPolicy.remainingS(restS, p.restStartedAtMs, System.currentTimeMillis())
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
    fun rateLastSet(rpe: Int?, failed: Boolean) = applyRating(stateFlow, rpe, failed, ratings, container.appScope)

    fun toggleLastSetWarmup() = applyWarmupMark(stateFlow, ratings, container.appScope)

    fun limitLastSet(limiter: SetLimiter?, note: String? = null) =
        applyLimiter(stateFlow, limiter, note, ratings, container.appScope)

    /** One tap of the prep control; the work is in the free [applyPrepAdjustment]. */
    fun adjustPrep(deltaS: Int) = applyPrepAdjustment(stateFlow.value, deltaS, container.appScope, container.settings)

    /** Advance to the next planned set; [startedNextSetState] says whether there is one (#195). */
    fun startNextSet() {
        stateFlow.value = startedNextSetState(stateFlow.value) ?: return
        restJob?.cancel()
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
     * accumulated across READY, IN_SET and RESTING, and each of those windows
     * reaches storage on its own: IN_SET as the set's `hrm` stream, READY and
     * each inter-set rest as the following set's `rest_before_hrm` stream, and
     * the window after the last set as `rest_after_hrm` written by the close
     * below (#109). What is held here and nowhere else is the accumulated
     * list itself and the single `hrvRmssdMs` computed from it. A cancelled
     * close is the difference between the lifter having that number and
     * never having it.
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
            restHrSamples = restHrBuffer.toList(),
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
                // A refusal is a fact about a set that was never started, and
                // this session has no more sets. The dialog draws only on
                // READY -- the stage dispatch sends FINISHED to
                // `FinishedStage`, which never calls `BodyWeightRefusal` --
                // so this clear is defensive: it stops a flag about a set
                // that was never started from outliving the session that
                // refused it (#61).
                bodyWeightRequiredForSet = false,
            )
    }

    /** Back to the session picker; every decision is [previewCancelledState]'s. */
    fun abandonSetup() {
        stateFlow.value = previewCancelledState(stateFlow.value)
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
