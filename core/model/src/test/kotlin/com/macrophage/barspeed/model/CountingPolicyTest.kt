package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who counts a set of each shape, and the three things that follow from it.
 *
 * ## What the table is evidence of
 *
 * The thirty-two rows are the WHOLE input space -- four [ExerciseKind]s against
 * tempo, timed and sensor -- and they are literals rather than a re-derivation
 * of [CountingPolicy.counterFor]'s own branches, which would agree with it by
 * construction and could not catch a lost clause. `SetVoicePolicyTest` states
 * the same reason for the same reason.
 *
 * They are also the characterization of what `RecordViewModel.beginSet`
 * computed before the decision was lifted out of it:
 * `var manualSet = !currentIsTimed && (kind != EXPLOSIVE || !imuConnected)`
 * with `if (guidedSet) manualSet = true`. `the lift changes one row and names
 * it` asserts that agreement over all thirty-two shapes and names the single
 * row where the answer is DIFFERENT on purpose -- the untempo'd dynamic set
 * with a sensor connected, which is issue #286's whole subject.
 */
class CountingPolicyTest {
    private fun counter(
        hasTempo: Boolean = false,
        isTimed: Boolean = false,
        kind: ExerciseKind = ExerciseKind.DYNAMIC,
        imuConnected: Boolean = true,
    ) = CountingPolicy.counterFor(hasTempo, isTimed, kind, imuConnected)

    /** The owner's rule: a straight-reps set with the sensor on is the sensor's. */
    @Test
    fun `an untempo'd dynamic set with a sensor is counted by the sensor`() {
        assertEquals(RepCounter.SENSOR, counter())
        assertEquals(RepCounter.SENSOR, counter(kind = ExerciseKind.EXPLOSIVE))
    }

    /** With no sensor there is nothing counting but the lifter. */
    @Test
    fun `an untempo'd dynamic set without a sensor is counted by the lifter`() {
        assertEquals(RepCounter.MANUAL, counter(imuConnected = false))
        assertEquals(RepCounter.MANUAL, counter(kind = ExerciseKind.EXPLOSIVE, imuConnected = false))
    }

    /**
     * A tempo'd rep-based set is the metronome's, sensor or no sensor.
     *
     * An explosive lift carrying a tempo string is NOT: `LeadInPolicy.prepCase`
     * gives it no cadence to follow, so there is no metronome to count it and
     * the sensor does.
     */
    @Test
    fun `a tempo'd set is counted by the metronome unless nothing plays the tempo`() {
        assertEquals(RepCounter.METRONOME, counter(hasTempo = true))
        assertEquals(RepCounter.METRONOME, counter(hasTempo = true, imuConnected = false))
        assertEquals(RepCounter.METRONOME, counter(hasTempo = true, kind = ExerciseKind.HOLD))
        assertEquals(RepCounter.SENSOR, counter(hasTempo = true, kind = ExerciseKind.EXPLOSIVE))
    }

    /** A set measured in seconds has no rep for anything to count. */
    @Test
    fun `a timed set is counted by nobody`() {
        assertEquals(RepCounter.NOBODY, counter(isTimed = true, kind = ExerciseKind.HOLD))
        assertEquals(RepCounter.NOBODY, counter(isTimed = true, kind = ExerciseKind.CARRY))
        assertEquals(RepCounter.NOBODY, counter(isTimed = true, kind = ExerciseKind.HOLD, imuConnected = false))
        assertEquals(RepCounter.NOBODY, counter(isTimed = true, hasTempo = true, kind = ExerciseKind.HOLD))
    }

    /**
     * An UNTIMED hold or carry is the lifter's, with a sensor connected and no
     * tempo prescribed.
     *
     * Reachable on the ad-hoc path, which has no validator. This is #217 as a
     * counting rule: a phase counter let loose on a lifter hanging still counts
     * whatever the sensor made of it, and field-37's sets 11 and 12 carry the
     * stray digits it spoke.
     */
    @Test
    fun `an untimed hold or carry is never the sensor's to count`() {
        assertEquals(RepCounter.MANUAL, counter(kind = ExerciseKind.HOLD))
        assertEquals(RepCounter.MANUAL, counter(kind = ExerciseKind.CARRY))
    }

    /** Every shape answers from a table written out by hand. */
    @Test
    fun `every shape answers from a table written out by hand`() {
        COUNTER_TABLE.forEach { (shape, expected) ->
            assertEquals(
                expected,
                CountingPolicy.counterFor(shape.hasTempo, shape.isTimed, shape.kind, shape.imuConnected),
                "$shape",
            )
        }
        assertEquals(
            ExerciseKind.entries.size * 2 * 2 * 2,
            COUNTER_TABLE.size,
            "the table stopped covering every shape",
        )
        assertEquals(COUNTER_TABLE.size, COUNTER_TABLE.map { it.first }.toSet().size, "a shape is listed twice")
    }

