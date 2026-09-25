package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clock-end release check #311 added applies to HOLD sets only (#314).
 *
 * A timed CARRY -- `farmers_walk` and `suitcase_carry` in the seed list, and
 * any id the kind inference reads as one -- also ends on its clock, so since
 * #311 it took the same check. No walking-carry stream exists in the corpus or
 * in any field capture, and `HoldRelease`'s band and settle are fitted to
 * static holds: a footstrike's acceleration is exactly the kind of crossing
 * that could read as a let-go and shorten a carry that ran to `Time`.
 *
 * The owner was told the recommendation -- limit the check to holds until a
 * walking-carry capture shows how footstrikes read -- and did not object.
 *
 * The numbers are invented for the shape, not measured: a 30 s carry the clock
 * ended, whose analysed stream crossed the band 22 s in.
 */
class HoldEndCarryGuardTest {
    private fun decide(kind: ExerciseKind, measuredS: Int, targetS: Int?, autoEnded: Boolean, sensorEndS: Int?) =
        HoldEndPolicy.decideFor(kind, measuredS, targetS, autoEnded, sensorEndS)

    @Test
    fun `a carry run to Time with a mid-walk crossing records the target under clock`() {
        assertEquals(
            HoldEndPolicy.Decision(30, HoldEndSource.CLOCK),
            decide(ExerciseKind.CARRY, measuredS = 30, targetS = 30, autoEnded = true, sensorEndS = 22),
        )
    }

    @Test
    fun `a carry the clock ended is not offered the release`() {
        assertFalse(HoldEndPolicy.releaseConsulted(ExerciseKind.CARRY, autoEnded = true))
    }

    // ---- what must not move ------------------------------------------------

    @Test
    fun `a hold the clock ended still takes the release, as field-45 set 13 would`() {
        assertTrue(HoldEndPolicy.releaseConsulted(ExerciseKind.HOLD, autoEnded = true))
        assertEquals(
            HoldEndPolicy.Decision(30, HoldEndSource.SENSOR),
            decide(ExerciseKind.HOLD, measuredS = 35, targetS = 35, autoEnded = true, sensorEndS = 30),
        )
    }

    /**
     * A carry the lifter TAPPED is asked as it was before #311 (#259's tap
     * trim). The footstrike risk is the same there and is not this issue's
     * change; it is named in the commit that carries this pin.
     */
    @Test
    fun `a carry the lifter tapped is still offered the release`() {
        assertTrue(HoldEndPolicy.releaseConsulted(ExerciseKind.CARRY, autoEnded = false))
        assertEquals(
            HoldEndPolicy.Decision(29, HoldEndSource.SENSOR),
            decide(ExerciseKind.CARRY, measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = 29),
        )
    }

    /**
     * The owner's timed farmer's set is a static HOLD, so the guard does not
     * reach it: field-42's `rope_farmers_hold` exports `kind: "hold"`, and the
     * inference reads "hold" before it reads "farmer".
     */
    @Test
    fun `the owner's rope farmers hold is a hold and the seed carries are carries`() {
        assertEquals(ExerciseKind.HOLD, ExerciseDef.inferKind("rope_farmers_hold"))
        assertEquals(ExerciseKind.CARRY, ExerciseDef.resolvedById("farmers_walk").kind)
        assertEquals(ExerciseKind.CARRY, ExerciseDef.resolvedById("suitcase_carry").kind)
    }
}
