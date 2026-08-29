package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.data.PlanImportResult
import com.macrophage.barspeed.model.PlanLifecycle
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.model.PlanStartPolicy
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row of the plans list: the stored plan, and what a START control on it
 * would do.
 *
 * The decision is carried on the row rather than computed while drawing,
 * because computing it means decoding the plan JSON, and a decode per plan per
 * recomposition is work on the frame thread for an answer that only changes
 * when the list does.
 */
data class PlanRow(val entity: PlanEntity, val start: PlanStartDecision)

class PlansViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.planRepository

    /**
     * Every stored plan with its start decision, decoded off the main thread.
     *
     * The active plan's NAME comes from the entity column rather than from a
     * second decode: `importPlan` writes `name = plan.planName`, so the column
     * is that name already. A plan is never told it displaces itself — the
     * active row's own decision is computed with a null displaced name, and
     * `PlanStartPolicy` returns no prompt for it anyway.
     */
    val planRows: Flow<List<PlanRow>> =
        repository.allPlans
            .map { plans ->
                val activeName = plans.firstOrNull { it.status == PlanEntity.STATUS_ACTIVE }?.name
                plans.map { entity ->
                    PlanRow(
                        entity = entity,
                        start =
                        PlanStartPolicy.decide(
                            plan = repository.decode(entity),
                            lifecycle = PlanLifecycle.of(entity.status),
                            activePlanName = activeName?.takeIf { entity.status != PlanEntity.STATUS_ACTIVE },
                        ),
                    )
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val importResult = MutableStateFlow<PlanImportResult?>(null)

    /**
     * Emitted once the plan the lifter chose is the active one, so the record
     * screen it opens reads the plan they asked for and not the one they were
     * switching away from.
     *
     * A one-shot event and not a flag in state: navigation that survives in
     * state gets replayed by the next recomposition, and a screen that
     * re-navigates itself on rotation is worse than one that does not navigate
     * at all.
     *
     * A Channel and not a SharedFlow: a shared flow with replay 0 drops an
     * emission that lands while nothing is collecting, and the collector is
     * exactly what a configuration change tears down between the tap and the
     * activation completing. A conflated channel holds the one pending request
     * until a collector returns, and delivers it once.
     */
    private val startRequests = Channel<Unit>(Channel.CONFLATED)
    val recordRequests: Flow<Unit> = startRequests.receiveAsFlow()

    /**
     * Start a session from [planId], making it the active plan first when
     * [activateFirst].
     *
     * The activation is awaited before the event is emitted. The record
     * screen's ViewModel reads `activePlan` when it is constructed, so
     * navigating first would race the write and could open the session against
     * the plan being switched away from.
     */
    fun start(planId: Long, activateFirst: Boolean) {
        viewModelScope.launch {
            if (activateFirst) repository.activate(planId)
            startRequests.send(Unit)
        }
    }

    /**
     * The lifter's display unit, for the one line at the gate that quotes a
     * body weight back to them. A figure in a unit they do not weigh themselves
     * in is a figure they cannot check.
     */
    val weightUnit =
        container.settings.weightUnit
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KG)

    fun import(text: String) {
        viewModelScope.launch { stage(text) }
    }

    /**
     * Validate, stage, and apply anything the document declares about the
     * lifter rather than about the training — today that is the body weight
     * alone (issue #161).
     *
     * ONE write site, called by both import paths, because there are two ways
     * into this screen and a rule applied on one of them is a rule that holds
     * until the lifter picks a file instead of pasting.
     *
     * The write happens when the plan is ACCEPTED, not when it is approved. The
     * owner's rule is recency between two sources of one fact, and discarding a
     * plan does not make the coach's statement of the lifter's weight untrue;
     * the gate's line says the change already happened and survives a discard,
     * so nothing here is silent.
     *
     * A refused document writes nothing at all: `importPlan` returns Invalid
     * before any summary exists, so a plan carrying a bodyweight AND a
     * contradiction leaves the stored figure alone.
     */
    private suspend fun stage(text: String) {
        val result = repository.importPlan(text)
        (result as? PlanImportResult.Staged)?.summary?.bodyWeightKg?.let {
            container.settings.setBodyWeightKg(it)
        }
        importResult.value = result
    }

    /** File-based import: read the picked document's text, then validate as usual. */
    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        }
                    }.getOrNull()
                }
            if (text.isNullOrBlank()) {
                importResult.value = PlanImportResult.Invalid(listOf("Could not read the selected file."))
            } else {
                stage(text)
            }
        }
    }

    fun dismissImportResult() {
        importResult.value = null
    }

    /** Explicit approval: promotes a staged plan to active (the approval gate). */
    fun approve(planId: Long) {
        viewModelScope.launch {
            repository.activate(planId)
            importResult.value = null
        }
    }

    fun discard(planId: Long) {
        viewModelScope.launch {
            repository.delete(planId)
            importResult.value = null
        }
    }

    fun activate(planId: Long) {
        viewModelScope.launch { repository.activate(planId) }
    }

    fun delete(planId: Long) {
        viewModelScope.launch { repository.delete(planId) }
    }
}
