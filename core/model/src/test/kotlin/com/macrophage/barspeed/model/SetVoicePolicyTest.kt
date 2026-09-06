package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who speaks on a set of each shape.
 *
 * The set of shapes is exhaustive: every [ExerciseKind] against tempo, timed,
 * demo and sensor.
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
        demoMode: Boolean = false,
        imuConnected: Boolean = true,
    ) = SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, demoMode, imuConnected)

    /** A tempo'd rep-based lift is paced by the metronome and by nothing else. */
    @Test
    fun `a tempo'd dynamic set is guided by the cadence alone`() {
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true))
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true, imuConnected = false))
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true, kind = ExerciseKind.HOLD))
    }

    /**
     * An untempo'd rep-based lift with a sensor says nothing.
     *
     * The bar sensor is record-only there: the lifter counts, and the app's
     * counter stays out of it.
     */
    @Test
    fun `an untempo'd dynamic set has no voice at all`() {
        assertEquals(emptySet(), guides())
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

    /** Demo mode drives the stream itself and shows the live counter working. */
    @Test
    fun `demo mode is counted by the sensor path it is demonstrating`() {
        assertEquals(setOf(SetVoiceGuide.SENSOR_COUNT), guides(demoMode = true))
    }

    /** A tempo'd demo set is still paced: the cadence wins over the demo counter. */
    @Test
    fun `a tempo'd demo set is guided by the cadence`() {
        assertEquals(setOf(SetVoiceGuide.CUED_CADENCE), guides(hasTempo = true, demoMode = true))
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
     */
    @Test
    fun `a timed set is guided by its clock alone`() {
        assertEquals(setOf(SetVoiceGuide.TIMED_CLOCK), guides(isTimed = true, kind = ExerciseKind.HOLD))
        assertEquals(setOf(SetVoiceGuide.TIMED_CLOCK), guides(isTimed = true, kind = ExerciseKind.CARRY))
        assertEquals(
            setOf(SetVoiceGuide.TIMED_CLOCK),
            guides(isTimed = true, hasTempo = true, kind = ExerciseKind.HOLD),
        )
        assertEquals(setOf(SetVoiceGuide.TIMED_CLOCK), guides(isTimed = true, demoMode = true))
    }

    /**
     * No set is guided by two voices at once.
     *
     * The whole contract, over every shape: kind against tempo, timed, demo
     * and sensor. Two voices counting different quantities in overlapping
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
                    for (demo in listOf(false, true)) {
                        for (imu in listOf(false, true)) {
                            val g = SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, demo, imu)
                            assertEquals(
                                true,
                                g.size <= 1,
                                "$kind tempo=$hasTempo timed=$isTimed demo=$demo imu=$imu speaks with $g",
                            )
                        }
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
                    for (demo in listOf(false, true)) {
                        for (imu in listOf(false, true)) {
                            assertEquals(
                                SetVoiceGuide.SENSOR_COUNT in
                                    SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, demo, imu),
                                SetVoicePolicy.sensorCounts(hasTempo, isTimed, kind, demo, imu),
                                "$kind tempo=$hasTempo timed=$isTimed demo=$demo imu=$imu",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * CHARACTERIZATION, issue #262. Every shape with demo mode OFF, written out
     * rather than computed, so the removal of the parameter can be checked
     * against a table that does not move with the code.
     *
     * Thirty-two rows: four [ExerciseKind]s against tempo, timed and sensor.
     * They are literals on purpose -- a expectation re-derived from
     * `LeadInPolicy.prepCase` and the sensor term would agree with the
     * implementation by construction and could not catch the collapse of
     * `demoMode || (kind == EXPLOSIVE && imuConnected)` going wrong.
     *
     * This is the whole behaviour that must survive #262, because demo mode is
     * the only other input and after the removal there is no other value for
     * it to take.
     */
    @Test
    fun `every non-demo shape answers from a table written out by hand`() {
        NON_DEMO_TABLE.forEach { (shape, expected) ->
            assertEquals(
                expected,
                SetVoicePolicy.guidesFor(
                    shape.hasTempo,
                    shape.isTimed,
                    shape.kind,
                    false,
                    shape.imuConnected,
                ),
                "$shape",
            )
        }
        assertEquals(
            ExerciseKind.entries.size * 2 * 2 * 2,
            NON_DEMO_TABLE.size,
            "the table stopped covering every shape",
        )
        assertEquals(NON_DEMO_TABLE.size, NON_DEMO_TABLE.map { it.first }.toSet().size, "a shape is listed twice")
    }

    /** One row of the hand-written table: a set shape with demo mode out of it. */
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

        private val NON_DEMO_TABLE: List<Pair<Shape, Set<SetVoiceGuide>>> =
            listOf(
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = false) to SILENT,
                Shape(ExerciseKind.DYNAMIC, hasTempo = false, isTimed = false, imuConnected = true) to SILENT,
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
