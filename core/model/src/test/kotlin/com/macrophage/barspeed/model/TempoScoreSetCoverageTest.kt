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
 * RED WHEN WRITTEN, except the two guards named in their KDoc: on a set of
 * eight whose eccentric four reps resolved, the chip read "Tempo 4/4 ✓" in
 * the OK tone with no note, a tick over half the set.
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

    /** Eight reps, four of which resolved the only scored phase: no tick, and not a failure either. */
    @Test
    fun `reps that resolved no scored phase withhold the tick`() {
        val s = assertNotNull(TempoScoreLabel.of(4, 4, setReps = 8, phases = explosiveUp(4)))
        assertEquals("Tempo 4/4", s.text)
        assertEquals(TempoScoreTone.PARTIAL, s.tone)
    }

    /** The count is PhaseCoverage's over the set's reps -- the eccentric caption's own. */
    @Test
    fun `the note says how many reps went unmeasured`() {
        assertEquals(
            "Eccentric: 4 of 8 reps measured · 4 not measured.",
            assertNotNull(TempoScoreLabel.of(4, 4, setReps = 8, phases = explosiveUp(4))).ungradedNote,
        )
    }

    /** Coverage and compliance stay two questions: a miss is a miss, and the note is still owed. */
    @Test
    fun `a miss beside unmeasured reps is off tempo and still qualified`() {
        val s = assertNotNull(TempoScoreLabel.of(3, 4, setReps = 8, phases = explosiveUp(4)))
        assertEquals("Tempo 3/4", s.text)
        assertEquals(TempoScoreTone.OFF_TEMPO, s.tone)
        assertEquals("Eccentric: 4 of 8 reps measured · 4 not measured.", s.ungradedNote)
    }

    /** GUARD, green before and after: no graded rep is no ratio, however many reps the set had. */
    @Test
    fun `no graded rep draws nothing whatever the set's size`() {
        assertNull(TempoScoreLabel.of(0, 0, setReps = 8, phases = explosiveUp(0)))
    }
}
