package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.AppLog
import com.macrophage.barspeed.CrashReport
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.OrphanedSet
import com.macrophage.barspeed.data.RecoveryOutcome
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.data.SessionEntity
import com.macrophage.barspeed.model.ExportNaming
import com.macrophage.barspeed.model.JournalRecoveryPolicy
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RecordingServicePolicy
import com.macrophage.barspeed.model.RecoveryRefusal
import com.macrophage.barspeed.model.RestoreVerdict
import com.macrophage.barspeed.model.SessionHeartRate
import com.macrophage.barspeed.model.VoidSetPolicy
import com.macrophage.barspeed.model.VolumeSet
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.model.sessionTimestamp
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Logcat tag for the one thing on this screen that is logged rather than shown.
 *
 * Routed through [AppLog] rather than `android.util.Log` since #272, so the
 * line reaches the crash buffer as well as logcat. It was the ONLY logging
 * call in the tree when that buffer was added.
 */
private const val TAG = "HomeViewModel"

/** Why RESTORE did not restart the app: a set-end write or a session close is still running. */
private const val SAVING_NOTE = "A set or session is still saving -- try restoring again in a moment"

/** Where "Export all sessions" has got to: [done] of [total] sessions written. */
data class ExportAllProgress(val done: Int, val total: Int)

