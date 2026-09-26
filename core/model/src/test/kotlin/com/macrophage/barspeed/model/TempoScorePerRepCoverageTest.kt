package com.macrophage.barspeed.model

import com.macrophage.barspeed.model.TempoScoreLabel.PhaseFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tempo tick carries per-rep coverage (#230).
 *
 * `PhaseFacts.scored` is set-level -- true the moment ONE rep resolves the
 * phase -- so a set where some graded reps resolved an eccentric and others
 * did not reported no gap and ticked. [PhaseFacts.repsResolved] is the
 * analyzer's per-phase count, and the label reads it through
 * [PhaseCoverage], the rule the rest screen's eccentric card reads too.
 *
 * RED WHEN WRITTEN, except the guard named in its KDoc.
 */
class TempoScorePerRepCoverageTest {
    /** Seven reps graded; the eccentric resolved on [eccResolved] of them, the concentric on all seven. */
    private fun phases(eccResolved: Int, conResolved: Int = 7) = listOf(
        PhaseFacts("eccentric", prescribed = true, scored = eccResolved > 0, repsResolved = eccResolved),
        PhaseFacts("bottomPause", prescribed = true, scored = false, repsResolved = 0),
        PhaseFacts("concentric", prescribed = true, scored = conResolved > 0, repsResolved = conResolved),
        PhaseFacts("topPause", prescribed = true, scored = false, repsResolved = 0),
    )

    @Test
    fun `a set whose eccentric was measured on some reps does not tick`() {
        val s = assertNotNull(TempoScoreLabel.of(7, 7, setReps = 7, phases = phases(eccResolved = 5)))
        assertEquals("Tempo 7/7", s.text)
        assertEquals(TempoScoreTone.PARTIAL, s.tone)
    }

    @Test
    fun `the gap is stated with the count`() {
        assertEquals(
            "Eccentric: 5 of 7 reps measured · 2 not measured.",
            assertNotNull(TempoScoreLabel.of(7, 7, setReps = 7, phases = phases(eccResolved = 5))).ungradedNote,
        )
    }

    /** Coverage and compliance stay two questions: a miss is a miss, and the note is still owed. */
    @Test
    fun `a missed rep on a partly measured set is off tempo and still qualified`() {
        val s = assertNotNull(TempoScoreLabel.of(6, 7, setReps = 7, phases = phases(eccResolved = 5)))
        assertEquals("Tempo 6/7", s.text)
        assertEquals(TempoScoreTone.OFF_TEMPO, s.tone)
        assertEquals("Eccentric: 5 of 7 reps measured · 2 not measured.", s.ungradedNote)
    }

    /**
     * Both phases partly measured, each gap stated, in phase order. (A phase
     * measured on NO rep beside a partly measured one cannot come out of the
     * analyzer: a rep is graded once any scored phase resolved, so the other
     * phase then resolved on every graded rep.)
     */
    @Test
    fun `two partly measured phases are both stated`() {
        assertEquals(
            "Eccentric: 5 of 7 reps measured · 2 not measured. " +
                "Concentric: 6 of 7 reps measured · 1 not measured.",
            assertNotNull(
                TempoScoreLabel.of(7, 7, setReps = 7, phases = phases(eccResolved = 5, conResolved = 6)),
            ).ungradedNote,
        )
    }

    /** GUARD, green before and after: every graded rep resolved every prescribed phase. */
    @Test
    fun `a set measured on every graded rep ticks with no note`() {
        val s = assertNotNull(TempoScoreLabel.of(7, 7, setReps = 7, phases = phases(eccResolved = 7)))
        assertEquals("Tempo 7/7 ✓", s.text)
        assertEquals(TempoScoreTone.ON_TEMPO, s.tone)
        assertNull(s.ungradedNote)
    }
}
