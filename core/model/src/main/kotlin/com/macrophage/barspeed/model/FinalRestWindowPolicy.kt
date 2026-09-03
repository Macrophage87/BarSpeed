package com.macrophage.barspeed.model

/**
 * What the session close should do with the heart rate captured after the last
 * set, issue #109.
 *
 * Every branch is a distinct state and none of them is a fallback. Which one
 * was taken is the whole account of why a session's archive does or does not
 * carry a trailing rest file, and collapsing the three refusals into one
 * boolean would make "the strap was off" and "nothing was recorded to attach
 * it to" indistinguishable afterwards.
 */
enum class FinalRestWindowDecision {
    /** Write it, onto the last set of the session, under its own kind. */
    WRITE,

    /**
     * The final rest window is empty.
     *
     * A file would be worse than no file: an empty heart-rate CSV claims a
     * window was captured and that it was silent, which is a different fact
     * from the strap having been off or out of range. Absence stays absence.
     */
    NO_SAMPLES,

    /**
     * There is no set row to hang the window on.
     *
     * A session that recorded no set has no row of its own either, so the
     * READY window before a set that never happened belongs to nothing. This
     * is the one window #109 does not close and it is named rather than
     * silently folded into [NO_SAMPLES].
     */
    NO_SET_TO_ATTACH_TO,

    /**
     * The window is already on the set.
     *
     * The close is retryable -- `SessionCloser` replays a frozen close after a
     * failure -- so this write can be attempted more than once for one
     * session. A second attempt must not append a second copy, which would put
     * two files of the same kind on one set and leave a reader to guess which
     * is the window.
     */
    ALREADY_WRITTEN,
}

/**
 * The decision above, taken from three facts and nothing else.
 *
 * Pure and in `:core:model` for the reason every policy here is: the write it
 * governs runs in `:core:data` against Room, on a path `:app` triggers, and
 * none of that is reachable by a test in this repository. The decision is.
 *
 * All three facts are asked for up front rather than lazily, and the cost is
 * stated rather than hidden: [alreadyWritten] is answered by reading the last
 * set's existing streams, whose gzipped blobs come off disk with it, on every
 * session close including the common one where no strap was worn. Taking that
 * read conditionally would put half of this `when` at the call site, and two
 * descriptions of one rule are how they start to disagree. The read happens
 * after the session close's own write, which is the one that cannot be
 * reconstructed from anything durable.
 *
 * The precedence between the three refusals is load-bearing and is pinned
 * rather than left to be read off the code.
 */
object FinalRestWindowPolicy {
    /**
     * [sampleCount] is how many samples the final rest window holds. Since
     * #178 that includes the last set's own capture from the instant it was
     * called over -- copied forward, not moved -- so a set that spoke `Done`
     * and kept recording now reaches [FinalRestWindowDecision.WRITE] where it
     * used to reach [FinalRestWindowDecision.NO_SAMPLES]: the buffer this
     * count is taken over is seeded by [RestClockPolicy.restWindowSeed] before
     * any genuinely-after-the-set sample can arrive.
     * [hasSetToAttachTo] is whether the session has any set row at all;
     * [alreadyWritten] is whether that set already carries a trailing window.
     */
    fun decide(sampleCount: Int, hasSetToAttachTo: Boolean, alreadyWritten: Boolean): FinalRestWindowDecision = when {
        // Non-positive rather than zero. A negative count is a caller
        // error, and the response that loses nothing is to write no file
        // rather than one whose length nobody can state.
        sampleCount <= 0 -> FinalRestWindowDecision.NO_SAMPLES
        !hasSetToAttachTo -> FinalRestWindowDecision.NO_SET_TO_ATTACH_TO
        alreadyWritten -> FinalRestWindowDecision.ALREADY_WRITTEN
        else -> FinalRestWindowDecision.WRITE
    }
}
