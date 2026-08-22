package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoAdjustPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wheels a lifter scrolls between sets say what the guide will say during
 * the set.
 *
 * Two statements of one rule exist and cannot be reduced to one:
 * `TempoSchedule.of` is in `:core:dsp` and `TempoAdjustPolicy.digits` is in
 * `:core:model`, which `:core:dsp` depends on and not the other way about. This
 * file is the mechanical pin that keeps them equal, so the drift is a red build
 * rather than a wheel captioned "eccentric" over the stroke the lifter drives.
 *
 * The tempo used throughout is `1234`, four distinct digits, so the stroke
 * carrying digit 1 and the stroke carrying digit 3 can be told apart by their
 * seconds no matter which order the schedule performs them in. That ordering is
 * `startsWith`'s job and is deliberately not the wheels': the wheels are the
 * NOTATION, digit 1 first, and every case below is checked against both start
 * phases for exactly that reason.
 */
class TempoLabelContractTest {
    private fun def(concentricUp: Boolean, horizontal: Boolean, startsWith: StartPhase) = ExerciseDef(
        id = "under_test",
        displayName = "Under Test",
        startsWith = startsWith,
        concentricUp = concentricUp,
        horizontal = horizontal,
    )

    private fun strokeCarrying(schedule: TempoSchedule, seconds: Double): TempoStroke =
        listOf(schedule.first, schedule.second).single { it.seconds == seconds }

    private fun phaseWord(stroke: TempoStroke) = if (stroke.isConcentric) "concentric" else "eccentric"

    /** Every claim the wheels make about one lift, against what the schedule says. */
    private fun assertWheelsAgreeWithTheGuide(concentricUp: Boolean, horizontal: Boolean) {
        StartPhase.entries.forEach { startsWith ->
            val where = "concentricUp=$concentricUp horizontal=$horizontal startsWith=$startsWith"
            val direction = def(concentricUp, horizontal, startsWith).liftDirection()
            val schedule = TempoSchedule.of(Tempo.parse("1234"), direction)
            val digits = TempoAdjustPolicy.digits(concentricUp, horizontal)
            val downStroke = strokeCarrying(schedule, 1.0)
            val upStroke = strokeCarrying(schedule, 3.0)

            assertEquals(downStroke.label, digits[0].label, "digit 1's word, $where")
            assertEquals(upStroke.label, digits[2].label, "digit 3's word, $where")
            assertEquals(phaseWord(downStroke), digits[0].caption, "digit 1's phase, $where")
            assertEquals(phaseWord(upStroke), digits[2].caption, "digit 3's phase, $where")
            assertEquals("after the ${digits[0].caption}", digits[1].caption, "digit 2 follows digit 1, $where")
            assertEquals("after the ${digits[2].caption}", digits[3].caption, "digit 4 follows digit 3, $where")
        }
    }

    @Test
    fun `a drive-up vertical lift's wheels say what the guide will say`() {
        assertWheelsAgreeWithTheGuide(concentricUp = true, horizontal = false)
    }

    /**
     * The case #148 exists to get right and the one v0.1.41 already had to fix
     * once elsewhere: a triceps pushdown, a lat pulldown, a leg curl. Digit 1
     * is still the DOWN stroke, and on these lifts down is the drive, so the
     * wheel that says "DOWN" is captioned concentric.
     */
    @Test
    fun `a drive-down vertical lift's wheels say what the guide will say`() {
        assertWheelsAgreeWithTheGuide(concentricUp = false, horizontal = false)
    }

    @Test
    fun `horizontal work's wheels say what the guide will say`() {
        assertWheelsAgreeWithTheGuide(concentricUp = true, horizontal = true)
    }

    /**
     * A horizontal machine that also declares a downward drive. `TempoSchedule`
     * reads horizontal work by PHASE and ignores the drive direction there --
     * there is no up or down on a seated row for a positional reading to attach
     * to -- so the wheels must ignore it in the same place and for the same
     * reason.
     */
    @Test
    fun `a horizontal machine ignores the drive direction, and the wheels ignore it too`() {
        assertWheelsAgreeWithTheGuide(concentricUp = false, horizontal = true)
    }

    /**
     * Which phase the lift STARTS with reorders the schedule and must not
     * reorder the wheels. The wheels are the notation, digit 1 first; the
     * schedule is the performance order. Both are asserted above against both
     * start phases, and this states the property directly so a change that made
     * the wheels performance-ordered cannot pass by making both sides move
     * together.
     */
    @Test
    fun `the wheels are the notation order, whichever phase the lift starts with`() {
        listOf(true to true, true to false, false to true, false to false).forEach { (concentricUp, horizontal) ->
            val digits = TempoAdjustPolicy.digits(concentricUp, horizontal)
            assertEquals(listOf(1, 2, 3, 4), digits.map { it.position })

            val eccentricFirst =
                TempoSchedule.of(
                    Tempo.parse("1234"),
                    def(concentricUp, horizontal, StartPhase.ECCENTRIC).liftDirection(),
                )
            val concentricFirst =
                TempoSchedule.of(
                    Tempo.parse("1234"),
                    def(concentricUp, horizontal, StartPhase.CONCENTRIC).liftDirection(),
                )
            assertEquals(
                eccentricFirst.first.label,
                concentricFirst.second.label,
                "the two start phases perform the same two strokes in opposite orders",
            )
            assertEquals(digits[0].label, strokeCarrying(concentricFirst, 1.0).label)
        }
    }
}
