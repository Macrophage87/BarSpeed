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
import com.macrophage.barspeed.dsp.SyntheticSets
import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.PlanSessionDef
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { SETUP, READY, IN_SET, RESTING, FINISHED }

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
    val isTimed: Boolean get() = durationS != null || exercise.isTimed
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
)

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
    val audioCues: Boolean = false,
    val imuConnected: Boolean = false,
    val hrmConnected: Boolean = false,
    val demoMode: Boolean = false,
    val sessionId: Long? = null,
    val setsCompleted: Int = 0,
    val weightUnit: WeightUnit = WeightUnit.KG,
) {
    val currentSlot: PlannedSlot? get() = queue.getOrNull(queueIndex)
    val nextSlot: PlannedSlot? get() = queue.getOrNull(queueIndex + 1)

    /** Index of the first not-yet-done slot: during rest the current one is already complete. */
    val upcomingIndex: Int get() = if (stage == Stage.RESTING) queueIndex + 1 else queueIndex

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
}

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val autoConnect = container.autoConnect
    private val sessionRepository = container.sessionRepository

    private val stateFlow = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = stateFlow

    private val imuBuffer = mutableListOf<ImuSample>()
    private val hrBuffer = mutableListOf<HrSample>()
    private var tracker: StreamingSetTracker? = null
    private var collectJob: Job? = null
    private var hrJob: Job? = null
    private var tickJob: Job? = null
    private var restJob: Job? = null
    private var demoJob: Job? = null
    private var setStartedAtMs = 0L
    private var lastRecordedSetId: Long? = null

    /** All R-R intervals seen during the active session (sets + rests) for session HRV. */
    private val sessionRrMs = mutableListOf<Double>()

    /** Recent beats only, for the live rolling HRV readout. */
    private val recentRrMs = ArrayDeque<Double>()
    private var voice: VoiceCounter? = null
    private var lastCountedPhase: Phase = Phase.IDLE
    private var lastSpokenSecond = 0
    private var lastAnnouncedRep = 0
    private var plannedRepsForSet: Int? = null

    init {
        viewModelScope.launch {
            autoConnect.imuState.collect { s ->
                stateFlow.value = stateFlow.value.copy(imuConnected = s is ConnectionState.Connected)
            }
        }
        viewModelScope.launch {
            autoConnect.hrmState.collect { s ->
                stateFlow.value = stateFlow.value.copy(hrmConnected = s is ConnectionState.Connected)
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
                    val inSession = stateFlow.value.stage in setOf(Stage.READY, Stage.IN_SET, Stage.RESTING)
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
            val queue = flattenPlan(planSession)
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
        val upcoming = fixed.first()
        stateFlow.value =
            s.copy(
                queue = done + fixed,
                // Refresh the editable inputs so they describe the new upcoming set.
                loadInput = upcoming.loadKg?.let { s.weightUnit.inputValue(it) } ?: s.loadInput,
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
        val tracker = StreamingSetTracker(exercise.startsWith)
        this.tracker = tracker
        imuBuffer.clear()
        hrBuffer.clear()
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
                        when (timedTargetS - seconds) {
                            in 1..REST_COUNTDOWN_FROM_S -> voice?.speak((timedTargetS - seconds).toString())
                            0 -> voice?.speak("Time")
                        }
                    }
                }
            }
        stateFlow.value = s.copy(stage = Stage.IN_SET, setElapsedS = 0, live = LiveSetState())
    }

    private fun onSample(sample: ImuSample) {
        imuBuffer += sample
        val live = tracker?.feed(sample) ?: return
        stateFlow.value = stateFlow.value.copy(live = live)
        countPhaseSeconds(live.phase, live.currentPhaseElapsedS)
        announceRepMilestones(live.repCount)
    }

    /** Voice: "last rep" going into the final planned rep, "done" when the count is hit. */
    private fun announceRepMilestones(repCount: Int) {
        val planned = plannedRepsForSet ?: return
        if (!stateFlow.value.audioCues || repCount == lastAnnouncedRep) return
        lastAnnouncedRep = repCount
        when (repCount) {
            planned - 1 -> if (planned > 1) voice?.speak("Last rep")
            planned -> voice?.speak("Done")
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
            voice?.speak(second.toString())
        }
    }

    /** Finish the set: analyze, persist, and enter the rest screen (spec 4.1). */
    fun endSet() {
        collectJob?.cancel()
        hrJob?.cancel()
        tickJob?.cancel()
        demoJob?.cancel()
        val s = stateFlow.value
        val exercise = currentExercise(s)
        val slot = s.currentSlot
        val isTimed = s.currentIsTimed
        val loadKg =
            if (s.adHoc || slot?.loadKg == null) s.weightUnit.parseToKg(s.loadInput) ?: 0.0 else slot.loadKg
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

        viewModelScope.launch {
            val targets =
                SetTargets(
                    plannedReps = plannedReps,
                    tempo = tempoText?.let { Tempo.parseOrNull(it) },
                    targetMeanConcentricVelocityMps = slot?.targetMeanConVelMps,
                    velocityLossStopPct = slot?.velocityLossStopPct,
                )
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
                        samples.size >= 8 -> SetAnalyzer.analyze(samples, exercise.startsWith, loadKg, targets)
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
            lastRecordedSetId = sessionRepository.recordSet(
                sessionId = sessionId,
                orderIdx = stateFlow.value.setsCompleted,
                set =
                CompletedSet(
                    exerciseId = exercise.id,
                    exerciseName = exercise.displayName,
                    loadKg = loadKg,
                    plannedLoadKg = slot?.plannedLoadKg,
                    plannedReps = plannedReps,
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
                ),
            )

            val restS = slot?.restS ?: DEFAULT_REST_S
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
                    ),
                    lastSetRpe = null,
                    lastSetFailed = false,
                    lastSetWarmup = false,
                    restRemainingS = restS,
                    setsCompleted = stateFlow.value.setsCompleted + 1,
                    // Pre-fill next-set inputs so in-rest edits start from plan values.
                    loadInput = stateFlow.value.weightUnit.inputValue(stateFlow.value.nextSlot?.loadKg ?: loadKg),
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
     * Rest-screen RPE tap: saves the rating on the just-finished set, then —
     * when another set is queued (or ad-hoc) — immediately starts the next set.
     */
    fun rateLastSetAndContinue(rpe: Int?, failed: Boolean, warmup: Boolean) {
        val setId = lastRecordedSetId ?: return
        viewModelScope.launch {
            sessionRepository.rateSet(setId, rpe, failed, warmup)
            stateFlow.value =
                stateFlow.value.copy(lastSetRpe = rpe, lastSetFailed = failed, lastSetWarmup = warmup)
            if (stateFlow.value.adHoc || stateFlow.value.nextSlot != null) startNextSet()
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

    private suspend fun flattenPlan(planSession: PlanSessionDef): List<PlannedSlot> {
        val slots = mutableListOf<PlannedSlot>()
        for ((exerciseIdx, exerciseDef) in planSession.exercises.withIndex()) {
            val exercise = sessionRepository.exerciseById(exerciseDef.exercise)
            exerciseDef.sets.forEachIndexed { setIdx, set ->
                slots +=
                    PlannedSlot(
                        exercise = exercise,
                        setIndexInExercise = setIdx,
                        setsInExercise = exerciseDef.sets.size,
                        reps = set.reps,
                        durationS = set.durationS,
                        loadKg = set.resolvedLoadKg,
                        plannedLoadKg = set.resolvedLoadKg,
                        tempo = set.tempo,
                        side = set.side,
                        exerciseNotes = exerciseDef.notes,
                        targetMeanConVelMps = set.targetMeanConcentricVelocityMps,
                        velocityLossStopPct = set.velocityLossStopPct,
                        restS = set.restS,
                        isExerciseChange = setIdx == 0 && exerciseIdx > 0,
                    )
            }
        }
        return slots
    }

    /** Demo/replay mode (spec 5): synthesizes a realistic set through the full pipeline. */
    private fun startDemoStream(s: RecordState, exercise: ExerciseDef) {
        val slot = s.currentSlot
        val reps = (if (s.adHoc) s.repsInput.toIntOrNull() else slot?.reps) ?: 5
        val tempo = (if (s.adHoc) s.tempoInput else slot?.tempo)?.let { Tempo.parseOrNull(it) }
        demoJob =
            viewModelScope.launch(Dispatchers.Default) {
                delay(1_500)
                val spec =
                    SyntheticSets.RepSpec(
                        eccS = tempo?.eccentricS?.coerceAtLeast(0.5) ?: 2.0,
                        bottomPauseS = (tempo?.bottomPauseS ?: 0.3).coerceAtLeast(0.3),
                        conS = tempo?.concentricS ?: 1.0,
                        topPauseS = (tempo?.topPauseS ?: 1.0).coerceAtLeast(0.8),
                        romM = 0.55,
                    )
                val samples =
                    SyntheticSets.generate(
                        List(reps) { spec },
                        eccentricFirst = exercise.startsWith == StartPhase.ECCENTRIC,
                    )
                val epoch = System.currentTimeMillis()
                for (sample in samples) {
                    onSample(sample.copy(timestampMs = epoch + sample.timestampMs))
                    delay(5)
                }
            }
    }

    private fun timedVerdicts(actualS: Int?, plannedS: Int?): List<String> {
        if (actualS == null) return emptyList()
        return when {
            plannedS == null -> listOf("Held ${actualS}s.")
            actualS >= plannedS -> listOf("Held ${actualS}s — full ${plannedS}s target. Nice.")
            actualS >= (plannedS * TIMED_CLOSE_ENOUGH_FRACTION).toInt() ->
                listOf("Held ${actualS}s of ${plannedS}s — just short.")
            else -> listOf("Held ${actualS}s of ${plannedS}s. Consider a shorter target or lighter load.")
        }
    }

    override fun onCleared() {
        voice?.shutdown()
        voice = null
        super.onCleared()
    }

    companion object {
        const val DEFAULT_REST_S = 150
        const val REST_COUNTDOWN_FROM_S = 3
        const val TIMED_CLOSE_ENOUGH_FRACTION = 0.9

        /** ~2 minutes of beats at typical training heart rates. */
        const val ROLLING_HRV_BEATS = 150
    }
}
