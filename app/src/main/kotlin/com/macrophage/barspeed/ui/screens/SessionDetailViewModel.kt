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
import com.macrophage.barspeed.model.VelocityLossRegime
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.model.sessionTimestamp
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

    /**
     * Which question this set's velocity loss answers, from the geometry
     * FROZEN on its row (#250).
     *
     * Off the stored row, never off the exercise definition as it stands
     * today: a leg curl re-declared as concentric-up next month must not
     * restate how a set recorded last month should be read. Null where the
     * row carries no geometry, which is every set recorded before that column
     * existed -- and null draws the card exactly as it drew before this
     * existed, which is what makes the absence safe.
     */
    fun velocityLossRegime(record: SetRecordEntity): VelocityLossRegime? {
        val geometry = repository.decodeGeometry(record)
        return VelocityLossRegime.of(record.tempo, geometry?.concentricUp, geometry?.kind)
    }

    /**
     * Mark a recorded set as one the lifter did not perform, or take the mark
     * back (#60).
     *
     * NOT A DELETE, and it sits beside one that is. The row keeps its place,
     * its gzipped IMU, heart-rate and cue streams stay in the archive, and
     * both export documents go on carrying the set -- marked.
     * [com.macrophage.barspeed.model.VoidSetPolicy] states what the mark
     * excludes and what it keeps; the repository normalizes the reason and
     * clears it on un-void, so nothing here has to.
     *
     * The screen re-reads through observeSets, so no explicit refresh is
     * needed and none is done.
     */
    fun setVoided(setId: Long, voided: Boolean, reason: String?) {
        viewModelScope.launch { repository.setVoided(setId, voided, reason) }
    }

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
     * be read depends on which build wrote it -- more true than ever with
     * schema 1.4 through 1.9 landing across two releases.
     *
     * Named after the session's own start time, not a database row id --
     * direct lifter request, since `session12` says nothing off the phone
     * and sorts a Downloads folder full of these by nothing useful.
     * [sessionTimestamp] carries the actual reasoning (the zone it renders
     * in, the null fallback, why seconds); this function only assembles the
     * three pieces around it.
     *
     * Suspend, and nullable, because the session row is looked up fresh
     * here rather than trusted from the [session] StateFlow's cached
     * value: the six export buttons on the detail screen are gated on
     * [exporting], not on session being non-null, so a tap in the brief
     * window before that flow's first emission must not synthesise a name
     * from nothing. Null propagates the same way a missing session already
     * does through [container]'s exportJson/buildZip -- the caller bails
     * out via `?: return@launch`, exactly as it already does when the
     * payload itself comes back null.
     */
    private suspend fun exportName(suffix: String): String? {
        val row = repository.session(sessionId) ?: return null
        val timestamp = sessionTimestamp(row.startedAtMs, row.zoneId, row.utcOffsetMinutes)
        return "BarSpeed-v${BuildConfig.VERSION_NAME}-$timestamp-$suffix"
    }

    private suspend fun jsonName(includeDetail: Boolean) =
        exportName(if (includeDetail) "detailed.json" else "summary.json")

    fun shareJson(includeDetail: Boolean) {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
                val name = jsonName(includeDetail) ?: return@launch
                ShareUtil.shareJson(getApplication(), name, json)
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
                val name = exportName("raw.zip") ?: return@launch
                ShareUtil.shareFile(getApplication(), name, zip, "application/zip")
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
                val name = jsonName(includeDetail) ?: return@launch
                pendingSave = json.toByteArray(Charsets.UTF_8)
                onReady(name)
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
                val name = exportName("raw.zip") ?: return@launch
                pendingSave = zip
                onReady(name)
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
