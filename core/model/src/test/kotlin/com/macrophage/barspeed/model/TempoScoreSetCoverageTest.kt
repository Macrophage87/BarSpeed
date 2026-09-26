package com.macrophage.barspeed.model

import com.macrophage.barspeed.model.TempoScoreLabel.PhaseFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tempo chip's coverage is taken over every rep of the set (#329).
 *
 * The ratio's denominator is `repsEvaluated`: reps that resolved at least one
 * scored phase. A rep that resolved none is dropped before anything is
 * compared, so a coverage count taken over that denominator can never see
 * it. `RepAnalysis.conS` is never null, so a rep resolves no scored phase
 * only when the concentric is not scored at all -- an "X" up stroke, which
 * leaves the eccentric as the only scored phase. That is the set built here.
 *
 * GREEN WHEN WRITTEN: the guards below hold before and after #329.
 */
class TempoScoreSetCoverageTest {
    /** An "X" up stroke: the eccentric is the only scored phase, resolved on [eccResolved] reps. */
    private fun explosiveUp(eccResolved: Int) = listOf(
        PhaseFacts("eccentric", prescribed = true, scored = eccResolved > 0, repsResolved = eccResolved),
        PhaseFacts("bottomPause", prescribed = true, scored = false, repsResolved = 0),
        PhaseFacts("concentric", prescribed = false, scored = false, repsResolved = 0),
        PhaseFacts("topPause", prescribed = true, scored = false, repsResolved = 0),
    )

    /** GUARD, green before and after: every rep of the set resolved the eccentric. */
    @Test
    fun `a set measured on every rep ticks with no note`() {
        val s = assertNotNull(TempoScoreLabel.of(8, 8, setReps = 8, phases = explosiveUp(8)))
        assertEquals("Tempo 8/8 ✓", s.text)
        assertEquals(TempoScoreTone.ON_TEMPO, s.tone)
        assertNull(s.ungradedNote)
    }

    /** GUARD, green before and after: no graded rep is no ratio, however many reps the set had. */
    @Test
    fun `no graded rep draws nothing whatever the set's size`() {
        assertNull(TempoScoreLabel.of(0, 0, setReps = 8, phases = explosiveUp(0)))
    }
}
