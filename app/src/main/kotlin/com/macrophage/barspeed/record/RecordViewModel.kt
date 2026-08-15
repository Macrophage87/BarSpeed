package com.macrophage.barspeed.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.RecordingService
import com.macrophage.barspeed.VoiceCounter
import com.macrophage.barspeed.ble.ConnectionState
import com.macrophage.barspeed.data.CompletedSet
import com.macrophage.barspeed.dsp.LiveSetState
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.SetAnalyzer
import com.macrophage.barspeed.dsp.SetTargets
import com.macrophage.barspeed.dsp.StreamingSetTracker
import com.macrophage.barspeed.dsp.TempoSchedule
import com.macrophage.barspeed.dsp.liftDirection
import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.SetLoadPolicy
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { SETUP, READY, IN_SET, RESTING, FINISHED }

/**
 * How the set felt, tapped at the moment the set ends. The effort grid IS the
 * end-set control — one tap finishes the set and logs the rating, rather than
 * ending the set and then asking on a separate screen.
 */
data class SetRating(val rpe: Int?, val failed: Boolean, val warmup: Boolean)

/** One planned set, flattened from the plan into an ordered queue. */
data class PlannedSlot(
    val exercise: ExerciseDef,
    val setIndexInExercise: Int,
    val setsInExercise: Int,
    val reps: Int?,
    /** Hold/carry seconds for timed sets (plank, farmer's walk); null for rep sets. */
    val durationS: Int? = null,
    val loadKg: Double?,
    val plannedLoadKg: Double?,
    val tempo: String?,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /** Coach/LLM comment on this exercise from the plan, shown with the set. */
    val exerciseNotes: String? = null,
    val targetMeanConVelMps: Double? = null,
    val velocityLossStopPct: Double? = null,
    val restS: Int? = null,
    val isExerciseChange: Boolean = false,
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
) {
    val effectiveReps: Int get() = repsOverride ?: analysis.reps.size
}

