package com.macrophage.barspeed.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The canonical CSV for rep marks, and its agreement with the journal.
 *
 * [RepMarkCsv] was extracted from [SetJournal], which already wrote this exact
 * format one line at a time; the extraction is what lets a mark recovered from
 * an interrupted capture and a mark read out of a stored set be the same
 * column instead of two formats that happen to look alike today.
 */
class RepMarkCsvTest {
    @Test
    fun `marks round trip through the canonical CSV`() {
        val marks = listOf(1_100L, 1_900L, 2_650L)
        assertEquals(marks, RepMarkCsv.decode(RepMarkCsv.encode(marks)))
    }

    /**
     * The header is written even for a set that produced no marks.
     *
     * A one-column file with no header is indistinguishable from a file whose
     * first mark happens to be missing, and this document is read by hand as
     * often as by code.
     */
    @Test
    fun `an empty mark list encodes to the header alone`() {
        assertEquals("timestamp_ms\n", RepMarkCsv.encode(emptyList()))
        assertEquals(emptyList(), RepMarkCsv.decode(RepMarkCsv.encode(emptyList())))
    }

    /** Whole milliseconds, never scientific notation or a thousands separator. */
    @Test
    fun `a mark is written as a plain integer`() {
        assertEquals("timestamp_ms\n1756382400000\n", RepMarkCsv.encode(listOf(1_756_382_400_000L)))
    }

    @Test
    fun `the header, a blank line and a comment are not marks`() {
        assertEquals(emptyList(), RepMarkCsv.decodeLine(RepMarkCsv.HEADER))
        assertEquals(emptyList(), RepMarkCsv.decodeLine("   "))
        assertEquals(emptyList(), RepMarkCsv.decodeLine("# recovered"))
        assertEquals(listOf(1_100L), RepMarkCsv.decodeLine(" 1100 "))
    }

    /**
     * A line that is not a mark THROWS rather than being skipped.
     *
     * That is what ends `SetJournalStore`'s partial read at the damage: a
     * process killed mid-append leaves a half-written final line, and the
     * reader keeps everything before the first refusal. A decoder that stepped
     * over the bad line would step over a truncated digit too and report a
     * mark at an instant nobody counted.
     */
    @Test
    fun `a line that is not a whole number is refused`() {
        assertFailsWith<NumberFormatException> { RepMarkCsv.decodeLine("11o0") }
        assertFailsWith<NumberFormatException> { RepMarkCsv.decodeLine("1100.5") }
    }

    /** One format, one header: the journal and the stored stream share it. */
    @Test
    fun `the journal writes the canonical header`() {
        assertEquals(RepMarkCsv.HEADER, SetJournal.REPS_HEADER)
        assertTrue(RepMarkCsv.encode(listOf(1_100L)).startsWith(SetJournal.REPS_HEADER + "\n"))
    }
}
