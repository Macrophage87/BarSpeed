package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RepCounter
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drive-impulse counter's first real session, and the loads it went
 * silent at. Issue #305.
 *
 * ## Provenance
 *
 * Session 44, `BarSpeed-v0.1.54-2026-09-24_072324-raw.zip` (sha256
 * `f6f2f4c671f608db71ecfe14b4925b834f108f23476763e39b6f0490b6e370cd`), app
 * **0.1.54**, sensor WitMotion WT901BLECL, the first five sets of a lower
 * session. Every field below is copied from that archive's own `meta.json`;
 * nothing here is inferred from the streams.
 *
 * | fixture | set | load kg | reps stored | plannedReps | rpe | repsSource | liveReps | other |
 * |---|---|---|---|---|---|---|---|---|
 * | `5rep-s44-set01` | 1 | 61.234969951354785 | 5 | 5 | 1 | `sensor` | 5 | `warmup` |
 * | `5rep-s44-set02` | 2 | 83.91458845185656 | 5 | 5 | 1 | `sensor` | 5 | `warmup` |
 * | `5rep-s44-set03` | 3 | 102.05828325225797 | 5 | 5 | 1 | `sensor` | 5 | |
 * | `4rep-s44-set04` | 4 | 111.13013065245867 | 5 | absent | 4 | `corrected` | 0 | `added`, `repsManual` |
 * | `2rep-s44-set05` | 5 | 120.20197805265938 | 3 | absent | -- | `corrected` | 0 | see below |
 *
 * Set 5 also carries `added`, `repsManual`, `failed`, `failedByLifter` and
 * `limiter: "grip"`, and no `rpe` key. Common to all five:
 * `startsWith: "concentric"`, `concentric: "up"` with
 * `concentricSource: "declared"`, `plane: "vertical"`, `sensorOnStack` and
 * `sensorInverted` false, `travelRatio` 1.0, `kind: "dynamic"`, `bodyweight`
 * false, **no `tempoPrescribed` key**, `sensorsArmed` 2 with roles `a` and `b`,
 * `analysedRole: "a"`. No `-prep.csv` and no `-reps.csv` exists for any of
 * them. Committed per set: role a as the capture, role b as an `-imu-b`
 * partner, and the cue track where the app wrote one -- it wrote none for set 5.
 *
 * ## The truth is the owner's, settled after the report, and it is not `reps`
 *
 * **Completed reps 5, 5, 5, 4, 2**, and set 5's third pull is a FAILED ATTEMPT.
 * The fixture names carry the completed count. In the owner's words:
 *
 * - Mount: *"Both on center of thr bar"* -- both units at the bar's CENTRE,
 *   where field-43's sat on the collars. Style: *"Dead stop"*, all five sets.
 * - Set 4: the capture shows four pulls against the stored 5 (`repsSource`
 *   `corrected`, a mental count). *"It could indeed be 4."* The capture is
 *   taken as the truth and the stored 5 as one high.
 * - Set 5, pull 3: *"It was about halfway up. Grip failed."* So the set is two
 *   completed reps and one failed attempt, stored as 3. A counter scored here
 *   must be scored against 2, and calling the third pull is a defect, not a
 *   rep: *"Don't ask for an RPE on failed sets, if you can't do it, it's
 *   failed."*
 *
 * ## What the cue tracks are
 *
 * Sets 1-3: the sensor's own live calls -- the replay licence below is what
 * proves the committed CSVs are the stream the app ran on. Set 4: five
 * `+1 REP` taps spoken through the same one-number path, all within one
 * second and 58.4 s into the set, after the bar was down -- not live calls.
 * Set 5: no file, so nothing was spoken and its stored 3 was entered without
 * a spoken tap; #305's lens A reads that as a set-end entry.
 *
 * ## What this file pins, and what it does not
 *
 * TODAY's counts, on the counter `LiveRepCounters.forCounted` arms for a
 * sensor-counted set -- `DriveImpulseCounter`, unchanged on `main` since the
 * `v0.1.54` tag the session ran. 5, 5, 5, 0, 0 on role a is the number the
 * lifter heard. Nothing here is fixed, and a design that counts sets 4 and 5
 * is EXPECTED to red the live-count pin. The candidates are issue #305's
 * design round, not this file.
 */
class DeadliftHeavyFieldTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** The geometry all five sets declared; nothing about it is inferred. */
    private val deadlift = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private data class Fixture(val name: String, val loadKg: Double, val completed: Int, val liveReps: Int)

    private val sets = listOf(
        Fixture("field-deadlift-straight-5rep-s44-set01", 61.234969951354785, completed = 5, liveReps = 5),
        Fixture("field-deadlift-straight-5rep-s44-set02", 83.91458845185656, completed = 5, liveReps = 5),
        Fixture("field-deadlift-straight-5rep-s44-set03", 102.05828325225797, completed = 5, liveReps = 5),
        Fixture("field-deadlift-straight-4rep-s44-set04", 111.13013065245867, completed = 4, liveReps = 0),
        Fixture("field-deadlift-straight-2rep-s44-set05", 120.20197805265938, completed = 2, liveReps = 0),
    )

    /** Every [RepCall.Speak] the app makes on a set of this shape, through `RecordViewModel`'s own call. */
    private fun liveCalls(fixture: String): List<RepCall.Speak> {
        val counter = LiveRepCounters.forCounted(RepCounter.SENSOR, deadlift)
            ?: error("a sensor-counted set must arm a counter")
        val tracker = StreamingSetTracker.forLift(deadlift)
        val spoken = mutableListOf<RepCall.Speak>()
        for (sample in load(fixture)) {
            val call = counter.feed(tracker.feed(sample), sample.timestampMs)
            if (call is RepCall.Speak) spoken += call
        }
        return spoken
    }

    /**
     * The replay licence. Without it every other figure in this file is a
     * model of the live path rather than a reproduction of it.
     *
     * On sets 1-3 the five cue rows are the five calls the lifter heard.
     * Feeding role a reproduces each to within 2 ms -- the cue writer stamps
     * from its own clock read one statement after the sample, and set 3 shows
     * the widest gap of the three.
     */
    @Test
    fun `replaying role a reproduces the three live-counted tracks to within 2 ms`() {
        sets.take(3).forEach { set ->
            val spoken = liveCalls(set.name)
            val cues = CueTrack.read(set.name)
            assertEquals(set.liveReps, spoken.size, "${set.name}: calls against the exported liveReps")
            assertEquals(5, cues.size, "${set.name}: rows on the cue track")
            spoken.forEachIndexed { i, call ->
                assertEquals("Rep ${i + 1}", cues[i].label, "${set.name}: cue row ${i + 1}")
                assertTrue(
                    abs(call.atTimestampMs - cues[i].timestampMs) <= 2L,
                    "${set.name}: call ${i + 1} at ${call.atTimestampMs} against cue ${cues[i].timestampMs}",
                )
            }
        }
    }

    /**
     * 5, 5, 5, 0, 0 -- fifteen calls for twenty-one completed reps, and every
     * one of the six completed reps at 111 and 120 kg missed.
     *
     * Role b, the same code on the second unit, calls 6, 6, 5, 0, 0 -- #305's
     * lens A reads the extra call on sets 1 and 2 as the final set-down -- so
     * the partner is not a spare copy even when both units ride one rigid body.
     */
    @Test
    fun `the shipped counter calls 5, 5, 5, 0 and 0 and falls silent above 102 kg`() {
        assertEquals(21, sets.sumOf { it.completed }, "completed reps, the owner's settled count")
        assertEquals(listOf(5, 5, 5, 0, 0), sets.map { liveCalls(it.name).size }, "role a, set by set")
        assertEquals(listOf(5, 5, 5, 0, 0), sets.map { it.liveReps }, "the archive's liveReps")
        assertEquals(
            listOf(6, 6, 5, 0, 0),
            sets.map { liveCalls("${it.name}-imu-b").size },
            "role b, set by set",
        )
    }

    /**
     * Set 4's cue rows are the lifter's taps, not the counter's, and set 5 has
     * no track at all. Both fit the post-set correction rule on a deadlift:
     * the lifter's hands are on the bar until the set is over.
     */
    @Test
    fun `set 4's track is five post-set taps and set 5 wrote no track`() {
        val set4 = sets[3].name
        val cues = CueTrack.read(set4)
        assertEquals((1..5).map { "Rep $it" }, cues.map { it.label }, "set 4's rows")
        val firstSampleMs = load(set4).first().timestampMs
        assertEquals(58.4, (cues.first().timestampMs - firstSampleMs) / 1000.0, 0.05, "first tap, s into the set")
        assertTrue(cues.last().timestampMs - cues.first().timestampMs < 1000L, "all five taps inside one second")
        assertNull(javaClass.getResource("/${sets[4].name}-cues.csv"), "set 5 carries no cue track")
    }

    /**
     * WHICH gate went silent, pinned on the class's own constants.
     *
     * A drive qualifies when the drive-frame acceleration holds above
     * `driveAccelThresholdMps2` for `driveMinPhaseS` and peaks at
     * `drivePeakAccelMps2`. On sets 4 and 5 of role a exactly ONE run anywhere
     * in either stream qualifies, and it is not a pull: it peaks at 58.8 m/s^2,
     * which is set 4's floor contact at 11.06 s ringing through the 8 Hz
     * low-pass, and no brake follows it. Every pull at 111 and 120 kg is under
     * the duration gate.
     */
    @Test
    fun `on sets 4 and 5 the only qualifying drive is a floor contact's ringing`() {
        val config = DspConfig()
        val qualifying = sets.drop(3).map { set ->
            val tracker = StreamingSetTracker.forLift(deadlift)
            val runs = mutableListOf<Pair<Double, Double>>()
            var startS = Double.NaN
            var lastS = Double.NaN
            var peak = 0.0
            for (sample in load(set.name)) {
                val live = tracker.feed(sample)
                val accel = live.accelMps2 * deadlift.sensorToLifter * deadlift.concentricSign
                if (accel > config.driveAccelThresholdMps2) {
                    if (startS.isNaN()) {
                        startS = live.elapsedS
                        peak = accel
                    }
                    peak = maxOf(peak, accel)
                    lastS = live.elapsedS
                } else if (!startS.isNaN()) {
                    if (lastS - startS >= config.driveMinPhaseS && peak >= config.drivePeakAccelMps2) {
                        runs += startS to peak
                    }
                    startS = Double.NaN
                }
            }
            runs
        }
        assertEquals(listOf(1, 0), qualifying.map { it.size }, "qualifying drives on sets 4 and 5")
        assertEquals(11.03, qualifying[0][0].first, 0.02, "set 4's one qualifying run opens, s")
        assertEquals(58.8, qualifying[0][0].second, 0.1, "and peaks, m/s^2")
    }

    /**
     * The batch path is not the fallback here either: 6, 7, 13, 8 and 4
     * detections on role a for 5, 5, 5, 4 and 2 completed reps. Set 3's
     * thirteen include the first 119 s of the set, which is plate handling.
     */
    @Test
    fun `the batch path resolves 6, 7, 13, 8 and 4 drives`() {
        assertEquals(
            listOf(6, 7, 13, 8, 4),
            sets.map { SetAnalyzer.analyze(load(it.name), deadlift, it.loadKg).reps.size },
            "batch drives on role a, set by set",
        )
    }
}
