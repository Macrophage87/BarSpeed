package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.PlanImportResult
import com.macrophage.barspeed.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlansViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.planRepository

    val plans = repository.allPlans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val importResult = MutableStateFlow<PlanImportResult?>(null)

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
