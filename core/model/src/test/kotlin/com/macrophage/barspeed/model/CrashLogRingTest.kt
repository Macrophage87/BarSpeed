package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounded buffer of the app's own log lines that a crash report carries.
 * Issue #272.
 *
 * BOTH CAPS ARE PINNED, and separately, because either one alone bounds
 * nothing: fifty unbounded lines is unbounded, and three hundred characters
 * of unbounded line count is unbounded. #271 was an OutOfMemoryError caused
 * by a diagnostic path reading as much as it was handed, so a crash-time
 * buffer that could grow is that defect again with less heap to spare.
 *
 * Nothing here pins thread safety. Two threads cannot be scheduled against
 * each other and asserted on in this repository, so what the class claims is
 * the lock, not an absence of interleavings, and the tests claim no more.
 */
class CrashLogRingTest {
    @Test
    fun `an empty ring holds no lines`() {
        assertEquals(emptyList(), CrashLogRing().lines())
    }

    @Test
    fun `lines come back oldest first, the order the file writes them`() {
        val ring = CrashLogRing()
        ring.add("one")
        ring.add("two")
        ring.add("three")
        assertEquals(listOf("one", "two", "three"), ring.lines())
    }

    @Test
    fun `the oldest line goes when the ring is full`() {
        val ring = CrashLogRing(capacity = 3)
        for (line in listOf("one", "two", "three", "four")) ring.add(line)
        assertEquals(listOf("two", "three", "four"), ring.lines())
    }

    @Test
    fun `the ring never exceeds its capacity, however much is logged`() {
        val ring = CrashLogRing(capacity = 4)
        repeat(1_000) { ring.add("line $it") }
        assertEquals(4, ring.lines().size)
        assertEquals(listOf("line 996", "line 997", "line 998", "line 999"), ring.lines())
    }

    @Test
    fun `an over-long line is cut and says how many characters went`() {
        val ring = CrashLogRing(capacity = 2, maxLineChars = 10)
        ring.add("abcdefghijklmno")
        assertEquals(listOf("abcdefghij [+5 chars]"), ring.lines())
    }

    @Test
    fun `a line exactly at the cap is left alone`() {
        val ring = CrashLogRing(capacity = 2, maxLineChars = 10)
        ring.add("abcdefghij")
        assertEquals(listOf("abcdefghij"), ring.lines())
    }

    @Test
    fun `an embedded newline is flattened, so the file cannot disagree with its own line count`() {
        val ring = CrashLogRing(capacity = 2)
        ring.add("first half\nsecond half")
        assertEquals(listOf("first half second half"), ring.lines())
    }

    @Test
    fun `a carriage return is flattened too`() {
        val ring = CrashLogRing(capacity = 2)
        ring.add("first half\r\nsecond half")
        assertEquals(listOf("first half  second half"), ring.lines())
    }

    @Test
    fun `flattening happens before the cut, so the count of dropped characters is of the flattened line`() {
        val ring = CrashLogRing(capacity = 2, maxLineChars = 5)
        ring.add("ab\ncd\nefgh")
        assertEquals(listOf("ab cd [+5 chars]"), ring.lines())
    }

    @Test
    fun `an empty line is still a line`() {
        val ring = CrashLogRing(capacity = 2)
        ring.add("")
        assertEquals(listOf(""), ring.lines())
    }

    @Test
    fun `lines is a copy, so a later add cannot change what a crash report already collected`() {
        val ring = CrashLogRing(capacity = 2)
        ring.add("one")
        val snapshot = ring.lines()
        ring.add("two")
        assertEquals(listOf("one"), snapshot)
    }

    @Test
    fun `the shipped caps are fifty lines of three hundred characters`() {
        assertEquals(50, CrashLogRing.CAPACITY)
        assertEquals(300, CrashLogRing.MAX_LINE_CHARS)
        val ring = CrashLogRing()
        repeat(60) { ring.add("x".repeat(400)) }
        assertEquals(50, ring.lines().size)
        assertTrue(ring.lines().all { it.length <= 300 + " [+100 chars]".length }, "a line grew past its bound")
    }
}
