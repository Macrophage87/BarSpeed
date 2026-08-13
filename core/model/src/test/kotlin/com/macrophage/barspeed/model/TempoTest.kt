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
}
