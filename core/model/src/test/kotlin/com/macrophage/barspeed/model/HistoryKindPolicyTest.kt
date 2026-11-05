package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which kind History charts a recorded set as (#57).
 *
 * The first two pins are green when written: they hold the cases where the
 * stored kind and the seed list cannot disagree, or where there is no stored
 * kind at all and the seed lookup History has always made is the only answer.
 */
class HistoryKindPolicyTest {
    /**
     * A row with no geometry -- every set recorded before geometry was captured
     * -- reads as the seed list says, which is what History drew before.
     */
    @Test
    fun `a row with no stored kind falls back to the seed list`() {
        assertEquals(ExerciseKind.EXPLOSIVE, HistoryKindPolicy.of(stored = null, seeded = ExerciseKind.EXPLOSIVE))
        assertEquals(ExerciseKind.DYNAMIC, HistoryKindPolicy.of(stored = null, seeded = ExerciseKind.DYNAMIC))
    }

    /** A custom exercise with no geometry has no kind to read, and none is invented. */
    @Test
    fun `a row with neither a stored kind nor a seed has none`() {
        assertNull(HistoryKindPolicy.of(stored = null, seeded = null))
    }

    /** Where the two agree there is nothing to decide. */
    @Test
    fun `a stored kind that agrees with the seed list reads as both say`() {
        assertEquals(ExerciseKind.HOLD, HistoryKindPolicy.of(stored = ExerciseKind.HOLD, seeded = ExerciseKind.HOLD))
    }

    /**
     * RED until the stored kind is read (#57). A plan that declared a lift
     * explosive recorded it as explosive, and the rest screen charted its peak
     * velocity; History must read the same answer rather than the seed's.
     */
    @Test
    fun `a stored explosive kind wins over a dynamic seed`() {
        assertEquals(
            ExerciseKind.EXPLOSIVE,
            HistoryKindPolicy.of(stored = ExerciseKind.EXPLOSIVE, seeded = ExerciseKind.DYNAMIC),
        )
    }

    /** RED, the other direction: a seeded explosive lift a plan declared dynamic charts as dynamic. */
    @Test
    fun `a stored dynamic kind wins over an explosive seed`() {
        assertEquals(
            ExerciseKind.DYNAMIC,
            HistoryKindPolicy.of(stored = ExerciseKind.DYNAMIC, seeded = ExerciseKind.EXPLOSIVE),
        )
    }

    /** RED: a custom exercise has no seed, and its stored kind is the only answer there is. */
    @Test
    fun `a custom exercise reads its stored kind`() {
        assertEquals(ExerciseKind.EXPLOSIVE, HistoryKindPolicy.of(stored = ExerciseKind.EXPLOSIVE, seeded = null))
    }
}
