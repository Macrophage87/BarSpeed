package com.macrophage.barspeed.data

import com.macrophage.barspeed.hrm.HrTrust
import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HrTrust] run against both real captures, before it is wired to anything.
 *
 * The worn control is the point of this file. Two earlier candidate rules for
 * this defect looked correct against the unworn capture and were withdrawn only
 * once they were run against session 26, where each fired on 16 of 17 real
 * sets. The discharge is executable here rather than quoted in a commit message
 * so that it is re-run on every push instead of being true once.
 *
 * The bound on what this can show is asserted below rather than left to a
 * reader: the worn control has NO sample under 70 bpm, so it is silent about a
 * resting athlete. It can demonstrate that a rule costs nothing on a working
 * lifter. It cannot demonstrate that a rule is safe at rest, and one earlier
 * rule was accepted on exactly that confusion.
 */
class FieldHrTrustDischargeTest {
    /** [SessionRepository.recordSet]'s summary as it stands before this change. */
    private fun ungated(samples: List<HrSample>): Triple<Int?, Int?, Int?> {
        val bpm = samples.map { it.bpm }
        return Triple(
            samples.lastOrNull()?.bpm,
            if (bpm.isEmpty()) null else bpm.average().toInt(),
            bpm.maxOrNull(),
        )
    }

    // ---- the worn control: what the rule costs a real session --------------

    @Test
    fun `not one sample of the worn control is rejected`() {
        val perSet = HrFixtures.allWorn().map { set -> set.count { !HrTrust.isTrusted(it) } }
        assertEquals(List(HrFixtures.WORN_SETS) { 0 }, perSet, "a worn sample was called untrustworthy")

        val total = HrFixtures.allWorn().sumOf { it.size }
        assertEquals(1930, total, "the worn control changed size")
    }

    @Test
    fun `no worn set loses its summary, and none loses its end-of-set reading`() {
        HrFixtures.allWorn().forEachIndexed { index, set ->
            val summary = HrTrust.summarize(set)
            assertNotNull(summary.avgBpm, "worn set ${index + 1} lost its mean")
            assertNotNull(summary.maxBpm, "worn set ${index + 1} lost its maximum")
            assertNotNull(summary.minBpm, "worn set ${index + 1} lost its minimum")
            assertNotNull(summary.endOfSetBpm, "worn set ${index + 1} lost its end-of-set reading")
            assertEquals(set.size, summary.trustedSamples, "worn set ${index + 1} lost samples")
        }
    }

    @Test
    fun `every worn figure is identical to the one published today`() {
        HrFixtures.allWorn().forEachIndexed { index, set ->
            val summary = HrTrust.summarize(set)
            assertEquals(
                ungated(set),
                Triple(summary.endOfSetBpm, summary.avgBpm, summary.maxBpm),
                "worn set ${index + 1} would be published differently",
            )
        }
    }

    /**
     * The blind spot, pinned so that it is re-read rather than remembered.
     *
     * What actually trips this is [HrFixtures.WORN_SETS], not the resources
     * directory. `allWorn()` iterates `1..WORN_SETS`, so dropping an
     * eighteenth capture into `src/test/resources` and stopping there changes
     * nothing here: the file is never opened, this stays green, and the new
     * capture is a silent no-op. Adding a worn capture means adding the file
     * AND incrementing WORN_SETS, and only the second half is what makes this
     * assertion speak.
     *
     * Written the other way round first -- "if a capture containing
     * resting-rate heart rate is ever added to these resources, this assertion
     * fails" -- which was a statement about what would happen rather than
     * about what the code checks. It was false: a resting fixture was dropped
     * into the resources directory under two different names and this test
     * stayed green both times. In the one file whose declared job is honesty
     * about its own limits, that is the worst place to have got it wrong.
     */
    @Test
    fun `the worn control contains no resting heart rate at all`() {
        val bpm = HrFixtures.allWorn().flatten().map { it.bpm }
        assertEquals(78, bpm.min(), "the worn control's slowest sample")
        assertEquals(134, bpm.max())
        assertEquals(0, bpm.count { it < 70 }, "the worn control now has resting-rate samples")
    }

    // ---- the unworn capture: what the rule actually reaches ----------------

    @Test
    fun `the rule reaches all of the first two unworn sets and half of the third`() {
        val trusted = HrFixtures.allUnworn().map { set -> set.count { HrTrust.isTrusted(it) } }
        assertEquals(listOf(0, 0, 47), trusted)
        assertEquals(listOf(72, 91, 91), HrFixtures.allUnworn().map { it.size })
    }

    /**
     * WHAT AN UNWORN STRAP PUBLISHES TODAY, all three figures, pinned before
     * anything moves so the differential shows exactly what changes.
     *
     * Three, not two. #79 left avgBpm and maxBpm; #90 part B added minBpm over
     * the same trusted list, so a set recorded with the strap on a table now
     * publishes THREE believable resting figures where it published two. That
     * was my change and it enlarged this issue rather than being found in it.
     */
    @Test
    fun `the unworn set publishes three figures today, and they agree with each other`() {
        val summary = HrTrust.summarize(HrFixtures.unworn(3))
        assertEquals(46, summary.avgBpm)
        assertEquals(46, summary.maxBpm)
        assertEquals(46, summary.minBpm)
        assertEquals(47, summary.trustedSamples, "the samples the sample-level rule cannot reach")
        assertEquals(91, summary.totalSamples)
    }

    /**
     * And the other two unworn sets publish nothing already, so the whole of
     * this issue is one set. The firing evidence for any rule here is n=1.
     */
    @Test
    fun `the other two unworn sets already publish nothing`() {
        for (set in listOf(1, 2)) {
            val summary = HrTrust.summarize(HrFixtures.unworn(set))
            assertNull(summary.avgBpm, "unworn set $set")
            assertNull(summary.maxBpm, "unworn set $set")
            assertNull(summary.minBpm, "unworn set $set")
        }
    }

    /**
     * Set 3 is not closed by this change and the number that survives is
     * recorded here so the remainder is not mistaken for a fix. 47 of its 91
     * samples carry plausible-band R-R, so a mean, a maximum AND a minimum
     * still come out -- all three from the same 47 identical readings, so a
     * set-level minBpm inherits this exact, already-published gap rather than
     * creating a new one: this set already publishes avgBpm 46 and maxBpm 46
     * today. Issue #83.
     */
    @Test
    fun `the third unworn set still summarises to a plausible resting heart rate`() {
        val summary = HrTrust.summarize(HrFixtures.unworn(3))
        assertEquals(46, summary.avgBpm)
        assertEquals(46, summary.maxBpm)
        assertEquals(46, summary.minBpm, "same 47 samples as avg/max, so the same fabricated-looking floor")
        assertTrue(summary.endOfSetBpm == null, "its final sample is untrusted")
        assertEquals(47, summary.trustedSamples)
        assertEquals(91, summary.totalSamples)
    }
}
