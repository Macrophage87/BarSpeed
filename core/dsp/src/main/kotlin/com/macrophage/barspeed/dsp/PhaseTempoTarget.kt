package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.Tempo

/**
 * How many seconds the phase a lift is currently in was prescribed.
 *
 * This is `RecordScreen.phaseTargetS` lifted out of `:app`, which has no test
 * source set, so nothing there could ever be run against it. It lives here so
 * one thing can be true of the whole app: the mapping from a phase to a digit
 * is stated once.
 *
 * THE TWO MOVEMENT PHASES ARE RESOLVED THROUGH [TempoSchedule], which is what
 * [SetAnalyzer] already grades against, so the target a screen shows and the
 * target the set is scored on cannot come apart. Tempo digits are POSITIONAL --
 * digit 1 is the DOWN stroke -- so digit 1 is the eccentric only while the
 * drive moves up. On a seated leg curl or a pushdown the drive pulls DOWN,
 * which makes digit 1 the concentric and digit 3 the eccentric: a 1030 leg curl
 * prescribes a three-second lowering, and the three sites that read digit 1
 * resolved one. See #127 and #56.
 *
 * That resolution does not depend on which phase the lift is declared to open
 * with, which matters because #131 is open: the opening phase is currently
 * inferred from the exercise name and gets at least the leg press backwards.
 *
 * WHICH OF THE THREE CALL SITES THE WRONG NUMBER REACHED, stated here because
 * an earlier version of this comment claimed more than that. Only the two
 * post-set charts render it. `RecordScreen.InSetStage` sends every set that
 * carries a tempo to the guided-cadence branch before the in-set ring is
 * reached -- `beginSet` derives `guidedSet` from the same expression the ring
 * parses its tempo from -- so the ring's target is null in every reachable
 * state, before this change and after it. The ring calls through here
 * regardless, because a third copy of this decision in a module with no test
 * source set is the thing that had to stop existing.
 *
 * THE TWO PAUSE PHASES ARE NOT RESOLVED, and must not be. Digit 2 is the pause
 * at the bottom and digit 4 the pause at the top, which is exactly what
 * [Phase.BOTTOM_PAUSE] and [Phase.TOP_PAUSE] are named after; both sides are
 * positional and they agree. Which of those two labels [StreamingSetTracker]
 * emits is a separate question with a separate defect behind it, named in
 * `PhaseTempoTargetTest` and not addressed here.
 */
object PhaseTempoTarget {
    fun secondsFor(tempo: Tempo, direction: LiftDirection, phase: Phase): Double? = when (phase) {
        Phase.ECCENTRIC -> TempoSchedule.of(tempo, direction).eccentricS
        Phase.CONCENTRIC -> TempoSchedule.of(tempo, direction).concentricS
        Phase.BOTTOM_PAUSE -> tempo.bottomPauseS
        Phase.TOP_PAUSE -> tempo.topPauseS
        Phase.IDLE -> null
    }
}