    /**
     * The lift changes exactly one row, and this names it.
     *
     * `manualSet` is re-stated here as the shipped expression rather than read
     * from anywhere, because the point is to compare against what the app DID.
     * Thirty-one of thirty-two shapes agree with [CountingPolicy.tallyIsTheCount]
     * -- which is the evidence that lifting the decision preserved behaviour --
     * and the untempo'd dynamic set with a sensor connected disagrees, which is
     * the change issue #286 asks for.
     */
    @Test
    fun `the lift changes one row and names it`() {
        var changed = 0
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (imu in listOf(false, true)) {
                        val cued = LeadInPolicy.prepCase(hasTempo, isTimed, kind) == PrepCase.CUED
                        val shipped = cued || (!isTimed && (kind != ExerciseKind.EXPLOSIVE || !imu))
                        val now = CountingPolicy.tallyIsTheCount(
                            CountingPolicy.counterFor(hasTempo, isTimed, kind, imu),
                        )
                        val isTheChangedRow =
                            kind == ExerciseKind.DYNAMIC && !hasTempo && !isTimed && imu
                        if (isTheChangedRow) {
                            changed++
                            assertEquals(true, shipped, "the shipped app did not count this set by hand")
                            assertEquals(false, now, "the sensor does not count the straight-reps set")
                        } else {
                            assertEquals(
                                shipped,
                                now,
                                "$kind tempo=$hasTempo timed=$isTimed imu=$imu moved and should not have",
                            )
                        }
                    }
                }
            }
        }
        assertEquals(1, changed, "more or fewer than one shape changed")
    }

    /**
     * What a `+1 REP` tap does, per counter.
     *
     * The sensor's set accepts the tap as a CORRECTION rather than ignoring it:
     * issue #284 measured the live detector reading 6 for 5 performed on a
     * synthetic hitch and 1 for 5 on a synthetic drop, so the lifter has to be
     * able to disagree with it while the set is under way.
     */
    @Test
    fun `a tap counts on a manual set, corrects on a sensor set and does nothing elsewhere`() {
        assertEquals(RepTap.COUNT, CountingPolicy.tapMeaning(RepCounter.MANUAL))
        assertEquals(RepTap.CORRECTION, CountingPolicy.tapMeaning(RepCounter.SENSOR))
        assertEquals(RepTap.IGNORED, CountingPolicy.tapMeaning(RepCounter.METRONOME))
        assertEquals(RepTap.IGNORED, CountingPolicy.tapMeaning(RepCounter.NOBODY))
        assertEquals(
            RepCounter.entries.size,
            RepCounter.entries.map { CountingPolicy.tapMeaning(it) }.size,
            "a counter has no answer for what a tap means",
        )
    }

    /**
     * The sensor's counter is given no planned count to treat as a milestone,
     * so it cannot say the word that bounds the analysis.
     *
     * `"Done"` is `SetEnd`'s terminal word and reaches the cue track through
     * `speakCue`, so a counter that says it at the planned rep cuts every later
     * drive out of the analysed list (#285). The sensor is exactly the counter
     * that can reach the planned count before the lifter has finished.
     */
    @Test
    fun `only the sensor's counter is denied the planned count`() {
        assertNull(CountingPolicy.milestonePlannedReps(RepCounter.SENSOR, 5))
        assertNull(CountingPolicy.milestonePlannedReps(RepCounter.SENSOR, null))
        assertEquals(5, CountingPolicy.milestonePlannedReps(RepCounter.MANUAL, 5))
        assertEquals(5, CountingPolicy.milestonePlannedReps(RepCounter.METRONOME, 5))
        assertEquals(5, CountingPolicy.milestonePlannedReps(RepCounter.NOBODY, 5))
        assertNull(CountingPolicy.milestonePlannedReps(RepCounter.MANUAL, null))
    }

    /**
     * The button says who is about to count, and only the lifter's set says
     * "you count".
     *
     * The label it replaces said "you count" on a guided set too, because the
     * copy of the rule in `RecordScreen` ignored the tempo.
     */
    @Test
    fun `the start label names the counter and says you count only on a manual set`() {
        assertEquals("START SET — you count", CountingPolicy.startSetLabel(RepCounter.MANUAL))
        assertEquals("START SET — sensor counts", CountingPolicy.startSetLabel(RepCounter.SENSOR))
        assertEquals("START SET — the guide counts", CountingPolicy.startSetLabel(RepCounter.METRONOME))
        assertEquals("START SET", CountingPolicy.startSetLabel(RepCounter.NOBODY))
        assertEquals(
            1,
            RepCounter.entries.count { "you count" in CountingPolicy.startSetLabel(it) },
            "more than one counter tells the lifter they are counting",
        )
        assertEquals(
            RepCounter.entries.size,
            RepCounter.entries.map { CountingPolicy.startSetLabel(it) }.toSet().size,
            "two counters share a label, so the lifter cannot tell them apart",
        )
    }

    /** `sensorCounts` is the same answer read for one member. */
    @Test
    fun `the per-sample question agrees with the counter for every shape`() {
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (imu in listOf(false, true)) {
                        assertEquals(
                            CountingPolicy.counterFor(hasTempo, isTimed, kind, imu) == RepCounter.SENSOR,
                            CountingPolicy.sensorCounts(hasTempo, isTimed, kind, imu),
                            "$kind tempo=$hasTempo timed=$isTimed imu=$imu",
                        )
                    }
                }
            }
        }
    }

    /** One row of the hand-written table: everything the counter depends on. */
    private data class Shape(
        val kind: ExerciseKind,
        val hasTempo: Boolean,
        val isTimed: Boolean,
        val imuConnected: Boolean,
    )

    private companion object {
        private val COUNTER_TABLE: List<Pair<Shape, RepCounter>> =
            listOf(
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = false)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = true)
                    to RepCounter.SENSOR,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = false, imuConnected = false)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = false, imuConnected = true)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = false, imuConnected = false)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = false, imuConnected = true)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = false, imuConnected = false)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = false, imuConnected = true)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = false, imuConnected = false)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = false, imuConnected = true)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = false, imuConnected = false)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = false, imuConnected = true)
                    to RepCounter.METRONOME,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = false, imuConnected = false)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = false, imuConnected = true)
                    to RepCounter.SENSOR,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = false, imuConnected = false)
                    to RepCounter.MANUAL,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = false, imuConnected = true)
                    to RepCounter.SENSOR,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = true, imuConnected = false)
                    to RepCounter.NOBODY,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = true, imuConnected = true)
                    to RepCounter.NOBODY,
            )
    }
}
