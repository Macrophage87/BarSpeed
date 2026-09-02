package com.macrophage.barspeed.record

import com.macrophage.barspeed.RecordingHolds
import com.macrophage.barspeed.data.SessionRepository
import com.macrophage.barspeed.hrm.Hrv
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.SessionCloseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Everything the session close needs, frozen at the moment the lifter asked for
 * it.
 *
 * Frozen for the reason the set-end write's input is: a retry that recomputed
 * would not be a retry. [endedAtMs] would move to whenever the second attempt
 * happened rather than to when the lifter tapped Finish, and [hrvRmssdMs] would
 * be recomputed over beats that kept arriving in between, so what got stored
 * would not be the number the session ended on.
 *
 * [sessionId] is nullable because a session that recorded no set has no row.
 * Nothing is written in that case, which is what happens today.
 */
private data class PendingSessionClose(
    val sessionId: Long?,
    val endedAtMs: Long,
    val hrvRmssdMs: Double?,
    /**
     * The rating the lifter gave at the finish, or null where they skipped it
     * (#159).
     *
     * Frozen here for a reason the other two do not have. A retry cannot ask
     * again -- the panel is gone and the lifter is looking at a failure notice
     * -- so a rating not held here is a rating lost, and the column it goes to
     * is written once per session with no correction surface anywhere.
     */
    val sessionRpe: Int?,
    /**
     * The heart rate captured after the last set, on its way to that set's
     * `rest_after_hrm` stream (#109).
     *
     * Frozen for the reason the other three are, and one more: the passive HR
     * collector keeps appending to the buffer this came from, so a retry that
     * re-read it would store a window whose end moved to whenever the retry
     * happened rather than to when the lifter tapped Finish.
     */
    val restHrSamples: List<HrSample>,
)

/**
 * Closing the session, on a scope the record screen cannot take with it.
 *
 * A collaborator rather than more methods on `RecordViewModel`, in the shape
 * [SetRatingTracker] already uses: it owns the bookkeeping and hands results
 * back for the ViewModel to apply to its own state.
 *
 * The close was the last durable writer on the record screen still running on
 * `viewModelScope`, which the pop cancels wherever the write has reached —
 * inside the session read, inside the read of every set row, or inside the
 * update itself. What that costs is not the timestamp. `endedAtMs`, `hrAvgBpm`
 * and `hrMaxBpm` can all be rebuilt later from rows that are already durable,
 * because each set row carries its own end time and its own heart-rate columns.
 * The session HRV cannot. Its input is the R-R intervals collected across the
 * whole session, rests included, and the only R-R that reaches storage is the
 * per-set `hrm` stream covering the sets themselves. A cancelled close is the
 * difference between the lifter having that number and never having it.
 *
 * The session rating (#159) is in the same position and is worse: HRV could in
 * principle be recomputed if the R-R ever reached disk, and how a workout FELT
 * is recorded in no artifact at all. It is stated once, at the finish, and a
 * close that never lands takes it with it.
 *
 * The rest window after the last set (#109) is written here too, after the
 * close, because there is no next set for it to ride into the database with.
 * Its durability before this point is the same as every other rest window's --
 * none, it lives in `:app`'s heap -- so a process death between the last set
 * and the tap on Finish loses it exactly as a death mid-rest loses the window
 * in progress. What changes is that it now has a write to reach at all.
 *
 * [scope] is the process-wide one, created once in `AppContainer` and never
 * cancelled. Dispatching on `Dispatchers.Main.immediate` is the load-bearing
 * half rather than a detail: that scope is `SupervisorJob() +
 * Dispatchers.Default`, and every state update the caller makes from these
 * callbacks is a non-atomic read-modify-write on a flow the main thread already
 * writes to.
 */
class SessionCloser(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val holds: RecordingHolds,
) {
    /**
     * True once a close has been asked for, and never cleared.
     *
     * The rest screen's Finish session control is an undebounced `TextButton`,
     * so two taps would otherwise launch two closes; and because both start
     * inline on the main thread, both can read a null end time inside
     * `endSession` before either writes. The repository's own guard closes the
     * sequential case, this closes the concurrent one, and neither needs a lock.
     *
     * Not cleared on failure. The way forward from there is [retry], which
     * replays the frozen close rather than freezing a new one.
     */
    private var asked = false

    private var pending: PendingSessionClose? = null

    /**
     * Freeze the close's input and run it. Returns false, having done nothing,
     * when a close was already asked for.
     *
     * [onState] reports where the close has got to. [onClosed] runs once, on the
     * main thread, when it has landed — including when there was no session row
     * to write, because a session that recorded nothing still ends.
     */
    fun close(
        sessionId: Long?,
        endedAtMs: Long,
        rrMs: List<Double>,
        sessionRpe: Int?,
        restHrSamples: List<HrSample>,
        onState: (SessionCloseState) -> Unit,
        onClosed: () -> Unit,
    ): Boolean {
        if (asked) return false
        asked = true
        pending = PendingSessionClose(sessionId, endedAtMs, Hrv.rmssdMs(rrMs), sessionRpe, restHrSamples)
        run(onState, onClosed)
        return true
    }

    /** Replay a close that came back failed, from the copy frozen at the first tap. */
    fun retry(onState: (SessionCloseState) -> Unit, onClosed: () -> Unit) = run(onState, onClosed)

    private fun run(onState: (SessionCloseState) -> Unit, onClosed: () -> Unit) {
        val p = pending ?: return
        onState(SessionCloseState.IN_FLIGHT)
        // Taken before the launch and given up in the finally below, so the
        // process keeps foreground-service priority for as long as the close is
        // outstanding. The prompt this window raises offers the lifter a labelled
        // exit that promises the close lands either way; dropping priority on the
        // way out is what would make that promise false, and hrvRmssdMs is the
        // column that cannot be rebuilt from anything durable.
        holds.acquire(RecordingHold.SESSION_CLOSE)
        scope.launch(Dispatchers.Main.immediate) {
            try {
                p.sessionId?.let {
                    repository.endSession(it, p.endedAtMs, p.hrvRmssdMs, p.sessionRpe)
                    // AFTER the close and never before it, and the ordering is
                    // the whole of the reasoning (#109). This is a second,
                    // non-atomic write onto a row that is already durable --
                    // exactly what `rest_before_hrm`'s forward attachment
                    // exists to avoid -- so it is placed where it cannot delay
                    // or fail the one write whose input exists nowhere else.
                    // If it throws, the close reports FAILED and the frozen
                    // copy is replayed; `endSession` refuses a second write
                    // and the repository refuses a second window, so the retry
                    // can only add what is missing.
                    repository.recordFinalRestWindow(it, p.restHrSamples)
                }
                pending = null
                onClosed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Deliberately broad, as the set write's catch is. The scope has
                // no CoroutineExceptionHandler, so anything escaping kills the
                // process, and it can now arrive after the screen is gone.
                // Swallowing it would be worse: the stage would never reach
                // FINISHED and the lifter would be left on a rest screen with no
                // account of why. The failure becomes a state the screen shows,
                // with the frozen close still behind it.
                onState(SessionCloseState.FAILED)
            } finally {
                // Both terminal branches, which is why it is a finally and not a
                // line at the end of the try. A failed close still ends the
                // close: if the lifter is still here the screen's own hold keeps
                // the service up for the retry, and if they are gone there is
                // nothing left to protect.
                holds.release(RecordingHold.SESSION_CLOSE)
            }
        }
    }
}
