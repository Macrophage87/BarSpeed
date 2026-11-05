package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RepMarkAlignment] on synthetic grids (#246). The committed captures it is
 * argued from are measured in `RepMarkAlignmentFieldTest` in `:core:dsp`.
 *
 * Every grid here is the guide's: a mark every [CYCLE] ms. A drive starts
 * [PHASE] ms into its cycle, which is where an eccentric-first 3010 rep starts
 * its drive, and the tests move single detections off that phase.
 */
class RepMarkAlignmentTest {
    private val marks = (1..12).map { 10_000L + it * CYCLE }

    /** Rep k's drive, [PHASE] into the cycle that ends at mark k. */
    private fun drive(k: Int, jitterMs: Long = 0L): Long = marks[k] - CYCLE + PHASE + jitterMs

    private val faithful = marks.indices.map { drive(it) }

    @Test
    fun `a detection in every cycle is aligned`() {
        assertEquals(true, RepMarkAlignment.aligned(marks, faithful))
    }

    /** #246's shape: cycle 3 resolves nothing and cycle 8 resolves two. Twelve of twelve. */
    @Test
    fun `a missed rep and an invented one that cancel are not aligned`() {
        val cancelling = faithful.filterIndexed { i, _ -> i != 3 } + (drive(8) + 1_200L)
        assertEquals(12, cancelling.size, "the counts must still agree for this to be #246's shape")
        assertEquals(false, RepMarkAlignment.aligned(marks, cancelling))
    }

    /**
     * THE BLIND SPOT, pinned so it cannot be read as covered: a movement before
     * the first cycle and a missed last rep shift EVERY pair by one cycle, and a
     * uniform shift is indistinguishable from the guide counting at a
     * different phase, the property `the verdict does not depend on where in
     * the cycle the marks fall` relies on. Aligned, wrongly. The head of that
     * shape is what the work-start bound (#245) removes before the list is
     * built.
     */
    @Test
    fun `a list shifted by one whole cycle reads aligned`() {
        val shifted = listOf(drive(0) - CYCLE) + faithful.dropLast(1)
        assertEquals(true, RepMarkAlignment.aligned(marks, shifted))
    }

    /**
     * A lifter drifting against the guide. `field-inclinepress-3010-12rep-s38-set02`
     * drifts about 2 s over twelve reps and deviates 1681 ms from its median;
     * this drifts 1.8 s, every residual within 0.9 s of the median.
     */
    @Test
    fun `a lifter drifting less than half a cycle is aligned`() {
        val drifting = marks.indices.map { drive(it, jitterMs = -150L * it) }
        assertEquals(true, RepMarkAlignment.aligned(marks, drifting))
    }

    /** One drive more than half a cycle from where the others sit is out of its cycle. */
    @Test
    fun `half a cycle is the boundary`() {
        val inside = faithful.toMutableList().also { it[5] = it[5] + CYCLE / 2 - 1 }
        val outside = faithful.toMutableList().also { it[5] = it[5] + CYCLE / 2 }
        assertEquals(true, RepMarkAlignment.aligned(marks, inside))
        assertEquals(false, RepMarkAlignment.aligned(marks, outside))
    }

    /** Where in a cycle the guide counts is the guide's business: shifting every mark changes nothing. */
    @Test
    fun `the verdict does not depend on where in the cycle the marks fall`() {
        val cancelling = faithful.filterIndexed { i, _ -> i != 3 } + (drive(8) + 1_200L)
        for (offset in listOf(-3_000L, -1_000L, 0L, 1_000L, 3_000L)) {
            val moved = marks.map { it + offset }
            assertEquals(true, RepMarkAlignment.aligned(moved, faithful), "faithful, marks moved $offset ms")
            assertEquals(false, RepMarkAlignment.aligned(moved, cancelling), "cancelling, marks moved $offset ms")
        }
    }

    @Test
    fun `the question is not asked where it cannot be`() {
        assertNull(RepMarkAlignment.aligned(marks.take(1), faithful.take(1)), "one mark gives no cycle")
        assertNull(RepMarkAlignment.aligned(marks, faithful.drop(1)), "unequal counts are count equality's answer")
        assertNull(RepMarkAlignment.aligned(listOf(5L, 5L), listOf(1L, 2L)), "marks that do not advance")
    }

    @Test
    fun `repMetricsComplete is absence, then the counts, then the order`() {
        val cancelling = faithful.filterIndexed { i, _ -> i != 3 } + (drive(8) + 1_200L)
        assertNull(RepMarkAlignment.repMetricsComplete(0, 12, marks, emptyList()), "nothing resolved")
        assertEquals(false, RepMarkAlignment.repMetricsComplete(11, 12, marks, faithful.take(11)), "short")
        assertEquals(true, RepMarkAlignment.repMetricsComplete(12, 12, marks, faithful), "aligned")
        assertEquals(false, RepMarkAlignment.repMetricsComplete(12, 12, marks, cancelling), "cancelling")
    }

    /** Count equality is what is left wherever the order cannot be judged. */
    @Test
    fun `repMetricsComplete falls back to the counts where nothing can judge the order`() {
        val cancelling = faithful.filterIndexed { i, _ -> i != 3 } + (drive(8) + 1_200L)
        assertEquals(true, RepMarkAlignment.repMetricsComplete(12, 12, emptyList(), cancelling), "no guide marks")
        val unstamped: List<Long?> = cancelling.mapIndexed { i, t -> t.takeIf { i != 0 } }
        assertEquals(true, RepMarkAlignment.repMetricsComplete(12, 12, marks, unstamped), "a rep with no instant")
        assertEquals(true, RepMarkAlignment.repMetricsComplete(12, 12, marks.drop(2), cancelling), "fewer marks")
    }

    private companion object {
        const val CYCLE = 4_004L
        const val PHASE = 3_000L
    }
}
