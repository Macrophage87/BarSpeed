package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The verdict line "Tempo (phase): x/y reps on tempo" never divides by the
 * measured reps alone without saying how many went unmeasured (#328).
 *
 * The line's y is the phase's `repsEvaluated`: the reps that RESOLVED the
 * phase. A rep whose eccentric nothing measured is dropped before anything is
 * compared, so on a set of eight with four measured eccentrics "2/4 reps on
 * tempo" reads as a statement about a four-rep set. The gap is counted by
 * [com.macrophage.barspeed.model.PhaseCoverage] over every rep of the
 * analysis -- the rule the eccentric caption on the same card reads (#89).
 *
 * The line is written only when some measured rep is OUT of tolerance, so
 * every case here has one; a partly measured set with every measured rep in
 * tolerance writes no tempo line at all, and the guard below says so.
 *
 * Reps are built by hand: target 3010 on a drive-up lift (3.00 s eccentric,
 * 1.00 s concentric), tolerance 0.5 s. Every concentric is 1.0 s, so only the
 * eccentric line is written.
 *
 * RED WHEN WRITTEN, except the two guards named in their KDoc.
 */
class TempoVerdictCoverageTest {
    private val tempo = Tempo.parse("3010")

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

    private fun verdicts(reps: List<RepAnalysis>): List<String> {
        val compliance = SetAnalyzer.complianceFor(tempo, 0.5, reps)
        return CoachingRules.verdicts(reps, null, compliance, SetTargets(tempo = tempo, toleranceS = 0.5))
    }

    /** Eight reps, four measured eccentrics of which two are in tolerance, four drive-only. */
    @Test
    fun `a partly measured phase says how many reps went unmeasured`() {
        val reps = listOf(3.2, 3.1, 4.0, 4.2, null, null, null, null).mapIndexed { i, e -> rep(i, e) }
        assertEquals(
            listOf(
                "Tempo (eccentric): 2 of 4 measured reps on tempo · 4 not measured; " +
                    "worst was 1.20 s too slow (target 3.00 s).",
            ),
            verdicts(reps),
        )
    }

    /** One measured rep is one rep, not "reps", and the rest of the set is still counted. */
    @Test
    fun `a single measured rep is named as one`() {
        val reps = (0..4).map { rep(it, if (it == 2) 4.0 else null) }
        assertEquals(
            listOf(
                "Tempo (eccentric): 0 of 1 measured rep on tempo · 4 not measured; " +
                    "worst was 1.00 s too slow (target 3.00 s).",
            ),
            verdicts(reps),
        )
    }

    /**
     * GUARD, green before and after: a fully measured phase keeps the x/y form
     * byte for byte. A set already recorded carries its line frozen in
     * analysisJson, and on a fully measured phase that form is already a
     * statement about every rep.
     */
    @Test
    fun `a fully measured phase keeps the ratio form`() {
        val reps = listOf(3.2, 3.1, 2.9, 4.0, 4.2, 2.2).mapIndexed { i, e -> rep(i, e) }
        assertEquals(
            listOf("Tempo (eccentric): 3/6 reps on tempo; worst was 1.20 s too slow (target 3.00 s)."),
            verdicts(reps),
        )
    }

    /**
     * GUARD, green before and after: a partly measured set whose measured reps
     * are all in tolerance writes no tempo line, so this line never claims "on
     * tempo" for a set on its own. That sentence is the eccentric caption's,
     * which scopes it (#89).
     */
    @Test
    fun `a partly measured phase with every measured rep on tempo writes no line`() {
        val reps = (0..5).map { rep(it, if (it < 3) 3.2 else null) }
        assertEquals(emptyList<String>(), verdicts(reps))
    }
}
