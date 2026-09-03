package com.macrophage.barspeed.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.macrophage.barspeed.LiftingApp
import com.macrophage.barspeed.data.OrphanedSet
import com.macrophage.barspeed.data.RescuedDatabase
import com.macrophage.barspeed.data.SessionEntity
import com.macrophage.barspeed.model.VoidSetPolicy
import com.macrophage.barspeed.model.VolumeSet
import com.macrophage.barspeed.model.WeightUnit
import com.macrophage.barspeed.ui.ShareUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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

    init {
        refreshInterrupted()
        refreshRescued()
    }

    /** Re-scan private storage. Cheap: an empty root is the normal case. */
    fun refreshInterrupted() {
        viewModelScope.launch {
            interruptedFlow.value = withContext(Dispatchers.IO) { container.setJournals.orphans() }
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
        rescuedFlow.value = withContext(Dispatchers.IO) { container.rescuedDatabases.rescued() }
    }

    /**
     * Send an interrupted capture to the lifter, as the raw files.
     *
     * The only route this data has off the phone -- app-private storage is not
     * browsable -- so without it the capture would be safe and permanently out
     * of reach of the person whose set it is.
     *
     * Deliberately does NOT discard afterwards. Sharing can fail at the share
     * sheet, silently as far as this code can tell, and a capture deleted on
     * the assumption that it arrived somewhere is the defect this whole branch
     * exists to close, re-created one step further along.
     */
    fun shareInterrupted(orphan: OrphanedSet) {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { container.setJournals.zip(orphan) }
            ShareUtil.shareFile(getApplication(), interruptedName(orphan), zip, "application/zip")
        }
    }

    /**
     * Throw an interrupted capture away, at the lifter's word and never on a
     * timer. Nothing else deletes one: a capture the lifter has not ruled on
     * outlives any number of launches.
     */
    fun discardInterrupted(orphan: OrphanedSet) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.setJournals.discard(orphan) }
            refreshInterrupted()
        }
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
        if (item.directory in busyRescuesFlow.value) return
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
        if (item.directory in busyRescuesFlow.value) return
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
                HistoryRow(session, performed.size, spark)
            }

        return HomeState(
            planName = plan?.planName,
            planSessionCount = plan?.sessions?.size ?: 0,
            planSetCount = plan?.sessions?.sumOf { s -> s.exercises.sumOf { it.sets.size } } ?: 0,
            planExerciseCount = plan?.sessions?.flatMap { s -> s.exercises.map { it.exercise } }?.distinct()?.size ?: 0,
            weekVolumeKg = weekVolumeKg,
            weekSessions = weekSessions,
            history = history,
            weightUnit = unit,
            bodyWeightKg = bodyWeightKg,
        )
    }

    companion object {
        const val HISTORY_LIMIT = 20
        const val DAYS_PER_WEEK = 7L
    }
}

/** Names the file after the set it holds, so two captures cannot collide. */
private fun interruptedName(orphan: OrphanedSet): String =
    "interrupted-${orphan.header.exerciseId}-${orphan.header.startedAtMs}.zip"

/** Names the file after the directory it holds, so two rescues cannot collide. */
private fun rescuedName(item: RescuedDatabase): String = "${item.directory.name}.zip"
