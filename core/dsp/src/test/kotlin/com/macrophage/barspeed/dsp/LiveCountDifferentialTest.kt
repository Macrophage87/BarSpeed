package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.LiveCounter
import com.macrophage.barspeed.model.RepCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #301's differential: what a sensor-counted set counted once the
 * drive-impulse counter was the counter it armed -- `v0.1.54`'s live path.
 *
 * ## It was RED at the commit that added it, and it now names its detector
 *
 * Every count below was taken through `LiveRepCounters.forCounted`, the one
 * call `RecordViewModel` makes, and at the commit that added this file
 * `LiveCounterPolicy.counterFor(SENSOR)` still returned `LiveCounter.SEGMENTER`,
 * so every method failed -- the durable evidence that the pins guarded a
 * behaviour change. Since #305's implement round the counter is built BY NAME,
 * `LiveRepCounters.of(LiveCounter.DRIVE_IMPULSE)`, which is what `forCounted`
 * armed until then, so every figure is unchanged and stays a statement about
 * that detector whatever the policy arms. What the app counts is
 * `CycleLiveCountFieldTest`'s, not this file's.
 *
 * ## The instants are on the DSP's reconstructed clock
 *
 * Seconds from the first sample on the span-based rate the tracker integrates
 * against, which is what `LiveSetState.elapsedS` carries -- not the arrival
 * clock, and not wall time. A cue the app writes carries the ARRIVAL stamp
 * instead; `DeadliftLiveCountFieldTest`'s replay licence is what ties the two
 * together on these three captures.
 */
class LiveCountDifferentialTest {
    private val deadlifts = listOf(
        "field-deadlift-straight-5rep-s43-set04",
        "field-deadlift-straight-5rep-s43-set05",
        "field-deadlift-straight-5rep-s43-set06",
    )

    /**
     * The instants the drive-impulse counter speaks at, in seconds on the
     * reconstructed clock, built by name through the factory.
     */
    private fun callsAtS(fixture: String): List<Double> {
        val direction = CandidateCorpus.capture(fixture).direction
        val counter = LiveRepCounters.of(LiveCounter.DRIVE_IMPULSE, direction)
        val tracker = StreamingSetTracker.forLift(direction)
        val calls = mutableListOf<Double>()
        for (sample in LiveCountCandidates.load(fixture)) {
            val live = tracker.feed(sample)
            if (counter.feed(live, sample.timestampMs) is RepCall.Speak) calls += live.elapsedS
        }
        return calls
    }

    private fun rounded(calls: List<Double>): List<Double> = calls.map { (it * 100).toInt() / 100.0 }

    /**
     * Set 4, the dead-stop set at 61.2 kg: five calls for five performed reps,
     * where the shipped counter made three.
     *
     * The instants are pinned and not only the count. A counter that called five
     * times in the right total and the wrong places would be a different defect
     * wearing the right number, and on a set the lifter counts along with, WHEN
     * the number is said is most of what it is worth.
     */
    @Test
    fun `set 4 calls all five reps, each at the brake after its own pull`() {
        assertEquals(listOf(5.95, 10.31, 14.06, 18.14, 23.35), rounded(callsAtS(deadlifts[0])))
    }

    /**
     * Set 5, touch-and-go at 83.9 kg: five calls where the shipped counter made
     * ONE.
     *
     * This is the set no re-tuned ZUPT band can fix -- the design round measured
     * 15.5 s of continuous motion with no quiet window to anchor on at any band
     * -- so the whole case for a velocity-free counter is this row.
     */
    @Test
    fun `set 5 calls all five reps on the set that offered no anchor at all`() {
        assertEquals(listOf(6.37, 9.01, 11.94, 14.89, 18.07), rounded(callsAtS(deadlifts[1])))
    }

    /**
     * Set 6 at 102.1 kg: three calls, and the one the lifter did not earn is
     * gone.
     *
     * TWO REPS ARE STILL MISSED and they are pinned as missed rather than left
     * out of the file. Reps 4 and 5 are the two slowest pulls of the session, at
     * peak 0.581 and 0.336 m/s under 102 kg -- a grind produces little impulse
     * and this counter reads impulse. That is the cost of the design, measured
     * on the one session there is.
     *
     * The set-up pull at 4.37-5.11 s -- the lifter taking the slack out of the
     * bar, which the shipped counter called "Rep 1" -- draws no call, and
     * `DriveImpulseCandidateTest` measures that `DspConfig.drivePeakAccelMps2`
     * is the only term excluding it.
     */
    @Test
    fun `set 6 calls three of five and does not call the set-up pull`() {
        val calls = callsAtS(deadlifts[2])
        assertEquals(listOf(7.43, 9.75, 12.23), rounded(calls))
        assertTrue(
            calls.none { it in 4.37..5.11 },
            "a call inside set 6's set-up pull window: $calls",
        )
    }

