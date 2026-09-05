package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ExerciseDef.startsAtTop] as a property and as a companion function, pinned
 * equal over every combination of the two fields that decide it.
 *
 * The function is new (#241): the prep countdown has to answer "which way does
 * the first movement go" while holding a start phase and a drive direction, not
 * a whole [ExerciseDef], and a second copy of `(startsWith == ECCENTRIC) ==
 * concentricUp` written at that call site would be a second fact that can
 * disagree with this one. The property now delegates to the function, so this
 * file is a characterization of behaviour that did not change.
 */
class StartsAtTopFunctionTest {
    @Test
    fun `a lift lowered first with an upward drive starts at the top`() {
        assertTrue(ExerciseDef.startsAtTop(StartPhase.ECCENTRIC, concentricUp = true))
    }

    @Test
    fun `a lift driven first with an upward drive starts at the bottom`() {
        assertFalse(ExerciseDef.startsAtTop(StartPhase.CONCENTRIC, concentricUp = true))
    }

    @Test
    fun `a pulldown driven first drives downward from the top`() {
        assertTrue(ExerciseDef.startsAtTop(StartPhase.CONCENTRIC, concentricUp = false))
    }

    @Test
    fun `a downward drive lowered first starts at the bottom`() {
        assertFalse(ExerciseDef.startsAtTop(StartPhase.ECCENTRIC, concentricUp = false))
    }

    @Test
    fun `the property is the function of its own two fields`() {
        for (phase in StartPhase.entries) {
            for (up in listOf(true, false)) {
                val def = ExerciseDef("x", "X", startsWith = phase, concentricUp = up)
                assertEquals(
                    ExerciseDef.startsAtTop(phase, up),
                    def.startsAtTop,
                    "property and function disagree for $phase / concentricUp=$up",
                )
            }
        }
    }

    @Test
    fun `the seed's own squat and deadlift keep the answers they had`() {
        assertTrue(ExerciseDef.seedById("back_squat")!!.startsAtTop)
        assertFalse(ExerciseDef.seedById("deadlift")!!.startsAtTop)
    }
}
