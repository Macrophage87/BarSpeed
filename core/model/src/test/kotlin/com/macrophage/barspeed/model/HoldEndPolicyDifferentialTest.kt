package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What must be true instead. Issue #259, and RED at the commit that adds it.
 *
 * `HoldEndPolicyTest` pins what [HoldEndPolicy] answers today: a hold the
 * lifter ended records the span that ran to the tap, and the correction offers
 * one step. Both are the defect. The owner, after field-38:
 *
 * > "The problem with a lot of these holds are that my hands are full because
 * > they are holding onto the rope. So it takes about 5-10 sec to actually
 * > grab my phone."
 *
 * The figures below are field-38's own, measured this round and pinned in
 * `:core:dsp`'s `HoldReleaseFieldTest`: set 17 measured 36.228 s to the tap
 * with its armed unit's release 7.039 s earlier, set 18 measured 32.188 s with
 * its release 6.130 s earlier. Floored to whole seconds by the same
 * `SetClockPolicy.heldSeconds` both figures come from, that is 36 against 29
 * and 32 against 26.
 */
class HoldEndPolicyDifferentialTest {
    @Test
    fun `a sensor end decides the seconds on a hold the lifter ended, and says so`() {
        // field-38 set 17. The seven seconds coming off are the walk to the
        // phone, and the word is what lets a reader tell this 29 from a 29 the
        // lifter typed.
        assertEquals(
            HoldEndPolicy.Decision(29, HoldEndSource.SENSOR),
            HoldEndPolicy.decide(measuredS = 36, targetS = 45, autoEnded = false, sensorEndS = 29),
        )
        // field-38 set 18, six seconds. The owner's own correction on this hang
        // was two taps of five, which took it to 22 -- four seconds under what
        // his unit saw. That is the second reason for the change: the
        // correction is a guess made after the fact and the stream is not.
        assertEquals(
            HoldEndPolicy.Decision(26, HoldEndSource.SENSOR),
            HoldEndPolicy.decide(measuredS = 32, targetS = 45, autoEnded = false, sensorEndS = 26),
        )
        // An ad-hoc hold has no target and the release still decides.
        assertEquals(
            HoldEndPolicy.Decision(41, HoldEndSource.SENSOR),
            HoldEndPolicy.decide(measuredS = 48, targetS = null, autoEnded = false, sensorEndS = 41),
        )
    }

    @Test
    fun `the correction offers a bigger step wherever the sensor did not decide the end`() {
        // One tap for a 5-10 s reach is two taps on a good day, on a rest
        // screen with a countdown running. The step the fix adds is for exactly
        // the sets that still carry the reach: the ones no armed unit spoke
        // for.
        assertEquals(
            listOf(5, 10),
            HoldEndPolicy.downStepsS(HoldEndSource.LIFTER),
            "a hold whose tap decided still carries the whole reach",
        )
        // A hold the CLOCK ended too, and this is the case that is easy to miss:
        // a lifter who lets go at 20 s of a 30 s hold and walks away still has
        // the clock end it at 30 and record 30, so the overstatement there can
        // be ten seconds or more.
        assertEquals(
            listOf(5, 10),
            HoldEndPolicy.downStepsS(HoldEndSource.CLOCK),
            "a clock-ended hold the lifter abandoned early is overstated by whatever was left",
        )
        // A row with no word -- recorded before database v19 -- gets the step
        // too, because absence there means the build could not say, not that a
        // sensor decided.
        assertEquals(listOf(5, 10), HoldEndPolicy.downStepsS(null), "no word stored is not a sensor end")
        // And after a correction, when what decided the pre-correction figure
        // is no longer on the row: keep the control available rather than
        // guessing it is no longer needed.
        assertEquals(listOf(5, 10), HoldEndPolicy.downStepsS(HoldEndSource.CORRECTED), "a second correction")
    }
}
