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
     * Asks only about a session that is open (no end time), holds at least one
     * set, and had its last set end no more than [RESUME_WINDOW_MS] before
     * [nowMs], and only while nothing from the earlier visit is still writing.
     * A row still being written to could gain a set or an end time after the
     * question is answered, and the answer would be about a row that no longer
     * looks like the one asked about.
     *
     * The window is measured from the last set rather than from the start,
     * because a long session left mid-way is as continuable as a short one,
     * and it ends: a row left open in the morning is not offered in the
     * evening as though it were the same workout.
     */
    fun offer(latest: LatestSession?, nowMs: Long, writeOutstanding: Boolean): LatestSession? {
        if (latest == null || writeOutstanding || latest.endedAtMs != null) return null
        val last = latest.lastSetEndedAtMs ?: return null
        return latest.takeIf { it.setCount > 0 && nowMs - last <= RESUME_WINDOW_MS }
    }

    /**
     * The R-R intervals a close may compute the session HRV from.
     *
     * [rrMs] is what this visit to the record screen has received. A session
     * continued from an earlier visit gets none: that visit's beats were in its
     * memory and are gone, so an RMSSD over this visit's alone would describe
     * part of the session under the whole session's name, by an amount nothing
     * here can bound. The figure is withheld instead, and `Hrv.rmssdMs`
     * answers an empty list with null.
     */
    fun closeRrMs(resumed: Boolean, rrMs: List<Double>): List<Double> = if (resumed) emptyList() else rrMs

    /**
     * The heart rate a close may write as the window after the last set
     * (`rest_after_hrm`, #109).
     *
     * [resumedAtOrderIdx] is the `orderIdx` the first set of this visit takes
     * in a session continued from an earlier visit, or null where the session
     * was started on this visit. [setsCompleted] is one past the `orderIdx` of
     * the last set this visit has written. [samples] is what this visit
     * captured since its last set ended, or, with no set yet, since the
     * session was picked.
     *
     * A continued session that has recorded no set on this visit writes no
     * window. The close attaches the window to the row's last set, which is
     * then the earlier visit's, and what this visit captured is the heart
     * rate on READY, up to [RESUME_WINDOW_MS] later: written there it would
     * state "the window after the last set" about samples that are not. The
     * window the earlier visit captured after that set was in its memory and
     * is gone, so the set keeps no window rather than a wrong one. Once this
     * visit has written a set, the window is that set's, continued or not.
     */
    fun closeRestHr(resumedAtOrderIdx: Int?, setsCompleted: Int, samples: List<HrSample>): List<HrSample> =
        if (resumedAtOrderIdx != null && setsCompleted <= resumedAtOrderIdx) emptyList() else samples

    /** The prompt's title. */
    const val TITLE = "Continue this session?"

    /** The control that continues it. */
    const val CONTINUE_LABEL = "Continue it"

    /** The control that leaves it as it is and starts afresh. */
    const val START_NEW_LABEL = "Start a new one"

    /**
     * The prompt's body, for [latest] and its start time as the screen
     * renders it, [startedAt].
     *
     * It says what continuing does that the lifter cannot see from the
     * prompt alone: the sets go into this row whichever session is picked
     * next, so the row keeps its own plan session's name over sets recorded
     * under another, and a plan's queue starts again at its first slot,
     * because where the earlier visit stood in it was never stored.
     */
    fun body(latest: LatestSession, startedAt: String): String {
        val name = latest.planSessionName?.let { " ($it)" } ?: ""
        val sets = if (latest.setCount == 1) "1 set" else "${latest.setCount} sets"
        return "You left a session$name open, started at $startedAt, with $sets saved. Continue it and the " +
            "sets you record next are added to it, whichever session you pick; a plan starts again from its " +
            "first set. Start a new one and it stays as it is, with no end time."
    }
}
