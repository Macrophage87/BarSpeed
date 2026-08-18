package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HrCsv] can say "the strap reported no R-R interval" and "the strap reported
 * an R-R interval of zero", and they are not the same statement.
 *
 * The distinction is load-bearing rather than decorative. `encode` writes
 * `rrIntervalsMs.joinToString("|")`, so an empty list produces an EMPTY field
 * and a list holding 0.0 produces `0.0`; `decode` reads the field back through
 * `takeIf { it.isNotEmpty() }`. Both forms occur inside one real set:
 * session 28's first set opens with 26 rows carrying an empty field and then
 * runs 46 rows carrying `0.0`.
 *
 * That is what makes a zero here evidence rather than noise. A 0.0 in this
 * column is a value the strap put on the wire -- the R-R Present flag set and
 * two zero bytes behind it -- and not a field the app failed to fill in.
 */
class HrCsvTest {
    @Test
    fun `an absent R-R field and a reported zero survive a round trip as different values`() {
        val samples =
            listOf(
                HrSample(timestampMs = 1L, bpm = 50, rrIntervalsMs = emptyList()),
                HrSample(timestampMs = 2L, bpm = 50, rrIntervalsMs = listOf(0.0)),
            )
        val text = HrCsv.encode(samples)
        assertEquals("timestamp_ms,hr_bpm,rr_ms\n1,50,\n2,50,0.0\n", text)

        val decoded = HrCsv.decode(text)
        assertEquals(emptyList(), decoded[0].rrIntervalsMs, "an absent R-R field decoded as a value")
        assertEquals(listOf(0.0), decoded[1].rrIntervalsMs, "a reported zero decoded as absence")
    }

    @Test
    fun `the unworn capture carries both forms in one set`() {
        val set1 = HrFixtures.unworn(1)
        assertEquals(72, set1.size)
        assertEquals(26, set1.count { it.rrIntervalsMs.isEmpty() }, "rows with no R-R field")
        assertEquals(46, set1.count { it.rrIntervalsMs == listOf(0.0) }, "rows reporting a zero R-R")
        // The two forms do not interleave: the strap sent nothing, then zeros.
        assertTrue(set1.take(26).all { it.rrIntervalsMs.isEmpty() })
        assertTrue(set1.drop(26).all { it.rrIntervalsMs == listOf(0.0) })
    }
}
