package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the rest screen's rep-quality card draws (#281).
 *
 * `RepQualityCard` in `:app` renders [RepQualityCardPolicy.layout]'s answer
 * and decides nothing else; nothing here can say what a device drew.
 */
class RepQualityCardPolicyTest {
    private fun layout(
        timed: Boolean = false,
        hasReps: Boolean = true,
        hasVerdicts: Boolean = true,
        explosive: Boolean = false,
        gradedEccentric: Boolean = false,
    ) = RepQualityCardPolicy.layout(timed, hasReps, hasVerdicts, explosive, gradedEccentric)

    @Test
    fun `a hold with verdicts draws them and no chart`() {
        assertEquals(RepQualityLayout.VERDICTS, layout(timed = true, hasReps = false))
    }

    @Test
    fun `a hold with no verdict draws no card`() {
        assertEquals(RepQualityLayout.NONE, layout(timed = true, hasReps = false, hasVerdicts = false))
    }

    @Test
    fun `an explosive set with reps draws its peak velocity`() {
        assertEquals(RepQualityLayout.PEAK_VELOCITY_CHART, layout(explosive = true, gradedEccentric = true))
    }

    @Test
    fun `a set graded on its eccentric draws the tempo chart`() {
        assertEquals(RepQualityLayout.ECC_TEMPO_CHART, layout(gradedEccentric = true))
    }

    @Test
    fun `any other set with reps draws its concentric velocity`() {
        assertEquals(RepQualityLayout.CON_VELOCITY_CHART, layout())
        assertEquals(RepQualityLayout.CON_VELOCITY_CHART, layout(hasVerdicts = false))
    }

    /** No rep and nothing to say: there is nothing for a card to hold. */
    @Test
    fun `a rep set with no reps and no verdict draws no card`() {
        assertEquals(RepQualityLayout.NONE, layout(hasReps = false, hasVerdicts = false))
        assertEquals(RepQualityLayout.NONE, layout(hasReps = false, hasVerdicts = false, explosive = true))
    }

    /**
     * #281: a set #247 refuses has an empty rep list by construction and
     * carries its reason as a verdict. The rest screen is the one window in
     * which the set can still be corrected, so the reason is drawn, as text,
     * with no chart there is nothing to plot on.
     */
    @Test
    fun `a refused set with no reps draws its reason`() {
        assertEquals(RepQualityLayout.VERDICTS, layout(hasReps = false))
    }

    /** Whatever kind of chart the set would have had, none is drawn without reps. */
    @Test
    fun `a set with no reps draws its verdicts whatever chart it would have had`() {
        assertEquals(RepQualityLayout.VERDICTS, layout(hasReps = false, explosive = true))
        assertEquals(RepQualityLayout.VERDICTS, layout(hasReps = false, gradedEccentric = true))
    }
}
