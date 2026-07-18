package com.macrophage.barspeed.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionDetailViewModel(app: Application, private val sessionId: Long) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val repository = container.sessionRepository

    val session =
        repository.observeSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sets =
        repository.observeSets(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val weightUnit =
        container.settings.weightUnit
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KG)

    fun decodeAnalysis(record: SetRecordEntity) = repository.decodeAnalysis(record)

    fun shareJson(includeDetail: Boolean) {
        viewModelScope.launch {
            val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
            val name = if (includeDetail) "session-$sessionId-detailed.json" else "session-$sessionId.json"
            ShareUtil.shareJson(getApplication(), name, json)
        }
    }

    fun shareRawZip() {
        viewModelScope.launch {
            val zip = container.rawExporter.buildZip(sessionId) ?: return@launch
            ShareUtil.shareFile(getApplication(), "session-$sessionId-raw.zip", zip, "application/zip")
        }
    }

    class Factory(private val app: Application, private val sessionId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionDetailViewModel(app, sessionId) as T
    }
}
