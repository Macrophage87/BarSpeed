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
     * offered here; the record screen already carries a save-and-end control,
     * and the prompt's text points at it.
     *
     * The reason for that used to be that a save button here would be a race,
     * because the durable write ran on `viewModelScope` and the pop cancelled
     * it. Both halves of that stopped being true when the write moved to the
     * process-wide scope, and the conclusion inverts with them: a save from
     * here would now complete. It is still not offered, for a different and
     * smaller reason — this prompt is raised mid-set, where "save" would mean
     * ending the set, and ending a set is a decision with a rating attached
     * rather than a side effect of leaving.
     *
     * A set whose write is already running is not this prompt at all; see
     * [ExitPrompt.SET_SAVING].
     */
    DISCARD_SET_AND_LEAVE,

    /**
     * Close the session row, and STAY on the screen.
     *
     * The reason given here used to be that `finishSession` wrote on
     * `viewModelScope`, so popping in the same frame would race the write. That
     * is no longer true and the note is corrected rather than deleted: the close
     * has joined the set write and the two rest-screen corrections on the
     * process-wide scope, so it is now the case that no durable writer on this
     * screen can be cancelled by leaving it.
     *
     * Still not a leave, for a smaller and better reason. The FINISHED stage is
     * a destination rather than a formality — it offers the session and its
     * export — and arriving there is what the lifter asked for. While the close
     * runs, [SessionCloseState.IN_FLIGHT] answers Back with its own prompt, so
     * staying here is no longer the thing that protects the write.
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

    /**
     * Leave while the session is being closed. The close lands either way.
     *
     * Separate from [LEAVE_SESSION_OPEN] because of what the label has to say,
     * not because the navigation differs — both pop. "Leave without finishing"
     * is false once a finish is in flight: the session is being finished, and
     * nothing the lifter does from here stops that. Offering the other label
     * would be a choice with no effect dressed as a choice.
     */
    LEAVE_SESSION_CLOSING,

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

    /**
     * A finish the lifter asked for is in flight and has not landed yet.
     *
     * The same shape as [SET_SAVING] and for the same reason: the work outlives
     * the screen, so leaving is safe, and the only honest offers are to go or to
     * wait. [ExitAction.FINISH_SESSION] is absent because it is already
     * happening, and [ExitAction.LEAVE_SESSION_OPEN] is absent because the
     * session is not going to be left open.
     */
    SESSION_CLOSING(listOf(ExitAction.LEAVE_SESSION_CLOSING, ExitAction.STAY)),

    /**
     * A finish the lifter asked for came back failed. The session is still open.
     *
     * [ExitAction.FINISH_SESSION] is deliberately absent even though finishing
     * is exactly what failed, for the reason [SET_UNSAVED] withholds the
     * controls that end a set: the retry is a control on the rest screen, next
     * to the explanation of what went wrong, not a button in the gate the lifter
     * is trying to leave through. Leaving here really does leave the session
     * open, so that action returns.
     */
    SESSION_NOT_CLOSED(listOf(ExitAction.LEAVE_SESSION_OPEN, ExitAction.STAY)),
}

/**
 * Where the close of the session has got to.
 *
 * A third fact rather than more values on [SetWriteState], because the two are
 * independent: a set write and a session close can be outstanding at the same
 * moment, and merging them into one enum would make that pair unrepresentable
 * and would silently delete the guarantee that every stage but IN_SET answers
 * the same whatever the write state.
 *
 * [FAILED] exists for the reason [SetWriteState.FAILED] does. "Nothing is being
 * closed" and "the close came back failed" are different facts, and the second
 * is the one worth stopping the lifter over: the session's HRV is computed from
 * R-R intervals that are held in memory and nowhere else, so a close that did
 * not land is the difference between having that number and never having it.
 */
enum class SessionCloseState {
    /** No close is outstanding. Either none was asked for, or one finished. */
    NONE,

    /** A close is running now. */
    IN_FLIGHT,

    /** A close came back failed and the session row is still open. */
    FAILED,
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
     * IN_SET is ranked first for consequence. Nothing of a set still being
     * performed has reached the database, so leaving destroys it outright, and
     * on a session's first set the session row goes with it. That is true of
     * [SetWriteState.NONE] and of [SetWriteState.FAILED]; it stopped being true
     * of a set whose write is in flight, which is why the three are answered
     * separately below.
     *
     * RESTING loses less and still loses something no reprocessing can get
     * back. Every set is durably written by then, but the session row is open,
     * and the R-R intervals collected across the rest window live only in
     * memory — the per-set HR streams keep the in-set beats, nothing keeps
     * these. The last set's rep count and effort rating also stop being
     * correctable, because the only screen that can edit them is this one.
     *
     * [write] and [close] are required rather than defaulted so that a caller
     * cannot keep compiling while silently answering the old question. [write]
     * is read for `IN_SET` alone and [close] for `RESTING` alone -- every other
     * stage ignores both, which is pinned rather than assumed, because "cannot
     * happen" and "is not looked at" are different guarantees and only the
     * second one is this function's to make.
     *
     * The two never contend. A set write is only outstanding in `IN_SET`, and a
     * close is only outstanding once every set is written; they are separate
     * parameters rather than one merged state precisely so that the pair can be
     * represented and answered rather than assumed away.
     *
     * IN_SET therefore has three answers, not one, and the difference between
     * them is what the prompt is allowed to claim. Before the write starts,
     * nothing of the set is in the database and leaving destroys it.
     * [SetWriteState.IN_FLIGHT] cannot say that: the insert is already running,
     * so the set is not the lifter's to discard any more, and the prompt drops
     * the offer to finish the session instead -- closing it here would
     * summarise a set list the in-flight set is missing from.
     * [SetWriteState.FAILED] is back to "nothing was saved", but the set is
     * over, so the controls the in-progress wording names are no longer drawn
     * and its own prompt points at the retry instead.
     */
    fun promptFor(stage: Stage, write: SetWriteState, close: SessionCloseState): ExitPrompt = when (stage) {
        Stage.SETUP, Stage.READY, Stage.FINISHED -> ExitPrompt.NONE
        Stage.IN_SET ->
            when (write) {
                SetWriteState.NONE -> ExitPrompt.SET_IN_PROGRESS
                SetWriteState.IN_FLIGHT -> ExitPrompt.SET_SAVING
                SetWriteState.FAILED -> ExitPrompt.SET_UNSAVED
            }
        Stage.RESTING ->
            when (close) {
                SessionCloseState.NONE -> ExitPrompt.SESSION_OPEN
                SessionCloseState.IN_FLIGHT -> ExitPrompt.SESSION_CLOSING
                SessionCloseState.FAILED -> ExitPrompt.SESSION_NOT_CLOSED
            }
    }
}
