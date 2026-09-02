package com.macrophage.barspeed.model

import com.macrophage.barspeed.model.TempoScoreLabel.PhaseFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tempo chip's claim, pinned where CI can reach it.
 *
 * Phase names are spelled as `SetAnalyzer` writes them; `TempoScoreWiringTest`
 * in `:core:dsp` is what holds those two spellings together.
 */
class TempoScoreLabelTest {
    private fun bothGraded() = listOf(
        PhaseFacts("eccentric", prescribed = true, scored = true),
        PhaseFacts("bottomPause", prescribed = true, scored = false),
        PhaseFacts("concentric", prescribed = true, scored = true),
        PhaseFacts("topPause", prescribed = true, scored = false),
    )

    private fun score(compliant: Int, evaluated: Int, phases: List<PhaseFacts> = bothGraded()) =
        TempoScoreLabel.of(compliant, evaluated, phases)

    @Test
    fun `nothing to draw when no rep was evaluated`() {
        assertNull(score(0, 0))
    }

    @Test
    fun `a set graded on every prescribed phase and on tempo ticks`() {
        val s = assertNotNull(score(4, 4))
        assertEquals("Tempo 4/4 ✓", s.text)
        assertEquals(TempoScoreTone.ON_TEMPO, s.tone)
    }

    @Test
    fun `a missed rep drops the tick`() {
        val s = assertNotNull(score(3, 4))
        assertEquals("Tempo 3/4", s.text)
        assertEquals(TempoScoreTone.OFF_TEMPO, s.tone)
    }

    @Test
    fun `full coverage needs no qualifying sentence`() {
        assertNull(assertNotNull(score(4, 4)).ungradedNote)
        assertNull(assertNotNull(score(3, 4)).ungradedNote)
    }

    @Test
    fun `an ungraded eccentric is named as the gap`() {
        val phases =
            listOf(
                PhaseFacts("eccentric", prescribed = true, scored = false),
                PhaseFacts("bottomPause", prescribed = true, scored = false),
                PhaseFacts("concentric", prescribed = true, scored = true),
                PhaseFacts("topPause", prescribed = true, scored = false),
            )
        assertEquals(listOf("eccentric"), assertNotNull(score(4, 4, phases)).ungradedPhases)
    }

    @Test
    fun `an unmeasured pause is not a gap in coverage`() {
        // Pauses are measured and reported but deliberately never scored.
        // Naming one here would send a screen to print "bottom pause not
        // measured" on every set that prescribes one.
        assertEquals(emptyList(), assertNotNull(score(4, 4)).ungradedPhases)
    }

    @Test
    fun `a phase the prescription never named is not a gap either`() {
        val phases =
            listOf(
                PhaseFacts("eccentric", prescribed = false, scored = false),
                PhaseFacts("concentric", prescribed = true, scored = true),
            )
        assertEquals(emptyList(), assertNotNull(score(4, 4, phases)).ungradedPhases)
    }

    /**
     * A concentric-first set whose lowering never cleared the run threshold:
     * the eccentric was prescribed, nothing measured it, and every drive was
     * on tempo. `TempoScoreWiringTest` builds this same set for real.
     */
    private fun driveOnlyGraded() = listOf(
        PhaseFacts("eccentric", prescribed = true, scored = false),
        PhaseFacts("bottomPause", prescribed = true, scored = false),
        PhaseFacts("concentric", prescribed = true, scored = true),
        PhaseFacts("topPause", prescribed = true, scored = false),
    )

    @Test
    fun `no tick over a phase nothing graded`() {
        // "Tempo 4/4 ✓" over a set whose eccentric was never measured
        // states compliance on a phase that has no measurement behind it.
        assertEquals("Tempo 4/4", assertNotNull(score(4, 4, driveOnlyGraded())).text)
    }

    @Test
    fun `partial coverage is neither a pass nor a failure`() {
        assertEquals(TempoScoreTone.PARTIAL, assertNotNull(score(4, 4, driveOnlyGraded())).tone)
    }

    @Test
    fun `the gap is stated in words, naming the phase and what is left`() {
        assertEquals(
            "Eccentric not measured this set -- the ratio covers the concentric only.",
            assertNotNull(score(4, 4, driveOnlyGraded())).ungradedNote,
        )
    }

    @Test
    fun `a missed rep still reads as off tempo when coverage is partial`() {
        // Coverage and compliance are two questions. A rep outside tolerance
        // is a miss whether or not another phase went ungraded, and the note
        // is still owed.
        val s = assertNotNull(score(3, 4, driveOnlyGraded()))
        assertEquals("Tempo 3/4", s.text)
        assertEquals(TempoScoreTone.OFF_TEMPO, s.tone)
        assertNotNull(s.ungradedNote)
    }
}
