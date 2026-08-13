package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SessionEntity
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** One history row: session summary plus a per-set mean-velocity sparkline. */
data class HistoryRow(
    val session: SessionEntity,
    val setCount: Int,
    val sparkline: List<Double>,
)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class HomeState(
    val planName: String? = null,
    val planSessionCount: Int = 0,
    val planSetCount: Int = 0,
    val planExerciseCount: Int = 0,
    val weekVolumeKg: Double = 0.0,
    val weekSessions: Int = 0,
    val history: List<HistoryRow> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Base load for bodyweight work (pull-ups, dips); null until set. */
    val bodyWeightKg: Double? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val sessionRepository = container.sessionRepository
    private val planRepository = container.planRepository

    val imuState = container.autoConnect.imuState
    val hrmState = container.autoConnect.hrmState

    val state =
        combine(
            sessionRepository.sessions,
            planRepository.activePlan,
            container.settings.weightUnit,
            container.settings.bodyWeightKg,
        ) { sessions, planEntity, unit, bodyWeight -> Quad(sessions, planEntity, unit, bodyWeight) }
            .mapLatest { inputs ->
                withContext(Dispatchers.Default) {
                    buildState(inputs.first, inputs.second, inputs.third, inputs.fourth)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setBodyWeight(kg: Double) {
        viewModelScope.launch { container.settings.setBodyWeightKg(kg) }
    }

    fun toggleWeightUnit() {
        viewModelScope.launch {
            container.settings.setWeightUnit(state.value.weightUnit.other())
        }
    }

    private suspend fun buildState(
        sessions: List<SessionEntity>,
        planEntity: com.macrophage.barspeed.data.PlanEntity?,
        unit: WeightUnit,
        bodyWeightKg: Double?,
    ): HomeState {
        val plan = planEntity?.let { planRepository.decode(it) }
        val weekStartMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DAYS_PER_WEEK)

        var weekVolumeKg = 0.0
        var weekSessions = 0
        val history =
            sessions.take(HISTORY_LIMIT).map { session ->
                val sets = sessionRepository.sets(session.id)
                if (session.startedAtMs >= weekStartMs) {
                    weekSessions++
                    weekVolumeKg += sets.sumOf { it.loadKg * it.actualReps }
                }
                val spark =
                    sets.mapNotNull { set ->
                        sessionRepository.decodeAnalysis(set)
                            ?.reps
                            ?.takeIf { it.isNotEmpty() }
                            ?.map { rep -> rep.meanConVelMps }
                            ?.average()
                            ?.takeIf { it.isFinite() }
                    }
                HistoryRow(session, sets.size, spark)
            }

        return HomeState(
            planName = plan?.planName,
            planSessionCount = plan?.sessions?.size ?: 0,
            planSetCount = plan?.sessions?.sumOf { s -> s.exercises.sumOf { it.sets.size } } ?: 0,
            planExerciseCount = plan?.sessions?.flatMap { s -> s.exercises.map { it.exercise } }?.distinct()?.size ?: 0,
            weekVolumeKg = weekVolumeKg,
            weekSessions = weekSessions,
            history = history,
            weightUnit = unit,
            bodyWeightKg = bodyWeightKg,
        )
    }

    companion object {
        const val HISTORY_LIMIT = 20
        const val DAYS_PER_WEEK = 7L
    }
}
