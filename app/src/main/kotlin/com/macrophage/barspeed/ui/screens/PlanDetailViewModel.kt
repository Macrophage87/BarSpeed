package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.PlanEntity
import com.macrophage.barspeed.model.PlanFile
import com.macrophage.barspeed.model.PlanLifecycle
import com.macrophage.barspeed.model.PlanSessionLastDone
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.model.PlanStartPolicy
import com.macrophage.barspeed.model.PlanWarningLine
import com.macrophage.barspeed.model.RecordedPlanSession
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanDetailState(
    val entity: PlanEntity? = null,
    val plan: PlanFile? = null,
    val loaded: Boolean = false,
    /**
     * What a START control on this plan would do, or null while the plan has
     * not been read yet.
     *
     * Null is "not decided", which is a different thing from
     * [PlanStartDecision.Unstartable] — the second is an answer and draws a
     * reason, the first draws nothing at all. Collapsing them would put "this
     * plan could not be read" on screen for the frame before it was read.
     */
    val start: PlanStartDecision? = null,
    /**
     * A STAGED plan's import warnings, recomputed from its stored text
     * (`PlanRepository.reviewSummary`), and empty on any other row (#58). The
     * import dialog that first showed them can be dismissed, and this screen
     * is the other place a staged plan is made active from. Tiered and
     * worded for the lifter by `PlanWarningDisplay` (UX review 2026-11-06,
     * plans F3/F5); each line keeps the author's raw text for Copy.
     */
    val stagedWarnings: List<PlanWarningLine> = emptyList(),
    /**
     * Session name to when it was last recorded, from History (UX review
     * 2026-11-06, plans F7). Sessions never done are absent.
     */
    val lastDone: Map<String, Long> = emptyMap(),
    /** True while MAKE ACTIVE on a staged plan waits for the lifter's approval (#58). */
    val reviewing: Boolean = false,
)

class PlanDetailViewModel(app: Application, private val planId: Long) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.planRepository

    private val stateFlow = MutableStateFlow(PlanDetailState())
    val state: StateFlow<PlanDetailState> = stateFlow

    val weightUnit =
        container.settings.weightUnit
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KG)

    /**
     * The name of the plan a start from here would displace, or null when this
     * plan is the active one and nothing is displaced.
     *
     * Taken from the entity's `name` column rather than by decoding the other
     * plan's JSON: `importPlan` writes `name = plan.planName`, so the column is
     * that name, and decoding a second document to read one string would be
     * parse work on the main thread.
     */
    private var displacedPlanName: String? = null

    /**
     * Emitted once this plan is the active one, so the record screen opens
     * against the plan the lifter chose. See `PlansViewModel.recordRequests`
     * for why this is an event rather than a flag in state.
     */
    private val startRequests = Channel<Int?>(Channel.CONFLATED)

    /**
     * The index of the plan session to open on Record, or null to open
     * Record's session picker. A session's own START skips the second pick
     * (UX review 2026-11-06, plans F7).
     */
    val recordRequests: Flow<Int?> = startRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            val entity = repository.plan(planId)
            stateFlow.value =
                PlanDetailState(
                    entity = entity,
                    plan = repository.planFile(planId),
                    loaded = true,
                    stagedWarnings = stagedWarnings(entity),
                )
            decide()
        }
        // Collected rather than read once: MAKE ACTIVE on this very screen, and
        // an approval on the plans screen behind it, both move the active row,
        // and a prompt naming a plan that is no longer active is a false
        // statement about what the tap is about to do.
        viewModelScope.launch {
            repository.activePlan.collect { active ->
                displacedPlanName = active?.takeIf { it.id != planId }?.name
                decide()
            }
        }
        viewModelScope.launch {
            val planName = repository.planFile(planId)?.planName ?: return@launch
            container.sessionRepository.sessions.collect { rows ->
                stateFlow.value =
                    stateFlow.value.copy(
                        lastDone =
                        PlanSessionLastDone.of(
                            planName,
                            rows.map { RecordedPlanSession(it.planName, it.planSessionName, it.startedAtMs) },
                        ),
                    )
            }
        }
    }

    private fun decide() {
        val current = stateFlow.value
        if (!current.loaded) return
        stateFlow.value =
            current.copy(
                start =
                PlanStartPolicy.decide(
                    plan = current.plan,
                    lifecycle = PlanLifecycle.of(current.entity?.status),
                    activePlanName = displacedPlanName,
                    stagedWarnings = current.stagedWarnings.size,
                ),
            )
    }

    private fun stagedWarnings(entity: PlanEntity?): List<PlanWarningLine> =
        if (entity != null && entity.status == PlanEntity.STATUS_STAGED) {
            repository.reviewSummary(entity)?.warningLines.orEmpty()
        } else {
            emptyList()
        }

    /**
     * MAKE ACTIVE. A STAGED plan is not promoted on this tap (#58): it has not
     * been approved, and the warnings its import raised may only ever have
     * been shown in a dialog the lifter dismissed, so the tap opens an
     * approval naming them and [approve] is the activation -- the plans
     * list's rule, on the other screen that offers the same control. An
     * archived plan was approved once and is promoted directly, as before.
     */
    fun activate() {
        if (stateFlow.value.entity?.status == PlanEntity.STATUS_STAGED) {
            stateFlow.value = stateFlow.value.copy(reviewing = true)
            return
        }
        activateNow()
    }

    /** The approval [activate] asked for on a staged plan. */
    fun approve() {
        stateFlow.value = stateFlow.value.copy(reviewing = false)
        activateNow()
    }

    fun dismissReview() {
        stateFlow.value = stateFlow.value.copy(reviewing = false)
    }

    private fun activateNow() {
        viewModelScope.launch {
            repository.activate(planId)
            val entity = repository.plan(planId)
            stateFlow.value =
                stateFlow.value.copy(entity = entity, stagedWarnings = stagedWarnings(entity))
            decide()
        }
    }

    /**
     * Start a session from this plan, making it active first when
     * [activateFirst]. The activation is awaited before the event, for the
     * reason `PlansViewModel.start` gives. [sessionIndex] is the plan
     * session to open on Record, or null for Record's picker.
     */
    fun start(activateFirst: Boolean, sessionIndex: Int? = null) {
        viewModelScope.launch {
            if (activateFirst) {
                repository.activate(planId)
                val entity = repository.plan(planId)
                stateFlow.value =
                    stateFlow.value.copy(entity = entity, stagedWarnings = stagedWarnings(entity))
                decide()
            }
            startRequests.send(sessionIndex)
        }
    }

    class Factory(private val app: Application, private val planId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanDetailViewModel(app, planId) as T
    }
}
