package com.macrophage.barspeed.model

/**
 * Whether the set in progress may still be taken back with CANCEL SET (owner's
 * ruling 2026-11-06, UX review rest F2).
 *
 * A START tapped too early, or on the wrong exercise, had two ways out: END SET
 * EARLY, which writes a 0-rep row marked short of target and uses up the plan
 * slot, or leaving the screen. CANCEL SET is the third: the set is thrown away
 * as though START had not been tapped -- no row, no capture kept, the same slot
 * next -- and the lifter is back where he was, on READY or on the rest screen.
 *
 * It is offered only while nothing the set would be measured on has happened:
 *
 *  - during the lead-in (the prep before a hold or a carry, or before a
 *    guided cadence's first stroke), on every kind of set;
 *  - on a set counted in reps, until the first rep is counted, by the sensor,
 *    the guide or a tap.
 *
 * A hold or a carry has no reps, so once its clock is running it is under way
 * and the exits [SetEndControlPolicy] gives it are the only ones. From the
 * first rep on, the set has happened and is recorded like any other, however
 * it ends.
 *
 * Here rather than in `:app` for the reason [SetEndControlPolicy] is: no test
 * on the CI path can reach a decision written beside a composable.
 */
object SetCancelPolicy {
    /**
     * [timed] is whether the set is measured on the clock; [leadInRunning]
     * whether its lead-in is still playing; [repsCounted] the highest count
     * any counter on the set has reached so far (the lifter's or the guide's
     * tally, or the sensor's count with the lifter's corrections).
     */
    fun mayCancel(timed: Boolean, leadInRunning: Boolean, repsCounted: Int): Boolean =
        leadInRunning || (!timed && repsCounted == 0)
}
