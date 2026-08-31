package com.macrophage.barspeed.model

/**
 * A reason the recording foreground service must be running.
 *
 * A set of reasons rather than one boolean, because the service has more than
 * one job and they do not begin and end together. The screen needs it so the
 * sensor keeps streaming with the display off; a durable write needs it so the
 * process keeps its priority until the bytes are on disk. Collapsing them into
 * "recording, yes or no" is what makes the second job invisible, and the second
 * job is the one whose loss is unrecoverable.
 *
 * [armsService] is a property of the reason, not of the moment. Only [SESSION]
 * carries it, and it carries it on EVERY acquire rather than only the first.
 * That is deliberate and it is load-bearing: all three catch clauses in
 * `RecordingService.onStartCommand` end in `stopSelf(startId)`, so a start that
 * was refused leaves no service running, and the next `beginSet` re-arming it
 * is the only retry there is. A first-acquire-only rule would leave the service
 * dead for the rest of the session after one refusal.
 *
 * The write holds do not arm it. They are acquired while a set is already being
 * recorded, so the service is already up; issuing another start there would
 * re-enter `onStartCommand` mid-set for nothing, and on API 34 with Nearby
 * devices denied that path can throw and stop the service that was legitimately
 * held.
 */
enum class RecordingHold(val armsService: Boolean) {
    /**
     * The record screen is live and a set has been begun.
     *
     * Acquired on every `beginSet`, released when the screen goes away or the
     * session is closed.
     *
     * This is the only hold that is not scoped to a coroutine, and so the only
     * one that can be left held. [SET_WRITE] and [SESSION_CLOSE] are given up
     * in a `finally` that covers both terminal branches of the work they
     * describe, so short of the process dying they cannot leak. This one is a
     * flag in all but name, and it has two known non-release paths, neither of
     * them closed here and neither of them new: nothing releases it on a task
     * swipe, and a foreground service that START_STICKY recreates after a
     * process kill comes back with no holder able to release anything at all.
     * Both are stated rather than fixed; see the commit body.
     */
    SESSION(armsService = true),

    /**
     * A set-end write is outstanding on the process-wide scope.
     *
     * Acquired before the launch and released in that coroutine's `finally`, so
     * it covers the success path and the failure path alike. What it protects is
     * the interval in which `ImuCsv.encode`, `Gzip.compress` and the insert run:
     * the raw capture is in memory and nowhere else for the whole of it, and
     * the in-memory copy dies with the ViewModel, so a process killed there
     * loses the set with no retry left to offer.
     */
    SET_WRITE(armsService = false),

    /**
     * A session close is outstanding on the process-wide scope.
     *
     * The same shape as [SET_WRITE] and for a sharper reason: `ExitPrompt`
     * offers the lifter a labelled exit during exactly this window, and its
     * `ExitAction.LEAVE_SESSION_CLOSING` promises in words that the close lands
     * either way. Dropping the process's priority on the way out is what would
     * make that promise false, over `hrvRmssdMs`, whose input is held in memory
     * and reaches storage nowhere else.
     *
     * A separate member from [SET_WRITE] rather than one shared "a write is
     * running", so that the pair can be represented. The same reasoning keeps
     * `SetWriteState` and `SessionCloseState` apart.
     */
    SESSION_CLOSE(armsService = false),
}

/** What the holder must do to the service, or [NONE] to leave it as it is. */
enum class FgsCommand {
    /** Start it, or re-arm it if it is already running. */
    START,

    /** Stop it. Emitted once, when the last reason to run it goes away. */
    STOP,

    /**
     * Leave it alone.
     *
     * A named answer rather than a null command, so "nothing to do" cannot be
     * confused with "the policy had no answer".
     */
    NONE,
}

/** The held set after the change, and the one command that change implies. */
data class HoldTransition(val held: Set<RecordingHold>, val command: FgsCommand)

/**
 * When the recording foreground service runs.
 *
 * Pure, and here rather than in `:app`, for the reason [RecordExitPolicy] is
 * here: no test on the CI path can construct its Android callers, so a
 * decision written beside them cannot be tested at all. Nothing in this file touches Android. The holder in
 * `:app` keeps the set, calls this, and turns the command into a `startService`
 * or a `stopService`.
 *
 * Both functions take the held set and return the new one rather than mutating
 * anything, so the whole decision — including what is idempotent — is
 * observable from a test. Re-acquiring a hold that is already held and
 * releasing one that was never held are both ordinary, and both had to be
 * decided rather than left to whatever a caller happened to do.
 */
object RecordingServicePolicy {
    /**
     * Take [hold], and say whether the service must be started or re-armed.
     *
     * Idempotent in the held set: a hold taken twice is held once. `beginSet`
     * takes [RecordingHold.SESSION] on every set, so a counted hold would need
     * five releases after five sets and the service would never stop.
     */
    fun acquire(held: Set<RecordingHold>, hold: RecordingHold): HoldTransition = HoldTransition(
        held = held + hold,
        command = if (hold.armsService) FgsCommand.START else FgsCommand.NONE,
    )

    /**
     * Give up [hold], and say whether the service must now stop.
     *
     * STOP exactly when the last reason to run goes away, rather than when the
     * screen leaves. The screen leaving used to be the whole rule, and that is
     * the defect: a set-end write and a session close both outlive the record
     * screen by design, and stopping the service under them drops the process
     * out of foreground priority while the only copy of a set's samples, or the
     * only copy of a session's R-R intervals, is still in memory.
     *
     * "The last reason" is measured on what this call actually took away, which
     * is why an empty result is not enough on its own. A release of something
     * that was not held changes nothing, so it may not report that everything
     * just ended; `onCleared` runs after `onSessionClosed` on the finish path,
     * so that double release is the ordinary case rather than an edge, and
     * answering STOP to it would tear down a service the next hold still needs.
     */
    fun release(held: Set<RecordingHold>, hold: RecordingHold): HoldTransition {
        val remaining = held - hold
        return HoldTransition(
            held = remaining,
            command = if (remaining.isEmpty() && held.isNotEmpty()) FgsCommand.STOP else FgsCommand.NONE,
        )
    }
}
