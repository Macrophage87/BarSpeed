package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.PrepWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The prep window as it is written to disk and read back (#185).
 *
 * The format is two columns and one row, so most of what there is to pin is
 * what happens when a file is NOT that -- and every one of those cases has to
 * answer null rather than a partial pair, because a bracket around samples
 * nobody measured is worse than no bracket at all.
 */
class PrepWindowCsvTest {
    private val window = PrepWindow(startedAtMs = 1_756_500_000_000L, workStartedAtMs = 1_756_500_005_000L)

    @Test
    fun `a window survives the round trip exactly`() {
        assertEquals(window, PrepWindowCsv.decode(PrepWindowCsv.encode(window)))
    }

    /**
     * The instants are written as whole milliseconds and nothing else.
     *
     * Asserted as text, because what a person opening the archive reads is the
     * text. A float here would be a millisecond clock published in a form that
     * invites rounding on the way back in.
     */
    @Test
    fun `the encoded file is the header and one row of two integers`() {
        assertEquals(
            listOf("prep_started_ms,work_started_ms", "1756500000000,1756500005000"),
            PrepWindowCsv.encode(window).trim().lines(),
        )
    }

    @Test
    fun `the header alone states no window`() {
        assertNull(PrepWindowCsv.decode(PrepWindowCsv.HEADER + "\n"))
    }

    @Test
    fun `empty text states no window`() {
        assertNull(PrepWindowCsv.decode(""))
    }

    /**
     * A row cut short by a process that died mid-write is not half a window.
     *
     * `SetJournalStore` reads a partially written capture line by line for
     * exactly this reason; here the whole file is one row, so a truncated one
     * leaves nothing to keep.
     */
    @Test
    fun `a row with one field states no window`() {
        assertNull(PrepWindowCsv.decode("prep_started_ms,work_started_ms\n1756500000000\n"))
    }

    @Test
    fun `a row whose fields are not whole numbers states no window`() {
        assertNull(PrepWindowCsv.decode("prep_started_ms,work_started_ms\n1756500000000,not-a-number\n"))
        assertNull(PrepWindowCsv.decode("prep_started_ms,work_started_ms\n1756500000000.0,1756500005000\n"))
    }

    /**
     * An inverted pair is refused on the way OUT as well as on the way in.
     *
     * `PrepWindowPolicy` is the rule and every writer goes through it, but a
     * reader that trusts a writer it cannot see is how a file written by
     * another build becomes a negative prep in somebody's analysis.
     */
    @Test
    fun `a pair in the wrong order states no window`() {
        assertNull(PrepWindowCsv.decode("prep_started_ms,work_started_ms\n1756500005000,1756500000000\n"))
    }

    /**
     * A zero-length window reads back as a window, not as an absence.
     *
     * A prep of zero seconds is legal and is a real prescription; the empty
     * pair says there is no stationary period to look for, which the null every
     * other case here returns does not say.
     */
    @Test
    fun `a zero-length window reads back as a window`() {
        val empty = PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 1_000L)
        assertEquals(empty, PrepWindowCsv.decode(PrepWindowCsv.encode(empty)))
    }
}
