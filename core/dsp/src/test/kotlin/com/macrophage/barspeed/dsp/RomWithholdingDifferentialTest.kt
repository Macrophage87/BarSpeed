package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * THE DIFFERENTIALS FOR ISSUE #291, RED AT THE COMMIT THAT ADDS THEM.
 *
 * `SetAnalyzer.romSpreadPct` is the figure `RangeConsistencyChip` draws and the
 * figure the export publishes as `summary.romSpread_pct`. Today it is the
 * population deviation over EVERY rep, whether or not the analysis can bound the
 * displacements it is a deviation of, and on the committed corpus it reads
 * 98.1 % on six bench reps performed to a 3010 count.
 *
 * After the fix it is taken over [RomBound.boundedReps] and is absent below
 * [RomBound.MIN_BOUNDED_REPS] of them. `RomBoundCorpusTest` measures that no
 * capture in this corpus has two, so every assertion of absence below is a
 * capture whose chip goes DARK rather than reading a number the analysis cannot
 * bound -- the owner's rule, and the same treatment `AccelArtefact` gives a peak
 * it cannot bound.
 *
 * The compatibility direction is asserted here too, and is green in both states
 * on purpose: a set of reps all carrying `null` -- an analysis stored before the
 * rule existed -- must keep the figure it has always published.
 */
class RomWithholdingDifferentialTest {
    private fun rep(index: Int, romM: Double, bounded: Boolean?) = RepAnalysis(
        index = index,
        eccS = 1.0,
        bottomPauseS = 0.0,
        conS = 1.0,
        topPauseS = 0.0,
        meanConVelMps = 0.3,
        peakConVelMps = 0.5,
        meanEccVelMps = -0.2,
        peakEccVelMps = -0.3,
        romM = romM,
        peakPowerW = null,
        romBounded = bounded,
    )

    @Test
    fun `the spread is taken over the bounded reps, not over every rep`() {
        // 0.3 and 0.5 bounded, 2.0 unbounded: mean 0.4, population deviation
        // 0.1, so 25.0 per cent. Over all three it reads 81.3 -- a figure whose
        // whole magnitude comes from a displacement nothing bounds.
        val reps = listOf(rep(0, 0.3, true), rep(1, 0.5, true), rep(2, 2.0, false))
        assertEquals(25.0, SetAnalyzer.romSpreadPct(reps))
    }

    @Test
    fun `a set with one bounded rep publishes no spread`() {
        // A deviation over one rep is zero by construction and would read as
        // reps that agreed perfectly. Absence, never 0.0 and never the
        // all-reps figure.
        assertNull(SetAnalyzer.romSpreadPct(listOf(rep(0, 0.4, true), rep(1, 0.5, false), rep(2, 0.6, false))))
    }

    @Test
    fun `a set with no bounded rep publishes no spread`() {
        assertNull(SetAnalyzer.romSpreadPct(listOf(rep(0, 0.4, false), rep(1, 0.9, false))))
    }

    @Test
    fun `a set whose bounded reps average nothing publishes no spread rather than NaN`() {
        // The divide guard, re-asserted over the narrowed population: the two
        // bounded reps here are the zero ones, and the unbounded rep that would
        // have lifted the mean is not in the population any more.
        assertNull(SetAnalyzer.romSpreadPct(listOf(rep(0, 0.0, true), rep(1, 0.0, true), rep(2, 0.9, false))))
    }

    @Test
    fun `an archived set carrying no answer keeps the spread it has always published`() {
        // Green in both states. Absence is not falsity: these reps were measured
        // under a rule that asked nothing about anchors, and losing the figure
        // would be a regression for every set already on disk.
        assertEquals(25.0, SetAnalyzer.romSpreadPct(listOf(rep(0, 0.3, null), rep(1, 0.5, null))))
    }

    @Test
    fun `every capture in the corpus publishes no spread instead of up to 98 per cent`() {
        // The figures on the left are what each capture publishes today, pinned
        // by RomDriftBaselineTest at this commit's parent.
        val today = mapOf(
            "field-ohp-3010-7rep-s42-set02" to 31.0,
            "field-bench-3010-6rep-s42-set05" to 87.4,
            "field-bench-3010-6rep-s42-set07" to 98.1,
            "field-cablerow-3010-8rep-s42-set09" to 76.7,
            "field-pullup-3010-8rep-s42-set11" to 96.9,
            "field-pullup-4010-8rep-s42-set13" to 62.1,
            "field-deadlift-straight-5rep-s43-set04" to 50.9,
            "field-deadlift-straight-5rep-s43-set05" to 53.4,
            "field-deadlift-straight-5rep-s43-set06" to 97.6,
            "field-assistedpullup-3010-s37-set08" to 80.4,
            "field-ohp-prepinflated-s37-set03" to 63.5,
        )
        today.keys.forEach { fixture ->
            val a = ArtefactCorpus.analyse(ArtefactCorpus.cases.first { it.fixture == fixture })
            assertNull(
                SetAnalyzer.romSpreadPct(a.reps),
                "$fixture must withhold its spread, which reads ${today[fixture]} today",
            )
        }
    }

    @Test
    fun `no capture publishes a mean range either, because none has a bounded rep`() {
        // RED AT THE COMMIT THAT ADDS THIS, on two of the eleven. `meanRom_m` is
        // Exporters' figure over RomBound.boundedReps and needs only ONE bounded
        // rep, so withholding it needs an empty population rather than a small
        // one -- and today field-43 set 4 publishes 0.351 m and field-37 set 8
        // publishes 0.200 m, each from the single rep the route-blind rule
        // admitted. AnchorRouteTest measures the 1.0180 m and 5.0198 m the
        // correction erased across those reps' own intervals.
        ArtefactCorpus.cases.forEach { case ->
            val a = ArtefactCorpus.analyse(case)
            assertEquals(
                emptyList(),
                RomBound.boundedReps(a.reps).map { it.index },
                "${case.fixture} reps a range claim may be taken over",
            )
        }
    }

    @Test
    fun `the rep count and every per-rep figure are untouched by the withholding`() {
        // Green in both states, and asserted because it is the whole terms of the
        // trade: the SET-LEVEL claim narrows and nothing else moves. The bars on
        // the chart are still what their windows measured.
        val seven = ArtefactCorpus.analyse(
            ArtefactCorpus.cases.first { it.fixture == "field-bench-3010-6rep-s42-set07" },
        )
        assertEquals(6, seven.reps.size, "detections")
        assertEquals(listOf(0.168, 0.758, 0.188, 0.124, 0.353, 1.592), seven.reps.map { it.romM }, "rom_m per rep")
        assertEquals(1.084, seven.reps.maxOf { it.peakConVelMps }, "the rep-level peak velocity")
    }
}
