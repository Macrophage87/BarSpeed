package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The measured ecc:con ratio says how many reps it was taken over (#88).
 *
 * `actualEccConRatio` is the mean eccentric over the mean concentric of the
 * reps that resolved an eccentric (#46), so a ratio from two reps of thirteen
 * and one from six of six read the same. `of` beside it is not the answer: it
 * counts reps that resolved ANY scored phase, and a drive-only rep is in it.
 * [TempoComplianceResult] therefore carries the count with the ratio, taken
 * from the same population in the same pass, and the export copies it out.
 *
 * READ THROUGH THE STORED FORM. Each assertion reads the result as it is
 * frozen into a set's `analysisJson`, which is what the exporter reads back
 * and the only place the count exists for a recorded set. A count computed
 * and dropped before storage would reach no export.
 *
 * RED WHEN WRITTEN, except the guard named in its KDoc.
 */
class EccConRatioCoverageTest {
    private fun rep(index: Int, eccS: Double?) = RepAnalysis(
        index = index,
        eccS = eccS,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.4,
        peakConVelMps = 0.6,
        meanEccVelMps = eccS?.let { -0.2 },
        peakEccVelMps = eccS?.let { -0.3 },
        romM = 0.5,
        peakPowerW = null,
    )

    private fun stored(c: TempoComplianceResult): JsonObject =
        Json.encodeToJsonElement(TempoComplianceResult.serializer(), c).jsonObject

    private fun storedCount(c: TempoComplianceResult): Int? =
        stored(c)["actualEccConRatioReps"]?.jsonPrimitive?.content?.toInt()

    /**
     * Eight reps, four of which resolved an eccentric: the ratio covers four,
     * and `of` -- every rep resolved its concentric -- is eight.
     */
    @Test
    fun `the ratio's count is the reps that resolved an eccentric, not the set`() {
        val reps = (1..8).map { rep(it, if (it % 2 == 0) 3.0 else null) }
        val c = SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, reps)
        assertEquals(3.0, c.actualEccConRatio, "the fixture's ratio moved")
        assertEquals(8, c.repsEvaluated, "the fixture's `of` moved")
        assertEquals(4, storedCount(c), "the stored result does not say how many reps the ratio covers")
    }

    /** Every rep resolved both phases: the ratio covers the whole set. */
    @Test
    fun `a set whose every rep resolved an eccentric counts every rep`() {
        val reps = (1..6).map { rep(it, 2.5) }
        val c = SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, reps)
        assertEquals(2.5, c.actualEccConRatio, "the fixture's ratio moved")
        assertEquals(6, storedCount(c), "a ratio over all six reps does not say six")
    }

    /**
     * GUARD, green before and after: no rep resolved an eccentric, so there is
     * no ratio and nothing for a count to qualify -- absent, never 0.
     */
    @Test
    fun `no eccentric resolved publishes neither a ratio nor a count`() {
        val c = SetAnalyzer.complianceFor(Tempo.parse("3010"), 0.5, (1..5).map { rep(it, null) })
        assertNull(c.actualEccConRatio, "a ratio with no eccentric")
        assertNull(stored(c)["actualEccConRatioReps"], "a count beside no ratio")
    }

    /**
     * The seven 2026-08-17 captures #88's table was read from, replayed under
     * a 3010 prescription: wherever a ratio is published, the count beside it
     * is the number of reps that resolved an eccentric -- the same reps the
     * ratio's two means are taken over. The per-capture figures are pinned in
     * `FieldDataRegressionTest`, "the ecc con ratio each capture publishes,
     * and over how many reps"; this asserts the stored count agrees with them
     * without restating them.
     */
    @Test
    fun `on the field captures the stored count is the reps the ratio was taken over`() {
        val captures =
            listOf(
                Triple("field-ohp-rotating-8rep.csv", StartPhase.ECCENTRIC, 20.411656650451594),
                Triple("field-ohp-rotating-8rep-b.csv", StartPhase.ECCENTRIC, 24.94758035055195),
                Triple("field-bench-rotating-6rep-ok.csv", StartPhase.ECCENTRIC, 43.091275150953365),
                Triple("field-bench-rotating-6rep.csv", StartPhase.ECCENTRIC, 43.091275150953365),
                Triple("field-cablerow-static-8rep.csv", StartPhase.CONCENTRIC, 27.215542200602126),
                Triple("field-facepull-static-12rep.csv", StartPhase.CONCENTRIC, 9.97903214022078),
                Triple("field-pallof-static-12rep.csv", StartPhase.CONCENTRIC, 11.79340234968141),
            )
        for ((file, startsWith, loadKg) in captures) {
            val samples = ImuCsv.decode(javaClass.getResourceAsStream("/$file")!!.readBytes().decodeToString())
            val analysis =
                SetAnalyzer.analyze(
                    samples,
                    startsWith,
                    loadKg = loadKg,
                    targets = SetTargets(tempo = Tempo.parse("3010"), toleranceS = 0.5),
                )
            val compliance = assertNotNull(analysis.tempoCompliance, "$file: compliance")
            val expected = analysis.reps.count { it.eccS != null }.takeIf { compliance.actualEccConRatio != null }
            assertEquals(expected, storedCount(compliance), "$file: the ratio's rep count")
        }
    }
}
