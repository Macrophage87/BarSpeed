package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.RepCounter
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which lifts the full-cycle counter's FALL rejection guards: the predicate
 * [CycleRule.fallRejectsFor], the clause it switches, and the deadlift family
 * it must leave alone. Issue #335.
 *
 * The clause tests run on drawn frames, 100 Hz, in the drive frame, through
 * the default [DspConfig] -- every figure in them is a property of the shapes,
 * not of a lift. A FALL is five frames whose accelerometer magnitude is 0.1 g,
 * so their mean is under [DspConfig.cycleFallG].
 */
class CycleFallRejectionTest {
    private val eccentricFirst = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val concentricFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /**
     * Every combination of the six other geometry terms, under both start
     * phases: the answer follows [LiftDirection.startsWith] and nothing else.
     */
    @Test
    fun `the rejection follows the phase a lift starts with and no other term`() {
        var combinations = 0
        for (startsWith in StartPhase.entries) {
            for (concentricUp in listOf(true, false)) {
                for (sensorInverted in listOf(true, false)) {
                    for (sensorOnStack in listOf(true, false)) {
                        for (plane in MovementPlane.entries) {
                            for (travelRatio in listOf(1.0, 2.0)) {
                                val direction = LiftDirection(
                                    startsWith = startsWith,
                                    concentricUp = concentricUp,
                                    sensorInverted = sensorInverted,
                                    travelRatio = travelRatio,
                                    plane = plane,
                                    sensorOnStack = sensorOnStack,
                                )
                                assertEquals(
                                    startsWith == StartPhase.CONCENTRIC,
                                    CycleRule.fallRejectsFor(direction),
                                    "$direction",
                                )
                                combinations++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(64, combinations, "geometries checked")
    }

    /**
     * The seeded lifts, through `ExerciseDef.liftDirection()`, the geometry the
     * app hands the live counter when no plan overrides it.
     */
    @Test
    fun `the deadlift keeps the rejection and the squat and bench do not`() {
        val seed = ExerciseDef.SEED.associateBy { it.id }
        assertEquals(
            mapOf(
                "deadlift" to true,
                "overhead_press" to true,
                "barbell_row" to true,
                "back_squat" to false,
                "front_squat" to false,
                "bench_press" to false,
                "romanian_deadlift" to false,
            ),
            listOf(
                "deadlift",
                "overhead_press",
                "barbell_row",
                "back_squat",
                "front_squat",
                "bench_press",
                "romanian_deadlift",
            ).associateWith { CycleRule.fallRejectsFor(seed.getValue(it).liftDirection()) },
        )
    }

    /** Drawn frames, 10 ms apart, from t = 0. */
    private class Stream {
        private val frames = mutableListOf<LiveSetState>()
        private var t = 0.0

        fun hold(untilS: Double, accel: Double, magnitudeG: Double = 1.0): Stream {
            while (t < untilS - 1e-9) {
                frames += LiveSetState(elapsedS = t, accelMps2 = accel, accMagnitudeG = magnitudeG, quiet = false)
                t += STEP_S
            }
            return this
        }

        fun contact(): Stream {
            frames += LiveSetState(elapsedS = t, accelMps2 = 0.0, accMagnitudeG = 5.0, quiet = false)
            t += STEP_S
            return this
        }

        /** The instants [counter] speaks at. */
        fun calls(counter: LiveRepCounter): List<Double> = frames.mapNotNull { live ->
            live.elapsedS.takeIf { counter.feed(live, 0L) is RepCall.Speak }?.let { Math.round(it * 100) / 100.0 }
        }
    }

    /**
     * A drive from 1.0 to 1.5 s, its brake to 2.8 s, and a FALL from 2.0 s --
     * 0.5 s after the drive ended, before the brake that would arm it is over
     * -- then the floor contact at 3.2 s.
     */
    private fun pullWithEarlyFall(): Stream = Stream().hold(1.0, 0.0).hold(1.5, 1.0)
        .hold(2.0, -1.0).hold(2.05, -1.0, magnitudeG = 0.1).hold(2.8, -1.0).hold(3.2, 0.0).contact().hold(4.0, 0.0)

    /** The counter built BY ITS PARTS with the predicate's answer for [direction]. */
    private fun byPredicate(direction: LiftDirection): LiveRepCounter =
        CycleRepCounter(direction, CycleRule(DspConfig(), CycleRule.fallRejectsFor(direction)))

    /**
     * The clause the predicate switches. On a concentric-first lift the early
     * FALL drops the drive and nothing is ever spoken; on an eccentric-first
     * lift it is ignored, the drive arms, and the contact calls it.
     */
    @Test
    fun `an early FALL drops a concentric-first drive and not an eccentric-first one`() {
        assertEquals(emptyList(), pullWithEarlyFall().calls(byPredicate(concentricFirst)), "concentric-first")
        assertEquals(listOf(3.2), pullWithEarlyFall().calls(byPredicate(eccentricFirst)), "eccentric-first")
    }

    /**
     * A CONTACT is not switched. The same pull with the floor contact at
     * 2.0 s in place of the FALL is refused on either lift, so an
     * eccentric-first attempt the bar is back on the floor from inside
     * [DspConfig.cycleMinCycleS] still draws nothing.
     */
    @Test
    fun `an early contact drops the drive whichever phase the lift starts with`() {
        fun pullWithEarlyContact() = Stream().hold(1.0, 0.0).hold(1.5, 1.0).hold(2.0, -1.0).contact()
            .hold(2.8, -1.0).hold(4.0, 0.0)
        assertEquals(emptyList(), pullWithEarlyContact().calls(byPredicate(concentricFirst)), "concentric-first")
        assertEquals(emptyList(), pullWithEarlyContact().calls(byPredicate(eccentricFirst)), "eccentric-first")
    }

    /**
     * THE DEADLIFT FAMILY, through the app's own construction: the counts the
     * full-cycle counter speaks on the eight deadlift sets -- field-43 sets
     * 4-6, then field-44 sets 1-5 -- are 5, 5, 5, 5, 5, 5, 4 and 2, and
     * field-44 set 5's failed third pull (from 17.23 s) draws no number.
     * `CycleLiveCountFieldTest` scores the same calls rep by rep; this is the
     * pin #335 names, because the predicate must not reach these sets.
     */
    @Test
    fun `the app's deadlift live counts are 5, 5, 5, 5, 5, 5, 4 and 2 and the failed pull is silent`() {
        val sets = listOf(
            "field-deadlift-straight-5rep-s43-set04",
            "field-deadlift-straight-5rep-s43-set05",
            "field-deadlift-straight-5rep-s43-set06",
            "field-deadlift-straight-5rep-s44-set01",
            "field-deadlift-straight-5rep-s44-set02",
            "field-deadlift-straight-5rep-s44-set03",
            "field-deadlift-straight-4rep-s44-set04",
            "field-deadlift-straight-2rep-s44-set05",
        )
        val closed = sets.associateWith { fixture ->
            val direction = CandidateCorpus.capture(fixture).direction
            assertEquals(true, CycleRule.fallRejectsFor(direction), "$fixture: the rejection applies")
            val counter = LiveRepCounters.forCounted(RepCounter.SENSOR, direction) as CycleRepCounter
            val tracker = StreamingSetTracker.forLift(direction)
            LiveCountCandidates.load(fixture).mapNotNull { sample ->
                val call = counter.feed(tracker.feed(sample), sample.timestampMs)
                if (call is RepCall.Speak) counter.lastClosed else null
            }
        }
        assertEquals(listOf(5, 5, 5, 5, 5, 5, 4, 2), sets.map { closed.getValue(it).size }, "numbers spoken")
        assertEquals(
            emptyList(),
            closed.getValue(sets.last()).filter { it.driveStartS > 15.0 },
            "a number for the failed pull",
        )
    }

    private companion object {
        const val STEP_S = 0.01
    }
}
