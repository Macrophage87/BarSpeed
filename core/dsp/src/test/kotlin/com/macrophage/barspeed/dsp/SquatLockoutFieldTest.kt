package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.RepCounter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's first v0.2.2 session, the back squats the full-cycle counter
 * mis-counted live, and what the app counts on them now.
 *
 * ## Provenance
 *
 * `BarSpeed-2026-11-06_120201-Strength-A-Squat-Triples-raw.zip` (sha256
 * `3587bb1480a155803069ac0b03ed5fc865547d341fd08a1eeabd6cf0c2e51ecc`), epoch
 * 2026-11-06T17:02:01.788Z, app **0.2.2**, sensor WitMotion WT901BLECL,
 * sets 1-7 -- every `back_squat` set it holds. Enrolled with the owner's
 * consent on 2026-11-06 ("Yes, enrol the squat sets"). Committed per set
 * under `live-squat-v022/`: byte copies of `setNN_back_squat_imu-a.csv` as
 * `setNN.csv`, `-imu-b.csv` as `setNN-imu-b.csv` and `-cues.csv` as
 * `setNN-cues.csv`. No heart-rate file and no leg curl set is committed.
 *
 * Every field below is copied from the archive's own `meta.json`:
 *
 * | set | load lb | reps stored | plannedReps | repsSource | liveReps |
 * |---|---|---|---|---|---|
 * | 1 | 45 | 5 | 5 | `corrected` | 4 |
 * | 2 | 95 | 6 | 5 | `sensor` | 6 |
 * | 3 | 135 | 5 | 3 | `sensor` | 5 |
 * | 4 | 165 | 2 | 2 | `corrected` | 1 |
 * | 5 | 185 | 3 | 3 | `corrected` | 4 |
 * | 6 | 185 | 3 | 3 | `sensor` | 3 |
 * | 7 | 185 | 3 | 3 | `corrected` | 4 |
 *
 * Common to all seven: `startsWith: "eccentric"`, `concentric: "up"`
 * (`declared`), `plane: "vertical"`, `sensorOnStack` and `sensorInverted`
 * false, `travelRatio` 1.0, `kind: "dynamic"`, no `tempoPrescribed` key, two
 * sensors armed, `analysedRole: "a"`.
 *
 * ## The truth
 *
 * Sets 1, 4, 5, 6 and 7 carry the owner's count: the four he corrected by
 * hand, and set 6, where the sensor's 3 stood and is the plan's 3. Sets 2
 * and 3 do NOT: the sensor's 6 and 5 stood uncorrected, and the owner, asked
 * whether the traces' 5 and 4 were right, wrote *"Trace might be accurate. I
 * use bar speed because it's hard to count when I have weight on top of
 * me"*. So nothing here scores sets 2 and 3 against a number; they are
 * pinned as counts.
 *
 * ## Why not the field corpus
 *
 * Under its own directory rather than beside the `field-*.csv` captures,
 * because `FieldCorpus` lists that directory and fifteen batch-path suites
 * pin a figure per capture it holds. This enrolment exists for the live
 * counter; measuring these streams on the batch path is its own change.
 */
class SquatLockoutFieldTest {
    private fun load(name: String): List<ImuSample> = ImuCsv.decode(
        SquatLockoutFieldTest::class.java.getResourceAsStream("/live-squat-v022/$name.csv")!!
            .readBytes().decodeToString(),
    )

    private fun cues(set: Int): List<Long> =
        SquatLockoutFieldTest::class.java.getResourceAsStream("/live-squat-v022/${name(set)}-cues.csv")!!
            .readBytes().decodeToString().lines().drop(1).filter { it.isNotBlank() }
            .map { it.substringBefore(',').toLong() }

    private fun name(set: Int) = "set%02d".format(set)

    private val squat = LiftDirection(squat = true)

    private fun calls(counter: LiveRepCounter, stream: String): List<RepCall.Speak> {
        val tracker = StreamingSetTracker.forLift(squat)
        return load(stream).mapNotNull { counter.feed(tracker.feed(it), it.timestampMs) as? RepCall.Speak }
    }

    /** v0.2.2's counter, built by its parts so it does not follow production. */
    private fun v022(): LiveRepCounter = CycleRepCounter(squat, CycleRule(DspConfig(), fallRejects = false))

    private fun app(): LiveRepCounter =
        LiveRepCounters.forCounted(RepCounter.SENSOR, squat) ?: error("a sensor-counted set must arm a counter")

    private val sets = 1..7

    /**
     * The replay licence: v0.2.2's rule on role a calls exactly the exported
     * `liveReps` on every set, each within 2 ms of the cue row the app wrote
     * for it. The rows after a set's `liveReps` are the lifter's `+1 REP`.
     */
    @Test
    fun `replaying role a under v0_2_2's rule reproduces every live call`() {
        val liveReps = listOf(4, 6, 5, 1, 4, 3, 4)
        sets.forEach { set ->
            val spoken = calls(v022(), name(set))
            assertEquals(liveReps[set - 1], spoken.size, "set $set: calls against liveReps")
            spoken.zip(cues(set)).forEach { (call, cue) ->
                assertTrue(abs(call.atTimestampMs - cue) <= 2L, "set $set: call ${call.atTimestampMs} against $cue")
            }
        }
    }

    /**
     * What the app counts now, through `RecordViewModel`'s one call, on both
     * units: 5, 5, 4, 2, 3, 3 and 3. Sets 1, 4, 5, 6 and 7 equal the owner's
     * count on both; sets 2 and 3 are pinned as counts only.
     */
    @Test
    fun `the app counts the seven squats at lockout`() {
        val expected = listOf(5, 5, 4, 2, 3, 3, 3)
        assertEquals(expected, sets.map { calls(app(), name(it)).size }, "role a")
        assertEquals(expected, sets.map { calls(app(), "${name(it)}-imu-b").size }, "role b")
        val owner = mapOf(1 to 5, 4 to 2, 5 to 3, 6 to 3, 7 to 3)
        owner.forEach { (set, reps) -> assertEquals(reps, calls(app(), name(set)).size, "set $set against the owner") }
    }

    /**
     * The racking the owner heard as a rep: on sets 5 and 7, 185 lb triples,
     * v0.2.2 called four times, one of them a drive no rep made -- the
     * walk-out's, closed by the first descent -- and its fourth on the rack.
     * The app now calls three on each. Only the count is pinned: on set 5
     * v0.2.2's first call and the app's first lockout were measured within
     * 30 ms of each other on role a (a one-off replay, not pinned), so no
     * timing claim here separates them.
     */
    @Test
    fun `the 185 lb sets v0_2_2 called four on are called three`() {
        listOf(5, 7).forEach { set ->
            val v022 = calls(v022(), name(set))
            val now = calls(app(), name(set))
            assertEquals(4 to 3, v022.size to now.size, "set $set: v0.2.2 against now")
        }
    }
}
