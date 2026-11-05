package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.SensorContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic cases for [HrTrust]. The real captures are exercised in :core:data. */
class HrTrustTest {
    private fun sample(bpm: Int, vararg rr: Double, at: Long = 0L) =
        HrSample(timestampMs = at, bpm = bpm, rrIntervalsMs = rr.toList())

    // ---- what a sample has to be to count ----------------------------------

    @Test
    fun `a bpm of zero is not a measurement`() {
        assertFalse(HrTrust.isTrusted(sample(0)))
        assertFalse(HrTrust.isTrusted(sample(-1)))
        assertTrue(HrTrust.isTrusted(sample(1)))
    }

    @Test
    fun `a reported R-R interval of zero is not a measurement`() {
        assertFalse(HrTrust.isTrusted(sample(50, 0.0)))
        assertFalse(HrTrust.isTrusted(sample(50, 800.0, 0.0)), "one impossible interval is enough")
        assertTrue(HrTrust.isTrusted(sample(50, 800.0, 820.0)))
    }

    /**
     * The vacuous branch, pinned as the gap it is rather than left to be
     * discovered. `all` over an empty list is true, so a sample with no R-R
     * evidence at all is believed on its bpm alone. Issue #82.
     */
    @Test
    fun `a sample carrying no R-R interval is trusted on its bpm alone`() {
        assertTrue(HrTrust.isTrusted(sample(46)))
    }

    /**
     * DIFFERENTIAL, #82. A sample with no R-R interval was trusted on its bpm
     * alone because `all` over an empty list is true, and the strap's own
     * contact report was the only other evidence the notification carried.
     * Where the strap SAYS it has no skin contact and the R-R field is
     * silent, nothing supports the bpm.
     */
    @Test
    fun `no R-R and a reported loss of contact is not a measurement`() {
        assertFalse(HrTrust.isTrusted(sample(72).copy(contact = SensorContact.NOT_DETECTED)))
    }

    /** The stream-level consequence: such a stream publishes no heart rate at all. */
    @Test
    fun `a stream reporting no contact and no R-R publishes no heart rate`() {
        val offSkin = List(5) { sample(72, at = it * 1_000L).copy(contact = SensorContact.NOT_DETECTED) }
        val summary = HrTrust.summarize(offSkin)
        assertNull(summary.avgBpm)
        assertNull(summary.maxBpm)
        assertEquals(0, summary.trustedSamples)
        assertEquals(5, summary.totalSamples)
    }

    /**
     * Contact is consulted only where the R-R field is silent (#82), so a
     * sample whose own reported intervals are possible is not overruled by
     * the contact bit. That is the scope the change was written to, kept
     * narrow because no capture held here has ever carried the bits.
     */
    @Test
    fun `a sample with possible R-R intervals is trusted whatever the contact bit says`() {
        assertTrue(HrTrust.isTrusted(sample(72, 830.0).copy(contact = SensorContact.NOT_DETECTED)))
        assertTrue(HrTrust.isTrusted(sample(72, 830.0).copy(contact = SensorContact.DETECTED)))
    }

    /**
     * A strap that says nothing about contact keeps the old behaviour, and so
     * does every capture written before the column existed: silencing on
     * missing evidence would withhold heart rate from a compliant strap on
     * every set, which #82's own ruling refuses.
     */
    @Test
    fun `no R-R and no contact report is still trusted on its bpm`() {
        assertTrue(HrTrust.isTrusted(sample(72).copy(contact = SensorContact.NOT_RECORDED)))
        assertTrue(HrTrust.isTrusted(sample(72).copy(contact = SensorContact.DETECTED)))
    }

    /**
     * A resting heart passes both clauses trivially. This is the property that
     * distinguishes an impossibility from the two statistics withdrawn before
     * it: there is no rate term, so nothing here separates slow hearts from
     * fast ones.
     */
    @Test
    fun `a resting athlete is trusted exactly as a working one is`() {
        assertTrue(HrTrust.isTrusted(sample(45, 1333.0)))
        assertTrue(HrTrust.isTrusted(sample(50, 1200.0)))
        assertTrue(HrTrust.isTrusted(sample(180, 333.0)))
    }

