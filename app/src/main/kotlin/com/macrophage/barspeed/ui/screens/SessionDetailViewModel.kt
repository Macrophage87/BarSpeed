package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Permanently deletes the session (sets and raw streams cascade). */
    fun deleteSession(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            onDeleted()
        }
    }

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

    // --- Save-to-phone flow: build the bytes, then write to the SAF uri the user picks. ---

    private var pendingSave: ByteArray? = null

    fun prepareJsonSave(includeDetail: Boolean, onReady: (suggestedName: String) -> Unit) {
        viewModelScope.launch {
            val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
            pendingSave = json.toByteArray(Charsets.UTF_8)
            onReady(if (includeDetail) "session-$sessionId-detailed.json" else "session-$sessionId.json")
        }
    }

    fun prepareRawZipSave(onReady: (suggestedName: String) -> Unit) {
        viewModelScope.launch {
            val zip = container.rawExporter.buildZip(sessionId) ?: return@launch
            pendingSave = zip
            onReady("session-$sessionId-raw.zip")
        }
    }

    /** Write the pending export to the picked document. Null uri = picker cancelled. */
    fun savePendingTo(uri: Uri?) {
        val bytes = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
                }
            Toast.makeText(app, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    class Factory(private val app: Application, private val sessionId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionDetailViewModel(app, sessionId) as T
    }
}
