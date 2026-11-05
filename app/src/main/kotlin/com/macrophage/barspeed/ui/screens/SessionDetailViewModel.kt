package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.BuildConfig
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.data.repsSourceOf
import com.macrophage.barspeed.model.RepsCountChip
import com.macrophage.barspeed.model.SaveKind
import com.macrophage.barspeed.model.SaveOutcome
import com.macrophage.barspeed.model.SaveToPhonePolicy
import com.macrophage.barspeed.model.SessionHeartRate
import com.macrophage.barspeed.model.VelocityLossRegime
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.model.sessionTimestamp
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

    /**
     * The header's heart-rate summary (#62): what the close stored, or, on a
     * session never closed, the aggregate over every set row and an HRV
     * computed from the session's stored heart-rate streams.
     *
     * Off the main thread, because an unclosed session's figure inflates and
     * parses every stored heart-rate stream; a closed session reads none.
     * Null until the session row has been read.
     */
    val heartRate: StateFlow<SessionHeartRate?> =
        combine(session, sets) { s, rows -> s?.let { repository.sessionHeartRate(it, rows) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        return VelocityLossRegime.of(record.tempo, geometry?.concentricUp, geometry?.horizontal, geometry?.kind)
    }

    /**
     * The count chip's text for this row: whose count its rep figure is, from
     * the export's own derivation over the geometry FROZEN on the row, so the
     * card and the export's `repsSource` name one counter (#325). Null on a
     * timed set, where nothing counted reps.
     */
    fun countChip(record: SetRecordEntity): String? =
        RepsCountChip.label(record.repsSourceOf(repository.decodeGeometry(record)?.kind))

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

    /**
     * The export built for the open picker, with the kind it was built as
     * (#37). One field for every picker, so a second build replaces the
     * first's bytes; the kind is what lets the picker that returns tell its
     * own bytes from another's.
     */
    private var pendingSave: Pair<SaveKind, ByteArray>? = null

    fun prepareJsonSave(includeDetail: Boolean, onReady: (suggestedName: String) -> Unit) {
        viewModelScope.launch {
            _exporting.value = true
            try {
                val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@launch
                val name = jsonName(includeDetail) ?: return@launch
                val kind = if (includeDetail) SaveKind.DETAILED_JSON else SaveKind.SUMMARY_JSON
                pendingSave = kind to json.toByteArray(Charsets.UTF_8)
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
                pendingSave = SaveKind.RAW_ZIP to zip
                onReady(name)
            } finally {
                _exporting.value = false
            }
        }
    }

    /**
     * Write the pending export to the picked document. Null uri = picker
     * cancelled.
     *
     * [kind] is the export the returning picker was opened for, which the
     * screen knows from the launcher it returned to, so it survives the
     * Activity being destroyed behind the picker where [pendingSave] does not,
     * and it is this picker's own where two are stacked (#37). What happens is
     * [SaveToPhonePolicy.onPickedFor]'s decision; this carries it out.
     */
    fun savePendingTo(uri: Uri?, kind: SaveKind) {
        val held = pendingSave
        pendingSave = null
        val outcome =
            SaveToPhonePolicy.onPickedFor(documentCreated = uri != null, heldKind = held?.first, pickedKind = kind)
        if (uri == null || outcome == SaveOutcome.NOTHING) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val bytes =
                when (outcome) {
                    SaveOutcome.WRITE_HELD -> held?.second
                    SaveOutcome.REGENERATE -> exportBytes(kind)
                    SaveOutcome.NOTHING, SaveOutcome.REFUSE -> null
                }
            val ok =
                bytes != null &&
                    withContext(Dispatchers.IO) {
                        runCatching {
                            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                        }.getOrDefault(false)
                    }
            // The picker created the document before it returned, so where
            // nothing was written the file already exists, empty or cut short.
            // It is removed rather than left under the name the lifter chose,
            // whether there was nothing to write or the write failed; if the
            // provider refuses, the message below still says the save failed.
            // Where the picker offered to overwrite and returned a document
            // that already existed, a failed write deletes it too, including
            // the lifter's earlier file when nothing had been written over it
            // yet. Named rather than designed away: whether a picker offers
            // overwrite and what deleteDocument then removes is device
            // behaviour no test here reaches. [Field]
            if (!ok) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                }
            }
            val message =
                when {
                    ok -> "Saved"
                    bytes == null -> SaveToPhonePolicy.REFUSED_MESSAGE
                    else -> "Save failed"
                }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** The bytes of [kind], built again from the stored session (#37). */
    private suspend fun exportBytes(kind: SaveKind): ByteArray? = when (kind) {
        SaveKind.SUMMARY_JSON -> container.sessionExporter.exportJson(sessionId, false)?.toByteArray(Charsets.UTF_8)
        SaveKind.DETAILED_JSON -> container.sessionExporter.exportJson(sessionId, true)?.toByteArray(Charsets.UTF_8)
        SaveKind.RAW_ZIP -> container.rawExporter.buildZip(sessionId)
    }

    class Factory(private val app: Application, private val sessionId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionDetailViewModel(app, sessionId) as T
    }
}
