package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clock-end release on the two timed kinds that are neither a hold nor a
 * carry (#315 item 1).
 *
 * `Plan.kindVsShape` lets a plan prescribe `duration_s` on a DYNAMIC or an
 * EXPLOSIVE exercise -- it warns, and the set runs on the clock -- and
 * [HoldEndPolicy.releaseConsulted] is `!autoEnded || kind == HOLD`, so such a
 * set the CLOCK ended is not offered the release and records its target under
 * [HoldEndSource.CLOCK], as a carry does. Nothing pinned that, so a mutant
 * reading `kind != CARRY` -- which keeps every hold and carry case green --
 * survived. It is pinned here as the behaviour, with the reason it is the
 * right one: `HoldRelease`'s band and settle are fitted to static holds, and
 * nothing on disk is a timed dynamic or explosive set to fit them to
 * otherwise. The export's `duration_s` and `durationEndedBy` say so from the
 * same change.
 *
 * The numbers are invented for the shape: a 30 s set the clock ended whose
 * stream crossed the band 22 s in.
 */
class HoldEndOtherTimedKindsTest {
    @Test
    fun `a timed dynamic or explosive set the clock ended is not offered the release`() {
        for (kind in listOf(ExerciseKind.DYNAMIC, ExerciseKind.EXPLOSIVE)) {
            assertFalse(HoldEndPolicy.releaseConsulted(kind, autoEnded = true), "$kind")
            assertEquals(
                HoldEndPolicy.Decision(30, HoldEndSource.CLOCK),
                HoldEndPolicy.decideFor(kind, measuredS = 30, targetS = 30, autoEnded = true, sensorEndS = 22),
                "$kind",
            )
        }
    }

    @Test
    fun `the same sets ended by a tap are offered it, as every timed kind is`() {
        for (kind in ExerciseKind.entries) {
            assertTrue(HoldEndPolicy.releaseConsulted(kind, autoEnded = false), "$kind")
        }
    }

    @Test
    fun `of the four kinds only a hold takes the release at the clock's end`() {
        assertEquals(
            listOf(ExerciseKind.HOLD),
            ExerciseKind.entries.filter { HoldEndPolicy.releaseConsulted(it, autoEnded = true) },
        )
    }
}
