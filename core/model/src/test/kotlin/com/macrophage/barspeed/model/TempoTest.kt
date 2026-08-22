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
     * "X" is taken on the up stroke and nowhere else. A down stroke, a pause
     * or a top pause written "X" is refused, so the explosive marker belongs
     * to one digit rather than to the notation.
     */
    @Test
    fun `X is accepted on the up stroke alone`() {
        assertNull(Tempo.parseOrNull("X010"))
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
}
