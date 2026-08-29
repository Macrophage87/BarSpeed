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
import com.macrophage.barspeed.model.PlanStartDecision
import com.macrophage.barspeed.model.PlanStartPolicy
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
    private val startRequests = Channel<Unit>(Channel.CONFLATED)
    val recordRequests: Flow<Unit> = startRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            stateFlow.value =
                PlanDetailState(
                    entity = repository.plan(planId),
                    plan = repository.planFile(planId),
                    loaded = true,
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
                ),
            )
    }

    fun activate() {
        viewModelScope.launch {
            repository.activate(planId)
            stateFlow.value = stateFlow.value.copy(entity = repository.plan(planId))
            decide()
        }
    }

    /**
     * Start a session from this plan, making it active first when
     * [activateFirst]. The activation is awaited before the event, for the
     * reason `PlansViewModel.start` gives.
     */
    fun start(activateFirst: Boolean) {
        viewModelScope.launch {
            if (activateFirst) {
                repository.activate(planId)
                stateFlow.value = stateFlow.value.copy(entity = repository.plan(planId))
                decide()
            }
            startRequests.send(Unit)
        }
    }

    class Factory(private val app: Application, private val planId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanDetailViewModel(app, planId) as T
    }
}
