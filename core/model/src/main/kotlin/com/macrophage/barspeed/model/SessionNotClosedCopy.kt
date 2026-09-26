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

    /** The body of the [ExitPrompt.SESSION_NOT_CLOSED] prompt. */
    const val EXIT_BODY =
        "Part of this session was not written — the end time and the heart-rate and HRV summary, or the " +
            "rest recorded after your last set. Tapping FINISH SESSION AGAIN on this screen can still " +
            "write what is missing — freeing some space on the phone first if that is what stopped it. " +
            "Every set is already saved either way. Whatever has not been written is held only here, and " +
            "leaving now loses it."
}
