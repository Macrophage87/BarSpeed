package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A session's heart-rate summary, and which answer a reader publishes (#62).
 *
 * GREEN WHEN WRITTEN, and deliberately so: [SessionHeartRate] is a new symbol
 * whose [SessionHeartRate.aggregate] is `endSession`'s existing arithmetic,
 * moved rather than changed, and whose [SessionHeartRate.of] nothing reads
 * yet. The differential is the exporter's, in `:core:data`, and it cannot
 * compile until this rule exists.
 *
 * The aggregate cases are characterization pins on what `endSession` has
 * always written: a reader deriving an unclosed session's figure by any
 * other arithmetic would publish a number no closed session could have.
 */
class SessionHeartRateTest {
    // ---- the aggregate: endSession's arithmetic ------------------------------

    @Test
    fun `the session average is the mean of the per-set averages`() {
        assertEquals(130, SessionHeartRate.aggregate(listOf(120, 140), listOf(150, 165)).avgBpm)
    }

    /**
     * 120 and 121 average to 120.5 and three sets of 120, 121 and 121 to
     * 120.67; `endSession` writes 120 for both. A rounding rule writes 121
     * for both, which is the one-beat disagreement between a derived and a
     * stored figure the rule exists to rule out.
     */
    @Test
    fun `the mean is truncated as endSession truncates it, never rounded`() {
        assertEquals(120, SessionHeartRate.aggregate(listOf(120, 121), emptyList()).avgBpm)
        assertEquals(120, SessionHeartRate.aggregate(listOf(120, 121, 121), emptyList()).avgBpm)
    }

    @Test
    fun `the session maximum is the highest per-set maximum`() {
        assertEquals(165, SessionHeartRate.aggregate(listOf(120, 140), listOf(150, 165, 158)).maxBpm)
    }

    /**
     * A set with no figure is absent from the mean, not a zero in it. Counted
     * as 0, the three sets below would average 86 rather than 130 -- a
     * believable resting figure for a session whose worn sets ran at 130.
     */
    @Test
    fun `a set with no figure is skipped rather than counted as zero`() {
        val hr = SessionHeartRate.aggregate(listOf(120, null, 140), listOf(150, null, 165))

        assertEquals(SessionHeartRate(avgBpm = 130, maxBpm = 165), hr)
    }

    @Test
    fun `no figure on any set is no summary, never a zero`() {
        assertEquals(SessionHeartRate(null, null), SessionHeartRate.aggregate(listOf(null, null), listOf(null)))
        assertEquals(SessionHeartRate(null, null), SessionHeartRate.aggregate(emptyList(), emptyList()))
    }

    // ---- the read: stored where the close wrote, derived where it did not ---

    /**
     * The field case #62 was filed for: the lifter left without finishing, so
     * the session row has no end time and no summary, while every set row
     * carries its own figures.
     */
    @Test
    fun `an unclosed session's summary is derived from its set rows`() {
        val hr =
            SessionHeartRate.of(
                closed = false,
                storedAvgBpm = null,
                storedMaxBpm = null,
                setAvgBpm = listOf(120, 140),
                setMaxBpm = listOf(150, 165),
            )

        assertEquals(SessionHeartRate(avgBpm = 130, maxBpm = 165), hr)
    }

    /**
     * An unclosed row's columns are not an answer, whatever they hold: the
     * only writer of them is the close, and it has not run. Nothing today
     * writes a figure onto an unclosed row, so this pins the key -- the end
     * time -- rather than a reachable state.
     */
    @Test
    fun `an unclosed session reads its set rows, not its columns`() {
        val hr =
            SessionHeartRate.of(
                closed = false,
                storedAvgBpm = 99,
                storedMaxBpm = 101,
                setAvgBpm = listOf(120, 140),
                setMaxBpm = listOf(150, 165),
            )

        assertEquals(SessionHeartRate(avgBpm = 130, maxBpm = 165), hr)
    }

    /**
     * A closed session publishes what the close stored, even where its set
     * rows would now aggregate to something else: re-deriving on every read
     * is how a correct summary is replaced by one drawn from a list that has
     * since changed, the same reason `endSession` refuses a second close.
     */
    @Test
    fun `a closed session keeps the pair it stored, whatever its rows now say`() {
        val hr =
            SessionHeartRate.of(
                closed = true,
                storedAvgBpm = 130,
                storedMaxBpm = 165,
                setAvgBpm = listOf(100, 180),
                setMaxBpm = listOf(110, 195),
            )

        assertEquals(SessionHeartRate(avgBpm = 130, maxBpm = 165), hr)
    }

    /**
     * A closed session that stored no summary keeps none: that null is the
     * close's own answer -- no set carried a figure when it read them -- and
     * a reader keyed on the null column rather than on the end time would
     * overwrite it.
     */
    @Test
    fun `a closed session that stored no summary is not given one at read time`() {
        val hr =
            SessionHeartRate.of(
                closed = true,
                storedAvgBpm = null,
                storedMaxBpm = null,
                setAvgBpm = listOf(120),
                setMaxBpm = listOf(150),
            )

        assertEquals(SessionHeartRate(null, null), hr)
    }
}
