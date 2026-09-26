package com.macrophage.barspeed.model

/**
 * The words the record screen puts in front of a lifter whose finish came back
 * failed: the body of the [ExitPrompt.SESSION_NOT_CLOSED] prompt, and the label
 * of the retry control that prompt names.
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

    /**
     * The body of the [ExitPrompt.SESSION_NOT_CLOSED] prompt.
     *
     * A failed close has two causes (`SessionCloser`): `endSession` did not
     * land, or it landed and the final rest window's write after it did not.
     * Every clause here is meant to be true in both, which is why the body
     * says "or only that rest", and why the heart-rate and HRV clause speaks
     * only of figures the finish did not write.
     *
     * What a reader rebuilds for a session with no end time (#62): its
     * heart-rate average and maximum from its set rows, and its HRV from its
     * stored heart-rate streams, which `SessionHrv.rmssdMs` leaves null where
     * fewer than ten successive differences survive -- hence "only where they
     * hold enough usable heartbeats". Whatever the close froze and has not
     * written -- the end time, the final rest window, the session rating -- is
     * held in memory only.
     */
    const val EXIT_BODY =
        "Part of this session was not written: the end time and the rest recorded after your last set, " +
            "or only that rest. Tapping FINISH SESSION AGAIN on this screen can still write them, after " +
            "freeing some space on the phone if that is what stopped it. Every set is already saved " +
            "either way, and any of the session's heart-rate and HRV figures the finish did not write " +
            "are rebuilt from those sets if you leave, the HRV only where they hold enough usable " +
            "heartbeats. Whatever has not been written is held only here, and leaving now loses it."
}
