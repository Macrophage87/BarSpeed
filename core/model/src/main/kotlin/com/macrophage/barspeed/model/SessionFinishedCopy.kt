package com.macrophage.barspeed.model

/**
 * The words at the top of the record screen's FINISHED stage (#48).
 *
 * Here rather than beside `FinishedStage` in `RecordScreen` for the reason
 * [SessionNotClosedCopy] is: no test on the CI path can render a
 * `@Composable`, so a headline written beside one cannot be pinned at all.
 *
 * [sessionWritten] is whether a session row exists for the session that just
 * finished -- `RecordState.sessionId != null` at the caller. A session that
 * recorded no set never opens one: the row is created with its first set, and
 * the close (`SessionCloser`) writes nothing when there is no row, then
 * finishes the stage anyway.
 */
object SessionFinishedCopy {
    /** The headline over a session whose row exists. */
    const val SAVED = "Session saved."

    /**
     * The headline, by whether a row was written.
     *
     * Both branches say [SAVED] today, written out as two branches so the
     * case that is false is visible: a session that recorded nothing is told
     * it was saved, while the button that would open it is hidden because
     * there is nothing to open.
     */
    fun headline(sessionWritten: Boolean): String = if (sessionWritten) SAVED else SAVED
}
