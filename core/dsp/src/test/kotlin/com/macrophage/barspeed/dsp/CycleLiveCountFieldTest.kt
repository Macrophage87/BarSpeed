package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.RepCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE DIFFERENTIAL for issue #305: what a sensor-counted set counts once the
 * full-cycle counter is the one it arms, on the eight deadlift sets the corpus
 * holds -- field-44 sets 1-5, then field-43 sets 4-6, [DeadliftTruth.SETS]'
 * order.
 *
 * ## This file is RED at the commit that adds it, deliberately
 *
 * Every count is taken through `LiveRepCounters.forCounted(RepCounter.SENSOR)`,
 * the one call `RecordViewModel` makes, fed by `StreamingSetTracker.forLift`,
 * the app's tracker -- so these are assertions about what the APP counts. At
 * the commit that adds this file `LiveCounterPolicy` still arms
 * `DriveImpulseCounter` on a sensor-counted set, so every method fails, and
 * that failing CI run is the evidence the pins guard a behaviour change.
 * `ClosingRuleCandidateTest` measures the same counter through the harness
 * and is green at this commit; the difference is the selection.
 *
 * ## What is scored against what
 *
 * The truth is the owner's: 5, 5, 5, 4 and 2 completed reps on field-44, set
 * 5's third pull a FAILED attempt ("It was about halfway up. Grip failed."),
 * and 5, 5 and 5 on field-43. A call is matched by the DRIVE it closes to a
 * batch-derived window, [DeadliftTruth.score]'s rule, because this counter
 * speaks at the floor -- inside the next rep's window, not its own.
 *
 * The instants are on the reconstructed clock, `LiveSetState.elapsedS`.
 */
class CycleLiveCountFieldTest {
    private data class Call(val atS: Double, val count: Int, val closed: RepClosed?)

    /** Every call the app makes on [fixture], through its own construction. */
    private fun appCalls(fixture: String): List<Call> {
        val direction = CandidateCorpus.capture(fixture).direction
        val counter = LiveRepCounters.forCounted(RepCounter.SENSOR, direction)
            ?: error("a sensor-counted set must arm a counter")
        val cycle = counter as? CycleRepCounter
        val tracker = StreamingSetTracker.forLift(direction)
        val calls = mutableListOf<Call>()
        for (sample in LiveCountCandidates.load(fixture)) {
            val live = tracker.feed(sample)
            val call = counter.feed(live, sample.timestampMs)
            if (call is RepCall.Speak) calls += Call(live.elapsedS, call.count, cycle?.lastClosed)
        }
        return calls
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    @Test
    fun `a sensor-counted set arms the full-cycle counter`() {
        val counter = LiveRepCounters.forCounted(RepCounter.SENSOR, LiftDirection())
        assertTrue(counter is CycleRepCounter, "a sensor-counted set arms ${counter?.javaClass?.simpleName}")
    }

    /**
     * Counted / missed / phantom, set by set: 35 of 36 completed reps, one
     * phantom, the failed pull not called.
     *
     * Where the app counted 5, 5, 5, 0, 0 and 5, 5, 3 -- 28 of 36 -- it now
     * counts every completed rep at 111 and 120 kg. What it still gets wrong is
     * on field-43 set 6: rep 4 is missed, and the set-up pull the drive-impulse
     * counter's 2.0 m/s^2 peak term excluded is called.
     */
    @Test
    fun `the app counts 35 of 36 completed reps on the eight deadlift sets`() {
        val scores = DeadliftTruth.SETS.map { set -> DeadliftTruth.score(set, appCalls(set).mapNotNull { it.closed }) }
        assertEquals(
            listOf("5/0/0", "5/0/0", "5/0/0", "4/0/0", "2/0/0", "5/0/0", "5/0/0", "4/1/1"),
            scores.map { it.toString() },
            "counted/missed/phantom, field-44 sets 1-5 then field-43 sets 4-6",
        )
        assertEquals(listOf(5, 5, 5, 4, 2, 5, 5, 5), DeadliftTruth.SETS.map { appCalls(it).size }, "numbers spoken")
        assertEquals(0, scores.sumOf { it.failedCalled }, "the failed attempt is called")
    }

    /**
     * WHEN each number is spoken, set by set -- as the bar lands, not at the
     * brake. The running total is spoken, one per call, from 1.
     *
     * Field-44 set 1 is the light soft-landing case: reps 1 and 3 land with no
     * event this rule reads and are closed by the next rep's drive, spoken at
     * that rep's brake (8.39 and 14.35 s), and that rep's own number follows
     * 1.1-1.2 s later (9.53 and 15.59 s). Field-44 set 5's third pull, from
     * 17.23 s, draws no number at all.
     */
    @Test
    fun `the app speaks each number as the bar lands`() {
        val expected = listOf(
            listOf(8.39, 9.53, 14.35, 15.59, 18.75),
            listOf(8.78, 9.94, 13.04, 16.86, 20.43),
            listOf(121.84, 124.14, 126.57, 129.22, 131.15),
            listOf(11.06, 15.98, 21.55, 50.87),
            listOf(8.71, 13.42),
            listOf(9.31, 12.52, 16.03, 20.42, 24.94),
            listOf(7.86, 10.50, 13.29, 16.30, 19.11),
            listOf(6.37, 9.45, 11.18, 13.72, 19.21),
        )
        DeadliftTruth.SETS.zip(expected).forEach { (set, instants) ->
            val calls = appCalls(set)
            assertEquals(instants, calls.map { r2(it.atS) }, "$set: call instants, s")
            assertEquals((1..calls.size).toList(), calls.map { it.count }, "$set: the running total spoken")
        }
    }

    /**
     * The failed pull is silent. Field-44 set 5 calls its two completed reps
     * and no call closes a drive that starts after 15 s, where the third pull
     * is -- nothing is spoken for an attempt that failed.
     */
    @Test
    fun `nothing is spoken for field-44 set 5's failed pull`() {
        val calls = appCalls(DeadliftTruth.SETS[4])
        assertEquals(2, calls.size, "calls on the 120 kg set")
        assertEquals(emptyList(), calls.filter { (it.closed?.driveStartS ?: 0.0) > 15.0 }, "a call for the failed pull")
    }

    /**
     * The six overhead-press captures under the app's counter, MEASURED AND
     * NOT ENDORSED. Every one is a tempo'd DYNAMIC set the metronome counts, so
     * no live counter runs on them in the app; they are the nearest measured
     * analogue to the one tempo'd shape that does reach it, an EXPLOSIVE lift
     * carrying a tempo. 7, 8, 6, 10, 9 and 2 against hand counts of 6, 7, 5,
     * 8, 8 and 2 -- five of six over. The drive-impulse counter read 8, 8, 7,
     * 10, 11 and 2 on the same captures (`LiveCountDifferentialTest`).
     */
    @Test
    fun `the overhead press captures over-count under the app's counter too`() {
        val presses = listOf(
            "field-ohp-3010-6rep-s37-set02",
            "field-ohp-prepinflated-s37-set03",
            "field-ohp-prepinflated-s37-set04",
            "field-ohp-3010-8rep-s38-set04",
            "field-ohp-3010-8rep-s38-set05",
            "field-seated-ohp-2rep",
        )
        assertEquals(listOf(6, 7, 5, 8, 8, 2), presses.map { CandidateCorpus.truth(it).reps }, "performed")
        assertEquals(listOf(7, 8, 6, 10, 9, 2), presses.map { appCalls(it).size }, "measured, not endorsed")
    }
}
