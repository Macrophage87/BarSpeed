package com.macrophage.barspeed.model

/**
 * The newest session row, as the resume question needs it (#48).
 *
 * [lastSetEndedAtMs] is null only where the row has no set at all, and
 * [nextOrderIdx] is one past the highest `orderIdx` the row holds -- the slot
 * the next set recorded into this session must take, so a continued session
 * cannot write two sets at one position.
 */
data class LatestSession(
    val sessionId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val setCount: Int,
    val lastSetEndedAtMs: Long?,
    val nextOrderIdx: Int,
    val planSessionName: String?,
)

/**
 * Whether walking back into Record should ask "Continue this session?" (#48).
 *
 * THE DEFECT. The record screen's state lives in a ViewModel scoped to its
 * back-stack entry, and `RecordState.sessionId` is assigned only when the first
 * set is written. Nothing read an open session back on entry. So a lifter who
 * left after two sets and walked back in made one workout two rows: the first
 * with no end time, forever, and both exporting separately.
 *
 * THE OWNER'S RULING for 0.2.0 is a prompt, never a silent adoption. A null
 * `endedAtMs` has meant "abandoned" since issue 19's piece 4; an app that
 * quietly appended to such a row would make the column stop meaning anything.
 * Asking keeps it meaning one thing at every moment: a row with no end time
 * is a session the lifter left and has not chosen to continue, and a row the
 * lifter does continue gets its end time from the finish they ask for.
 *
 * Here rather than in `:app` for the reason [RecordExitPolicy] is: no test on
 * the CI path can reach a decision written beside a `@Composable` or a
 * ViewModel.
 */
object SessionResumePolicy {
    /** How far back the newest session is looked for, by its start. */
    const val LOOKBACK_MS: Long = 24L * 60 * 60 * 1000

    /** How long after its last set an open session is still offered. */
    const val RESUME_WINDOW_MS: Long = 3L * 60 * 60 * 1000

    /**
     * The session to ask about, or null to ask nothing.
     *
     * [latest] is the newest session row started within [LOOKBACK_MS] of
     * [nowMs], or null where there is none. [writeOutstanding] is whether a
     * set write or a session close from an earlier visit is still running on
     * the process-wide scope.
     *
     * Never asks today: re-entry has always started a fresh session.
     */
    @Suppress("UnusedParameter") // The rule arrives with its red (#48); today it never asks.
    fun offer(latest: LatestSession?, nowMs: Long, writeOutstanding: Boolean): LatestSession? = latest?.takeIf {
        false
    }

    /**
     * The R-R intervals a close may compute the session HRV from.
     *
     * [rrMs] is what this visit to the record screen has received. Today it
     * is used whole whether or not the session was continued from an earlier
     * visit.
     */
    @Suppress("UnusedParameter") // The rule arrives with its red (#48); today the list is used whole.
    fun closeRrMs(resumed: Boolean, rrMs: List<Double>): List<Double> = rrMs

    /** The prompt's title. */
    const val TITLE = "Continue this session?"

    /** The control that continues it. */
    const val CONTINUE_LABEL = "Continue it"

    /** The control that leaves it as it is and starts afresh. */
    const val START_NEW_LABEL = "Start a new one"

    /**
     * The prompt's body, for [latest] and its start time as the screen
     * renders it, [startedAt].
     */
    fun body(latest: LatestSession, startedAt: String): String {
        val name = latest.planSessionName?.let { " ($it)" } ?: ""
        val sets = if (latest.setCount == 1) "1 set" else "${latest.setCount} sets"
        return "You left a session$name open, started at $startedAt, with $sets saved. Continue it and the " +
            "sets you record next are added to it. Start a new one and it stays as it is, with no end time."
    }
}
