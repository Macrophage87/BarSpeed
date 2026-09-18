package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the in-set ring draws for the sensor's count (#280).
 *
 * The three not-withheld groups are CHARACTERIZATION of the expressions this
 * replaces in `RecordScreen` -- `"${state.sensorReps}"`, the progress arc and
 * the reps/min line -- which no test on the CI path could reach before. The
 * withheld groups are the new answer, and they are what issue #280 needs: a
 * count given up must not be drawn as the number zero, because a lifter
 * glancing at the ring reads that as a detector that missed every rep.
 */
class LiveCountReadoutTest {
    /** Unchanged from the expression it replaces: the count, as a decimal string. */
    @Test
    fun `an ordinary count is drawn as its digits`() {
        assertEquals("0", LiveCountReadout.countLabel(0))
        assertEquals("1", LiveCountReadout.countLabel(1))
        assertEquals("12", LiveCountReadout.countLabel(12))
    }

    /** A withheld count draws no number at all, at every count it could have stood at. */
    @Test
    fun `a withheld count draws an em dash`() {
        for (reps in 0..3) {
            assertEquals(LiveCountReadout.NO_COUNT, LiveCountReadout.countLabel(reps, withheld = true), "reps=$reps")
        }
    }

    /** The em dash is one character and is not a digit, so it cannot read as a count. */
    @Test
    fun `the no-count mark is a single non-digit`() {
        assertEquals(1, LiveCountReadout.NO_COUNT.length)
        assertEquals("—", LiveCountReadout.NO_COUNT)
    }

    /**
     * Unchanged from the expression it replaces:
     * `String.format(Locale.US, "%+.2f", velocityMps)`. The sign is always
     * drawn -- a lowering reads negative on screen -- and the locale is pinned
     * so a decimal comma cannot appear beside an `m/s` label.
     */
    @Test
    fun `a live velocity is drawn signed to two decimals`() {
        assertEquals("+0.00", LiveCountReadout.velocityLabel(0.0))
        assertEquals("+1.23", LiveCountReadout.velocityLabel(1.234))
        assertEquals("-0.45", LiveCountReadout.velocityLabel(-0.451))
        assertEquals("+10.00", LiveCountReadout.velocityLabel(10.0))
    }

    /**
     * A withheld set draws no velocity either, at every figure the frozen
     * `LiveSetState` could be holding.
     *
     * The em dash and not `+0.00`: the withhold drops the tracker, so nothing
     * is integrating and a signed two-decimal figure beside an `m/s` label is a
     * measurement the app no longer has. The same rule the count, the arc and
     * the cadence line already follow. This REPLACES the characterization pin
     * `a withheld set draws plus zero velocity today`, which stated the defect.
     */
    @Test
    fun `a withheld set draws no velocity`() {
        assertEquals(LiveCountReadout.NO_COUNT, LiveCountReadout.velocityLabel(0.0, withheld = true))
        assertEquals(LiveCountReadout.NO_COUNT, LiveCountReadout.velocityLabel(1.23, withheld = true))
        assertEquals(LiveCountReadout.NO_COUNT, LiveCountReadout.velocityLabel(-0.4, withheld = true))
    }

    /** Unchanged: the fraction of the prescription, and 0 where nothing was prescribed. */
    @Test
    fun `the arc fills to the fraction of the prescription`() {
        assertEquals(0.5f, LiveCountReadout.repProgress(4, 8))
        assertEquals(1f, LiveCountReadout.repProgress(8, 8))
        assertEquals(0f, LiveCountReadout.repProgress(4, null), "nothing prescribed")
        assertEquals(0f, LiveCountReadout.repProgress(4, 0), "a zero prescription is not divided by")
    }

    /** A withheld count draws an empty arc, whatever was prescribed. */
    @Test
    fun `the arc is empty where the count was given up`() {
        assertEquals(0f, LiveCountReadout.repProgress(4, 8, withheld = true))
    }

    /** Unchanged, including both of its existing null cases. */
    @Test
    fun `reps per minute needs two reps and a running clock`() {
        assertEquals(60, LiveCountReadout.repsPerMin(10, 10))
        assertNull(LiveCountReadout.repsPerMin(1, 10), "one rep is not a cadence")
        assertNull(LiveCountReadout.repsPerMin(4, 0), "no clock")
    }

    /** A withheld count publishes no cadence, which is the third null and not a zero. */
    @Test
    fun `no cadence is published where the count was given up`() {
        assertNull(LiveCountReadout.repsPerMin(10, 10, withheld = true))
    }

    /** Unchanged: the caption names the rep about to be started, one past the count. */
    @Test
    fun `the caption names the next rep`() {
        assertEquals("rep 1 ready", LiveCountReadout.nextRepCaption(0))
        assertEquals("rep 5 ready", LiveCountReadout.nextRepCaption(4))
    }

    /** Suppressed where the count was given up, rather than frozen at "rep 1 ready". */
    @Test
    fun `no caption where the count was given up`() {
        assertNull(LiveCountReadout.nextRepCaption(0, withheld = true))
        assertNull(LiveCountReadout.nextRepCaption(4, withheld = true))
    }
}
