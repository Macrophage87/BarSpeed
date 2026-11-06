package com.macrophage.barspeed.model

/**
 * The words the record screen puts in front of a lifter whose finish came back
 * failed: the title and body of the [ExitPrompt.SESSION_NOT_CLOSED] and
 * [ExitPrompt.FINAL_REST_UNSAVED] prompts, worded from what the close did not
 * write ([UnwrittenClose]), and the label of the retry control they name.
 *
 * Here rather than beside the dialog in `RecordScreen` for the reason
 * [RecordExitPolicy] is here: no test on the CI path can render a
 * `@Composable`, so wording written beside one cannot be pinned at all, and
 * this is the one prompt meant to state exactly what leaving costs (#331).
 *
 * [RETRY_LABEL] is the text of the control the rest screen draws in place of
 * Finish session after a failed close, and the body names that control by its
 * label. Both are held here so a test can check the body names a control that
 * exists, rather than one that was renamed under it.
 */
object SessionNotClosedCopy {
    /** The rest screen's retry control after a failed close. */
    const val RETRY_LABEL = "FINISH SESSION AGAIN"

    /** The title of the unfinished-session prompt. */
    const val NOT_FINISHED_TITLE = "This session did not finish"

    /** The title where `endSession` landed and only the final rest window did not (#333). */
    const val REST_UNSAVED_TITLE = "The last rest did not save"

    /**
     * The prompt's title for what [unwritten] says is missing.
     *
     * Where `endSession` landed the session DID finish, so it is not titled as
     * unfinished: only the rest after the last set is missing.
     */
    fun title(unwritten: UnwrittenClose): String = if (unwritten.sessionRow) NOT_FINISHED_TITLE else REST_UNSAVED_TITLE

    /**
     * The prompt's body for what [unwritten] says is missing (#333).
     *
     * Every item named is one the close carried and did not write; nothing
     * that was never there is named as at risk. Two shapes, by cause:
     *
     *  - `endSession` did not land. The end time is missing always, and the
     *    rating, the skipped sets and the heart rate after the last set where
     *    the close held them. What a reader rebuilds for a session with no end
     *    time (#62) is its heart-rate average and maximum from its set rows,
     *    and its HRV from its stored heart-rate streams, which
     *    `SessionHrv.rmssdMs` leaves null where fewer than ten successive
     *    differences survive. The body used to explain that rebuild; it now
     *    names no HRV at all, so it cannot promise one (UX review
     *    2026-11-06, copy F7: two sentences, not five).
     *  - `endSession` landed and the rest window after it did not. The end
     *    time, the summary, the HRV, the rating and the skips are stored, so
     *    the body says the session is finished and names only the rest. That
     *    write runs only where the close captured at least one sample after
     *    the last set (`SessionCloser`), so in this shape there is always a
     *    rest to name.
     */
    fun body(unwritten: UnwrittenClose): String {
        if (!unwritten.sessionRow) {
            return "The session is finished and every set is saved, but the heart rate recorded after your " +
                "last set didn't save. Free some space if the phone is full, then tap $RETRY_LABEL: " +
                "leaving now loses it."
        }
        val missing =
            listOfNotNull(
                "the end time",
                "your rating of the session".takeIf { unwritten.sessionRating },
                "the sets you skipped".takeIf { unwritten.skippedSets },
                "the heart rate recorded after your last set".takeIf { unwritten.finalRest },
            )
        val list =
            when (missing.size) {
                1 -> missing.single()
                else -> missing.dropLast(1).joinToString(", ") + " and " + missing.last()
            }
        val them = if (missing.size == 1) "it" else "them"
        return "Every set is saved, but the finish didn't save $list. Free some space if the phone is " +
            "full, then tap $RETRY_LABEL: leaving now loses $them."
    }
}
