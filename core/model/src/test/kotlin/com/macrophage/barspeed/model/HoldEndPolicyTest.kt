package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HoldEndPolicy] and [HoldEndSource]: which of four things a finished hold's
 * seconds came from, and the four words the export carries.
 *
 * The seconds half of this file was pinned so the fix could be a differential
 * against a statement in the tree rather than against a memory of what the code
 * used to do. Two of those pins -- that a sensor end changed nothing, and that
 * the correction offered one step -- are DELETED rather than reworded now that
 * `HoldEndPolicyDifferentialTest` has moved both; what is left here is the rule
 * as it stands, plus the guards that could not be written red because the
 * pre-fix code happened to answer the same thing.
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
    fun `a release the clock beat is not consulted at all`() {
        // The clock is not overruled: a hold that ran to `Time` records the
        // target whatever its stream says afterwards. Letting a crossing shorten
        // it would record less than the lifter was told they had completed.
        assertEquals(
            HoldEndPolicy.Decision(30, HoldEndSource.CLOCK),
            HoldEndPolicy.decide(measuredS = 31, targetS = 30, autoEnded = true, sensorEndS = 20),
            "a clock-ended hold with a release ten seconds early",
        )
        assertEquals(
            HoldEndPolicy.Decision(30, HoldEndSource.CLOCK),
            HoldEndPolicy.decide(measuredS = 31, targetS = 30, autoEnded = true, sensorEndS = 29),
        )
    }

    @Test
    fun `a sensor end may not lengthen a hold, and may not take more than the cap off`() {
        // GREEN at the commit that adds it, and said so rather than implied: the
        // pre-fix code answered the tap's own seconds for both of these too, so
        // neither could be written as a failing differential. The mutation table
        // is what covers them.
        //
        // At or after the tap there is nothing to remove. A release stamped
        // after the write cannot come from the app -- the stream is frozen at the
        // write -- so if it ever arrives the wall clock moved, and the figure the
        // lifter's tap produced is the one to keep.
        assertEquals(
            HoldEndPolicy.Decision(36, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = 36),
            "a release at the tap",
        )
        assertEquals(
            HoldEndPolicy.Decision(36, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = 40),
            "a release after the tap",
        )
        // The cap, at its two edges, in LITERAL seconds. Written with
        // MAX_TRIM_S on both sides of each case first, which moved the input and
        // the expectation together and let a 20 -> 19 mutation pass -- measured,
        // on a run. Twenty off is a reach; twenty-one is not, and the tap stands
        // rather than a hold losing a third of itself to one crossing.
        assertEquals(20, HoldEndPolicy.MAX_TRIM_S, "the cap the literals below are written against")
        assertEquals(
            HoldEndPolicy.Decision(40, HoldEndSource.SENSOR),
            HoldEndPolicy.decide(measuredS = 60, targetS = 90, autoEnded = false, sensorEndS = 40),
            "exactly twenty seconds off is believed",
        )
        assertEquals(
            HoldEndPolicy.Decision(61, HoldEndSource.LIFTER),
            HoldEndPolicy.decide(measuredS = 61, targetS = 90, autoEnded = false, sensorEndS = 40),
            "twenty-one seconds off is not",
        )
    }

    @Test
    fun `a hold the sensor ended is offered the fine step and no other`() {
        // GREEN, and unredable for the reason the file's KDoc gives: the pre-fix
        // control offered one step on every hold, so this case answered the same
        // thing before the fix. Its own test rather than a line inside the
        // differential, because a `10 sensor gets both steps` mutation survived
        // a run while the differential covered only the other four cases.
        assertEquals(
            listOf(TimedSetEndPolicy.CORRECTION_STEP_S),
            HoldEndPolicy.downStepsS(HoldEndSource.SENSOR),
            "the release already took the reach off, so the big step is noise",
        )
        assertEquals(listOf(5), HoldEndPolicy.downStepsS(HoldEndSource.SENSOR), "five, in literal seconds")
        assertEquals(
            listOf(5, 10),
            HoldEndPolicy.downStepsS(HoldEndSource.LIFTER),
            "and the sets that still carry the reach get both, in literal seconds",
        )
    }

    @Test
    fun `a sensor end moves the seconds and never the verdict`() {
        // The shortfall is DERIVED from the recorded seconds, so a sensor end
        // can move it -- and that is the right answer rather than a side effect.
        // Both field-38 hangs were short before and after: 36 and 29 against
        // 90% of 45.
        assertTrue(TimedSetEndPolicy.fellShort(36, 45), "the tap's figure fell short")
        assertTrue(TimedSetEndPolicy.fellShort(29, 45), "so does the release's")
        // And the case where it does change the answer, pinned so nobody is
        // surprised by it: a hold ended by hand at 43 s of 45 counted as close
        // enough, and its release at 36 does not.
        assertFalse(TimedSetEndPolicy.fellShort(43, 45), "43 of 45 is within the tolerance")
        assertEquals(
            HoldEndPolicy.Decision(36, HoldEndSource.SENSOR),
            HoldEndPolicy.decide(measuredS = 43, targetS = 45, autoEnded = false, sensorEndS = 36),
        )
        assertTrue(TimedSetEndPolicy.fellShort(36, 45), "and the hold it actually did was short")
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
