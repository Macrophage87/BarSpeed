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
}
