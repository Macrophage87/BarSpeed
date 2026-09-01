package com.macrophage.barspeed.model

import com.macrophage.barspeed.model.BodyWeightPromptPolicy.BarColorRole
import com.macrophage.barspeed.model.BodyWeightPromptPolicy.StoredBodyWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The owner's four-state colour mapping for the "change body weight" control
 * issue #199 moves to the Plans screen (owner comment
 * https://github.com/Macrophage87/BarSpeed/issues/199#issuecomment-5475853845):
 *
 * | [StoredBodyWeight] | colour |
 * |---|---|
 * | ABSENT | RED |
 * | UNKNOWN_AGE | AMBER |
 * | DATED_STALE | AMBER |
 * | DATED_FRESH | VOLT |
 *
 * Two members share AMBER on purpose -- see [BodyWeightPromptPolicy.colorRoleFor]'s
 * own KDoc for why the compile still forces both to be named explicitly. This
 * test enumerates all four [StoredBodyWeight] members individually rather than
 * looping over `entries`, so a fifth member added later fails this test file to
 * compile instead of silently passing with three cases checked.
 */
class BodyWeightColorRoleTest {
    @Test
    fun `absent is red`() {
        assertEquals(BarColorRole.RED, BodyWeightPromptPolicy.colorRoleFor(StoredBodyWeight.ABSENT))
    }

    @Test
    fun `unknown age is amber`() {
        assertEquals(BarColorRole.AMBER, BodyWeightPromptPolicy.colorRoleFor(StoredBodyWeight.UNKNOWN_AGE))
    }

    @Test
    fun `dated stale is amber`() {
        assertEquals(BarColorRole.AMBER, BodyWeightPromptPolicy.colorRoleFor(StoredBodyWeight.DATED_STALE))
    }

    @Test
    fun `dated fresh is volt`() {
        assertEquals(BarColorRole.VOLT, BodyWeightPromptPolicy.colorRoleFor(StoredBodyWeight.DATED_FRESH))
    }
}
