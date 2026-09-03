package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who speaks on a set of each shape.
 *
 * Every case here is what `RecordViewModel.beginSet` computed before the
 * decision was lifted out of it, so this class is the record that the lift
 * changed nothing. The set of shapes is exhaustive: every [ExerciseKind]
 * against tempo, timed, demo and sensor.
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
     * A timed set runs its clock -- and, before #217, the sensor counter too.
     *
     * This is the defect as the shipped app computed it, pinned so the lift
     * out of `:app` can be shown to have changed nothing. Both cases the
     * field-37 captures were recorded under are here: a hold with a sensor
     * connected, which is what sets 11 and 12 were.
     */
    @Test
    fun `a timed set is spoken over by the sensor counter as well as its clock`() {
        assertEquals(
            setOf(SetVoiceGuide.TIMED_CLOCK, SetVoiceGuide.SENSOR_COUNT),
            guides(isTimed = true, kind = ExerciseKind.HOLD),
        )
        assertEquals(
            setOf(SetVoiceGuide.TIMED_CLOCK, SetVoiceGuide.SENSOR_COUNT),
            guides(isTimed = true, kind = ExerciseKind.CARRY),
        )
        assertEquals(
            setOf(SetVoiceGuide.TIMED_CLOCK, SetVoiceGuide.SENSOR_COUNT),
            guides(isTimed = true, hasTempo = true, kind = ExerciseKind.HOLD),
        )
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
     * The cadence and the sensor counter never both run.
     *
     * That much of the one-voice contract already held, and is pinned here so
     * a later change to the timed branch cannot break it on the way past.
     */
    @Test
    fun `no set is both cued and sensor counted`() {
        for (kind in ExerciseKind.entries) {
            for (hasTempo in listOf(false, true)) {
                for (isTimed in listOf(false, true)) {
                    for (demo in listOf(false, true)) {
                        for (imu in listOf(false, true)) {
                            val g = SetVoicePolicy.guidesFor(hasTempo, isTimed, kind, demo, imu)
                            assertEquals(
                                false,
                                SetVoiceGuide.CUED_CADENCE in g && SetVoiceGuide.SENSOR_COUNT in g,
                                "$kind tempo=$hasTempo timed=$isTimed demo=$demo imu=$imu",
                            )
                        }
                    }
                }
            }
        }
    }
}
