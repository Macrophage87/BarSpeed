package com.macrophage.accelerometerlifting.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.accelerometerlifting.LiftingApp
import com.macrophage.accelerometerlifting.RecordingService
import com.macrophage.accelerometerlifting.ble.ConnectionState
import com.macrophage.accelerometerlifting.data.CompletedSet
import com.macrophage.accelerometerlifting.dsp.LiveSetState
import com.macrophage.accelerometerlifting.dsp.SetAnalysis
import com.macrophage.accelerometerlifting.dsp.SetAnalyzer
import com.macrophage.accelerometerlifting.dsp.SetTargets
import com.macrophage.accelerometerlifting.dsp.StreamingSetTracker
import com.macrophage.accelerometerlifting.dsp.SyntheticSets
import com.macrophage.accelerometerlifting.model.ExerciseDef
import com.macrophage.accelerometerlifting.model.HrSample
import com.macrophage.accelerometerlifting.model.ImuSample
import com.macrophage.accelerometerlifting.model.PlanSessionDef
import com.macrophage.accelerometerlifting.model.StartPhase
import com.macrophage.accelerometerlifting.model.Tempo
import com.macrophage.accelerometerlifting.model.WeightUnit
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
    val loadKg: Double?,
    val plannedLoadKg: Double?,
    val tempo: String?,
    val targetMeanConVelMps: Double? = null,
    val velocityLossStopPct: Double? = null,
    val restS: Int? = null,
    val isExerciseChange: Boolean = false,
)

data class SetFeedback(
    val exerciseName: String,
    val loadKg: Double,
    val analysis: SetAnalysis,
    val plannedReps: Int?,
    val tempo: String?,
)

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
    val tempoInput: String = "",
    val live: LiveSetState = LiveSetState(),
    val setElapsedS: Int = 0,
    val hrBpm: Int? = null,
    val lastFeedback: SetFeedback? = null,
    val restRemainingS: Int = 0,
    val imuConnected: Boolean = false,
    val hrmConnected: Boolean = false,
    val demoMode: Boolean = false,
    val sessionId: Long? = null,
    val setsCompleted: Int = 0,
    val weightUnit: WeightUnit = WeightUnit.KG,
) {
    val currentSlot: PlannedSlot? get() = queue.getOrNull(queueIndex)
    val nextSlot: PlannedSlot? get() = queue.getOrNull(queueIndex + 1)
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
        // Passive HR display even outside sets.
        viewModelScope.launch {
            autoConnect.hrSamples.collect { hr ->
                stateFlow.value = stateFlow.value.copy(hrBpm = hr.bpm)
            }
        }
        viewModelScope.launch {
            container.settings.weightUnit.collect { unit ->
                stateFlow.value = stateFlow.value.copy(weightUnit = unit)
            }
        }
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

    fun startAdHocSession() {
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
        setStartedAtMs = System.currentTimeMillis()
        RecordingService.start(getApplication())

        collectJob =
            viewModelScope.launch {
                autoConnect.imuSamples.collect { sample -> onSample(sample) }
            }
        if (s.demoMode) startDemoStream(s, exercise)
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
                }
            }
        stateFlow.value = s.copy(stage = Stage.IN_SET, setElapsedS = 0, live = LiveSetState())
    }

    private fun onSample(sample: ImuSample) {
        imuBuffer += sample
        val live = tracker?.feed(sample) ?: return
        stateFlow.value = stateFlow.value.copy(live = live)
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
        val loadKg =
            if (s.adHoc || slot?.loadKg == null) s.weightUnit.parseToKg(s.loadInput) ?: 0.0 else slot.loadKg
        val plannedReps = if (s.adHoc) s.repsInput.toIntOrNull() else slot?.reps
        val tempoText = if (s.adHoc) s.tempoInput.ifBlank { null } else slot?.tempo
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
                    if (samples.size >= 8) {
                        SetAnalyzer.analyze(samples, exercise.startsWith, loadKg, targets)
                    } else {
                        SetAnalysis(emptyList(), 0.0, null, null, listOf("No sensor data recorded."))
                    }
                }
            val sessionId =
                stateFlow.value.sessionId ?: sessionRepository.startSession(
                    planName = stateFlow.value.planName.takeIf { !stateFlow.value.adHoc },
                    planSessionName = stateFlow.value.planSessionName.takeIf { !stateFlow.value.adHoc },
                    startedAtMs = setStartedAtMs,
                ).also { stateFlow.value = stateFlow.value.copy(sessionId = it) }

            sessionRepository.ensureExerciseExists(exercise.id)
            sessionRepository.recordSet(
                sessionId = sessionId,
                orderIdx = stateFlow.value.setsCompleted,
                set =
                    CompletedSet(
                        exerciseId = exercise.id,
                        exerciseName = exercise.displayName,
                        loadKg = loadKg,
                        plannedLoadKg = slot?.plannedLoadKg,
                        plannedReps = plannedReps,
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
                    lastFeedback =
                        SetFeedback(
                            exerciseName = exercise.displayName,
                            loadKg = loadKg,
                            analysis = analysis,
                            plannedReps = plannedReps,
                            tempo = tempoText,
                        ),
                    restRemainingS = restS,
                    setsCompleted = stateFlow.value.setsCompleted + 1,
                    // Pre-fill next-set inputs so in-rest edits start from plan values.
                    loadInput = stateFlow.value.weightUnit.inputValue(stateFlow.value.nextSlot?.loadKg ?: loadKg),
                    repsInput = (stateFlow.value.nextSlot?.reps ?: plannedReps ?: 5).toString(),
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
                    stateFlow.value = stateFlow.value.copy(restRemainingS = stateFlow.value.restRemainingS - 1)
                }
            }
    }

    /** Advance to the next planned set, applying any in-rest load/rep edits. */
    fun startNextSet() {
        restJob?.cancel()
        val s = stateFlow.value
        if (!s.adHoc && s.nextSlot != null) {
            val edited =
                s.nextSlot!!.copy(
                    loadKg = s.weightUnit.parseToKg(s.loadInput) ?: s.nextSlot!!.loadKg,
                    reps = s.repsInput.toIntOrNull() ?: s.nextSlot!!.reps,
                    tempo = s.tempoInput.ifBlank { null } ?: s.nextSlot!!.tempo,
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
            stateFlow.value.sessionId?.let { sessionRepository.endSession(it, System.currentTimeMillis()) }
            RecordingService.stop(getApplication())
            stateFlow.value = stateFlow.value.copy(stage = Stage.FINISHED)
        }
    }

    fun abandonSetup() {
        stateFlow.value = stateFlow.value.copy(stage = Stage.SETUP, queue = emptyList(), queueIndex = 0)
    }

    private fun currentExercise(s: RecordState): ExerciseDef =
        s.currentSlot?.exercise
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
                        loadKg = set.resolvedLoadKg,
                        plannedLoadKg = set.resolvedLoadKg,
                        tempo = set.tempo,
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

    companion object {
        const val DEFAULT_REST_S = 150
    }
}