/** One history row: session summary plus a per-set mean-velocity sparkline. */
data class HistoryRow(
    val session: SessionEntity,
    /**
     * Sets the lifter PERFORMED, which since #60 is not the number of rows the
     * session holds: a set marked as not performed stays in the session and in
     * the export and is excluded here.
     */
    val setCount: Int,
    val sparkline: List<Double>,
    /**
     * The session's heart-rate summary: what the close stored, or, on a
     * session never closed, the same aggregate over every set row and an HRV
     * computed from the session's stored heart-rate streams (#62).
     */
    val heartRate: SessionHeartRate,
)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class HomeState(
    val planName: String? = null,
    val planSessionCount: Int = 0,
    val planSetCount: Int = 0,
    /**
     * DISTINCT exercise ids across the WHOLE plan, every session summed
     * together -- not a block count and not scoped to one session.
     *
     * Answers a different question from the two other surfaces that draw the
     * word "exercises": `RecordScreen.previewSummary` and the plan session
     * picker card both count BLOCKS of the one session about to run (two
     * blocks of the same lift count twice there; see `previewSummary`'s own
     * KDoc for why the two agree with each other). `HeroCard` draws this one
     * as "N distinct exercises" rather than bare "exercises" so the word does
     * not imply it is answering the same question (#227 item 3).
     */
    val planExerciseCount: Int = 0,
    val weekVolumeKg: Double = 0.0,
    val weekSessions: Int = 0,
    val history: List<HistoryRow> = emptyList(),
    /**
     * Every session past the first [HomeViewModel.HISTORY_LIMIT], newest
     * first, as stored rows only: nothing is read or decoded for them, so
     * Home costs the same with 20 sessions or 500 (history-export F5). Drawn
     * only when the lifter asks for them.
     */
    val olderSessions: List<SessionEntity> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Base load for bodyweight work (pull-ups, dips); null until set. */
    val bodyWeightKg: Double? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LiftingApp).container
    private val sessionRepository = container.sessionRepository
    private val planRepository = container.planRepository

    val imuState = container.autoConnect.imuState
    val hrmState = container.autoConnect.hrmState

    /**
     * When the analysed bar sensor last delivered a frame, and when its link
     * was armed (#213).
     *
     * Home's dot showed [imuState] alone, which goes volt as soon as the app
     * ISSUES a notification subscribe. Field-37's lifter read that as capture.
     * These two are what let the dot say whether anything is arriving; the
     * judgement is `ArmedSilencePolicy`'s, in `:core:model`.
     *
     * The strap has no equivalent and gets none: nothing publishes its frame
     * arrivals, and a dot that went amber because nobody looked would be the
     * same false claim in the other direction.
     */
    val imuFrameAtMs = container.autoConnect.imuFrameAtMs
    val imuArmedAtMs = container.autoConnect.imuArmedAtMs

    /**
     * Sets that were interrupted before they could be stored.
     *
     * A flow of its own rather than another arm of [state], and the separation
     * is deliberate. [state] is built by combining flows the database and the
     * settings store already publish; this is a directory scan with no
     * observer behind it, and folding it in would mean re-scanning the disk
     * every time the weight unit changed.
     *
     * Never merged into [HomeState.history] either. A history row carries a
     * set count and a per-set velocity sparkline, and an interrupted set has
     * neither -- rendering one as a history row would put invented numbers on
     * screen next to real ones.
     */
    private val interruptedFlow = MutableStateFlow<List<OrphanedSet>>(emptyList())
    val interrupted: StateFlow<List<OrphanedSet>> = interruptedFlow

    /**
     * Databases `container.rescuedDatabases` found moved aside by a
     * downgrade. Issue #111: the same shape as [interrupted] and for the
     * same reason -- a directory scan with no observer behind it, so it is
     * its own flow rather than another arm of [state].
     */
    private val rescuedFlow = MutableStateFlow<List<RescuedDatabase>>(emptyList())
    val rescued: StateFlow<List<RescuedDatabase>> = rescuedFlow

    /**
     * Issue #112: whether each rescue in [rescued] can be put back, keyed by
     * its directory. Computed with the list, on IO, because it reads each
     * rescue's header and `-wal` -- the same read the launch-time rescue
     * makes, and never an open.
     */
    private val restoreVerdictsFlow = MutableStateFlow<Map<File, RestoreVerdict>>(emptyMap())
    val restoreVerdicts: StateFlow<Map<File, RestoreVerdict>> = restoreVerdictsFlow

    /**
     * Issue #112, round 1 review (A2, B4): what the process-wide recording
     * holds hold now. The card's RESTORE reads it through
     * [rescuedCardButtons]; [restoreRescued] reads it again before it ends
     * the process.
     */
    val recordingHolds: StateFlow<Set<RecordingHold>> = container.recordingHolds.held

    /**
     * Rescued directories with a share or a discard currently in flight,
     * keyed by [RescuedDatabase.directory] so one busy item does not disable
     * every card.
     *
     * THE FIX FOR THIS ISSUE'S OWN GATE. Nothing gated these two buttons
     * before this: both were always enabled, both launched on their own
     * coroutine, and a lens executed the result -- tap SEND, see nothing
     * happen while the archive streams, tap DISCARD, and the corpus is
     * deleted mid-stream while the share sheet goes on to offer a valid,
     * empty zip with no exception, no toast, no log. This is
     * `SessionDetailViewModel.exporting`'s own reasoning from issue #29 --
     * "moving the build off Main widened a real race rather than only
     * fixing one" -- applied to the one place on this screen where the
     * adjacent button is destructive and the payload is not bounded the way
     * every one of that screen's six exports is.
     *
     * NO DURATION IS QUOTED, and the omission is deliberate. This KDoc used
     * to carry "12 s at 80 MB, 28 s at 200 MB on a desktop SSD", relayed
     * from a review into shipped source; an independent re-measurement of
     * the same code path got 3 to 30 times faster in every configuration
     * tried, so the figure is retracted rather than replaced. The fix does
     * not rest on it: any window longer than one frame admits the second
     * tap, and how long the window actually is on a phone is a field
     * question nothing here has measured.
     *
     * MUTATED WITH `update {}`, NOT `value = value + x`. Read-modify-write
     * is correct only while every caller is already on the same thread, and
     * issue #29's own defect was created by moving work off Main. The claim
     * this repository makes elsewhere (`Exporters.kt`) that
     * `viewModelScope` is `Dispatchers.Main.immediate` IS verified for the
     * lifecycle version this module builds against, from the artifact
     * already in the Gradle cache: `javap -c` on androidx.lifecycle
     * 2.8.7's `CloseableCoroutineScopeKt.createViewModelScope` shows
     * `Dispatchers.getMain()` followed by
     * `MainCoroutineDispatcher.getImmediate()`, and `ViewModelKt`'s
     * `getViewModelScope` calls that function on-path. Its only fallback
     * is `EmptyCoroutineContext`, taken where no Main dispatcher exists at
     * all, which is not a phone. The code still does not depend on it. If it
     * were a dispatching Main instead, two rapid SEND taps could both pass
     * the guard below and two concurrent `zipTo` calls would write the same
     * cache path.
     */
    private val busyRescuesFlow = MutableStateFlow<Set<File>>(emptySet())
    val busyRescues: StateFlow<Set<File>> = busyRescuesFlow

    /**
     * [shareInterrupted]'s busy key, the same shape [busyRescuesFlow] gives
     * [shareRescued] (#304). Keyed on the journal's own [File] the way
     * [busyRescuesFlow] is keyed on a rescue directory, because
     * [OrphanedSet.directory] is stable across a recomposition and is the same
     * identity `zipTo` writes from.
     */
    private val busyInterruptedFlow = MutableStateFlow<Set<File>>(emptySet())
    val busyInterrupted: StateFlow<Set<File>> = busyInterruptedFlow

    /**
     * Why each interrupted capture cannot be put back into History (#100),
     * keyed by its directory: null where it can. Read off the disk with the
     * listing, so the card shows the note instead of an action that would
     * only refuse. A capture missing from the map was not judged -- the scan
     * failed -- and its action stays live, because [recoverInterrupted]
     * judges it again before anything is written.
     */
    private val recoveryRefusalsFlow = MutableStateFlow<Map<File, RecoveryRefusal?>>(emptyMap())
    val recoveryRefusals: StateFlow<Map<File, RecoveryRefusal?>> = recoveryRefusalsFlow

    /**
     * [shareCrashReport]'s busy key, the same shape [busyRescuesFlow] gives
     * [shareRescued] (#304). Keyed on [CrashReport.file].
     */
    private val busyCrashReportsFlow = MutableStateFlow<Set<File>>(emptySet())
    val busyCrashReports: StateFlow<Set<File>> = busyCrashReportsFlow

    /**
     * Crash reports this app wrote about itself. Issue #272.
     *
     * A third flow of the same shape as [interrupted] and [rescued], for the
     * same reason both of those give: a directory scan with no observer behind
     * it does not belong in [state]'s combine, where it would re-run every
     * time the weight unit changed.
     *
     * The scan opens nothing. `CrashLogStore.list` reads the directory entry
     * and the file name and stops there, which is #271's whole lesson: that
     * crash was a launch-time LISTING that decoded what it was listing.
     */
    private val crashReportsFlow = MutableStateFlow<List<CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashReport>> = crashReportsFlow

    /** "Export all sessions" in flight: null when it is not running. */
    private val exportAllFlow = MutableStateFlow<ExportAllProgress?>(null)
    val exportAllProgress: StateFlow<ExportAllProgress?> = exportAllFlow

    /** The last "Export all sessions" result line, kept on screen until the next run. */
    private val exportAllMessageFlow = MutableStateFlow<String?>(null)
    val exportAllMessage: StateFlow<String?> = exportAllMessageFlow

    init {
        refreshInterrupted()
        refreshRescued()
        refreshCrashReports()
    }

    /**
     * Re-scan private storage. Cheap: an empty root is the normal case, and
     * since #271 a non-empty one costs one fixed buffer per stream file rather
     * than a decode of every sample in it.
     *
     * A FAILING SCAN LISTS NOTHING; IT DOES NOT TAKE THE APP DOWN WITH IT.
     * This runs from `init` and from a `LaunchedEffect` on the first screen of
     * every cold launch, so whatever it throws is thrown before the lifter can
     * reach anything else -- which is precisely what #271 was on the phone:
     * `OutOfMemoryError` inside the scan, eight times, 8 s after Home drew,
     * with no way to open the app and delete the capture that caused it. The
     * catch is `Throwable`-wide for that reason: an `Error` here is exactly the
     * case that must not be fatal. What is lost is one card; what is kept is
     * the app. `orphans()` does not suspend, so the usual objection to a
     * `Throwable`-wide catch inside a coroutine -- that it swallows
     * `CancellationException` -- has nothing to bite on here.
     */
    fun refreshInterrupted() {
        viewModelScope.launch {
            val (list, refusals) =
                withContext(Dispatchers.IO) {
                    val list =
                        runCatching { container.setJournals.orphans() }
                            .onFailure { AppLog.w(TAG, "interrupted-set scan failed; listing none", it) }
                            .getOrDefault(emptyList())
                    // Per capture, and under the same Throwable-wide catch for
                    // the same reason: one capture the judgement cannot read
                    // costs its note, never the card or the app.
                    val refusals =
                        list.mapNotNull { orphan ->
                            runCatching { orphan.directory to container.journalRecovery.refusal(orphan) }
                                .onFailure { AppLog.w(TAG, "recovery check failed; leaving it to the tap", it) }
                                .getOrNull()
                        }.toMap()
                    list to refusals
                }
            recoveryRefusalsFlow.value = refusals
            interruptedFlow.value = list
        }
    }

    /**
     * Re-scan the rescue directory. Cheap: an empty root is the normal case.
     *
     * RETURNS THE JOB so a caller that must not report "done" before the list
     * on screen reflects the disk can wait for it; [discardRescued] is the one
     * that must. [refreshInterrupted] above is deliberately left as it is:
     * [discardInterrupted] has the same un-awaited shape, but it predates this
     * branch and is not issue #111's to change.
     */
    fun refreshRescued(): Job = viewModelScope.launch {
        val (list, verdicts) = withContext(Dispatchers.IO) {
            val list = container.rescuedDatabases.rescued()
            val verdicts = runCatching {
                list.associate { it.directory to container.databaseRestore.verdict(it, list) }
            }.onFailure { AppLog.w(TAG, "restore verdicts failed; offering none", it) }.getOrDefault(emptyMap())
            list to verdicts
        }
        restoreVerdictsFlow.value = verdicts
        rescuedFlow.value = list
    }

    /**
     * Put a rescued database back. Issue #112. By the time this runs the
     * lifter has confirmed, in the card's own dialog.
     *
     * TWO STEPS, AND THIS IS ONLY THE FIRST. It writes a request naming the
     * rescue and restarts the app; the swap itself happens at the next
     * launch, in `AppDatabase.build`, before Room opens anything -- the one
     * place nothing holds the file. The verdict is checked again there, so a
     * request this build can honour and the next cannot is dropped.
     *
     * THE RESTART is a relaunch intent followed by ending the process. That
     * the system then starts the activity in a fresh process is the
     * documented shape of the call and has not been watched on a device; a
     * phone that does not come back simply shows the launcher, and the next
     * start does the restore. If no launch intent exists the lifter is told
     * to reopen the app themselves.
     *
     * NOT WHILE ANYTHING IS HELD. A set-end write or a session close runs on
     * the process-wide scope after the record screen has gone, with the only
     * copy of what it writes in memory, and the lifter is on Home while it
     * runs. Ending the process then loses it. So the restart is refused while
     * [RecordingServicePolicy.mayEndProcess] says no -- checked here before
     * anything is written, and again just before the exit; on the second, the
     * request is already on disk and the lifter is told to reopen the app
     * later instead. Round 1 review, A2/B4. The predicate is test-gated; this
     * guard is compile- and lint-gated only.
     */
    fun restoreRescued(item: RescuedDatabase) {
        if (!rescuedActionAllowed(item, busyRescuesFlow.value)) return
        if (!RecordingServicePolicy.mayEndProcess(recordingHolds.value)) {
            Toast.makeText(getApplication(), SAVING_NOTE, Toast.LENGTH_LONG).show()
            return
        }
        viewModelScope.launch {
            busyRescuesFlow.update { it + item.directory }
            val requested = withContext(Dispatchers.IO) { container.databaseRestore.request(item) }
            if (!requested) {
                busyRescuesFlow.update { it - item.directory }
                Toast.makeText(getApplication(), "Couldn't schedule the restore -- try again", Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            val app = getApplication<Application>()
            val relaunch = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (relaunch == null || !RecordingServicePolicy.mayEndProcess(recordingHolds.value)) {
                refreshRescued().join()
                busyRescuesFlow.update { it - item.directory }
                val note = if (relaunch == null) {
                    "Close BarSpeed and open it again to finish restoring"
                } else {
                    "A set or session is still saving. Once it has, close BarSpeed and open it again to " +
                        "finish restoring"
                }
                Toast.makeText(app, note, Toast.LENGTH_LONG).show()
                return@launch
            }
            app.startActivity(relaunch)
            Runtime.getRuntime().exit(0)
        }
    }

    /**
     * Re-scan the crash directory. Issue #272.
     *
     * `CrashLogStore.list` swallows its own failures and lists nothing, for
     * the reason [refreshInterrupted] states at length: this runs from `init`
     * and from a `LaunchedEffect` on the first screen of every cold launch,
     * and a scan that throws there is the #271 shape exactly -- the app cannot
     * be opened to deal with the thing that is breaking it. A crash-report
     * scan that took the app down would be the most ironic version of that
     * defect available.
     *
     * Returns the Job so [deleteCrashReport] can wait for the list on screen
     * to reflect the disk before it reports done, the same reason
     * [refreshRescued] does.
     */
    fun refreshCrashReports(): Job = viewModelScope.launch {
        crashReportsFlow.value = withContext(Dispatchers.IO) { container.crashLogs.list() }
    }

    /**
     * Send an interrupted capture to the lifter, as the raw files.
     *
     * The only route this data has off the phone -- app-private storage is not
     * browsable -- so without it the capture would be safe and permanently out
     * of reach of the person whose set it is.
     *
     * Streamed through [ShareUtil.shareStreamed] rather than
     * [ShareUtil.shareFile], with `SetJournalStore.zipTo` writing the archive
     * into the share cache itself and copying each stream through a fixed
     * buffer (#273). A journal directory has no size bound: the capture
     * behind #271 was a 314.6 MB `imu.csv`, and now that the listing is
     * bounded the card draws for one and this button is live on it. Taking
     * the archive back as a ByteArray here would put a full-size copy in the
     * heap on the way to a file the share sheet reads from anyway.
     *
     * BUSY-GUARDED AND CAUGHT, the same shape [shareRescued] already uses
     * (#304, closing the remainder #273 named on this KDoc without folding
     * it in). Before this: [interruptedName] is stable across taps, so a
     * second SEND reopened the same share-cache path while the first copy was
     * still streaming into it -- for the capture behind #271, up to 314.6 MB
     * of it -- and `FileOutputStream` truncated the file the chooser could
     * already have been handed, which a reader reports as a zip corrupt
     * before its central directory. The same missing guard left DISCARD live
     * during a send, so a stream not yet reached could disappear mid-copy.
     * Both are closed by the same key: [busyInterruptedFlow] disables SEND
     * for [orphan] the moment the coroutine starts, `InterruptedSetNotice`
     * reads it for `enabled`. [discardInterrupted] checks and holds the same key.
     *
     * ALSO CATCHES, where before nothing did: the per-file `runCatching`
     * inside `SetJournalStore.zipTo` does not cover the central directory
     * `zip.close()` writes, so a disk-full there used to throw out of an
     * unguarded `viewModelScope.launch` from the first screen of a cold
     * launch. Now it surfaces as a Toast, the same outcome [shareRescued]
     * already gives a failed build.
     *
     * Deliberately does NOT discard afterwards. Sharing can fail at the share
     * sheet, silently as far as this code can tell, and a capture deleted on
     * the assumption that it arrived somewhere is the defect this whole branch
     * exists to close, re-created one step further along.
     */
    fun shareInterrupted(orphan: OrphanedSet) {
        if (orphan.directory in busyInterruptedFlow.value) return
        viewModelScope.launch {
            busyInterruptedFlow.update { it + orphan.directory }
            try {
                ShareUtil.shareStreamed(getApplication(), interruptedName(orphan), "application/zip") { destination ->
                    container.setJournals.zipTo(orphan, destination)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Couldn't build the archive -- try again", Toast.LENGTH_SHORT).show()
            } finally {
                busyInterruptedFlow.update { it - orphan.directory }
            }
        }
    }

    /**
     * Throw an interrupted capture away, at the lifter's word and never on a
     * timer. Nothing else deletes one: a capture the lifter has not ruled on
     * outlives any number of launches.
     *
     * HOLDS [busyInterruptedFlow] too (#304), the same cross-guard
     * [discardRescued] takes against [shareRescued]: checking the key alone
     * guarded a double-tap of this function but never disabled SEND while a
     * discard was in flight, so a SEND could still reopen a directory this
     * function was in the middle of removing.
     */
    fun discardInterrupted(orphan: OrphanedSet) {
        if (orphan.directory in busyInterruptedFlow.value) return
        viewModelScope.launch {
            busyInterruptedFlow.update { it + orphan.directory }
            try {
                withContext(Dispatchers.IO) { container.setJournals.discard(orphan) }
                refreshInterrupted()
            } finally {
                busyInterruptedFlow.update { it - orphan.directory }
            }
        }
    }

    /**
     * Put an interrupted capture back into History (#100). The lifter has
     * confirmed, in the card's own dialog.
     *
     * Where it goes is `JournalRecoveryPolicy`'s decision, made inside
     * `JournalRecovery.recover`: its own session when that session is still
     * open and current, otherwise a new session of its own; a closed session
     * is never reopened. The capture is removed only after the row is
     * written, so a failure here keeps it on the phone and on this card.
     *
     * A RECOVERY IS A SET WRITE, AND HOLDS ONE (whole-branch review of
     * 0.2.0, round 1, CAP-B1). [RecordingHold.RECOVERY_WRITE] is held for
     * the whole call, so the resume offer will not offer to continue a
     * session while a recovery may be placing a set in it, and RESTORE is
     * refused under it (`RecordingServicePolicy.writing` and `mayEndProcess`).
     * Recoveries run one at a time: a tap while one runs is answered with
     * the saving note and starts nothing, so the hold the recovery leaves out
     * of its own wait is always its own. The holds are read by the recovery
     * when it places the set, after the decode, not here at the tap.
     *
     * Any other hold held then is a set write or a session close still
     * running, or the record screen's own SESSION hold -- held under Home
     * only when it has leaked, or while Record is open on top of Home. The
     * recovery decides no position while any is held, and the toast says
     * which (`JournalRecoveryPolicy.waitNote`).
     *
     * The hold's acquire and release are compile- and lint-gated only: no
     * test reaches this function. What is pinned is the policy it reads
     * (`JournalRecoveryPolicyTest`, `RecordingServicePolicyTest`) and the
     * read order in `JournalRecoveryTest`.
     *
     * HOLDS [busyInterruptedFlow] for the whole call, the key SEND and
     * DISCARD already share (#304), so neither can reach a directory this is
     * reading or about to remove.
     */
    fun recoverInterrupted(orphan: OrphanedSet) {
        if (orphan.directory in busyInterruptedFlow.value) return
        val holds = container.recordingHolds
        viewModelScope.launch {
            // viewModelScope dispatches on Main.immediate, so from a tap this
            // runs at once, on the main thread every acquire and release is
            // issued from, and no second tap can pass the check between it
            // and the acquire. A cancelled scope runs none of it, so nothing
            // is acquired that the finally would not release.
            if (RecordingHold.RECOVERY_WRITE in holds.held.value) {
                toast(JournalRecoveryPolicy.waitNote(holds.held.value))
                return@launch
            }
            holds.acquire(RecordingHold.RECOVERY_WRITE)
            busyInterruptedFlow.update { it + orphan.directory }
            try {
                val outcome =
                    withContext(Dispatchers.IO) { container.journalRecovery.recover(orphan) { holds.held.value } }
                toast(recoveryToast(outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "recovering an interrupted set failed; the capture is kept", e)
                toast("Couldn't put it back -- the capture is kept")
            } finally {
                holds.release(RecordingHold.RECOVERY_WRITE)
                busyInterruptedFlow.update { it - orphan.directory }
                refreshInterrupted()
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_LONG).show()
    }

    /**
     * Send a rescued database off the phone, zipped. Issue #111.
     *
     * Streamed through [ShareUtil.shareStreamed] rather than
     * [ShareUtil.shareFile]: a rescued corpus has no upper bound by design,
     * unlike everything else this screen shares, and
     * `container.rescuedDatabases.zipTo` is written to match -- see that
     * function's own KDoc for the measurement behind why.
     *
     * Deliberately does NOT discard afterwards, for the same reason
     * [shareInterrupted] does not: sharing can fail at the share sheet,
     * silently as far as this code can tell, and deleting on the assumption
     * it arrived somewhere would recreate the defect this whole feature
     * exists to close.
     *
     * MARKS [item] BUSY for the whole call, not only the build phase, and
     * CATCHES A FAILED BUILD rather than letting it surface as a bare crash.
     * `RescuedDatabaseStore.zipTo` itself deletes its own partial output on
     * failure and rethrows; this is the boundary where that becomes
     * something the lifter is actually told about, the same shape
     * `SessionDetailViewModel.savePendingTo` already uses for its own IO
     * failure.
     *
     * That last reference used to read as a KDoc link and to describe its
     * target as "two functions down in this same file". Both were wrong:
     * savePendingTo is declared exactly once, in SessionDetailViewModel, a
     * different class in a different file, so the link resolved to nothing.
     * Backticks and a qualified name, the convention `Exporters.kt` already
     * uses for a cross-class reference.
     *
     * CATCHES MORE THAN IOException. The narrower catch left the share
     * sheet's own startActivity free to crash the app outright -- an
     * ActivityNotFoundException when no target exists is a RuntimeException,
     * not an IOException -- from the first screen of a cold launch. A crash
     * here is not silent data loss; the rescued corpus is untouched by a
     * failed share, so a Toast is strictly the better outcome.
     * CancellationException is rethrown rather than swallowed, because
     * catching it would leave a cancelled coroutine reporting a failed share
     * instead of unwinding.
     */
    fun shareRescued(item: RescuedDatabase) {
        if (!rescuedActionAllowed(item, busyRescuesFlow.value)) return
        viewModelScope.launch {
            busyRescuesFlow.update { it + item.directory }
            try {
                ShareUtil.shareStreamed(getApplication(), rescuedName(item), "application/zip") { destination ->
                    container.rescuedDatabases.zipTo(item, destination)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Couldn't build the archive -- try again", Toast.LENGTH_SHORT).show()
            } finally {
                busyRescuesFlow.update { it - item.directory }
            }
        }
    }

    /**
     * Throw a rescued database away, at the lifter's word and never on a
     * timer -- the same rule [discardInterrupted] already follows, and
     * rescued data has the stronger claim to it: it is what an entire
     * corpus, not one set, would otherwise depend on nothing else deleting.
     *
     * Marks [item] busy for the same reason [shareRescued] does: this is
     * the other half of the race that finding closed. The confirmation
     * dialog is `RescuedDatabaseNotice`'s own concern, not this function's
     * -- by the time this runs, the lifter has already confirmed.
     *
     * TELLS THE LIFTER WHEN THE DISCARD DID NOT FULLY HAPPEN. `discard`
     * reports whether the directory actually went; see its own KDoc for why a
     * partial removal is not benign -- what survives re-reads as a fragment,
     * and the card re-titles itself as possibly holding data the live
     * database is missing, which after a deliberate discard is false. Without
     * this the lifter would watch a card they had just confirmed change its
     * own story, with nothing saying a delete had failed. Whether Android
     * ever produces that outcome is unmeasured, and said so where `discard`
     * lives rather than here.
     *
     * AWAITS THE RESCAN BEFORE RELEASING THE BUSY KEY. [refreshRescued] only
     * LAUNCHES a scan, so clearing the key first re-enabled both buttons
     * against a stale list -- a window needing an inhuman second tap, and
     * nothing is destroyed inside it, but joining closes it for the cost of
     * one suspension point.
     */
    fun discardRescued(item: RescuedDatabase) {
        if (!rescuedActionAllowed(item, busyRescuesFlow.value)) return
        viewModelScope.launch {
            busyRescuesFlow.update { it + item.directory }
            try {
                val removed = withContext(Dispatchers.IO) { container.rescuedDatabases.discard(item) }
                refreshRescued().join()
                if (!removed) {
                    Toast.makeText(getApplication(), "Couldn't remove all of it -- try again", Toast.LENGTH_SHORT)
                        .show()
                }
            } finally {
                busyRescuesFlow.update { it - item.directory }
            }
        }
    }

    /**
     * Send one crash report off the phone. Issue #272.
     *
     * The only route it has: app-private storage is not browsable, so without
     * this the trace that would explain a field crash is safe, on the phone,
     * and out of reach of the one person who can send it. Nothing uploads it
     * and there is no network call on this path -- the file goes where the
     * owner points the system share sheet and nowhere else.
     *
     * `text/plain`, so a mail client shows it inline rather than as an opaque
     * attachment.
     *
     * Streamed through `ShareUtil.shareStreamed` rather than read into a
     * ByteArray. A crash file is bounded by construction and this is
     * belt-and-braces, but #273 was the same shape one step along --
     * `SetJournalStore.zipTo` read every stream whole, and on an oversize
     * journal the archive was shared without it -- and the trust is cheap to
     * remove.
     *
     * Deliberately does NOT delete afterwards, the rule [shareInterrupted]
     * and [shareRescued] already follow: a share can fail at the sheet,
     * silently as far as this code can tell.
     *
     * Catches broadly and shows a Toast for the reason [shareRescued] gives:
     * `startActivity` with no target throws ActivityNotFoundException, a
     * RuntimeException, from the first screen of a cold launch -- and a crash
     * inside the crash reporter is a particularly poor outcome.
     *
     * BUSY-GUARDED (#304), the same shape [shareRescued] uses, though a crash
     * file matters less: [CrashLogStore.copyBounded] gives it a hard ceiling,
     * so a double tap here cannot reopen a multi-hundred-megabyte stream the
     * way [shareInterrupted]'s could.
     */
    fun shareCrashReport(report: CrashReport) {
        if (report.file in busyCrashReportsFlow.value) return
        viewModelScope.launch {
            busyCrashReportsFlow.update { it + report.file }
            try {
                ShareUtil.shareStreamed(getApplication(), report.file.name, "text/plain") { destination ->
                    container.crashLogs.copyBounded(report.file, destination)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Couldn't send that crash report", Toast.LENGTH_SHORT).show()
            } finally {
                busyCrashReportsFlow.update { it - report.file }
            }
        }
    }

    /**
     * Throw one crash report away at the owner's word. Issue #272.
     *
     * No confirmation dialog, and the posture is chosen rather than copied.
     * `RescuedDatabaseNotice` puts one in front of DISCARD because a rescued
     * database can be an entire training history; [discardInterrupted] does
     * not, because a set journal is one set. A crash report is neither -- it
     * is a diagnostic
     * about the app, not a record of anything the lifter did, and the app
     * itself deletes the eleventh oldest without asking. A dialog guarding
     * what the pruner already does unasked would be theatre.
     *
     * Awaits the rescan before returning, so the card cannot redraw from a
     * stale list.
     */
    fun deleteCrashReport(report: CrashReport) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.crashLogs.delete(report) }
            refreshCrashReports().join()
        }
    }

    /**
     * WHEN THESE FIGURES RECOMPUTE, and what does not invalidate them.
     *
     * The only Room source in this combine is `sessionRepository.sessions`,
     * whose query is `SELECT * FROM sessions ORDER BY startedAtMs DESC`
     * (`Daos.kt`), which observes the sessions table and nothing else. The
     * per-set reads below -- the sets of each session, and the analysis
     * decoded from each set -- are suspend calls made INSIDE the transform,
     * not flows, so nothing observes `set_records`. An edit confined to that
     * table does not make Room re-emit here: the week tonnage, the week set
     * count and the sparkline keep the values they were last built with
     * until the sessions table itself changes or this flow restarts, which
     * `WhileSubscribed(5_000)` does five seconds after the last collector
     * goes away -- returning to this screen from the session detail screen
     * is past that in ordinary use, and is what makes a void visible here.
     *
     * PRE-EXISTING AND NOT INTRODUCED BY THE VOID MARK (#60): editing a set
     * rpe or a set's load has always had the same bound, and voiding is one
     * more write to the same table. It is stated here because a stale figure
     * that is CORRECT once the screen is revisited reads exactly like a
     * guard that does not work.
     */
    val state =
        combine(
            sessionRepository.sessions,
            planRepository.activePlan,
            container.settings.weightUnit,
            container.settings.bodyWeightKg,
        ) { sessions, planEntity, unit, bodyWeight -> Quad(sessions, planEntity, unit, bodyWeight) }
            .mapLatest { inputs ->
                withContext(Dispatchers.Default) {
                    buildState(inputs.first, inputs.second, inputs.third, inputs.fourth)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setBodyWeight(kg: Double) {
        viewModelScope.launch { container.settings.setBodyWeightKg(kg) }
    }

    fun toggleWeightUnit() {
        viewModelScope.launch {
            container.settings.setWeightUnit(state.value.weightUnit.other())
        }
    }

    private suspend fun buildState(
        sessions: List<SessionEntity>,
        planEntity: com.macrophage.barspeed.data.PlanEntity?,
        unit: WeightUnit,
        bodyWeightKg: Double?,
    ): HomeState {
        val plan = planEntity?.let { planRepository.decode(it) }
        val weekStartMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DAYS_PER_WEEK)

        var weekVolumeKg = 0.0
        var weekSessions = 0
        val history =
            sessions.take(HISTORY_LIMIT).map { session ->
                val sets = sessionRepository.sets(session.id)
                // THE THREE READS BELOW ARE THE THREE #60 NAMES, and each one
                // asks VoidSetPolicy rather than testing the flag itself, so
                // the rule lives in one place that a test on the CI path can
                // reach. A set the lifter says they did not perform is still
                // in `sets` -- it is still in their history and still in the
                // export -- and must appear in none of these.
                val performed = VoidSetPolicy.performed(sets) { it.voided }
                if (session.startedAtMs >= weekStartMs) {
                    weekSessions++
                    // Not `performed.sumOf { … }`: volume asks its own
                    // question of VoidedSetEffects, because a 0 kg voided set
                    // moves no tonnage at all and a policy that only guarded
                    // this figure would have looked right while leaving the
                    // count below it wrong.
                    weekVolumeKg +=
                        VoidSetPolicy.volumeKg(
                            sets.map { VolumeSet(loadKg = it.loadKg, reps = it.actualReps, voided = it.voided) },
                        )
                }
                val spark =
                    performed.mapNotNull { set ->
                        sessionRepository.decodeAnalysis(set)
                            ?.reps
                            ?.takeIf { it.isNotEmpty() }
                            ?.map { rep -> rep.meanConVelMps }
                            ?.average()
                            ?.takeIf { it.isFinite() }
                    }
                HistoryRow(session, performed.size, spark, sessionRepository.sessionHeartRate(session, sets))
            }

        return HomeState(
            planName = plan?.planName,
            planSessionCount = plan?.sessions?.size ?: 0,
            planSetCount = plan?.sessions?.sumOf { s -> s.exercises.sumOf { it.sets.size } } ?: 0,
            planExerciseCount = plan?.sessions?.flatMap { s -> s.exercises.map { it.exercise } }?.distinct()?.size ?: 0,
            weekVolumeKg = weekVolumeKg,
            weekSessions = weekSessions,
            history = history,
            olderSessions = sessions.drop(HISTORY_LIMIT),
            weightUnit = unit,
            bodyWeightKg = bodyWeightKg,
        )
    }

    /** The name the system picker suggests for [exportAllTo]'s archive. */
    fun allSessionsArchiveName(): String = ExportNaming.allSessionsArchiveName(LocalDate.now())

    /**
     * Write every stored session's raw zip into the one archive at [uri]
     * (history-export F5). Each entry is exactly the bytes
     * `RawExporter.buildZip` gives the session's own "Raw CSV" export, under
     * the name that export gets ([ExportNaming.rawZipName]); entries are
     * STORED, not deflated again, so unzipping the archive gives the same
     * files a per-session save would. One session at a time, so memory holds
     * one session's zip, not all of them.
     *
     * ALL SESSIONS, NOT [HISTORY_LIMIT]: read once from the database here,
     * not from [state], which stops at the first 20 for its own figures.
     *
     * A SESSION THAT FAILS IS SKIPPED AND NAMED, never fatal to the rest: the
     * point is getting everything out before a reinstall, and one bad row
     * must not cost the other 46. A failure of the archive itself (the
     * picked file cannot be opened or written) deletes the half-written
     * document, as `SessionDetailViewModel.savePendingTo` does, and says so.
     * Catches Throwable, not only Exception, for the reason
     * [refreshInterrupted] gives: an OutOfMemoryError building one session
     * must not take the app down mid-export. CancellationException is
     * rethrown.
     *
     * NOTHING INCOMPLETE IS LEFT WHERE THE APP CAN HELP IT. If every
     * session fails, the empty archive is deleted and the line says nothing
     * was saved. If the export is cancelled (the lifter leaves Home and the
     * ViewModel is cleared), `use` closes the archive into a well-formed but
     * incomplete zip, so it is deleted under NonCancellable before the
     * cancellation propagates. Process death mid-export runs none of this
     * and can still leave a truncated file at [uri]; nothing here can
     * prevent that.
     *
     * Null [uri] is the picker cancelled. A second call while one runs is
     * ignored; the screen also disables the action.
     *
     * Compile- and lint-gated only: no :app test reaches SAF, Room or the
     * zip stream here. The naming and the summary line are
     * [ExportNaming]'s, pinned in :core:model.
     */
    fun exportAllTo(uri: Uri?) {
        if (uri == null || exportAllFlow.value != null) return
        exportAllFlow.value = ExportAllProgress(0, 0)
        exportAllMessageFlow.value = null
        viewModelScope.launch {
            val app = getApplication<Application>()
            val message =
                try {
                    val sessions = sessionRepository.sessions.first()
                    exportAllFlow.value = ExportAllProgress(0, sessions.size)
                    val failed = mutableListOf<String>()
                    var exported = 0
                    withContext(Dispatchers.IO) {
                        val stream = app.contentResolver.openOutputStream(uri) ?: error("couldn't open the file")
                        ZipOutputStream(stream.buffered()).use { archive ->
                            val taken = mutableSetOf<String>()
                            sessions.forEachIndexed { index, session ->
                                val bytes =
                                    try {
                                        container.rawExporter.buildZip(session.id)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        AppLog.w(TAG, "export all: session ${session.id} failed; skipping it", e)
                                        null
                                    }
                                if (bytes == null) {
                                    failed += failedLabel(session)
                                } else {
                                    archive.putStored(ExportNaming.uniqueEntryName(rawZipName(session), taken), bytes)
                                    exported++
                                }
                                exportAllFlow.value = ExportAllProgress(index + 1, sessions.size)
                            }
                        }
                    }
                    if (exported == 0) {
                        deleteArchive(app, uri)
                        "Export failed: no session could be built. Nothing was saved."
                    } else {
                        ExportNaming.allSessionsSummary(exported, failed)
                    }
                } catch (e: CancellationException) {
                    // ZipOutputStream.use has already closed the archive, so
                    // what is on disk is a well-formed zip missing the rest:
                    // remove it rather than leave it to pass for complete.
                    withContext(NonCancellable) { deleteArchive(app, uri) }
                    throw e
                } catch (e: Throwable) {
                    AppLog.w(TAG, "export all failed; removing the partial archive", e)
                    deleteArchive(app, uri)
                    "Export failed: ${e.message ?: e.javaClass.simpleName}. Nothing was saved."
                } finally {
                    exportAllFlow.value = null
                }
            exportAllMessageFlow.value = message
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Delete the picked document; a provider that refuses leaves it, and nothing more can be done here. */
    private suspend fun deleteArchive(app: Application, uri: Uri) {
        withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
        }
    }

    companion object {
        const val HISTORY_LIMIT = 20
        const val DAYS_PER_WEEK = 7L
    }
}

/**
 * What the lifter is told once [HomeViewModel.recoverInterrupted] has an
 * answer. A wait names the holds it waits for, [RecoveryOutcome.Waiting.held]:
 * the holds the recovery read when it placed the set, less its own.
 */
private fun recoveryToast(outcome: RecoveryOutcome): String = when (outcome) {
    is RecoveryOutcome.Stored ->
        if (outcome.newSession) "Saved to History as a session of its own" else "Saved to History in its session"
    RecoveryOutcome.AlreadyStored -> "Already in History -- the capture was removed"
    is RecoveryOutcome.Waiting -> JournalRecoveryPolicy.waitNote(outcome.held)
    is RecoveryOutcome.Refused -> JournalRecoveryPolicy.refusalNote(outcome.refusal)
}

/** The session's raw zip name, exactly as its own "Raw CSV" export names it. */
private fun rawZipName(session: SessionEntity): String = ExportNaming.rawZipName(
    sessionTimestamp(session.startedAtMs, session.zoneId, session.utcOffsetMinutes),
    session.planSessionName,
)

/** How a session that could not be exported is named in the summary: its day, as History shows it. */
private fun failedLabel(session: SessionEntity): String = DateTimeFormatter.ofPattern("EEE d MMM").format(
    Instant.ofEpochMilli(session.startedAtMs).atZone(ZoneId.systemDefault()),
)

/**
 * One archive entry holding [bytes] as they are: STORED, so a session's
 * already-deflated zip is neither re-compressed nor changed.
 */
private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    putNextEntry(
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        },
    )
    write(bytes)
    closeEntry()
}

/** Names the file after the set it holds, so two captures cannot collide. */
private fun interruptedName(orphan: OrphanedSet): String =
    "interrupted-${orphan.header.exerciseId}-${orphan.header.startedAtMs}.zip"

/** Names the file after the directory it holds, so two rescues cannot collide. */
private fun rescuedName(item: RescuedDatabase): String = "${item.directory.name}.zip"
