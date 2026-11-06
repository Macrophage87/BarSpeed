package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.AppLog
import com.macrophage.barspeed.BuildConfig
import com.macrophage.barspeed.CrashReporting
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.SetRecordEntity
import com.macrophage.barspeed.data.guideCalls
import com.macrophage.barspeed.data.repsSourceOf
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.ExportFailureCopy
import com.macrophage.barspeed.model.ExportNaming
import com.macrophage.barspeed.model.RepsCountChip
import com.macrophage.barspeed.model.RepsSource
import com.macrophage.barspeed.model.SaveKind
import com.macrophage.barspeed.model.SaveOutcome
import com.macrophage.barspeed.model.SaveToPhonePolicy
import com.macrophage.barspeed.model.SessionHeartRate
import com.macrophage.barspeed.model.VelocityLossRegime
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.model.sessionTimestamp
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.CancellationException
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

private const val TAG = "SessionDetailViewModel"

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
     * The kind FROZEN on this row -- `ResolvedGeometry.kind`, the effective
     * kind the set was analysed under, a plan's declaration included -- or
     * null where the row carries no geometry (#57). Off the stored row for
     * [velocityLossRegime]'s reason.
     */
    fun storedKind(record: SetRecordEntity): ExerciseKind? = repository.decodeGeometry(record)?.kind

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
     *
     * [guideCalls] is [guideCallsOf]'s answer for the same row, the input the
     * export reads off the set's archived rep marks (#336); null until it has
     * been read, which draws what the row's own columns say.
     */
    fun countChip(record: SetRecordEntity, guideCalls: Int?): String? =
        RepsCountChip.label(record.repsSourceOf(repository.decodeGeometry(record)?.kind, guideCalls))

    /**
     * How many reps the cadence guide called on [record], read off its
     * archived rep marks the way the export reads them (#336), or null where
     * none are stored.
     *
     * Read only where the count can move the word: a row whose own columns
     * already name the metronome. History never read a set's streams before,
     * and the streams come back whole -- the IMU capture with the marks -- so
     * every other row is answered without touching them. Off the main thread,
     * because the marks are gzipped.
     */
    suspend fun guideCallsOf(record: SetRecordEntity): Int? {
        if (record.repsSourceOf(repository.decodeGeometry(record)?.kind, null) != RepsSource.METRONOME) return null
        return withContext(Dispatchers.IO) { repository.rawStreams(record.id).guideCalls() }
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
     * does through [container]'s exportJson/buildZip -- the caller returns
     * false to [export], which says the session was not found, exactly as it
     * does when the payload itself comes back null.
     *
     * The JSON exports only. The raw zip is named by [rawZipName], which
     * drops the version (history-export F4); [ExportNaming] says why each
     * keeps or drops it.
     */
    private suspend fun exportName(suffix: String): String? {
        val row = repository.session(sessionId) ?: return null
        val timestamp = sessionTimestamp(row.startedAtMs, row.zoneId, row.utcOffsetMinutes)
        return ExportNaming.versionedName(BuildConfig.VERSION_NAME, timestamp, suffix)
    }

    /** The raw zip's name: [ExportNaming.rawZipName], looked up fresh like [exportName]. */
    private suspend fun rawZipName(): String? {
        val row = repository.session(sessionId) ?: return null
        return ExportNaming.rawZipName(
            sessionTimestamp(row.startedAtMs, row.zoneId, row.utcOffsetMinutes),
            row.planSessionName,
        )
    }

    private suspend fun jsonName(includeDetail: Boolean) =
        exportName(if (includeDetail) "detailed.json" else "summary.json")

    /**
     * One export tap: [build] with [exporting] held for its length, and
     * nothing it throws allowed to end the process. UX review 2026-11-06,
     * history-export F6.
     *
     * The four export paths each had `try { ... } finally` and no catch, so a
     * bad legacy row, a decode failure, running out of memory on a zip or the
     * FileProvider refusing escaped `viewModelScope.launch` and crashed the
     * app on the tap. Now any failure but cancellation is logged, written as
     * a crash report that can be sent, and shown as
     * [ExportFailureCopy.failed]. Throwable-wide on purpose: an
     * OutOfMemoryError building a large zip is one of the named cases, and
     * the buffer that failed to allocate is not held after the catch.
     *
     * [build] returns false when there was nothing to export because the
     * session row is gone; that is said in words rather than in silence.
     */
    private fun export(build: suspend () -> Boolean) {
        viewModelScope.launch {
            _exporting.value = true
            val app = getApplication<Application>()
            try {
                if (!build()) Toast.makeText(app, ExportFailureCopy.SESSION_MISSING, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w(TAG, "export failed", e)
                container.crashLogs.write(CrashReporting.recordOf(Thread.currentThread(), e))
                val message = ExportFailureCopy.failed(e::class.simpleName, e.message)
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            } finally {
                _exporting.value = false
            }
        }
    }

    fun shareJson(includeDetail: Boolean) = export {
        val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@export false
        val name = jsonName(includeDetail) ?: return@export false
        ShareUtil.shareJson(getApplication(), name, json)
        true
    }

    fun shareRawZip() = export {
        val zip = container.rawExporter.buildZip(sessionId) ?: return@export false
        val name = rawZipName() ?: return@export false
        ShareUtil.shareFile(getApplication(), name, zip, "application/zip")
        true
    }

    // --- Save-to-phone flow: build the bytes, then write to the SAF uri the user picks. ---

    /**
     * The export built for the open picker, with the kind it was built as
     * (#37). One field for every picker, so a second build replaces the
     * first's bytes; the kind is what lets the picker that returns tell its
     * own bytes from another's.
     */
    private var pendingSave: Pair<SaveKind, ByteArray>? = null

    fun prepareJsonSave(includeDetail: Boolean, onReady: (suggestedName: String) -> Unit) = export {
        val json = container.sessionExporter.exportJson(sessionId, includeDetail) ?: return@export false
        val name = jsonName(includeDetail) ?: return@export false
        val kind = if (includeDetail) SaveKind.DETAILED_JSON else SaveKind.SUMMARY_JSON
        pendingSave = kind to json.toByteArray(Charsets.UTF_8)
        onReady(name)
        true
    }

    fun prepareRawZipSave(onReady: (suggestedName: String) -> Unit) = export {
        val zip = container.rawExporter.buildZip(sessionId) ?: return@export false
        val name = rawZipName() ?: return@export false
        pendingSave = SaveKind.RAW_ZIP to zip
        onReady(name)
        true
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
            var failure: Throwable? = null
            val bytes =
                when (outcome) {
                    SaveOutcome.WRITE_HELD -> held?.second
                    // A rebuild that throws is a failed save, not a crash (history-export F6).
                    SaveOutcome.REGENERATE ->
                        try {
                            exportBytes(kind)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            AppLog.w(TAG, "rebuilding the export for the picker failed", e)
                            failure = e
                            null
                        }
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
                    failure != null -> ExportFailureCopy.failed(failure::class.simpleName, failure.message)
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