    // ---- what a stream summarises to ---------------------------------------

    @Test
    fun `a stream with nothing trustworthy in it summarises to nothing`() {
        val summary = HrTrust.summarize(listOf(sample(0), sample(50, 0.0), sample(49, 0.0)))
        assertNull(summary.endOfSetBpm)
        assertNull(summary.avgBpm)
        assertNull(summary.maxBpm)
        assertNull(summary.minBpm)
        assertEquals(0, summary.trustedSamples)
        assertEquals(3, summary.totalSamples)
    }

    @Test
    fun `an empty stream summarises to nothing`() {
        assertEquals(HrStreamSummary.NOTHING, HrTrust.summarize(emptyList()))
    }

    @Test
    fun `an untrusted sample does not raise the maximum`() {
        // The 190 is reported alongside an impossible interval; the highest
        // heart rate this stream actually supports is 120.
        val summary = HrTrust.summarize(listOf(sample(100, 600.0), sample(190, 0.0), sample(120, 500.0)))
        assertEquals(120, summary.maxBpm)
    }

    /**
     * The minimum's own version of the maximum test above -- same list shape,
     * opposite extremum, so a fix that only filters the maximum's population
     * and not the minimum's cannot pass both.
     */
    @Test
    fun `an untrusted sample does not lower the minimum`() {
        // The 40 is reported alongside an impossible interval; the lowest
        // heart rate this stream actually supports is 100.
        val summary = HrTrust.summarize(listOf(sample(120, 600.0), sample(40, 0.0), sample(100, 500.0)))
        assertEquals(100, summary.minBpm)
    }

    @Test
    fun `an untrusted sample is left out of the mean`() {
        // (100 + 120) / 2, not (100 + 0 + 120) / 3 = 73.
        val summary = HrTrust.summarize(listOf(sample(100, 600.0), sample(0), sample(120, 500.0)))
        assertEquals(110, summary.avgBpm)
        assertEquals(2, summary.trustedSamples)
        assertEquals(3, summary.totalSamples)
    }

    /**
     * The set's mean is TRUNCATED to a whole beat, never rounded (#296).
     * 120 and 121 average to 120.5, and 120, 121 and 121 to 120.67; both
     * store 120. Half-up rounding stores 121 for both; half-even passes the
     * first and fails the second, which every round-to-nearest rule fails.
     * The same pair of cases `SessionHeartRateTest` pins for the session
     * figure, because the session truncates these values again.
     */
    @Test
    fun `the set's mean is truncated, never rounded`() {
        assertEquals(120, HrTrust.summarize(listOf(sample(120, 500.0), sample(121, 496.0))).avgBpm)
        assertEquals(
            120,
            HrTrust.summarize(listOf(sample(120, 500.0), sample(121, 496.0), sample(121, 496.0))).avgBpm,
        )
    }

    @Test
    fun `the end-of-set reading is the last sample when that sample is trusted`() {
        val summary = HrTrust.summarize(listOf(sample(100, 600.0), sample(0), sample(118, 500.0)))
        assertEquals(118, summary.endOfSetBpm)
    }

    /**
     * Not backfilled from the last trusted sample. The key names a time, and an
     * older reading published under it is a claim the stream does not support.
     */
    @Test
    fun `the end-of-set reading is omitted when the last sample is untrusted`() {
        val summary = HrTrust.summarize(listOf(sample(100, 600.0), sample(118, 500.0), sample(46, 0.0)))
        assertNull(summary.endOfSetBpm)
        assertEquals(109, summary.avgBpm, "the rest of the summary survives")
        assertEquals(118, summary.maxBpm)
        assertEquals(100, summary.minBpm)
    }

    @Test
    fun `a wholly trusted stream is summarised exactly as it always was`() {
        val summary = HrTrust.summarize(listOf(sample(120, 500.0), sample(150, 400.0), sample(141)))
        assertEquals(141, summary.endOfSetBpm)
        assertEquals(137, summary.avgBpm)
        assertEquals(150, summary.maxBpm)
        assertEquals(120, summary.minBpm)
        assertEquals(3, summary.trustedSamples)
    }
}
