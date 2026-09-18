package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first deadlift session, and what the shipped counter did on it. Issue
 * #301.
 *
 * ## Provenance
 *
 * Session 43, `BarSpeed-v0.1.53-2026-09-17_065843-raw.zip`, app **0.1.53**,
 * sensor WitMotion WT901BLECL, three deadlift sets. Every field below is
 * copied from that archive's own `meta.json`; nothing here is inferred from
 * the streams.
 *
 * | fixture | set | load kg | reps | plannedReps | rpe | repsSource | liveReps |
 * |---|---|---|---|---|---|---|---|
 * | `-set04` | 4 | 61.234969951354785 | 5 | 5 | 1 | `corrected` | 3 |
 * | `-set05` | 5 | 83.91458845185656 | 5 | 5 | 1 | `corrected` | 1 |
 * | `-set06` | 6 | 102.05828325225797 | 5 | 5 | 4 | `corrected` | 2 |
 *
 * Common to all three: `startsWith: "concentric"`, `concentric: "up"` with
 * `concentricSource: "declared"`, `plane: "vertical"`, `sensorOnStack` and
 * `sensorInverted` false, `travelRatio` 1.0, `kind: "dynamic"`,
 * `bodyweight` false, **no `tempoPrescribed` key at all**, `sensorsArmed` 2
 * with roles `a` and `b`, `analysedRole: "a"`. **No prep row and no
 * `-prep.csv`**: a no-tempo dynamic set takes `PrepCase.NONE`, so
 * `workStartedAtMs` is null and the head of the stream is unbounded. Committed
 * per set: role a as the capture, role b as an `-imu-b` partner, and the cue
 * track. No `-reps.csv` exists for any of them -- the app wrote none.
 *
 * ## The hand count is the owner's words, not a file
 *
 * **5, 5, 5.** The owner reported it in chat, not on paper, and of set 6 said
 * it "could have been 6". The archive cannot settle 5 against 6: it holds
 * exactly five `Rep N` rows and `reps` corrected to 5. So the hand count in
 * this file is 5 per set, with set 6's carrying that stated uncertainty, and a
 * candidate design scoring 6 on set 6 is NOT thereby wrong.
 *
 * The owner, with the zip: *"It's still not counting everything. It stalls and
 * keeps climbing up. Maybe just focus on the concentric here."*
 *
 * ## What the cue track is here, and why no coverage file scores it
 *
 * On a sensor-counted set the rows are the SENSOR'S own calls and the LIFTER'S
 * catch-up taps, spoken through one voice path. It is not a metronome track.
 * Opening a rep window on one of those rows would score the counter against
 * its own output, which is why all three captures sit in `notRepCorpus` in
 * `BatchCueCoverageTest` and `CuedRepCoverageTest`. What the track IS good for
 * is the replay licence pinned below: the first rows of each track are the
 * live calls, to the millisecond.
 *
 * ## These are CHARACTERIZATION pins
 *
 * Nothing is fixed here. Every number below is what `v0.1.53`'s shipped
 * classes do on this capture, and a design that recovers the missed reps is
 * EXPECTED to red this file. Re-baseline it deliberately, in the commit that
 * changes the count, with the new numbers measured and the hand count
 * unchanged.
 */
class DeadliftLiveCountFieldTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** The geometry all three sets declared; nothing about it is inferred. */
    private val deadlift = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private data class Fixture(val name: String, val loadKg: Double, val handCount: Int, val liveReps: Int)

    private val sets = listOf(
        Fixture("field-deadlift-straight-5rep-s43-set04", 61.234969951354785, handCount = 5, liveReps = 3),
        Fixture("field-deadlift-straight-5rep-s43-set05", 83.91458845185656, handCount = 5, liveReps = 1),
        Fixture("field-deadlift-straight-5rep-s43-set06", 102.05828325225797, handCount = 5, liveReps = 2),
    )

    /** Every [RepCall.Speak] the shipped live path makes over a whole stream. */
    private fun calls(fixture: String): List<RepCall.Speak> {
        val tracker = StreamingSetTracker.forLift(deadlift)
        val caller = LiveRepCaller(deadlift)
        val spoken = mutableListOf<RepCall.Speak>()
        for (sample in load(fixture)) {
            val live = tracker.feed(sample)
            val call = caller.feed(live, sample.timestampMs)
            if (call is RepCall.Speak) spoken += call
        }
        return spoken
    }

    private fun finalState(fixture: String): LiveSetState {
        val tracker = StreamingSetTracker.forLift(deadlift)
        var state = LiveSetState()
        for (sample in load(fixture)) state = tracker.feed(sample)
        return state
    }

    /**
     * The replay licence. Without it every other figure in this file is a
     * model of the live path rather than a reproduction of it.
     *
     * The app wrote one cue row per spoken number, so the first `liveReps`
     * rows of each track are the calls the lifter heard. Feeding role a
     * reproduces them to the millisecond; the tolerance is 1 ms because the
     * cue writer stamps from its own clock read, one statement later than the
     * sample.
     */
    @Test
    fun `replaying role a reproduces the exported liveReps and every call instant to the millisecond`() {
        sets.forEach { set ->
            val spoken = calls(set.name)
            assertEquals(set.liveReps, spoken.size, "${set.name}: calls against the exported liveReps")
            val cues = CueTrack.read(set.name)
            assertEquals(5, cues.size, "${set.name}: rows on the cue track")
            spoken.forEachIndexed { i, call ->
                assertEquals("Rep ${i + 1}", cues[i].label, "${set.name}: cue row ${i + 1}")
                assertTrue(
                    abs(call.atTimestampMs - cues[i].timestampMs) <= 1L,
                    "${set.name}: call ${i + 1} at ${call.atTimestampMs} against cue ${cues[i].timestampMs}",
                )
                assertEquals(i + 1, call.count, "${set.name}: the running total spoken")
            }
        }
    }

    /**
     * SIX calls for fifteen performed reps, five of them on a rep, and the
     * count the tracker itself held agrees with what the caller spoke on all
     * three sets.
     *
     * **Issue #301's headline arithmetic is wrong and is corrected here.** It
     * says *"5 calls for 15 real reps, and one of the 5 is a phantom -- 4 real
     * reps called of 15."* 3 + 1 + 2 is 6, and with set 6's set-up pull
     * removed that is FIVE real reps called of fifteen, not four. The gate
     * table the issue is built on is right; its summary line added up its own
     * table wrongly.
     *
     * `countTrusted` is false on every stream of every set: the integrator
     * carried a run past `maxRunDisplacementM` on all six. It is the one fact
     * the lifter needed mid-set and nothing in `:app` reads it.
     */
    @Test
    fun `the sensor called six times for fifteen reps, and stood behind none of them`() {
        assertEquals(15, sets.sumOf { it.handCount }, "reps performed by hand count")
        assertEquals(6, sets.sumOf { calls(it.name).size }, "numbers the voice spoke")
        sets.forEach { set ->
            val state = finalState(set.name)
            assertEquals(set.liveReps, state.repCount, "${set.name}: the tracker's own count")
            assertFalse(state.countTrusted, "${set.name}: countTrusted")
            val partner = finalState("${set.name}-imu-b")
            assertFalse(partner.countTrusted, "${set.name}-imu-b: countTrusted")
        }
    }

    /**
     * The second unit is not a spare copy of the first.
     *
     * Role b, the same code and the same declared geometry, calls 0, 0 and 3
     * -- so a union of the two units would give 3, 1 and 4 for 5, 5, 5, and an
     * agreement rule would give 0, 0 and 2. Neither is the hand count. Both
     * units were on the bar on the session's gyro evidence, but not in the
     * same orientation: set 4 publishes 367.2 deg of roll excursion on role a
     * against 17.2 on role b.
     */
    @Test
    fun `the partner unit counts differently on every set`() {
        assertEquals(
            listOf(0, 0, 3),
            sets.map { calls("${it.name}-imu-b").size },
            "role b's live calls, set by set",
        )
    }

    /**
     * Set 6's first call is the set-up pull, so four of the five calls are
     * reps.
     *
     * The test is the bar breaking from or striking the floor, which arrives as
     * a single-sample acceleration transient: on set 6 the first one is over a
     * second AFTER the call that said "Rep 1".
     *
     * **AND A FIRST-CONTACT RULE ALONE DOES NOT SEPARATE THE THREE SETS.** On
     * set 4 the first call does follow the first transient; on set 5 it does
     * NOT, and set 5's first call is a real rep -- its rep 1 lift-off produced
     * no sample above 4 g, and the first transient of that set is the bar
     * arriving back at the floor 1.4 s later. So "exclude anything before the
     * first floor contact" would reject the phantom on set 6 and also reject a
     * true rep on set 5. That is measured here rather than argued: any
     * candidate resting on the transients as rep boundaries has to say what it
     * does on a pull whose lift-off is under the threshold.
     *
     * The transient is measured as accelerometer MAGNITUDE above 4 g. Issue
     * #301 quotes 5, 15 and 9 transients per set, which is the same data under
     * a PER-AXIS test -- pinned here too, because the two definitions give
     * different counts on the same stream and the issue's figures are the
     * per-axis ones.
     */
    @Test
    fun `only set 4's first call follows its first floor transient`() {
        val expected = mapOf(
            "field-deadlift-straight-5rep-s43-set04" to true,
            "field-deadlift-straight-5rep-s43-set05" to false,
            "field-deadlift-straight-5rep-s43-set06" to false,
        )
        expected.forEach { (fixture, callFollowsTransient) ->
            val samples = load(fixture)
            val firstTransient = samples.first { FrameTransform.accMagnitudeG(it) > 4.0 }.timestampMs
            val firstCall = calls(fixture).first().atTimestampMs
            assertEquals(
                callFollowsTransient,
                firstCall > firstTransient,
                "$fixture: first call $firstCall against first >4 g transient $firstTransient",
            )
        }
        assertEquals(
            listOf(5, 18, 10),
            sets.map { set -> load(set.name).count { FrameTransform.accMagnitudeG(it) > 4.0 } },
            "samples above 4 g by magnitude, set by set",
        )
        assertEquals(
            listOf(5, 15, 9),
            sets.map { set ->
                load(set.name).count { maxOf(abs(it.axG), abs(it.ayG), abs(it.azG)) > 4.0 }
            },
            "samples above 4 g on any single axis -- issue #301's figures",
        )
        assertEquals(
            listOf(0, 2, 0),
            sets.map { set ->
                load(set.name).count { maxOf(abs(it.axG), abs(it.ayG), abs(it.azG)) >= 15.999 }
            },
            "samples where an axis rails at the 16 g full scale",
        )
    }

    /**
     * Set 5 has no ZUPT anchor to find for fifteen and a half seconds, which
     * bounds what any re-tuned accept band can recover.
     *
     * `isQuietSample` is the shipped per-sample predicate and `minStationaryS`
     * is 0.30 s, so this is the window supply the live integrator actually
     * had. Sets 4 and 6 offer mid-set windows; set 5 offers none between
     * 5.12 s and 20.64 s, and 20.64 s is after its last pull. The owner
     * intended all three as dead stops, and set 5 ran touch-and-go.
     */
    @Test
    fun `set 5 offers no quiet window between its first pull and its last`() {
        assertEquals(
            listOf(9, 3, 8),
            sets.map { quietWindows(it.name).size },
            "quiet windows of at least minStationaryS, set by set",
        )
        val set5 = quietWindows("field-deadlift-straight-5rep-s43-set05")
        assertEquals(0.0, set5.first().first, 0.01, "set 5's first window opens at the head of the stream")
        assertEquals(5.12, set5.first().second, 0.01, "and closes before the first pull")
        assertEquals(20.64, set5[1].first, 0.01, "the next one opens only after the last pull")
        val set6Interior = quietWindows("field-deadlift-straight-5rep-s43-set06").filter { it.first in 4.0..16.9 }
        assertEquals(5, set6Interior.size, "set 6's interior windows")
        assertEquals(
            4,
            set6Interior.count { it.second - it.first < 0.7 },
            "windows shorter than the counted full second the protocol asked for: $set6Interior",
        )
        // The fifth is 1.95 s long, it sits immediately before the set's rep 5
        // -- and rep 5 is the one real rep set 6 counted. That is the strongest
        // in-file evidence for the mechanism #301 names: the count comes back
        // when, and only when, the integrator gets a long enough stop to
        // re-anchor on.
        val longest = set6Interior.maxBy { it.second - it.first }
        assertEquals(1.95, longest.second - longest.first, 0.05, "the one long interior window, seconds")
        assertEquals(14.81, longest.first, 0.05, "and where it opens, seconds into the set")
    }

    /**
     * The batch path is not the fallback. It resolves 9, 7 and 7 drives for
     * five performed reps, and two of its published ranges are longer than a
     * deadlift's whole pull.
     *
     * A deadlift ROM is about 0.5-0.6 m. Set 6 rep 2 publishes 1.507 m at
     * 102 kg and set 5 rep 6 publishes 1.761 m. Those are the drift, not the
     * lifter -- which is why raising the live displacement cap is not a fix
     * (#290, #291).
     */
    @Test
    fun `the batch path over-counts every set and publishes two impossible ranges`() {
        assertEquals(
            listOf(9, 7, 7),
            sets.map { SetAnalyzer.analyze(load(it.name), deadlift, it.loadKg).reps.size },
            "batch drives on role a, set by set",
        )
        assertEquals(
            listOf(7, 9, 6),
            sets.map { SetAnalyzer.analyze(load("${it.name}-imu-b"), deadlift, it.loadKg).reps.size },
            "batch drives on role b, set by set",
        )
        val set6 = SetAnalyzer.analyze(load("field-deadlift-straight-5rep-s43-set06"), deadlift, 102.05828325225797)
        assertEquals(0.122, set6.reps[0].romM, 1e-3, "set 6's first drive: the set-up pull the voice called")
        assertEquals(1.507, set6.reps[1].romM, 1e-3, "set 6's second drive")
        val set5 = SetAnalyzer.analyze(load("field-deadlift-straight-5rep-s43-set05"), deadlift, 83.91458845185656)
        assertEquals(1.761, set5.reps[5].romM, 1e-3, "set 5's sixth drive")
    }

    /**
     * Contiguous runs of [VelocityEstimator.isQuietSample] at least
     * `minStationaryS` long, in seconds from the first sample on the span-based
     * rate -- the same reconstruction the tracker integrates on.
     */
    private fun quietWindows(fixture: String): List<Pair<Double, Double>> {
        val samples = load(fixture)
        val config = DspConfig()
        val rateHz = (samples.size - 1) /
            ((samples.last().timestampMs - samples.first().timestampMs) / 1000.0)
        val quiet = samples.map { VelocityEstimator.isQuietSample(it, config) }
        val windows = mutableListOf<Pair<Double, Double>>()
        var i = 0
        while (i < quiet.size) {
            if (!quiet[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < quiet.size && quiet[j + 1]) j++
            if ((j - i) / rateHz >= config.minStationaryS) windows += i / rateHz to j / rateHz
            i = j + 1
        }
        return windows
    }
}
