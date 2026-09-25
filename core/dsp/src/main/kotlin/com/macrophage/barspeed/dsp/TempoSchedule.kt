package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo

/**
 * One movement stroke of a prescribed rep, with the word the voice guide says for it.
 *
 * [seconds] is null for a stroke the tempo writes as `X`, which has no
 * prescribed duration.
 */
data class TempoStroke(val label: String, val seconds: Double?, val isConcentric: Boolean)

/**
 * A tempo prescription resolved against a particular lift: the two strokes in
 * the order they are performed, with the pause that follows each.
 *
 * **How the digits are read depends on the plane, because "down" stops meaning
 * anything on a horizontal machine.**
 *
 * - Vertical work reads them POSITIONALLY — digit 1 is the down stroke, digit 3
 *   the up stroke — which is what the lifter hears and what makes a leg curl's
 *   `1030` a one-second pull down and a three-second return up.
 * - Horizontal work reads them by PHASE, the classic notation: digit 1 is the
 *   eccentric, digit 3 the concentric. There is no up or down on a seated row,
 *   so a positional reading has nothing to attach to.
 *
 * Either way digit 2 is the pause after the digit-1 stroke and digit 4 the
 * pause after the digit-3 stroke.
 */
data class TempoSchedule(
    val first: TempoStroke,
    val pauseAfterFirstS: Double,
    val second: TempoStroke,
    val pauseAfterSecondS: Double,
) {
    val eccentricS: Double? get() = if (first.isConcentric) second.seconds else first.seconds

    val concentricS: Double? get() = if (first.isConcentric) first.seconds else second.seconds

    /**
     * Seconds one rep is PRESCRIBED to take: both strokes and both pauses. An
     * explosive stroke has no prescribed seconds and contributes none.
     *
     * NOT what the metronome plays, and reading it as that is the mistake
     * #250's second comment made against this very paragraph. The voice guide
     * gives an X stroke a ONE-SECOND beat and always has -- from #264 an X
     * drive's beat carries the word `Drive` rather than `Up`, and its length
     * is unchanged:
     * `CadencePlan.strokeSeconds` substitutes 1.0 for a null and floors every
     * stroke at a second, so `30X0` is delivered as a four-second cycle
     * against a prescription of three. The same floor lifts a written `0`
     * digit, which is why `3000` also delivers four. `CadencePlanTest` pins
     * both, and `CadenceVoiceTest` pins that the X stroke's word is called
     * with no count behind it -- a one-second stroke is below
     * [GuidedCadence.COUNT_ALOUD_FROM_S].
     *
     * The gap between the two figures is the whole content of issue 106 and is
     * deliberate: this one is what the plan asked for, and the analysis side
     * grades against it. `SetAnalyzer.complianceFor` leaves a null-seconds
     * phase UNSCORED, which is what keeps an X stroke out of the tempo score.
     */
    val prescribedCycleS: Double
        get() = (first.seconds ?: 0.0) + pauseAfterFirstS + (second.seconds ?: 0.0) + pauseAfterSecondS

    companion object {
        fun of(tempo: Tempo, direction: LiftDirection): TempoSchedule {
            val horizontal = direction.plane == MovementPlane.HORIZONTAL
            // Which stroke carries digit 1, and is it the concentric?
            val digit1IsConcentric = if (horizontal) false else !direction.concentricUp
            val digit1 =
                TempoStroke(
                    label = strokeLabel(horizontal, down = true, isConcentric = digit1IsConcentric, explosive = false),
                    seconds = tempo.downS,
                    isConcentric = digit1IsConcentric,
                )
            val digit3 =
                TempoStroke(
                    label = strokeLabel(
                        horizontal,
                        down = false,
                        isConcentric = !digit1IsConcentric,
                        explosive = tempo.isExplosiveUpStroke,
                    ),
                    seconds = tempo.upS,
                    isConcentric = !digit1IsConcentric,
                )
            val firstIsConcentric = direction.startsWith == StartPhase.CONCENTRIC
            return if (digit1.isConcentric == firstIsConcentric) {
                TempoSchedule(digit1, tempo.bottomPauseS, digit3, tempo.topPauseS)
            } else {
                TempoSchedule(digit3, tempo.topPauseS, digit1, tempo.bottomPauseS)
            }
        }

        /**
         * Vertical work is called by direction; horizontal work has no up, so it
         * is called by phase.
         *
         * One exception on vertical work, and it is a DRIVE the tempo writes as
         * `X`: that stroke is called `DRIVE`, the word the horizontal guide and
         * the prep countdown already use for the working stroke (#264). Until
         * then it was called `UP`, so a `20X0` set was called exactly as a
         * `2010` set was -- field-39 sets 2, 6 and 10, row for row -- and the
         * one tempo whose job is to not pace the drive sounded like a paced
         * one-second drive. An `X` that is the RETURN -- digit 3 of a drive-down
         * lift -- keeps its direction word: a fast return is not a drive.
         *
         * [explosive] is `X` on THIS digit, so digit 1 always passes false:
         * `Tempo.parse` refuses `X` there (#258). The stroke keeps its
         * one-second beat either way -- `CadencePlan.strokeSeconds` decides the
         * seconds and is untouched -- so only the word moves.
         *
         * Stated a second time in `:core:model` for the prep countdown, as
         * `StartCuePolicy.firstMovementWord`, and pinned equal to this by
         * `StartCueVoiceContractTest`. `TempoAdjustPolicy`'s wheels keep `UP`
         * for an `X` on purpose; `TempoLabelContractTest` says why.
         */
        private fun strokeLabel(horizontal: Boolean, down: Boolean, isConcentric: Boolean, explosive: Boolean) = when {
            horizontal && isConcentric -> "DRIVE"
            horizontal -> "RETURN"
            isConcentric && explosive -> "DRIVE"
            down -> "DOWN"
            else -> "UP"
        }
    }
}
