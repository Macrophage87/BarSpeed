package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which rep figures a set carries in the set and on the row.
 *
 * The decision this replaces was one line in `RecordViewModel.endSet` --
 * `val manualReps = if (s.manualSet) s.manualReps else null` -- which decided
 * what the archive records a set as, in a module no test on the CI path
 * reaches.
 */
class RepCountPolicyTest {
    /** The lifter's tally is the count, and no live figure is claimed for it. */
    @Test
    fun `a manual set records the tally and no live count`() {
        val recorded = RepCountPolicy.recorded(RepCounter.MANUAL, tally = 5, liveCount = 0, correctionDelta = 0)
        assertEquals(5, recorded.stated)
        assertNull(recorded.live, "a set with no live counter claims a live count")
    }

    /**
     * A tally of zero is a count of zero and is recorded as one.
     *
     * The row's own pin says the same thing for the same reason: a set the
     * lifter counted no reps on is a fact, and folding it into absence would
     * make it indistinguishable from a set nothing counted.
     */
    @Test
    fun `a manual tally of zero is recorded rather than folded into absence`() {
        assertEquals(0, RepCountPolicy.recorded(RepCounter.MANUAL, 0, liveCount = 0, correctionDelta = 0).stated)
    }

    /** The guide's count arrives in the same tally and is recorded the same way. */
    @Test
    fun `a metronome set records the guide's count as the stated figure`() {
        val recorded = RepCountPolicy.recorded(RepCounter.METRONOME, tally = 12, liveCount = 0, correctionDelta = 0)
        assertEquals(12, recorded.stated)
        assertNull(recorded.live)
    }

    /**
     * An uncorrected sensor set states nothing and records what the sensor
     * counted.
     *
     * `stated` null is what leaves `repsManual` false on the row, which is what
     * the export reads as `sensor` rather than `corrected`.
     */
    @Test
    fun `an uncorrected sensor set records the live count and states nothing`() {
        val recorded = RepCountPolicy.recorded(RepCounter.SENSOR, tally = 0, liveCount = 5, correctionDelta = 0)
        assertNull(recorded.stated, "an uncorrected sensor set states a count the lifter never gave")
        assertEquals(5, recorded.live)
    }

    /**
     * A live count of zero is recorded as zero, not as absence.
     *
     * Absence means no live counter ran. A sensor-counted set that resolved no
     * rep at all is a measurement, and it is the measurement a coach most needs
     * to see: it says the detector saw nothing, not that nothing was recorded.
     */
    @Test
    fun `a sensor set that counted nothing records zero rather than absence`() {
        assertEquals(0, RepCountPolicy.recorded(RepCounter.SENSOR, 0, liveCount = 0, correctionDelta = 0).live)
    }

    /** A corrected sensor set carries both figures: the correction and what it corrected. */
    @Test
    fun `a corrected sensor set records the corrected count beside the sensor's own`() {
        val recorded = RepCountPolicy.recorded(RepCounter.SENSOR, tally = 0, liveCount = 5, correctionDelta = 1)
        assertEquals(6, recorded.stated)
        assertEquals(5, recorded.live, "the correction erased what the sensor counted")
    }

    /** A set nothing counts records neither figure. */
    @Test
    fun `a timed set records no rep count of any kind`() {
        val recorded = RepCountPolicy.recorded(RepCounter.NOBODY, tally = 3, liveCount = 4, correctionDelta = 2)
        assertNull(recorded.stated)
        assertNull(recorded.live)
    }

    /**
     * Both figures are non-null on exactly one shape of set.
     *
     * The pair is what lets the export name the source at all, so which shapes
     * carry both is the contract rather than an implementation detail.
     */
    @Test
    fun `both figures stand together only on a corrected sensor set`() {
        val both = mutableListOf<String>()
        for (counter in RepCounter.entries) {
            for (delta in listOf(0, 1, 2)) {
                val recorded = RepCountPolicy.recorded(counter, tally = 3, liveCount = 5, correctionDelta = delta)
                if (recorded.stated != null && recorded.live != null) both += "$counter delta=$delta"
            }
        }
        assertEquals(listOf("SENSOR delta=1", "SENSOR delta=2"), both)
    }

    /**
     * The correction is an OFFSET, so the sensor goes on counting behind it.
     *
     * A lifter who adds the rep the detector missed at rep 3 still reads 6 when
     * the detector has called 5. Replacing the figure instead would make every
     * later sensor call look like the count going backwards.
     */
    @Test
    fun `a correction offsets the live count rather than replacing it`() {
        assertEquals(4, RepCountPolicy.correctedCount(liveCount = 3, correctionDelta = 1))
        assertEquals(6, RepCountPolicy.correctedCount(liveCount = 5, correctionDelta = 1))
        assertEquals(7, RepCountPolicy.correctedCount(liveCount = 5, correctionDelta = 2))
        assertEquals(5, RepCountPolicy.correctedCount(liveCount = 5, correctionDelta = 0))
    }

    /** The corrected count never goes below zero. */
    @Test
    fun `a correction cannot take the count below zero`() {
        assertEquals(0, RepCountPolicy.correctedCount(liveCount = 0, correctionDelta = -1))
        assertEquals(0, RepCountPolicy.correctedCount(liveCount = 2, correctionDelta = -5))
    }

    /**
     * The ring and the voice read ONE number.
     *
     * Issue #252 filed the guided set's version of this: the voice named the
     * rep in hand while the ring drew finished reps, so screen and voice
     * differed by one for a whole set. The sensor's counter gets one figure
     * from the start.
     */
    @Test
    fun `the displayed count is the recorded one on every counter that counts`() {
        assertEquals(5, RepCountPolicy.displayedCount(RepCounter.MANUAL, tally = 5, liveCount = 9, 0))
        assertEquals(12, RepCountPolicy.displayedCount(RepCounter.METRONOME, tally = 12, liveCount = 9, 0))
        assertEquals(9, RepCountPolicy.displayedCount(RepCounter.SENSOR, tally = 5, liveCount = 9, 0))
        assertEquals(10, RepCountPolicy.displayedCount(RepCounter.SENSOR, tally = 5, liveCount = 9, 1))
        assertEquals(0, RepCountPolicy.displayedCount(RepCounter.NOBODY, tally = 5, liveCount = 9, 1))
    }

    /**
     * What is drawn is what would be recorded, wherever a count is recorded at
     * all.
     *
     * The defect this guards is the one shipped on explosive sets: the ring drew
     * `StreamingSetTracker.repCount` while the row stored the batch segmenter's
     * count, so the number the lifter watched all set was not the number the
     * archive kept.
     */
    @Test
    fun `nothing is drawn that would not be recorded`() {
        for (counter in listOf(RepCounter.MANUAL, RepCounter.METRONOME, RepCounter.SENSOR)) {
            for (delta in listOf(0, 1)) {
                val recorded = RepCountPolicy.recorded(counter, tally = 4, liveCount = 6, correctionDelta = delta)
                assertEquals(
                    RepCountPolicy.displayedCount(counter, tally = 4, liveCount = 6, correctionDelta = delta),
                    recorded.stated ?: recorded.live,
                    "$counter delta=$delta draws a count it does not record",
                )
            }
        }
    }
}