    /**
     * The same three sets scored per rep against the drive windows the session
     * analysis resolved: 13 of 15, no phantom, against 5 of 15 with one phantom
     * from the shipped counter.
     *
     * See `CandidateCorpus.DEADLIFT_WINDOWS` for what that truth is and is not
     * -- batch-derived spans reviewed by a human, not a hand measurement of the
     * barbell. Issue #145's F1 capture, whose marks would be the lifter's own
     * taps, is still owed and is what would replace it.
     */
    @Test
    fun `the three sets together call thirteen of fifteen reps with no phantom`() {
        val perSet = deadlifts.map { CandidateCorpus.perRep(it, callsAtS(it)) }
        assertEquals(
            listOf(
                CandidateCorpus.PerRep(counted = 5, missed = 0, phantom = 0),
                CandidateCorpus.PerRep(counted = 5, missed = 0, phantom = 0),
                CandidateCorpus.PerRep(counted = 3, missed = 2, phantom = 0),
            ),
            perSet,
            "counted / missed / phantom, set 4 then 5 then 6",
        )
        assertEquals(13, perSet.sumOf { it.counted }, "deadlift reps called, of fifteen")
        assertEquals(0, perSet.sumOf { it.phantom }, "phantom calls")
    }

    /**
     * The six overhead-press captures, MEASURED AND NOT ENDORSED.
     *
     * Every one of them is a tempo'd set whose kind is DYNAMIC, and such a set
     * is counted by the metronome and arms no live counter. That is NOT true of
     * every tempo'd set -- an EXPLOSIVE lift carrying a tempo is
     * RepCounter.SENSOR (CountingPolicyTest > a tempo'd set is counted by the
     * metronome unless nothing plays the tempo), and in v0.1.54 it counted on
     * impulse. So these six over-counts were the nearest measured analogue to
     * a tempo'd power clean under that detector: 8, 8, 7, 10, 11 and 2 against
     * hand counts of 6, 7, 5, 8, 8 and 2. Five of the six are OVER, one of them
     * by three reps.
     *
     * So this row is not evidence for the DYNAMIC design. It is the boundary of
     * that design written down, and it is also the nearest measurement of what
     * an EXPLOSIVE-with-tempo set costs, where a later change that widens the
     * gate has to walk past it.
     */
    @Test
    fun `the overhead press captures over-count under this counter, which is why the gate is narrow`() {
        val presses = listOf(
            "field-ohp-3010-6rep-s37-set02",
            "field-ohp-prepinflated-s37-set03",
            "field-ohp-prepinflated-s37-set04",
            "field-ohp-3010-8rep-s38-set04",
            "field-ohp-3010-8rep-s38-set05",
            "field-seated-ohp-2rep",
        )
        assertEquals(
            listOf(6, 7, 5, 8, 8, 2),
            presses.map { CandidateCorpus.truth(it).reps },
            "what the lifter performed",
        )
        assertEquals(
            listOf(8, 8, 7, 10, 11, 2),
            presses.map { callsAtS(it).size },
            "what this counter would call -- measured, not endorsed",
        )
    }

    /**
     * Nothing but a sensor-counted set moves, and the old counter is unselected
     * rather than deleted.
     *
     * A set counted by the metronome, by the lifter, or by nobody arms no live
     * counter, so no tempo-guided capture in this corpus can be reached by the
     * change at all. `LiveRepCountersTest` measures the other half of that
     * claim: `LiveCounter.SEGMENTER` still makes exactly the same calls over
     * all 54 committed captures that a directly constructed `LiveRepCaller`
     * makes; `LiveRepCountersTest` carries the total.
     */
    @Test
    fun `no other counter arms a live detector, and the segmenter still exists`() {
        val direction = LiftDirection()
        listOf(RepCounter.MANUAL, RepCounter.METRONOME, RepCounter.NOBODY).forEach { counter ->
            assertNull(LiveRepCounters.forCounted(counter, direction), "$counter arms no live counter")
        }
        assertTrue(
            LiveRepCounters.of(LiveCounter.SEGMENTER, direction) is LiveRepCaller,
            "the segmenter is still buildable by name",
        )
    }
}
