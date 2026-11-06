package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The words an unguided set says at START and END (voice F7). */
class UnguidedSetCallTest {
    @Test
    fun `an unguided rep set says it started and ended`() {
        assertEquals("Set started", UnguidedSetCall.atStart(PrepCase.NONE, timed = false, audioCues = true))
        assertEquals(
            "Set ended",
            UnguidedSetCall.atEnd(PrepCase.NONE, timed = false, audioCues = true),
        )
    }

    @Test
    fun `the voice switched off says nothing`() {
        assertNull(UnguidedSetCall.atStart(PrepCase.NONE, timed = false, audioCues = false))
        assertNull(UnguidedSetCall.atEnd(PrepCase.NONE, timed = false, audioCues = false))
    }

    /** A guided set has its lead-in and `Done`; a timed one its start word and `Time` or `Set ended`. */
    @Test
    fun `sets that already speak at their edges are left alone`() {
        for (case in listOf(PrepCase.CUED, PrepCase.TIMED)) {
            assertNull(UnguidedSetCall.atStart(case, timed = case == PrepCase.TIMED, audioCues = true), "$case")
            assertNull(
                UnguidedSetCall.atEnd(case, timed = case == PrepCase.TIMED, audioCues = true),
                "$case",
            )
        }
        assertNull(UnguidedSetCall.atStart(PrepCase.NONE, timed = true, audioCues = true))
        assertNull(UnguidedSetCall.atEnd(PrepCase.NONE, timed = true, audioCues = true))
    }

    /** Neither word is a rep call, so the engine is handed both unmarked (#339). */
    @Test
    fun `neither word is marked as a rep call`() {
        assertEquals(emptyList(), SpokenRepCall.spansOf(UnguidedSetCall.STARTED))
        assertEquals(emptyList(), SpokenRepCall.spansOf(UnguidedSetCall.ENDED))
    }
}
