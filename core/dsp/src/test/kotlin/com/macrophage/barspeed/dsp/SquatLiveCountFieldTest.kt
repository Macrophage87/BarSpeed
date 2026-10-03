package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RepCounter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full-cycle live counter on a back squat, the session where it read 1 or
 * 2 of 5. Issue #335.
 *
 * ## Provenance
 *
 * Session 46, `BarSpeed-v0.1.57-2026-10-01_061723-raw.zip` (sha256
 * `cdf1ad4ddf2349b0340d0dc3bf90956f996d91b2a375445d54054ad3ddd48d8a`), epoch
 * 2026-10-01T10:17:23.092Z, app **0.1.57**, sensor WitMotion WT901BLECL, the
 * first five sets of the session -- every `back_squat` set it holds. Every
 * field below is copied from that archive's own `meta.json`; nothing here is
 * inferred from the streams.
 *
 * | fixture | set | load kg (lb) | reps stored | plannedReps | repsSource | liveReps | other |
 * |---|---|---|---|---|---|---|---|
 * | `s46-set01` | 1 | 20.411656650451594 (45) | 5 | 5 | `sensor` | 5 | `warmup` |
 * | `s46-set02` | 2 | 43.091275150953365 (95) | 5 | 5 | `corrected` | 1 | `warmup`, `repsManual` |
 * | `s46-set03` | 3 | 61.234969951354785 (135) | 5 | 5 | `corrected` | 2 | `warmup`, `repsManual` |
 * | `s46-set04` | 4 | 70.3068173515555 (155) | 5 | 5 | `corrected` | 1 | `repsManual` |
 * | `s46-set05` | 5 | 79.3786647517562 (175) | 5 | 5 | `sensor` | 5 | |
 *
 * Common to all five: `exercise: "back_squat"`, `startsWith: "eccentric"`,
 * `concentric: "up"` with `concentricSource: "declared"`, `plane:
 * "vertical"`, `sensorOnStack` and `sensorInverted` false, `travelRatio` 1.0,
 * `kind: "dynamic"`, `bodyweight` false, `rpe` 1, **no `tempoPrescribed`
 * key**, `sensorsArmed` 2 with roles `a` and `b`, `analysedRole: "a"`. No
 * `-prep.csv` and no `-reps.csv` exists for any of them. Committed per set
 * as field-44's deadlifts were: byte copies of role a as the capture, role b
 * as an `-imu-b` partner, and the cue track. The fixture names carry no rep
 * count, because the count is not settled.
 *
 * ## The truth is bounded, not settled
 *
 * The owner, on the live counts 5, 1, 2, 1, 5: *"I know that I at least did 4
 * on each. It's harder to count when under a heavy bar."* So each set is AT
 * LEAST 4 reps and MOST LIKELY 5, the figure he entered by hand on sets 2-4.
 * `CandidateCorpus.UNSETTLED` therefore gives these five no truth in the
 * candidate corpus, and nothing in this file scores a count as right or wrong
 * by a number the owner did not state.
 *
 * ## What the cue tracks are
 *
 * Five `Rep N` rows on every set. On sets 1 and 5 all five are the sensor's
 * live calls. On sets 2, 3 and 4 the first `liveReps` rows (1, 2 and 1) are
 * the sensor's and the rest are the lifter's `+1 REP` taps, bunched inside one
 * second -- spoken through the same one-number path, but not live calls. The
 * replay licence below is what shows the committed role-a streams are the
 * streams the app counted on.
 */
class SquatLiveCountFieldTest {
    private fun load(fixture: String): List<ImuSample> = LiveCountCandidates.load(fixture)

    private data class Fixture(val name: String, val liveReps: Int)

    private val sets = listOf(
        Fixture("field-backsquat-straight-s46-set01", liveReps = 5),
        Fixture("field-backsquat-straight-s46-set02", liveReps = 1),
        Fixture("field-backsquat-straight-s46-set03", liveReps = 2),
        Fixture("field-backsquat-straight-s46-set04", liveReps = 1),
        Fixture("field-backsquat-straight-s46-set05", liveReps = 5),
    )

    /** The geometry all five sets declared, as `CandidateCorpus` carries it. */
    private val squat = CandidateCorpus.capture(sets[0].name).direction

