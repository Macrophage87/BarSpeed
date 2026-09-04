package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RepRefusal]'s shape, on CONSTRUCTED lists, before anything calls it.
 *
 * Constructed rather than captured because no committed capture exercises
 * most of these cases -- the boundary, a refusal in the middle of a list, two
 * outliers in one set -- and a rule pinned only by captures is pinned only
 * where the corpus happens to reach. What the rule does to the captures is
 * [RepRefusalCorpusTest], which is a different question with different
 * answers.
 */
class RepRefusalTest {
    private fun rep(index: Int, romM: Double, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.5,
        peakConVelMps = 0.8,
        meanEccVelMps = eccS?.let { -0.3 },
        peakEccVelMps = eccS?.let { -0.5 },
        romM = romM,
        peakPowerW = 100.0,
    )

    /** Four detections whose median-of-others is 0.4 m for the outlier at index 3. */
    private fun setWithLast(romM: Double, eccS: Double?) =
        listOf(rep(0, 0.4, 1.0), rep(1, 0.4, 1.0), rep(2, 0.4, 1.0), rep(3, romM, eccS))

    @Test
    fun `a drive-only detection ranging past the bound is refused`() {
        val reps = setWithLast(0.4 * RepRefusal.RANGE_RATIO_BOUND + 0.01, eccS = null)
        assertEquals(listOf(3), RepRefusal.refusedIndices(reps))
        assertEquals(1, RepRefusal.refusedCount(reps))
        assertEquals(RepRefusal.UNPAIRED_RANGE_OUTLIER, RepRefusal.reason(reps))
    }

    /**
     * Clause 1, on a range the bound would otherwise refuse.
     * [RepRefusalCorpusTest] shows the same clause carrying a real committed
     * detection at [RepRefusal.MAX_PAIRED_RANGE_RATIO_OBSERVED], which is
     * above the bound; this pins the decision itself.
     */
    @Test
    fun `the same range is kept when the detection resolved an eccentric partner`() {
        val reps = setWithLast(0.4 * RepRefusal.RANGE_RATIO_BOUND + 0.01, eccS = 2.0)
        assertEquals(emptyList(), RepRefusal.refusedIndices(reps))
        assertEquals(0, RepRefusal.refusedCount(reps))
        assertNull(RepRefusal.reason(reps))
    }

    @Test
    fun `the bound is strict, so a detection exactly at it is kept`() {
        val reps = setWithLast(0.4 * RepRefusal.RANGE_RATIO_BOUND, eccS = null)
        assertEquals(emptyList(), RepRefusal.refusedIndices(reps), "exactly at the bound")
        assertEquals(
            listOf(3),
            RepRefusal.refusedIndices(setWithLast(0.4 * RepRefusal.RANGE_RATIO_BOUND + 1e-9, eccS = null)),
            "a hair past it",
        )
    }

    @Test
    fun `below four detections no bound is derived, and the count says null rather than zero`() {
        val three = listOf(rep(0, 0.4, 1.0), rep(1, 0.4, 1.0), rep(2, 9.0, null))
        assertNull(RepRefusal.refusedCount(three), "three detections")
        assertEquals(emptyList(), RepRefusal.refusedIndices(three), "and nothing is refused")
        assertNull(RepRefusal.reason(three))
        assertNull(RepRefusal.rangeRatio(three, 2), "and no ratio can be derived either")
        assertEquals(three, RepRefusal.kept(three), "the list is returned untouched")
        assertEquals(
            0,
            RepRefusal.refusedCount(listOf(rep(0, 0.4, 1.0), rep(1, 0.4, 1.0), rep(2, 0.4, 1.0), rep(3, 0.4, 1.0))),
            "four clean detections answer 0, not null",
        )
    }

    @Test
    fun `a refused detection in the middle renumbers the survivors from zero`() {
        val reps = listOf(rep(0, 0.4, 1.0), rep(1, 9.0, null), rep(2, 0.4, 1.0), rep(3, 0.4, 1.0), rep(4, 0.4, 1.0))
        assertEquals(listOf(1), RepRefusal.refusedIndices(reps))
        val kept = RepRefusal.kept(reps)
        assertEquals(4, kept.size)
        assertEquals(listOf(0, 1, 2, 3), kept.map { it.index }, "survivors are renumbered")
        assertEquals(listOf(0.4, 0.4, 0.4, 0.4), kept.map { it.romM }, "and the refused one is gone")
    }

    @Test
    fun `refusing nothing returns the same list, not a copy of it`() {
        val reps = setWithLast(0.4, eccS = 1.0)
        assertTrue(RepRefusal.kept(reps) === reps)
    }

    /**
     * The medians are taken over the ORIGINAL list, so two outliers in one set
     * are both refused rather than the second hiding behind the first.
     */
    @Test
    fun `two outliers in one set are both refused`() {
        val reps = listOf(rep(0, 0.4, 1.0), rep(1, 9.0, null), rep(2, 0.4, 1.0), rep(3, 0.4, 1.0), rep(4, 8.0, null))
        assertEquals(listOf(1, 4), RepRefusal.refusedIndices(reps))
        assertEquals(2, RepRefusal.refusedCount(reps))
    }

    /**
     * The ratio the corpus walk measures is the rule's own, taken from the
     * rule rather than recomputed beside it -- a second implementation of
     * "median of the others" could measure a different quantity from the one
     * that decides.
     */
    @Test
    fun `the published ratio is range over the lower median of the others`() {
        val reps = listOf(rep(0, 0.2, 1.0), rep(1, 0.4, 1.0), rep(2, 0.6, 1.0), rep(3, 1.2, null))
        assertEquals(3.0, RepRefusal.rangeRatio(reps, 3)!!, 1e-12, "1.2 over the lower median 0.4 of 0.2, 0.4, 0.6")
        assertEquals(
            0.2 / 0.6,
            RepRefusal.rangeRatio(reps, 0)!!,
            1e-12,
            "and for index 0 the others are 0.4, 0.6, 1.2, whose lower median is 0.6",
        )
    }
}
