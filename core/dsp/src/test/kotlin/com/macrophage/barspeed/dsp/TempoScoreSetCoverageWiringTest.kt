package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.TempoScoreTone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tempo chip over a whole set, through [tempoScore] on [SetAnalysis] --
 * the function both screens call -- and through the STORED form, because the
 * history screen reads the analysis back out of the set's `analysisJson`
 * (#329).
 *
 * Target 30X0 on a drive-up lift: a 3.00 s eccentric and an explosive up
 * stroke, so the eccentric is the only scored phase and a rep whose
 * eccentric nothing measured resolved no scored phase at all. Tolerance
 * 0.5 s. Reps are built by hand so the population is exact.
 *
 * GREEN WHEN WRITTEN: the guards below hold before and after #329.
 */
class TempoScoreSetCoverageWiringTest {
    private val explosiveUp = Tempo.parse("30X0")

    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 0.6,
        topPauseS = null,
        meanConVelMps = 0.8,
        peakConVelMps = 1.1,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    private fun stored(reps: List<RepAnalysis>): SetAnalysis {
        val analysis =
            SetAnalysis(reps, 50.0, null, SetAnalyzer.complianceFor(explosiveUp, 0.5, reps), emptyList())
        return Json.decodeFromString(SetAnalysis.serializer(), Json.encodeToString(SetAnalysis.serializer(), analysis))
    }

    /** GUARD, green before and after: a set with no prescribed tempo draws no chip. */
    @Test
    fun `a set with no tempo compliance draws no chip`() {
        val reps = (0..3).map { rep(it, 3.0) }
        assertNull(SetAnalysis(reps, 50.0, null, null, emptyList()).tempoScore())
    }

    /** GUARD, green before and after: every rep resolved the only scored phase, on tempo. */
    @Test
    fun `an explosive-drive set measured on every rep ticks, read back from storage`() {
        val a = stored((0..7).map { rep(it, 3.0) })
        val c = assertNotNull(a.tempoCompliance)
        assertEquals(listOf(TempoComplianceResult.PHASE_ECCENTRIC), c.phases.filter { it.scored }.map { it.phase })
        assertEquals(8, c.repsEvaluated, "the fixture's graded count moved")
        val score = assertNotNull(a.tempoScore())
        assertEquals("Tempo 8/8 ✓", score.text)
        assertEquals(TempoScoreTone.ON_TEMPO, score.tone)
        assertNull(score.ungradedNote)
    }
}