    /** Every [RepCall.Speak] [counter] makes over [fixture], fed by the app's own tracker. */
    private fun calls(counter: LiveRepCounter, fixture: String): List<RepCall.Speak> {
        val tracker = StreamingSetTracker.forLift(squat)
        val spoken = mutableListOf<RepCall.Speak>()
        for (sample in load(fixture)) {
            val call = counter.feed(tracker.feed(sample), sample.timestampMs)
            if (call is RepCall.Speak) spoken += call
        }
        return spoken
    }

    /**
     * v0.1.57's rule, built BY ITS PARTS: the full-cycle counter with the FALL
     * rejection on, whatever the lift. It reproduces the build the session ran
     * and must not follow the production construction when that moves.
     */
    private fun v0157(): LiveRepCounter = CycleRepCounter(squat, CycleRule(DspConfig(), fallRejects = true))

    /** What the app arms on a sensor-counted set of this geometry, through `RecordViewModel`'s own call. */
    private fun app(): LiveRepCounter =
        LiveRepCounters.forCounted(RepCounter.SENSOR, squat) ?: error("a sensor-counted set must arm a counter")

    /**
     * The replay licence. Without it every other figure in this file is a model
     * of the live path rather than a reproduction of it.
     *
     * Feeding role a through v0.1.57's rule calls exactly the exported
     * `liveReps` on each set, and each call's arrival stamp lands within 2 ms
     * of the cue row the app wrote for it, `DeadliftHeavyFieldTest`'s bound.
     */
    @Test
    fun `replaying role a under v0_1_57's rule reproduces every live call to within 2 ms`() {
        sets.forEach { set ->
            val spoken = calls(v0157(), set.name)
            val cues = CueTrack.read(set.name)
            assertEquals(set.liveReps, spoken.size, "${set.name}: calls against the exported liveReps")
            assertEquals((1..5).map { "Rep $it" }, cues.map { it.label }, "${set.name}: the cue track's rows")
            spoken.forEachIndexed { i, call ->
                assertTrue(
                    abs(call.atTimestampMs - cues[i].timestampMs) <= 2L,
                    "${set.name}: call ${i + 1} at ${call.atTimestampMs} against cue ${cues[i].timestampMs}",
                )
            }
        }
    }

    /**
     * On sets 2, 3 and 4 the rows after the sensor's calls are the lifter's
     * taps: all of them inside one second, which no squat rep is.
     */
    @Test
    fun `on sets 2 to 4 the rows after the sensor's calls are taps bunched inside one second`() {
        sets.slice(1..3).forEach { set ->
            val taps = CueTrack.read(set.name).drop(set.liveReps)
            assertEquals(5 - set.liveReps, taps.size, "${set.name}: rows after the sensor's calls")
            assertTrue(
                taps.last().timestampMs - taps.first().timestampMs < 1000L,
                "${set.name}: taps spread over ${taps.last().timestampMs - taps.first().timestampMs} ms",
            )
        }
    }

    /**
     * v0.1.57's rule on both units: 5, 1, 2, 1 and 5 on role a, the counts the
     * lifter heard, and 4, 2, 1, 1 and 6 on role b. This pin never moves: it
     * builds the rule by its parts.
     */
    @Test
    fun `v0_1_57's rule counts 5, 1, 2, 1 and 5 on role a`() {
        assertEquals(listOf(5, 1, 2, 1, 5), sets.map { calls(v0157(), it.name).size }, "role a, sets 1-5")
        assertEquals(listOf(4, 2, 1, 1, 6), sets.map { calls(v0157(), "${it.name}-imu-b").size }, "role b, sets 1-5")
    }

    /**
     * What the app counts on the five squats, through the one call
     * `RecordViewModel` makes, on both units.
     */
    @Test
    fun `the app counts the five squats live`() {
        assertEquals(listOf(5, 1, 2, 1, 5), sets.map { calls(app(), it.name).size }, "role a, sets 1-5")
        assertEquals(listOf(4, 2, 1, 1, 6), sets.map { calls(app(), "${it.name}-imu-b").size }, "role b, sets 1-5")
    }
}
