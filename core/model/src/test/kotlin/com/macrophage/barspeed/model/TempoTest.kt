package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempoTest {
    @Test
    fun `parses compact notation`() {
        val tempo = Tempo.parse("4010")
        assertEquals(4.0, tempo.downS)
        assertEquals(0.0, tempo.bottomPauseS)
        assertEquals(1.0, tempo.upS)
        assertEquals(0.0, tempo.topPauseS)
    }

    @Test
    fun `parses explosive X concentric`() {
        val tempo = Tempo.parse("30X1")
        assertNull(tempo.upS)
        assertTrue(tempo.isExplosiveUpStroke)
        assertEquals("30X1", tempo.notation())
    }

    @Test
    fun `parses dash separated notation`() {
        val tempo = Tempo.parse("4-0-1-0")
        assertEquals(4.0, tempo.downS)
        assertEquals("4010", tempo.notation())
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalStateException> { Tempo.parse("4a10") }
        assertFailsWith<IllegalArgumentException> { Tempo.parse("40") }
        assertNull(Tempo.parseOrNull("nope"))
    }

    /**
     * `0000` parses. Nothing in this type refuses a prescription in which
     * neither stroke moves for any time at all, so whatever floor a control
     * that BUILDS a tempo needs, it does not get it from here.
     *
     * Premise pin for #148. No behaviour changes with it.
     */
    @Test
    fun `a tempo of all zeros parses, so nothing here refuses a prescription with no movement`() {
        val tempo = Tempo.parse("0000")
        assertEquals(0.0, tempo.downS)
        assertEquals(0.0, tempo.bottomPauseS)
        assertEquals(0.0, tempo.upS)
        assertEquals(0.0, tempo.topPauseS)
        assertEquals("0000", tempo.notation())
    }

    /**
     * "X" is taken on either STROKE and on no pause (#258, plan schema 1.15).
     * Digit 1 joined digit 3 because on a vertical concentric-down lift -- a
     * pulldown, a pushdown -- digit 1 is the drive, and the drive is what X
     * describes. Which stroke X is honoured on is the lift's business, read by
     * role downstream; the notation only has to be able to say it. A pause
     * written "X" is still refused: a pause has no speed.
     */
    @Test
    fun `X is accepted on either stroke and on no pause`() {
        assertEquals(Tempo(null, 0.0, 1.0, 0.0), Tempo.parseOrNull("X010"))
        assertEquals(Tempo(null, 0.0, 1.0, 0.0), Tempo.parseOrNull("x-0-1-0"), "the dash form and lower case too")
        assertEquals(Tempo(null, 0.0, null, 0.0), Tempo.parseOrNull("X0X0"))
        assertEquals("X010", Tempo.parse("X010").notation(), "and it round-trips")
        assertNull(Tempo.parseOrNull("3X10"))
        assertNull(Tempo.parseOrNull("301X"))
        assertNull(Tempo.parse("30X0").upS)
    }

    /**
     * A fractional component parses and its own [Tempo.notation] does not
     * parse back: `notation()` writes "1.5" as three characters, and the
     * compact form reads one character per component, so the round trip is
     * six components and is refused.
     *
     * Characterization, not an endorsement: this is what the code does today.
     * It is why a tempo carrying a fraction is not a thing four
     * single-character wheels can display without rewriting it.
     */
    @Test
    fun `a fractional component parses but its own notation does not parse back`() {
        val tempo = Tempo.parse("3-0-1.5-0")
        assertEquals(1.5, tempo.upS)
        assertEquals("301.50", tempo.notation())
        assertNull(Tempo.parseOrNull(tempo.notation()))
    }

    /** A component of ten or more needs the dash form; the compact form is one digit each. */
    @Test
    fun `a component wider than one character needs the dash form`() {
        assertNull(Tempo.parseOrNull("10010"))
        assertEquals(10.0, Tempo.parse("10-0-1-0").downS)
    }

    /**
     * A null DOWN stroke is "X", written back as one (#258). Built directly
     * rather than parsed, so this pins the notation and the flag whatever
     * [Tempo.parse] accepts; the parse half is the test above.
     */
    @Test
    fun `a null down stroke is explosive and is written back as X`() {
        val tempo = Tempo(downS = null, bottomPauseS = 0.0, upS = 3.0, topPauseS = 0.0)
        assertTrue(tempo.isExplosiveDownStroke)
        assertEquals(false, tempo.isExplosiveUpStroke, "the up stroke is the number it was given")
        assertEquals("X030", tempo.notation())
        assertEquals("X0X0", Tempo(null, 0.0, null, 0.0).notation())
        assertEquals(false, tempo.hasZeroStroke, "an explosive stroke is not a zero one")
        assertEquals(false, Tempo.parse("3010").isExplosiveDownStroke)
    }
}
