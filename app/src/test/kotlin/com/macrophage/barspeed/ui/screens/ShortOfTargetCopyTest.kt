package com.macrophage.barspeed.ui.screens

import com.macrophage.barspeed.model.ExitPrompt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whole-branch review of 0.2.0, round 1, UI-B3 (L2's #169 split against
 * older strings). History calls a set the app judged short "Short of
 * target" (`FailureProvenancePolicy.DERIVED_WORD`), and only a set the lifter
 * tapped "Failed". END SET EARLY's caption and the exit prompt for a set in
 * progress still said such a set "logs as failed" and that the control
 * "ends the set as a failure", so Record promised one word and History
 * showed another for one state. The Guide's copy is pinned in
 * `:core:model`, `ShortOfTargetCopyContractTest`.
 *
 * What this cannot reach: whether the composables draw these strings. That
 * step is compile- and lint-gated only.
 */
class ShortOfTargetCopyTest {
    @Test
    fun `end set early's caption says a short set is recorded as short of target`() {
        assertFalse("logs as failed" in END_SET_EARLY_CAPTION, END_SET_EARLY_CAPTION)
        assertTrue("a set that came up short is recorded as short of target" in END_SET_EARLY_CAPTION)
    }

    @Test
    fun `the in-set exit prompt says the control ends the set short of target`() {
        val body = exitBody(ExitPrompt.SET_IN_PROGRESS, null)
        assertFalse("as a failure" in body, body)
        assertTrue("that control ends the set short of target" in body, body)
    }
}
