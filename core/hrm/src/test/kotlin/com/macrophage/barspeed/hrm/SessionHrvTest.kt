package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [SessionHrv]: a session's HRV from its stored heart-rate windows (#62).
 *
 * GREEN WHEN WRITTEN, and deliberately so: [SessionHrv] is a new symbol that
 * nothing reads at the commit that adds it. The differential is the
 * exporter's, in `:core:data`.
 *
 * Every stream here is synthetic. What a real strap's windows look like
 * against the close's figure was measured on field captures, which are not
 * committed; see [SessionHrv]'s KDoc.
 */
class SessionHrvTest {
    private fun sample(atMs: Long, rr: Double) = HrSample(timestampMs = atMs, bpm = 75, rrIntervalsMs = listOf(rr))

    /** One notification per beat, each arriving one interval after the last. */
    private fun stream(startMs: Long, vararg rr: Double): List<HrSample> {
        var t = startMs
        return rr.map { interval ->
            t += interval.toLong()
            sample(t, interval)
        }
    }

    /**
     * The property the rule exists for: windows cut from one notification
     * stream the way the app stores them give the close's figure over that
     * stream. The close's figure is [Hrv.rmssdMs] over [RrIngest.newBeats]
     * of the stream as it arrived, which is what `SessionCloser` computes
     * over `RecordViewModel`'s accumulated beats.
     *
     * The stream carries two strap re-sends, and the second rest window
     * opens with a copy of the last five notifications of the set before it,
     * as #178 stores it.
     */
    @Test
    fun `windows cut from one stream give the close's figure over that stream`() {
        val rr =
            doubleArrayOf(
                800.0, 812.0, 795.0, 820.0, 805.0, 790.0, 815.0, 800.0, 825.0, 810.0,
                798.0, 798.0, 830.0, 815.0, 802.0, 840.0, 822.0, 808.0, 835.0, 818.0,
                806.0, 845.0, 828.0, 812.0, 812.0, 850.0, 833.0, 816.0, 855.0, 838.0,
                820.0, 860.0, 842.0, 824.0, 865.0, 846.0, 828.0, 870.0, 850.0, 832.0,
            )
        val arrived = stream(0L, *rr)
        val restOne = arrived.subList(0, 10)
        val setOne = arrived.subList(10, 25)
        val restTwo = arrived.subList(20, 35)
        val setTwo = arrived.subList(35, 40)

        val close = Hrv.rmssdMs(RrIngest.newBeats(arrived))

        assertNotNull(close, "the reference stream is too short to have a figure")
        assertEquals(close, SessionHrv.rmssdMs(listOf(restOne, setOne, restTwo, setTwo)))
    }

    /**
     * A rest window opens with a copy of the tail of the set before it
     * (#178). Joined as stored, the copy is counted twice and the join
     * splices a jump back in time: 840 to 820 here. Counted once, the stream
     * is 800 to 900 in steps of 10, ten differences of 10.
     *
     * Joined without dropping the copy it is thirteen differences, twelve of
     * 10 and one of 20, and the figure is about 11.09.
     */
    @Test
    fun `a notification already stored in an earlier window is counted once`() {
        val set = stream(0L, 800.0, 810.0, 820.0, 830.0, 840.0)
        val copiedTail = set.subList(2, 5)
        val rest = copiedTail + stream(set.last().timestampMs, 850.0, 860.0, 870.0, 880.0, 890.0, 900.0)

        assertEquals(10.0, SessionHrv.rmssdMs(listOf(set, rest))!!, 1e-9)
    }

    /**
     * A strap re-send whose two copies fall in two different windows is one
     * beat: the ingest runs over the joined stream, not afresh per window.
     * Counted once, the beats alternate 800 and 810, eleven differences of
     * 10. Restarting the ingest at the window boundary keeps the re-sent 810
     * and adds a difference of 0, which takes the figure to about 9.57.
     */
    @Test
    fun `a re-send straddling two windows is one beat`() {
        val first = stream(0L, 800.0, 810.0, 800.0, 810.0, 800.0, 810.0)
        val resend = sample(first.last().timestampMs + 500L, 810.0)
        val second = listOf(resend) + stream(resend.timestampMs, 800.0, 810.0, 800.0, 810.0, 800.0, 810.0)

        assertEquals(10.0, SessionHrv.rmssdMs(listOf(first, second))!!, 1e-9)
    }

    /** Ten beats, nine differences: below the close's minimum, so no figure -- never 0. */
    @Test
    fun `fewer than ten differences publish no figure`() {
        val window = stream(0L, 800.0, 810.0, 820.0, 830.0, 840.0, 850.0, 860.0, 870.0, 880.0, 890.0)

        assertNull(SessionHrv.rmssdMs(listOf(window)))
    }

    @Test
    fun `a session with no stored window has no figure`() {
        assertNull(SessionHrv.rmssdMs(emptyList()))
        assertNull(SessionHrv.rmssdMs(listOf(emptyList(), emptyList())))
    }
}
