package com.macrophage.barspeed.model

/** A control the rest screen can draw between sets. */
enum class RestControl {
    /** Begin the next set. Starts the foreground service and the sample collectors. */
    START_NEXT_SET,

    /** Close the session row and stop recording. */
    FINISH_SESSION,

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
        // Every branch is the same answer in this commit, on purpose: the
        // decision arrives here before the behaviour does, so this commit
        // changes nothing and the differentials that follow can be shown
        // failing against it.
        SessionCloseState.NONE,
        SessionCloseState.IN_FLIGHT,
        SessionCloseState.FAILED,
        -> setOf(RestControl.START_NEXT_SET, RestControl.FINISH_SESSION)
    }
}
