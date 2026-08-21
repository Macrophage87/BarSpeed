package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.Tempo

/**
 * How many seconds the phase a lift is currently in was prescribed.
 *
 * This is `RecordScreen.phaseTargetS` lifted out of `:app`, which has no test
 * source set, so nothing there could ever be run against it.
 *
 * THE BODY BELOW IS THE MOVED CODE, UNCHANGED, AND IT IS WRONG. It reads digit
 * 1 as the eccentric and digit 3 as the concentric. Tempo digits are POSITIONAL
 * -- digit 1 is the DOWN stroke -- so that holds only while the drive moves up.
 * On a seated leg curl or a pushdown the drive pulls DOWN, which makes digit 1
 * the concentric and digit 3 the eccentric: a 1030 leg curl prescribes a
 * three-second lowering and this returns one. [direction] is accepted and
 * ignored until the commit that uses it. See #127 and #56.
 *
 * WHICH OF THE THREE CALL SITES THE WRONG NUMBER ACTUALLY REACHES, stated here
 * because an earlier version of this comment claimed more than that. Only the
 * two post-set charts render it. `RecordScreen.InSetStage` sends every set that
 * carries a tempo to the guided-cadence branch before the in-set ring is
 * reached -- `beginSet` derives `guidedSet` from the same expression the ring
 * parses its tempo from -- so the ring's target is null in every reachable
 * state. The ring is repointed regardless, because a third copy of this
 * decision in a module with no test source set is the thing that has to stop
 * existing.
 *
 * The pause branches are not part of that defect and do not move. Digit 2 is
 * the pause at the bottom and digit 4 the pause at the top, which is what
 * [Phase.BOTTOM_PAUSE] and [Phase.TOP_PAUSE] are named after; both sides are
 * positional and they agree. Whether the tracker applies the right one of those
 * two LABELS is a separate question and a separate defect -- see the test
 * beside this.
 */
object PhaseTempoTarget {
    // The suppression goes with the defect, not with the design: detekt is
    // right that nothing reads `direction` yet, and the commit that reads it
    // removes this line.
    @Suppress("UnusedParameter")
    fun secondsFor(tempo: Tempo, direction: LiftDirection, phase: Phase): Double? = when (phase) {
        Phase.ECCENTRIC -> tempo.downS
        Phase.CONCENTRIC -> tempo.upS
        Phase.BOTTOM_PAUSE -> tempo.bottomPauseS
        Phase.TOP_PAUSE -> tempo.topPauseS
        Phase.IDLE -> null
    }
}
