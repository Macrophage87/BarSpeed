package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The history card's count chip names whose count the set's figure is (#325).
 *
 * The inputs are built through [RepsSourcePolicy.published] from the row's own
 * columns, so each case is the row a real set leaves behind, not a word
 * picked to suit the assertion.
 */
class RepsCountChipTest {
    private fun chipFor(liveReps: Int?, repsManual: Boolean, timed: Boolean = false, guided: Boolean = false) =
        RepsCountChip.label(RepsSourcePolicy.published(liveReps, repsManual, timed, guided))

    /**
     * RED WHEN WRITTEN. A guided set: no live count, `repsManual` true
     * because the metronome's figure is stored the way a tally is, and a
     * cadence ran. The metronome counted it and nobody entered it.
     */
    @Test
    fun `a guided set names the metronome, never a manual count`() {
        assertEquals("METRONOME COUNT", chipFor(liveReps = null, repsManual = true, guided = true))
    }

    /** RED WHEN WRITTEN. The sensor counted and the lifter did not argue. */
    @Test
    fun `a sensor-counted set names the sensor`() {
        assertEquals("SENSOR COUNT", chipFor(liveReps = 5, repsManual = false))
    }

    /** RED WHEN WRITTEN. The sensor counted and the lifter then disagreed. */
    @Test
    fun `a corrected sensor count says corrected`() {
        assertEquals("CORRECTED COUNT", chipFor(liveReps = 5, repsManual = true))
    }

    /** RED WHEN WRITTEN. Nothing counted live; the figure is the segmenter's, taken afterwards. */
    @Test
    fun `a count taken after the set names the analysis`() {
        assertEquals("ANALYSIS COUNT", chipFor(liveReps = null, repsManual = false))
    }

    /** RED WHEN WRITTEN. Only the lifter's own tally may read as manual, for every source there is. */
    @Test
    fun `no source but the lifter's tally reads manual`() {
        val manualWorded = RepsSource.entries.filter { RepsCountChip.label(it) == "MANUAL COUNT" }
        assertEquals(listOf(RepsSource.MANUAL), manualWorded)
    }

    /** GUARD, green before and after: the lifter's own tally is a manual count. */
    @Test
    fun `a tapped set reads manual`() {
        assertEquals("MANUAL COUNT", chipFor(liveReps = null, repsManual = true))
    }

    /** GUARD, green before and after: nothing counted reps on a hold, so no count chip. */
    @Test
    fun `a timed set draws no count chip`() {
        assertNull(chipFor(liveReps = null, repsManual = true, timed = true))
        assertNull(chipFor(liveReps = null, repsManual = false, timed = true))
    }
}
