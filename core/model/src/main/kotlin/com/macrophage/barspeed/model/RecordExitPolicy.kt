package com.macrophage.barspeed.model

/**
 * One way out of the record screen, or the way to stay on it.
 *
 * Which exits exist at a given moment is a decision, not presentation, so it is
 * stated here. The wording of each button stays with the screen that draws it.
 */
enum class ExitAction {
    /**
     * Leave, discarding the set being recorded.
     *
     * Nothing of that set has reached the database: `beginSet` writes only to
     * in-memory buffers and `recordSet` is not called until the set ends, so
     * the loss is total and unrecoverable. Saving instead is deliberately NOT
     * offered here — the durable write runs on `viewModelScope`, which the pop
     * cancels, so a save button on this prompt would be a race rather than a
     * feature. The record screen already carries a save-and-end control that is
     * not a race; the prompt's text points at it instead.
     */
    DISCARD_SET_AND_LEAVE,

    /**
     * Close the session row, and STAY on the screen.
     *
     * Deliberately not a leave. `finishSession` writes on `viewModelScope` too,
     * so popping in the same frame would race the write that closes the
     * session. The FINISHED stage draws its own exits once that write lands.
     */
    FINISH_SESSION,

    /**
     * Leave with the session row still open.
     *
     * Distinct from [FINISH_SESSION] on purpose. An abandoned session and a
     * finished one are different facts, and a null `endedAtMs` is the only
     * place that difference is recorded; routing every exit through a finish
     * would write a deliberate-finish timestamp over an abandonment.
     */
    LEAVE_SESSION_OPEN,

    /** Dismiss the prompt. Every prompt offers it — a prompt with no way back is a trap. */
    STAY,
}

/**
 * What Back offers, and the order the screen presents it in.
 *
 * [actions] is read by the screen to build the buttons, so the lists below are
 * what ships, not a description of it.
 */
enum class ExitPrompt(val actions: List<ExitAction>) {
    /**
     * Nothing is at risk: Back leaves at once, with no prompt at all. A named
     * state rather than an empty list, so "no prompt" cannot be confused with
     * "a prompt whose options went missing".
     */
    NONE(emptyList()),

    /** A set is being recorded and none of it is written yet. */
    SET_IN_PROGRESS(listOf(ExitAction.DISCARD_SET_AND_LEAVE, ExitAction.STAY)),

    /** Every set is written, but the session row is still open. */
    SESSION_OPEN(listOf(ExitAction.FINISH_SESSION, ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY)),

    /**
     * The set has been ended and its durable write has not finished yet.
     *
     * [ExitAction.FINISH_SESSION] is deliberately absent, and that is the
     * reason this member exists rather than reusing [SESSION_OPEN]. Closing the
     * session reads back every set row to build the session's heart-rate
     * summary, so a finish issued while a set insert is still outstanding
     * computes that summary over a set list the set is missing from -- and the
     * row is written once, so nothing corrects it afterwards.
     *
     * No action cancels the write. A prompt cannot offer to un-write a set
     * whose insert is already in flight, and offering it would be a race
     * dressed up as a choice.
     */
    SET_SAVING(listOf(ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY)),

    /**
     * The set was ended and its durable write failed.
     *
     * Distinct from [SET_IN_PROGRESS] even though the stage is the same and the
     * actions match. The set is over, so the controls that end a set are gone;
     * what the lifter has instead is a retry, and a prompt that pointed them at
     * the effort grid here would name a control that is no longer on screen.
     */
    SET_UNSAVED(listOf(ExitAction.DISCARD_SET_AND_LEAVE, ExitAction.STAY)),
}

/**
 * Where the set-end write has got to, which Back has to know about because the
 * honest answer changes three times in the second or so after the effort tile
 * is tapped.
 *
 * Deliberately three states rather than a flag. A boolean can say "saving or
 * not", and the state that needs saying most -- the write came back and failed,
 * the set is still only in memory -- is the one a boolean has to push into
 * prose in `:app`, where nothing tests it.
 *
 * This is not [Stage]. The stage is `IN_SET` for all three: the record screen
 * does not leave it until the write lands.
 */
enum class SetWriteState {
    /** No set-end write is outstanding. Either none was started or one finished. */
    NONE,

    /** A set-end write is running now. */
    IN_FLIGHT,

    /**
     * A set-end write came back failed and the set exists only in memory.
     *
     * Not folded into [NONE]. "Nothing is in flight" and "the last thing that
     * flew was lost" are different facts, and only the second one is worth
     * stopping the lifter over.
     */
    FAILED,
}

/**
 * What pressing Back on the record screen must do.
 *
 * Pure, and here rather than in `:app`, for the reason [Stage] is here: `:app`
 * has no test source set, so a decision written beside its callers cannot be
 * tested at all. Nothing in this file touches Android or navigation. The screen
 * asks, and renders the answer.
 */
object RecordExitPolicy {
    /**
     * The prompt to raise before leaving [stage], or [ExitPrompt.NONE] to leave
     * at once.
     *
     * Three stages leave at once, and that is a decision rather than an
     * omission. SETUP has started nothing. READY holds a chosen plan session
     * and nothing else: the session row is not created until the first set is
     * recorded, no set is in flight, and the foreground service has not been
     * started — and it cannot be reached with sets already behind it, because
     * `startNextSet` writes READY and calls `beginSet` in the same frame.
     * FINISHED has already written everything and stopped the service.
     * Prompting at any of the three would cost the prompt that matters its
     * credibility: a gate that fires where nothing is at risk teaches the
     * lifter to dismiss it without reading.
     *
     * IN_SET is ranked first for consequence. Nothing of the set in progress
     * has reached the database, so leaving destroys it outright, and on a
     * session's first set the session row goes with it.
     *
     * RESTING loses less and still loses something no reprocessing can get
     * back. Every set is durably written by then, but the session row is open,
     * and the R-R intervals collected across the rest window live only in
     * memory — the per-set HR streams keep the in-set beats, nothing keeps
     * these. The last set's rep count and effort rating also stop being
     * correctable, because the only screen that can edit them is this one.
     *
     * [write] is required rather than defaulted so that a caller cannot keep
     * compiling while silently answering the old question. It is read for
     * `IN_SET` alone -- every other stage ignores it, which is pinned rather
     * than assumed, because "cannot happen" and "is not looked at" are
     * different guarantees and only the second one is this function's to make.
     *
     * As of this commit [write] is inert: all three states map to the prompt
     * the stage alone gave. It is branched on rather than ignored because
     * detekt's `UnusedParameter` rejects a parameter that is accepted and never
     * read, and because an exhaustive `when` here means the commit that adds a
     * state cannot leave it silently answered by a fallback. The mapping
     * arrives two commits later, after the differentials that fail against
     * this version.
     */
    fun promptFor(stage: Stage, write: SetWriteState): ExitPrompt = when (stage) {
        Stage.SETUP, Stage.READY, Stage.FINISHED -> ExitPrompt.NONE
        Stage.IN_SET ->
            when (write) {
                SetWriteState.NONE -> ExitPrompt.SET_IN_PROGRESS
                SetWriteState.IN_FLIGHT -> ExitPrompt.SET_IN_PROGRESS
                SetWriteState.FAILED -> ExitPrompt.SET_IN_PROGRESS
            }
        Stage.RESTING -> ExitPrompt.SESSION_OPEN
    }
}
