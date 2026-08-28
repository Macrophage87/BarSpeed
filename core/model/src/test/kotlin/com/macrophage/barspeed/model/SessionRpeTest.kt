package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The session rating's scale and what it does with a value off it (#159).
 *
 * GREEN ON ARRIVAL, and said so rather than dressed up as a differential.
 * [SessionRpe] is a new pure object that nothing called when these were
 * written, so there was no behaviour for them to have failed against; the
 * commit that changes behaviour is the one that stores the value, and its
 * differentials red in the commit before it. What shows these can fail at all
 * is the mutation table in the commit body, not their presence here.
 */
class SessionRpeTest {
    @Test
    fun `the scale runs one to ten with nothing missing from the middle`() {
        assertEquals(1, SessionRpe.MIN)
        assertEquals(10, SessionRpe.MAX)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), SessionRpe.VALUES)
    }

    /**
     * The control's options and the scale are one fact.
     *
     * Written out as a literal above AND derived here, which is not a
     * duplicate: the literal says what the ten numbers are, and this says the
     * list cannot drift from the bounds the schema and the column are pinned
     * to. A control built from a hand-written `1..9` at the call site is
     * exactly what this makes impossible.
     */
    @Test
    fun `the offered values are the scale, not a list beside it`() {
        assertEquals((SessionRpe.MIN..SessionRpe.MAX).toList(), SessionRpe.VALUES)
        assertEquals(SessionRpe.MIN, SessionRpe.VALUES.first())
        assertEquals(SessionRpe.MAX, SessionRpe.VALUES.last())
    }

    @Test
    fun `every value the scale offers is accepted unchanged`() {
        for (rating in SessionRpe.VALUES) {
            assertEquals(rating, SessionRpe.accepted(rating), "$rating is on the scale and was not stored as itself")
        }
    }

    /**
     * A skipped rating is an absence and stays one.
     *
     * The whole point of the capture being skippable. Null in, null out, with
     * no midpoint substituted anywhere along the way -- a 5 here would be an
     * answer nobody gave, and it would be indistinguishable from one they did.
     */
    @Test
    fun `a skipped rating stays absent rather than becoming a number`() {
        assertNull(SessionRpe.accepted(null))
    }

    /**
     * A value off the scale is refused, not clamped.
     *
     * 0 and 11 are the two that matter. Clamping 11 to 10 would turn a
     * programming error into the hardest session the lifter ever recorded, and
     * accepting 0 would publish a rating the schema's own bounds reject into a
     * document whose reader has been told the range is 1 to 10.
     */
    @Test
    fun `a rating off the scale is refused rather than clamped onto it`() {
        assertNull(SessionRpe.accepted(0))
        assertNull(SessionRpe.accepted(11))
        assertNull(SessionRpe.accepted(-3))
        assertNull(SessionRpe.accepted(Int.MAX_VALUE))
    }
}