/** One pick in the "equipment busy — switch exercise" chooser. */
data class ExerciseChoice(val exerciseId: String, val displayName: String, val setsLeft: Int)

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
    val repsInput: String = "5",
    val durationInput: String = "60",
    /** Ad-hoc unilateral side: null (bilateral), "left", or "right". */
    val sideInput: String? = null,
    val tempoInput: String = "",
    val live: LiveSetState = LiveSetState(),
    /** Sensorless rep set: the lifter taps to count reps. */
    val manualSet: Boolean = false,
    val manualReps: Int = 0,
    /** Active guided-cadence set: the app calls the tempo and counts the reps. */
    val guidedSet: Boolean = false,
    val guidedLabel: String = "",
    val guidedCountdown: Int = 0,
    val guidedPhaseTotal: Int = 1,
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
    val lastSetFailed: Boolean = false,
    val lastSetWarmup: Boolean = false,
    val audioCues: Boolean = true,
    val imuConnected: Boolean = false,
    val imuConnecting: Boolean = false,
    val hrmConnected: Boolean = false,
    val hrmConnecting: Boolean = false,
    val demoMode: Boolean = false,
    val sessionId: Long? = null,
    val setsCompleted: Int = 0,
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Lifter body weight, the base load for pull-ups and dips; null until set. */
    val bodyWeightKg: Double? = null,
) {
    val currentSlot: PlannedSlot? get() = queue.getOrNull(queueIndex)
    val nextSlot: PlannedSlot? get() = queue.getOrNull(queueIndex + 1)

    /** Index of the first not-yet-done slot: during rating/rest the current one is already complete. */
    val upcomingIndex: Int
        get() = if (stage == Stage.RESTING) queueIndex + 1 else queueIndex

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
            currentIsTimed ->
                currentTimedTargetS?.let { setElapsedS >= (it * TIMED_CLOSE_ENOUGH_FRACTION).toInt() } ?: true
            // The guide finishing IS the set being done. Its rep count lands one
            // stroke early, before the closing cue is even spoken, and a guided
            // set given no rep target never finishes on its own at all.
            guidedSet -> guidedFinished || currentTargetReps == null
            manualSet -> currentTargetReps?.let { manualReps >= it } ?: true
            else -> true
        }
}

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val autoConnect = container.autoConnect
    private val sessionRepository = container.sessionRepository

    private val stateFlow = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = stateFlow

    private val imuBuffer = mutableListOf<ImuSample>()
    private val hrBuffer = mutableListOf<HrSample>()

    /** Spoken cues this set, epoch-ms stamped for IMU cross-reference in exports. */
    private val cueBuffer = mutableListOf<VoiceCue>()
    private var tracker: StreamingSetTracker? = null
    private var collectJob: Job? = null
    private var hrJob: Job? = null
    private var tickJob: Job? = null
    private var restJob: Job? = null
    private var demoJob: Job? = null
    private var guidedCadence: GuidedCadenceRunner? = null
    private var setStartedAtMs = 0L
    private val ratings = SetRatingTracker(sessionRepository)

    /**
     * The effort grid is seven separately clickable tiles, and finishing a set
     * takes a few hundred ms (analysis, then gzipping the whole sample stream).
     * Two fingers landing on two tiles would otherwise run endSet twice, which
     * starts two sessions and writes the same set twice.
     */
    private var endingSet = false

    /** All R-R intervals seen during the active session (sets + rests) for session HRV. */
    private val sessionRrMs = mutableListOf<Double>()

    /** Recent beats only, for the live rolling HRV readout. */
    private val recentRrMs = ArrayDeque<Double>()
    private var voice: VoiceCounter? = null
    private var lastCountedPhase: Phase = Phase.IDLE
    private var lastSpokenSecond = 0
    private var lastAnnouncedRep = 0
    private var plannedRepsForSet: Int? = null
    private var announceReps = false

    init {
        viewModelScope.launch {
            autoConnect.imuState.collect { s ->
                stateFlow.value =
                    stateFlow.value.copy(
                        imuConnected = s is ConnectionState.Connected,
                        imuConnecting = s is ConnectionState.Connecting,
                    )
            }
        }
        viewModelScope.launch {
            autoConnect.hrmState.collect { s ->
                stateFlow.value =
                    stateFlow.value.copy(
                        hrmConnected = s is ConnectionState.Connected,
                        hrmConnecting = s is ConnectionState.Connecting,
                    )
            }
        }
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
                if (hr.rrIntervalsMs.isNotEmpty()) {
                    recentRrMs.addAll(hr.rrIntervalsMs)
                    while (recentRrMs.size > ROLLING_HRV_BEATS) recentRrMs.removeFirst()
                    val inSession =
                        stateFlow.value.stage in setOf(Stage.READY, Stage.IN_SET, Stage.RESTING)
                    if (inSession) sessionRrMs += hr.rrIntervalsMs
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
        viewModelScope.launch {
            container.settings.bodyWeightKg.collect { kg ->
                stateFlow.value = stateFlow.value.copy(bodyWeightKg = kg)
            }
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

    /** Start a session following the given plan session. */
    fun startPlanSession(planSession: PlanSessionDef) {
        viewModelScope.launch {
            sessionRrMs.clear()
            val queue = sessionRepository.flattenPlan(planSession)
            stateFlow.value =
                stateFlow.value.copy(
                    stage = Stage.READY,
                    planSessionName = planSession.name,
                    queue = queue,
                    queueIndex = 0,
                    adHoc = false,
                )
        }
    }

    /**
     * Equipment busy: pull the chosen exercise's remaining sets forward so they
     * are done next, keeping everything else in order (deviating set order is fine
     * — recorded sets keep their actual timestamps).
     */
    fun jumpToExercise(exerciseId: String) {
        val s = stateFlow.value
        if (s.adHoc) return
        val done = s.queue.take(s.upcomingIndex)
        val remaining = s.queue.drop(s.upcomingIndex)
        if (remaining.firstOrNull()?.exercise?.id == exerciseId) return
        val (target, others) = remaining.partition { it.exercise.id == exerciseId }
        if (target.isEmpty()) return
        val reordered = target + others
        // Recompute "move the sensor" boundaries for the new order.
        val fixed =
            reordered.mapIndexed { i, slot ->
                val prevId = if (i == 0) done.lastOrNull()?.exercise?.id else reordered[i - 1].exercise.id
                slot.copy(isExerciseChange = prevId != null && prevId != slot.exercise.id)
            }
        // Non-empty because the empty-target case returned above, so there is
        // always a planned next set here. upcomingIndex is queueIndex + 1 from
        // the rest screen and queueIndex from READY: either way this slot has
        // not been through the bake at startNextSet's `loadKg = parseToKg(
        // loadInput) ?: next.loadKg`, so its loadKg is still the plan's
        // declaration rather than a value the app wrote back.
        val upcoming = fixed.first()
        val seedKg =
            SetLoadPolicy.seedAddedKg(
                hasPlannedNext = true,
                nextDeclaredAddedKg = upcoming.loadKg,
                lastAddedKg = null,
            )
        stateFlow.value =
            s.copy(
                queue = done + fixed,
                // Refresh the editable inputs so they describe the new upcoming set.
                loadInput = seedKg?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
                repsInput = upcoming.reps?.toString() ?: s.repsInput,
                durationInput = upcoming.durationS?.toString() ?: s.durationInput,
                tempoInput = upcoming.tempo ?: "",
            )
    }

    fun startAdHocSession() {
        sessionRrMs.clear()
        stateFlow.value = stateFlow.value.copy(stage = Stage.READY, adHoc = true, queue = emptyList())
    }

    fun selectExercise(id: String) {
        stateFlow.value = stateFlow.value.copy(selectedExerciseId = id)
    }

    fun updateLoadInput(text: String) {
        stateFlow.value = stateFlow.value.copy(loadInput = text)
    }

    fun updateRepsInput(text: String) {
        stateFlow.value = stateFlow.value.copy(repsInput = text)
    }

    fun updateDurationInput(text: String) {
        stateFlow.value = stateFlow.value.copy(durationInput = text)
    }

    fun selectSide(side: String?) {
        stateFlow.value = stateFlow.value.copy(sideInput = side)
    }

    fun updateTempoInput(text: String) {
        stateFlow.value = stateFlow.value.copy(tempoInput = text)
    }

    /** Begin recording the current set. */
    fun beginSet() {
        val s = stateFlow.value
        val exercise = currentExercise(s)
        val tracker = StreamingSetTracker(exercise.startsWith, velocityScale = exercise.sensorToLifter)
        this.tracker = tracker
        imuBuffer.clear()
        hrBuffer.clear()
        cueBuffer.clear()
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
        val guidedSet = !s.currentIsTimed && exercise.kind != ExerciseKind.EXPLOSIVE && guidedTempo != null
        if (guidedSet) manualSet = true
        setStartedAtMs = System.currentTimeMillis()
        RecordingService.start(getApplication())

        val timedTargetS = s.currentTimedTargetS
        collectJob =
            viewModelScope.launch {
                autoConnect.imuSamples.collect { sample -> onSample(sample) }
            }
        if (s.demoMode && !s.currentIsTimed) startDemoStream(s, exercise)
        hrJob =
            viewModelScope.launch {
                autoConnect.hrSamples.collect { hr ->
                    hrBuffer += hr
                    stateFlow.value = stateFlow.value.copy(hrBpm = hr.bpm)
                }
            }
        tickJob =
            viewModelScope.launch {
                var seconds = 0
                while (true) {
                    delay(1_000)
                    seconds++
                    stateFlow.value = stateFlow.value.copy(setElapsedS = seconds)
                    if (timedTargetS != null && stateFlow.value.audioCues) {
                        // Long holds/carries: milestone every 15 s remaining
                        // ("45 seconds"), then each second from 10 down.
                        val remaining = timedTargetS - seconds
                        when {
                            remaining == 0 -> speakCue("Time")
                            remaining in 1..TIMED_FINAL_COUNTDOWN_FROM_S -> speakCue(remaining.toString())
                            remaining > 0 && remaining % TIMED_MILESTONE_EVERY_S == 0 ->
                                speakCue("$remaining seconds")
                        }
                    }
                }
            }
        stateFlow.value =
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
            )
        if (guidedSet && guidedTempo != null) {
            startGuidedCadence(TempoSchedule.of(guidedTempo, exercise.liftDirection()), plannedRepsForSet)
        }
    }

    private fun onSample(sample: ImuSample) {
        imuBuffer += sample
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

    /** See [GuidedCadenceRunner]; the runner speaks and counts, the VM just mirrors state. */
    private fun startGuidedCadence(schedule: TempoSchedule, plannedReps: Int?) {
        if (voice == null) voice = VoiceCounter(getApplication())
        guidedCadence =
            GuidedCadenceRunner(
                scope = viewModelScope,
                speak = ::speakCue,
                update = { label, remaining, total ->
                    stateFlow.value =
                        stateFlow.value.copy(
                            guidedLabel = label,
                            guidedCountdown = remaining,
                            guidedPhaseTotal = total,
                        )
                },
                onRepCounted = { rep -> stateFlow.value = stateFlow.value.copy(manualReps = rep) },
                onFinished = { stateFlow.value = stateFlow.value.copy(guidedFinished = true) },
            ).also { it.start(schedule, plannedReps) }
    }

    /** Speak an in-set cue and log it on the sample clock (see VoiceCue). */
    private fun speakCue(text: String) {
        cueBuffer += VoiceCue(System.currentTimeMillis(), text)
        voice?.speak(text)
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
        stateFlow.value = s.copy(manualReps = count)
        announceRepMilestones(count)
    }

    /** Rest-screen correction when the sensor miscounted (or the set was manual). */
    fun overrideLastSetReps(reps: Int) {
        if (reps < 0) return
        viewModelScope.launch {
            val s = stateFlow.value
            val failed = ratings.correctReps(reps, rpe = s.lastSetRpe, warmup = s.lastSetWarmup) ?: return@launch
            stateFlow.value =
                stateFlow.value.copy(
                    lastFeedback = stateFlow.value.lastFeedback?.copy(repsOverride = reps),
                    lastSetFailed = failed,
                )
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

    /** Finish the set: analyze, persist, and enter the rest screen (spec 4.1). */
    /**
     * Finish the set, logging [rating] as part of the same tap. Analyze, persist,
     * and go straight to rest.
     */
    fun endSet(rating: SetRating? = null) {
        if (endingSet) return
        endingSet = true
        collectJob?.cancel()
        hrJob?.cancel()
        tickJob?.cancel()
        demoJob?.cancel()
        guidedCadence?.cancel()
        val s = stateFlow.value
        val exercise = currentExercise(s)
        val slot = s.currentSlot
        val isTimed = s.currentIsTimed
        val addedKg =
            SetLoadPolicy.resolve(s.adHoc, slot?.loadKg, s.weightUnit.parseToKg(s.loadInput))
        // Pull-ups and dips move the lifter: the plan's number is what was ADDED
        // (negative when a band or machine assists), so the load that actually
        // travelled is body weight plus that.
        val loadKg = if (exercise.bodyweight) (s.bodyWeightKg ?: 0.0) + addedKg else addedKg
        val plannedReps = if (s.adHoc) s.repsInput.toIntOrNull() else slot?.reps
        val side = if (s.adHoc) s.sideInput else slot?.side
        val plannedDurationS = if (isTimed) s.currentTimedTargetS else null
        val actualDurationS =
            if (isTimed) ((System.currentTimeMillis() - setStartedAtMs) / 1000L).toInt() else null
        val tempoText =
            when {
                isTimed -> null
                s.adHoc -> s.tempoInput.ifBlank { null }
                else -> slot?.tempo
            }
        val samples = imuBuffer.toList()
        val hrSamples = hrBuffer.toList()
        val cues = cueBuffer.toList()

        viewModelScope.launch {
            val targets =
                SetTargets(
                    plannedReps = plannedReps,
                    countedReps = if (s.manualSet) s.manualReps else null,
                    tempo = tempoText?.let { Tempo.parseOrNull(it) },
                    targetMeanConcentricVelocityMps = slot?.targetMeanConVelMps,
                    velocityLossStopPct = slot?.velocityLossStopPct,
                )
            val manualReps = if (s.manualSet) s.manualReps else null
            // Sensor data is analyzed even on manually-counted sets — the manual
            // count overrides the rep COUNT, but velocity/power metrics still come
            // from the bar sensor when it was recording.
            val analysis =
                withContext(Dispatchers.Default) {
                    when {
                        isTimed -> SetAnalysis(
                            emptyList(),
                            0.0,
                            null,
                            null,
                            timedVerdicts(actualDurationS, plannedDurationS),
                        )
                        samples.size >= 8 ->
                            SetAnalyzer.analyze(samples, exercise.liftDirection(), loadKg, targets)
                        manualReps != null ->
                            SetAnalysis(emptyList(), 0.0, null, null, listOf("Reps counted manually — no bar sensor."))
                        else -> SetAnalysis(emptyList(), 0.0, null, null, listOf("No sensor data recorded."))
                    }
                }
            val sessionId =
                stateFlow.value.sessionId ?: sessionRepository.startSession(
                    planName = stateFlow.value.planName.takeIf { !stateFlow.value.adHoc },
                    planSessionName = stateFlow.value.planSessionName.takeIf { !stateFlow.value.adHoc },
                    startedAtMs = setStartedAtMs,
                ).also { stateFlow.value = stateFlow.value.copy(sessionId = it) }

            sessionRepository.ensureExerciseExists(exercise.id)
            val setId = sessionRepository.recordSet(
                sessionId = sessionId,
                orderIdx = stateFlow.value.setsCompleted,
                set =
                CompletedSet(
                    exerciseId = exercise.id,
                    exerciseName = exercise.displayName,
                    loadKg = loadKg,
                    plannedLoadKg = slot?.plannedLoadKg,
                    plannedReps = plannedReps,
                    manualReps = manualReps,
                    actualDurationS = actualDurationS,
                    plannedDurationS = plannedDurationS,
                    side = side,
                    tempo = tempoText,
                    targetMeanConVelMps = slot?.targetMeanConVelMps,
                    velocityLossStopPct = slot?.velocityLossStopPct,
                    plannedRestS = slot?.restS,
                    startedAtMs = setStartedAtMs,
                    endedAtMs = System.currentTimeMillis(),
                    analysis = analysis,
                    imuSamples = samples,
                    hrSamples = hrSamples,
                    voiceCues = cues,
                ),
            )

            // Stopped early = failed. Judged only where the count is trustworthy:
            // timed sets against the clock, manual/guided sets against the app's
            // own rep count — never against a possibly-miscounted sensor total.
            val stoppedEarly =
                when {
                    isTimed && plannedDurationS != null ->
                        (actualDurationS ?: 0) < (plannedDurationS * TIMED_CLOSE_ENOUGH_FRACTION).toInt()
                    manualReps != null && plannedReps != null -> manualReps < plannedReps
                    else -> false
                }
            // The lifter's tap is authoritative for effort, but a set that ended
            // short of its target is still a failed set — both facts are recorded.
            val failed = ratings.onSetRecorded(setId, plannedReps, stoppedEarly, rating)

            val restS = slot?.restS ?: DEFAULT_REST_S
            // queue[queueIndex + 1] has not been through startNextSet's bake
            // yet, so its loadKg is still the plan's declaration. That is the
            // field to seed from, not plannedLoadKg: plannedLoadKg is frozen at
            // the plan's number and would discard an in-rest edit made to a set
            // that then got postponed. Nothing else pins that ordering, and the
            // seam's unit tests cannot see it break.
            //
            // The write half stays outside the seam: startNextSet does
            // `loadKg = parseToKg(loadInput) ?: next.loadKg` on this same slot
            // when the lifter taps through, so the string seeded below is read
            // straight back as a declaration one set later. Nothing is lost
            // there today, because 0 round-trips exactly through inputValue and
            // parseToKg in both display units — but nothing pins that either.
            val nextSlot = stateFlow.value.nextSlot
            val seedKg =
                SetLoadPolicy.seedAddedKg(
                    hasPlannedNext = nextSlot != null,
                    nextDeclaredAddedKg = nextSlot?.loadKg,
                    // addedKg, never loadKg: the field holds what is ADDED, and
                    // seeding it with the body-weight-inclusive total is what
                    // made a loadless block climb set over set.
                    lastAddedKg = addedKg,
                )
            stateFlow.value =
                stateFlow.value.copy(
                    stage = Stage.RESTING,
                    restTotalS = restS,
                    lastFeedback =
                    SetFeedback(
                        exerciseName = exercise.displayName,
                        loadKg = loadKg,
                        analysis = analysis,
                        plannedReps = plannedReps,
                        tempo = tempoText,
                        actualDurationS = actualDurationS,
                        plannedDurationS = plannedDurationS,
                        side = side,
                        explosive = exercise.kind == ExerciseKind.EXPLOSIVE,
                        repsOverride = manualReps,
                    ),
                    lastSetRpe = rating?.rpe,
                    lastSetFailed = failed,
                    lastSetWarmup = rating?.warmup ?: false,
                    restRemainingS = restS,
                    setsCompleted = stateFlow.value.setsCompleted + 1,
                    // Pre-fill next-set inputs so in-rest edits start from plan values.
                    loadInput =
                    seedKg?.let { stateFlow.value.weightUnit.inputValue(it) } ?: stateFlow.value.loadInput,
                    repsInput = (stateFlow.value.nextSlot?.reps ?: plannedReps ?: 5).toString(),
                    durationInput =
                    (stateFlow.value.nextSlot?.durationS ?: plannedDurationS)?.toString()
                        ?: stateFlow.value.durationInput,
                    tempoInput = stateFlow.value.nextSlot?.tempo ?: tempoText ?: "",
                )
            startRestCountdown()
        }
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
    fun rateLastSet(rpe: Int?, failed: Boolean, warmup: Boolean) {
        viewModelScope.launch {
            val effectiveFailed = ratings.rate(rpe, failed, warmup) ?: return@launch
            stateFlow.value =
                stateFlow.value.copy(lastSetRpe = rpe, lastSetFailed = effectiveFailed, lastSetWarmup = warmup)
        }
    }

    /** Advance to the next planned set, applying any in-rest load/rep edits. */
    fun startNextSet() {
        restJob?.cancel()
        val s = stateFlow.value
        if (!s.adHoc && s.nextSlot != null) {
            val next = s.nextSlot!!
            val edited =
                next.copy(
                    loadKg = s.weightUnit.parseToKg(s.loadInput) ?: next.loadKg,
                    reps = if (next.isTimed) next.reps else s.repsInput.toIntOrNull() ?: next.reps,
                    durationS = if (next.isTimed) s.durationInput.toIntOrNull() ?: next.durationS else next.durationS,
                    tempo = s.tempoInput.ifBlank { null } ?: next.tempo,
                )
            val queue = s.queue.toMutableList()
            queue[s.queueIndex + 1] = edited
            stateFlow.value = s.copy(queue = queue, queueIndex = s.queueIndex + 1, stage = Stage.READY)
        } else {
            stateFlow.value = s.copy(stage = Stage.READY)
        }
        beginSet()
    }

    fun finishSession() {
        restJob?.cancel()
        viewModelScope.launch {
            stateFlow.value.sessionId?.let {
                sessionRepository.endSession(it, System.currentTimeMillis(), Hrv.rmssdMs(sessionRrMs))
            }
            RecordingService.stop(getApplication())
            stateFlow.value = stateFlow.value.copy(stage = Stage.FINISHED)
        }
    }

    fun abandonSetup() {
        stateFlow.value = stateFlow.value.copy(stage = Stage.SETUP, queue = emptyList(), queueIndex = 0)
    }

    private fun currentExercise(s: RecordState): ExerciseDef = s.currentSlot?.exercise
        ?: ExerciseDef.seedById(s.selectedExerciseId)
        ?: ExerciseDef(s.selectedExerciseId, s.selectedExerciseId)

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

    override fun onCleared() {
        voice?.shutdown()
        voice = null
        super.onCleared()
    }

    companion object {
        const val DEFAULT_REST_S = 150
        const val REST_COUNTDOWN_FROM_S = 3
        const val TIMED_FINAL_COUNTDOWN_FROM_S = 10
        const val TIMED_MILESTONE_EVERY_S = 15

        /** Reps synthesized for a demo set when nothing planned one. */
        const val DEMO_REPS = 5

        /** ~2 minutes of beats at typical training heart rates. */
        const val ROLLING_HRV_BEATS = 150
    }
}
