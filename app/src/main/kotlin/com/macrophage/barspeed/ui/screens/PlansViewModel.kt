package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.PlanImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlansViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.planRepository

    val plans = repository.allPlans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val importResult = MutableStateFlow<PlanImportResult?>(null)

    fun import(text: String) {
        viewModelScope.launch { importResult.value = repository.importPlan(text) }
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
