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
     * The headline over a session that wrote no row: no set was saved, so
     * there is nothing saved to speak of, and the View session button beneath
     * it is hidden for the same reason.
     *
     * "No set was saved" rather than "Nothing was recorded": a set that was
     * recorded and whose write failed and was discarded reaches here too, and
     * it was recorded.
     */
    const val NOT_SAVED = "No set was saved, so no session was saved."

    /** The headline, by whether a row was written. */
    fun headline(sessionWritten: Boolean): String = if (sessionWritten) SAVED else NOT_SAVED
}
