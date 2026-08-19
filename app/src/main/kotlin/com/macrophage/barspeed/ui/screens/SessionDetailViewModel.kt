package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.BuildConfig
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * True while any of the four export-building functions below is in
     * flight, false the moment each one's build phase hands off -- to
     * ShareUtil for the two share functions, to [onReady] for the two save
     * functions. Not carried through savePendingTo's own async write, which
     * this issue's dispatcher change never touched.
     *
     * Exists because moving the build off Main widened a real race rather
     * than only fixing one: today's several hundred milliseconds of frozen
     * UI incidentally serialised a double tap on these six buttons, since
     * the UI could not register a second tap while Main was blocked.
     * Un-freezing Main frees it to register that second tap during the
     * exact window that used to make it impossible, launching a second
     * build against the same single [pendingSave] field the first one has
     * not finished with. Gating the buttons on this flag is what closes
     * that widening rather than merely moving it off the thread it was
     * discovered on.
     */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun decodeAnalysis(record: SetRecordEntity) = repository.decodeAnalysis(record)

    /** Permanently deletes the session (sets and raw streams cascade). */
    fun deleteSession(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            onDeleted()
        }
    }

    /**
     * Exports carry the app name and the version that produced them: several of
     * these end up in one Downloads folder or one chat, and how a number should
     * be read depends on which build wrote it.
     */
    private fun exportName(suffix: String) = "BarSpeed-v${BuildConfig.VERSION_NAME}-session$sessionId-$suffix"

    private fun jsonName(includeDetail: Boolean) = exportName(if (includeDetail) "detailed.json" else "summary.json")

    fun shareJson(includeDetail: Boolean) {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
                ShareUtil.shareJson(getApplication(), jsonName(includeDetail), json)
            } finally {
                _exporting.value = false
            }
        }
    }

    fun shareRawZip() {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val zip = container.rawExporter.buildZip(sessionId) ?: return@launch
                ShareUtil.shareFile(getApplication(), exportName("raw.zip"), zip, "application/zip")
            } finally {
                _exporting.value = false
            }
        }
    }

    // --- Save-to-phone flow: build the bytes, then write to the SAF uri the user picks. ---

    private var pendingSave: ByteArray? = null

    fun prepareJsonSave(includeDetail: Boolean, onReady: (suggestedName: String) -> Unit) {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
                pendingSave = json.toByteArray(Charsets.UTF_8)
                onReady(jsonName(includeDetail))
            } finally {
                _exporting.value = false
            }
        }
    }

    fun prepareRawZipSave(onReady: (suggestedName: String) -> Unit) {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val zip = container.rawExporter.buildZip(sessionId) ?: return@launch
                pendingSave = zip
                onReady(exportName("raw.zip"))
            } finally {
                _exporting.value = false
            }
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
