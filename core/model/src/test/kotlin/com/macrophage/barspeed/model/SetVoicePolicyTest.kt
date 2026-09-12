package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who speaks on a set of each shape.
 *
 * The set of shapes is exhaustive: every [ExerciseKind] against tempo, timed
 * and sensor.
 *
 * These were c1's characterization of what `RecordViewModel.beginSet` computed
 * before the decision was lifted out of it. The timed case is no longer that
 * and says so at the point it changed; the rest are unchanged by #217 and are
 * still the record that the lift changed nothing.
 */
class SetVoicePolicyTest {
    private fun guides(
        hasTempo: Boolean = false,
        isTimed: Boolean = false,
        kind: ExerciseKind = ExerciseKind.DYNAMIC,
        imuConnected: Boolean = true,
    ) = SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, imuConnected)

    /** A tempo'd rep-based lift is paced by the metronome and by nothing else. */
    @Test
    fun `a tempo'd dynamic set is guided by the cadence alone`() {
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true))
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true, imuConnected = false))
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true, kind = ExerciseKind.HOLD))
    }

    /**
     * An untempo'd rep-based lift is counted by whoever is counting it: the
     * sensor where one is connected, and nobody where none is.
     *
     * DELETED AND REPLACED, not reworded. This test read `an untempo'd dynamic
     * set has no voice at all` and asserted the empty set for both rows, with
     * a KDoc saying "the bar sensor is record-only there: the lifter counts,
     * and the app's counter stays out of it". That was true of the shipped app
     * and is what issue #286 changes on the owner's rule of 2026-09-12: "The
     * sensor should count the reps." A pin describing what the app used to do
     * reads later as a pin on what it should do.
     *
     * The sensorless row is unchanged and is the half that still holds: with no
     * sensor there is nothing for the sensor counter to count, and the lifter
     * counts silently.
     */
    @Test
    fun `an untempo'd dynamic set with a sensor is counted aloud by the sensor`() {
        assertEquals(setOf(SetVoiceGuide.SENSOR_COUNT), guides())
        assertEquals(emptySet(), guides(imuConnected = false))
    }

    /** An explosive lift is judged on peak velocity, so the sensor counts it. */
    @Test
    fun `an explosive lift with a sensor is counted by the sensor`() {
        assertEquals(setOf(SetVoiceGuide.SENSOR_COUNT), guides(kind = ExerciseKind.EXPLOSIVE))
        assertEquals(setOf(SetVoiceGuide.SENSOR_COUNT), guides(hasTempo = true, kind = ExerciseKind.EXPLOSIVE))
    }

    /** With no sensor there is nothing for the sensor counter to count. */
    @Test
    fun `an explosive lift without a sensor has no voice`() {
        assertEquals(emptySet(), guides(kind = ExerciseKind.EXPLOSIVE, imuConnected = false))
    }

    /**
     * A timed set is guided by its clock and by nothing else.
     *
     * The c1 pin here said the opposite -- that a timed set is spoken over by
     * the sensor counter as well as its clock -- and it was true of the
     * shipped app. It is DELETED rather than reworded: it characterised the
     * defect for one commit, its job is done, and a pin that describes what
     * the app used to do reads later as a pin on what it should do.
     *
     * All three shapes the field has produced are here. A hold and a carry
     * with a sensor connected are what field-37's sets 11, 12 and 13 were; a
     * timed set carrying a tempo string is reachable on the ad-hoc path, which
     * has no validator (#217).
     *
     * A fourth row asserted the same answer with demo mode on. It is DELETED
     * rather than reworded, with the two cases above it, because #262 removed
     * the input: a row naming a state the app cannot be in describes nothing.
     */
    @Test
    fun `a timed set is guided by its clock alone`() {
        assertEquals(setOf(SetVoiceGuide.TIMED_CLOCK), guides(isTimed = true, kind = ExerciseKind.HOLD))
        assertEquals(setOf(SetVoiceGuide.TIMED_CLOCK), guides(isTimed = true, kind = ExerciseKind.CARRY))
        assertEquals(
            setOf(SetVoiceGuide.TIMED_CLOCK),
            guides(isTimed = true, hasTempo = true, kind = ExerciseKind.HOLD),
        )
    }

    /**
     * No set is guided by two voices at once.
     *
     * The whole contract, over every shape: kind against tempo, timed and
     * sensor. Two voices counting different quantities in overlapping
     * vocabularies is what a lifter cannot resolve -- on field-37's set 11 the
     * bare `1` the sensor counter spoke landed 0.186 s before
     * `workStartedAt_ms`, 0.184 s before the `Hold` row that meant the clock
     * had started.
     */
    @Test
    fun `no set is guided by two voices at once`() {
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (imu in listOf(false, true)) {
                        val g = SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, imu)
                        assertEquals(
                            true,
                            g.size <= 1,
                            "$kind tempo=$hasTempo timed=$isTimed imu=$imu speaks with $g",
                        )
                    }
                }
            }
        }
    }

    /**
     * THE VOICE FOLLOWS THE COUNTER, on every shape a set can have.
     *
     * Two statements of "the sensor is counting this set" -- one here deciding
     * who speaks, one in [CountingPolicy] deciding who counts -- are two rules,
     * and the way they drift is the app speaking a count it does not record or
     * recording one it never spoke. That is the defect shipped on explosive
     * lifts today in the other direction: the ring drew
     * `StreamingSetTracker.repCount` while the row stored the batch
     * segmenter's figure.
     *
     * RED before #286's fix, on the one shape the two disagreed about: the
     * untempo'd dynamic set with a sensor connected, where `guidesFor`
     * required `kind == EXPLOSIVE` and [CountingPolicy.counterFor] does not.
     */
    @Test
    fun `the sensor speaks on exactly the sets the sensor counts`() {
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (imu in listOf(false, true)) {
                        assertEquals(
                            CountingPolicy.sensorCounts(hasTempo, isTimed, kind, imu),
                            SetVoicePolicy.sensorCounts(hasTempo, isTimed, kind, imu),
                            "$kind tempo=$hasTempo timed=$isTimed imu=$imu: the voice and the counter disagree",
                        )
                    }
                }
            }
        }
    }

    /**
     * `sensorCounts` is the same answer read one member at a time.
     *
     * Two statements of one rule are two rules, so the boolean the app reads
     * per sample is pinned against the set rather than computed beside it.
     */
    @Test
    fun `the per-sample question agrees with the set for every shape`() {
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (imu in listOf(false, true)) {
                        assertEquals(
                            SetVoiceGuide.SENSOR_COUNT in
                                SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, imu),
                            SetVoicePolicy.sensorCounts(hasTempo, isTimed, kind, imu),
                            "$kind tempo=$hasTempo timed=$isTimed imu=$imu",
                        )
                    }
                }
            }
        }
    }

    /**
     * Every shape a set can have, answered from a table written out by hand.
     *
     * Thirty-two rows -- four [ExerciseKind]s against tempo, timed and sensor
     * -- and since #262 removed demo mode that is the whole input space rather
     * than a slice of it.
     *
     * They are literals on purpose. An expectation re-derived from
     * [LeadInPolicy.prepCase] and the sensor term would agree with the
     * implementation by construction, and could not catch the sensor term
     * losing a clause.
     *
     * The rows are the ones #262's c0 pinned before the removal, minus the
     * argument, and that is the evidence the removal preserved behaviour: the
     * same thirty-two expectations, measured green on both sides of it.
     */
    @Test
    fun `every shape answers from a table written out by hand`() {
        SHAPE_TABLE.forEach { (shape, expected) ->
            assertEquals(
                expected,
                SetVoicePolicy.guidesFor(
                    shape.hasTempo,
                    shape.isTimed,
                    shape.kind,
                    shape.imuConnected,
                ),
                "$shape",
            )
        }
        assertEquals(
            ExerciseKind.entries.size * 2 * 2 * 2,
            SHAPE_TABLE.size,
            "the table stopped covering every shape",
        )
        assertEquals(SHAPE_TABLE.size, SHAPE_TABLE.map { it.first }.toSet().size, "a shape is listed twice")
    }

    /** One row of the hand-written table: everything a set's voice depends on. */
    private data class Shape(
        val kind: ExerciseKind,
        val hasTempo: Boolean,
        val isTimed: Boolean,
        val imuConnected: Boolean,
    )

    private companion object {
        private val CUED = setOf(SetVoiceGuide.CUED_CADENCE)
        private val TIMED = setOf(SetVoiceGuide.TIMED_CLOCK)
        private val SENSOR = setOf(SetVoiceGuide.SENSOR_COUNT)
        private val SILENT = emptySet<SetVoiceGuide>()

        private val SHAPE_TABLE: List<Pair<Shape, Set<SetVoiceGuide>>> =
            listOf(
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = false) to SILENT,
                // #286: the straight-reps set with a sensor connected. This
                // row read SILENT, which was true of the shipped app.
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = true) to SENSOR,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = false, imuConnected = false) to CUED,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = false, imuConnected = true) to CUED,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.DYNAMIC, hasTempo = true, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = false, imuConnected = false) to SILENT,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = false, imuConnected = true) to SILENT,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.HOLD, hasTempo = false, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = false, imuConnected = false) to CUED,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = false, imuConnected = true) to CUED,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.HOLD, hasTempo = true, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = false, imuConnected = false) to SILENT,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = false, imuConnected = true) to SILENT,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.CARRY, hasTempo = false, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = false, imuConnected = false) to CUED,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = false, imuConnected = true) to CUED,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.CARRY, hasTempo = true, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = false, imuConnected = false) to SILENT,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = false, imuConnected = true) to SENSOR,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = false, isTimed = true, imuConnected = true) to TIMED,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = false, imuConnected = false) to SILENT,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = false, imuConnected = true) to SENSOR,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = true, imuConnected = false) to TIMED,
                Shape(ExerciseKind.EXPLOSIVE, hasTempo = true, isTimed = true, imuConnected = true) to TIMED,
            )
    }
}
