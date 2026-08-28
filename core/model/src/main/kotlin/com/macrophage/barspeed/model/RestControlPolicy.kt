package com.macrophage.barspeed.model

/** A control the rest screen can draw between sets. */
enum class RestControl {
    /** Begin the next set. Starts the foreground service and the sample collectors. */
    START_NEXT_SET,

    /** Close the session row and stop recording. */
    FINISH_SESSION,

    /**
     * Ask for the session rating, and close the session on the answer or on
     * the skip beside it (#159).
     *
     * Drawn in place of [FINISH_SESSION], never beside it, for the reason
     * [RETRY_FINISH] replaces it too: two controls that both close the session
     * are two ways to launch the same work from different inputs, and one of
     * them would be the wrong one. Here the wrong one would be the one that
     * closes with no rating while the lifter is looking at the panel that asks
     * for it.
     */
    RATE_SESSION,

    /** Run a close that came back failed again, from the input frozen at the first tap. */
    RETRY_FINISH,
}

/**
 * Which rest-screen controls may be operated, given where the session close has
 * got to.
 *
 * Here rather than in `:app` for the reason [RecordExitPolicy] is here, and it
 * is a separate decision from that one rather than part of it: the gate on the
 * way out and the controls drawn on the screen are different surfaces, and the
 * defect this exists for is that guarding only the first is not a guard.
 *
 * The failure it prevents. While a close is in flight, START NEXT SET was still
 * a live 52dp button. Tapping it runs `startNextSet`, which writes `READY` and
 * calls `beginSet` in the same frame: the foreground service starts, the sample
 * collectors attach, and the stage becomes `IN_SET`. The close then resumes and
 * writes `FINISHED` over that stage and stops the service. The lifter is under a
 * loaded bar with the IMU buffer filling while the screen draws the
 * session-complete panel -- which takes no ViewModel, so it has no effort grid,
 * no END SET EARLY and no way to end the set at all, and both of its buttons
 * navigate away. The set is destroyed with no prompt of any kind.
 *
 * The same reasoning applies one control over. FINISH SESSION stays drawn while
 * a close is in flight, so it is a second tap that launches a second close.
 * Re-entry is refused in the ViewModel, which is where correctness lives; not
 * drawing the control is the honesty half, so the lifter is not tapping a target
 * that does nothing.
 */
object RestControlPolicy {
    /**
     * The controls to draw while the close is in [close].
     *
     * A set rather than a boolean per control, so a control added later has to
     * be placed in every state rather than defaulting into all of them.
     */
    fun controls(close: SessionCloseState): Set<RestControl> = when (close) {
        SessionCloseState.NONE -> setOf(RestControl.START_NEXT_SET, RestControl.FINISH_SESSION)
        // Empty, and empty is the answer rather than an omission. There is
        // nothing useful to tap while the close is landing, and the one thing a
        // lifter might reach for -- starting the next set -- is the thing that
        // destroys a set here.
        SessionCloseState.IN_FLIGHT -> emptySet()
        // The session is open again, so continuing is legitimate. The retry
        // replaces the finish rather than joining it: two controls that both
        // close the session would be two ways to launch the same work, from
        // different inputs, and one of them would be the wrong one.
        SessionCloseState.FAILED -> setOf(RestControl.START_NEXT_SET, RestControl.RETRY_FINISH)
    }

    /**
     * The controls to draw while the close is in [close] and the lifter has
     * asked to finish but not yet answered the session rating (#159).
     *
     * Layered on [controls] rather than restating it, so the two cannot drift:
     * the rating panel appears exactly where the finish control would have
     * been, and nowhere the finish control is not offered. A close that is in
     * flight still draws nothing and a failed close still draws its retry,
     * because [askedToFinish] cannot resurrect a control this object has
     * already withheld.
     *
     * START NEXT SET is deliberately LEFT DRAWN while the panel is up. The
     * close has not begun -- nothing is in flight and no row has been written
     * -- so the set-destroying hazard that empties the in-flight set does not
     * exist here, and a lifter who reached the panel by mistapping Finish
     * needs a way back that is not "answer a question about a workout you have
     * not finished". Today a mistap of Finish closes the session outright with
     * no confirmation at all, so this is strictly less trapping than what it
     * replaces.
     *
     * TWO FORMS, ON PURPOSE, and which one a call site asks matters. The
     * one-argument form answers the close's own question and is what the next-set
     * control asks; this form answers the session-close block's question. A
     * call site that asks the one-argument form while the panel is up gets the
     * plain finish control back, which is why the block that draws the finish
     * control asks this one.
     */
    fun controls(close: SessionCloseState, askedToFinish: Boolean): Set<RestControl> {
        val base = controls(close)
        return if (askedToFinish && RestControl.FINISH_SESSION in base) {
            base - RestControl.FINISH_SESSION + RestControl.RATE_SESSION
        } else {
            base
        }
    }
}
