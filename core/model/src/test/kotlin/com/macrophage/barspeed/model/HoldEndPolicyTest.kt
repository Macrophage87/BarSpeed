package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [HoldEndPolicy] and [HoldEndSource] as they answer BEFORE #259's fix, and the
 * four words the export will carry.
 *
 * The seconds half of this file is pinned so the fix is a differential against
 * a statement in the tree rather than against a memory of what the code used to
 * do: `HoldEndPolicyDifferentialTest` is written against these very cases, and
 * the ones the fix moves are deleted from here in the same commit that moves
 * them. `RestClockPolicy`'s KDoc records the same arrangement being used for
 * #172.
 *
 * The WORDS half is not a differential and does not move: the four strings are
 * a published contract from the moment the schema carries them.
 */
class HoldEndPolicyTest {
    @Test
    fun `a hold the clock ended records its target and says the clock decided`() {
        // #168's rule, restated where the provenance can be asserted beside it:
        // a set the app ended at the target records the target exactly, not the
        // 60-or-61 the tick loop measured.
        assertEquals(
            HoldEndPolicy.Decision(30, HoldEndSource.CLOCK),
            HoldEndPolicy.decide(measuredS = 31, targetS = 30, autoEnded = true, sensorEndS = null),
        )
        // With no target there is nothing to substitute, so an auto-ended hold
        // records what it measured -- and the clock is still who ended it.
        assertEquals(
            HoldEndPolicy.Decision(44, HoldEndSource.CLOCK),
            HoldEndPolicy.decide(measuredS = 44, targetS = null, autoEnded = true, sensorEndS = null),
        )
    }

    @Test
    fun `a hold the lifter ended records the measurement and names the lifter`() {
        // field-38 set 17's figures: 36 s measured against a 45 s target. The
        // reach is inside that 36 and nothing in the record says so.
        assertEquals(
            HoldEndPolicy.Decision(36, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = null),
        )
        // An ad-hoc hold: no target, ended by hand, records its measurement.
        assertEquals(
            HoldEndPolicy.Decision(22, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 22, targetS = null, autoEnded = false, sensorEndS = null),
        )
    }

    @Test
    fun `a sensor end changes nothing yet`() {
        // The state this branch starts from, pinned so the differential has
        // something to be a differential against. Set 17 again, with the
        // release its own unit saw at 29 s offered: the figure is still the
        // one that ran to the tap and the word is still the lifter's.
        assertEquals(
            HoldEndPolicy.Decision(36, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = 29),
        )
        assertEquals(
            HoldEndPolicy.Decision(32, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 32, targetS = 45, autoEnded = false, sensorEndS = 26),
        )
    }

    @Test
    fun `the correction offers one step, whatever ended the hold`() {
        // What the rest screen ships: one down step of five seconds. #259 asks
        // for a second, larger one where no unit spoke for the end.
        HoldEndSource.entries.forEach { source ->
            assertEquals(listOf(5), HoldEndPolicy.downStepsS(source), "$source")
        }
        assertEquals(listOf(5), HoldEndPolicy.downStepsS(null), "no provenance at all")
        assertEquals(
            listOf(TimedSetEndPolicy.CORRECTION_STEP_S),
            HoldEndPolicy.downStepsS(HoldEndSource.LIFTER),
            "the step is #168's constant and not a second copy of five",
        )
    }

    @Test
    fun `the four published words, and nothing else`() {
        assertEquals("clock", HoldEndSource.CLOCK.published)
        assertEquals("sensor", HoldEndSource.SENSOR.published)
        assertEquals("lifter", HoldEndSource.LIFTER.published)
        assertEquals("corrected", HoldEndSource.CORRECTED.published)
        assertEquals(4, HoldEndSource.entries.size, "a fifth word is a schema change")
        // Round trip, because the column is TEXT and the export reads it back
        // through this.
        HoldEndSource.entries.forEach {
            assertEquals(it, HoldEndSource.ofPublished(it.published), "${it.published} round trips")
        }
        assertNull(HoldEndSource.ofPublished(null), "no word stored is no word published")
        assertNull(HoldEndSource.ofPublished("tap"), "a word the app never wrote is not published")
        assertNull(HoldEndSource.ofPublished("Sensor"), "the words are exact, not case-insensitive")
    }
}
