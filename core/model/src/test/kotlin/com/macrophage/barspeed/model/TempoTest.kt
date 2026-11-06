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
     * A fractional component is written back in the dash form, which parses.
     * It used to be written "301.50" -- six characters the compact form reads
     * as six components -- so the export's `tempoCompliance.prescribed` could
     * not be read back (core-model review 2026-11-06, F5).
     */
    @Test
    fun `a fractional component is written back in the dash form`() {
        val tempo = Tempo.parse("3-0-1.5-0")
        assertEquals(1.5, tempo.upS)
        assertEquals("3-0-1.5-0", tempo.notation())
        assertEquals(tempo, Tempo.parseOrNull(tempo.notation()))
    }

    /** A component of ten or more needs the dash form; the compact form is one digit each. */
    @Test
    fun `a component wider than one character needs the dash form`() {
        assertNull(Tempo.parseOrNull("10010"))
        val tempo = Tempo.parse("10-0-1-0")
        assertEquals(10.0, tempo.downS)
        assertEquals("10-0-1-0", tempo.notation(), "it used to be written 10.0010")
        assertEquals("X-0-1.5-0", Tempo(null, 0.0, 1.5, 0.0).notation(), "X keeps its letter in the dash form")
    }

    /** Whole single digits keep the compact form every plan and export already uses. */
    @Test
    fun `whole single digits keep the compact form`() {
        assertEquals("3010", Tempo.parse("3-0-1-0").notation())
        assertEquals("90X9", Tempo(9.0, 0.0, null, 9.0).notation())
    }

    /**
     * Property: every tempo over a grid of strokes and pauses -- single digits,
     * X, halves, tenths, and two-digit values -- parses back to itself from
     * its own notation (F5).
     */
    @Test
    fun `notation round-trips through parse over a grid`() {
        val values = listOf(0.0, 0.5, 1.0, 1.25, 2.0, 3.0, 4.5, 9.0, 9.5, 10.0, 12.0, 30.0, 0.1)
        val strokes = values + listOf<Double?>(null)
        var checked = 0
        for (down in strokes) {
            for (bottom in values) {
                for (up in strokes) {
                    for (top in values) {
                        val tempo = Tempo(down, bottom, up, top)
                        assertEquals(tempo, Tempo.parseOrNull(tempo.notation()), tempo.notation())
                        checked++
                    }
                }
            }
        }
        assertEquals(14 * 13 * 14 * 13, checked)
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
