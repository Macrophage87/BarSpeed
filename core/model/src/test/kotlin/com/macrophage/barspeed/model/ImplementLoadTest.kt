package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Differentials for splitting one declared load across the objects a lifter
 * actually holds: "2 × 40 lb" for a pair, while the stored number stays 80.
 *
 * Three of these are RED when the file is written -- `two implements split the
 * total exactly`, `three implements split into three, not into two` and `the
 * split is rounded after dividing, so it need not multiply back` -- because
 * [ImplementLoad.decomposition] returns null unconditionally at the parent
 * commit. The rest were green the moment the symbol existed, since
 * [ImplementLoad.count] had to be real for any of this to COMPILE, and a test
 * that does not compile is a broken build rather than a red test. Which is
 * which is recorded in the commit body rather than blurred.
 *
 * Every load here is the ADDED load, and nothing in this file multiplies one.
 */
class ImplementLoadTest {
    private val eightyLbInKg = 80.0 / WeightUnit.LB_PER_KG

    @Test
    fun `an undeclared count is not decomposed`() {
        assertNull(ImplementLoad.decomposition(eightyLbInKg, null, WeightUnit.LB))
        assertNull(ImplementLoad.decomposition(eightyLbInKg, null, WeightUnit.KG))
    }

    @Test
    fun `a count of one is not decomposed`() {
        // Without this guard every barbell set in the app reads "1 × 100 kg".
        assertNull(ImplementLoad.decomposition(eightyLbInKg, 1, WeightUnit.LB))
        assertNull(ImplementLoad.decomposition(100.0, 1, WeightUnit.KG))
    }

    @Test
    fun `two implements split the total exactly`() {
        assertEquals("2 × 40 lb", ImplementLoad.decomposition(eightyLbInKg, 2, WeightUnit.LB))
        assertEquals("2 × 50 kg", ImplementLoad.decomposition(100.0, 2, WeightUnit.KG))
    }

    @Test
    fun `three implements split into three, not into two`() {
        // A hard-coded halving passes every assertion above this one.
        assertEquals("3 × 30 kg", ImplementLoad.decomposition(90.0, 3, WeightUnit.KG))
    }

    @Test
    fun `a count below one is one, and never divides by zero`() {
        listOf(0, -1, null).forEach {
            assertEquals(1, ImplementLoad.count(it), "declared $it must coerce to one implement")
            assertEquals(40.0, ImplementLoad.perImplementAddedKg(40.0, it), "declared $it")
            assertNull(ImplementLoad.decomposition(40.0, it, WeightUnit.KG), "declared $it")
        }
    }

    @Test
    fun `a single implement is the whole load, exactly`() {
        listOf(0.0, 20.0, eightyLbInKg, 55.0 / WeightUnit.LB_PER_KG, 175.0 / WeightUnit.LB_PER_KG)
            .forEach { assertEquals(it, ImplementLoad.perImplementAddedKg(it, 1), "added $it") }
    }

    /**
     * Characterization, not endorsement. A band-assisted set's load is already
     * invisible on every card in the app, because all four render sites guard
     * on `takeIf { it > 0 }`. Recording it here means a later decision to show
     * it is a deliberate visible diff rather than a side effect of this one.
     */
    @Test
    fun `no load and assistance both render as nothing, the way they do today`() {
        listOf(null, 0.0, -20.0).forEach {
            assertNull(ImplementLoad.decomposition(it, 2, WeightUnit.KG), "added load $it")
            assertNull(ImplementLoad.decomposition(it, 2, WeightUnit.LB), "added load $it")
        }
    }

    /**
     * The rounding artefact, pinned rather than suppressed. [WeightUnit.format]
     * quantises to a tenth of the DISPLAY unit AFTER converting, so an 80 lb
     * pair shown in kilograms reads "2 × 18.1 kg" beside a "36.3 kg" total and
     * 18.1 + 18.1 is 36.2. Bounded at 0.05 per implement, an order of
     * magnitude under the smallest real dumbbell increment.
     */
    @Test
    fun `the split is rounded after dividing, so it need not multiply back`() {
        assertEquals("36.3 kg", WeightUnit.KG.format(eightyLbInKg))
        assertEquals("2 × 18.1 kg", ImplementLoad.decomposition(eightyLbInKg, 2, WeightUnit.KG))
        assertEquals("81.5 lb", WeightUnit.LB.format(81.5 / WeightUnit.LB_PER_KG))
        assertEquals("2 × 40.8 lb", ImplementLoad.decomposition(81.5 / WeightUnit.LB_PER_KG, 2, WeightUnit.LB))
    }
}
