package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens to a drive-down lift when nothing declares its geometry.
 *
 * `ExerciseDef.concentricUp` defaults to true and is never inferred from the id.
 * Issue 96 was filed on the reading that this leaves `pairConcentricFirst`
 * scanning for `direction.concentricRun`, which stays UP, so the real opening
 * stroke would be structurally dropped from pairing. That reading was marked
 * UNVERIFIED and it is WRONG. These pins are the measurement.
 *
 * ## The default is accidentally correct, by a cancellation
 *
 * A stack-mounted leg curl declares two flips: the drive goes down
 * (`concentricUp = false`) and the sensor rides the stack, which moves the
 * other way (`sensorInverted = true`). They cancel. Leaving BOTH at their
 * defaults gives the same net geometry as declaring both, and the batch
 * analyzer produces bit-identical reps either way -- same count, same ROM, same
 * concentric velocity, same eccentric resolution, on all three captures.
 *
 * ## Declaring one half is destructive, and this is the reverted experiment
 *
 * `ExerciseDef`'s KDoc records that inferring direction from the id "collapsed
 * rep detection from 12 to 2" on a seated leg curl. That number has been an
 * anecdote in a comment. The third test below reproduces it: declaring
 * `concentricUp = false` WITHOUT the mount takes 12, 13, 11 reps to 5, 5, 7.
 *
 * The hazard is semantic rather than lexical. The note predates the move from
 * substring hint matching to whole-token matching (`e199119`, then `d9bdb2f`
 * two days later), so it is tempting to read it as an argument against a sloppy
 * matcher. It is not: a closed, token-matched, overridable table would produce
 * exactly the same collapse, because what breaks is declaring one half of a
 * cancelling pair.
 *
 * ## The inversion worth knowing before reasoning about declarations
 *
 * Before issue 102, DECLARING THE GEOMETRY CORRECTLY IS WHAT MADE THE LIVE PATH
 * WRONG. Simulated by forcing the pre-102 behaviour, the declared configuration
 * counted 10, 9, 11 on these captures -- the return stroke -- while the
 * all-defaults fallback counted 8, 6, 9, which is the drive. Issue 102 made the
 * declared path agree with a fallback that had been right all along. Both 96
 * and 102 were filed on the opposite intuition.
 */
class GeometryFallbackTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private val captures = listOf(
        "field-legcurl-1030-12rep",
        "field-legcurl-1030-12rep-b",
        "field-legcurl-1030-12rep-c",
    )

    /** Both halves declared, as LegCurlCueTrackTest declares them. */
    private val declared = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    /**
     * Neither half declared. `inferStartPhase` returns CONCENTRIC for any id
     * naming "curl", so this is what a plain `seated_leg_curl` resolves to with
     * no plan overrides at all.
     */
    private val fallback = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    /** Direction declared, mount not: the shape the reverted inference produced. */
    private val directionOnly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
    )

    private fun batch(d: LiftDirection) = captures.map { SetAnalyzer.analyze(load(it), d, loadKg = 20.0) }

    @Test
    fun `declaring both halves and declaring neither are the same analysis`() {
        // Not merely the same rep count: the same reps.
        batch(declared).zip(batch(fallback)).forEachIndexed { i, (a, b) ->
            val name = captures[i]
            assertEquals(a.reps.size, b.reps.size, "$name: rep count")
            assertEquals(a.reps.map { it.romM }, b.reps.map { it.romM }, "$name: ROM per rep")
            assertEquals(
                a.reps.map { it.meanConVelMps },
                b.reps.map { it.meanConVelMps },
                "$name: mean concentric velocity per rep",
            )
            assertEquals(
                a.reps.count { it.eccS != null },
                b.reps.count { it.eccS != null },
                "$name: reps that resolved an eccentric",
            )
        }
        assertEquals(listOf(12, 13, 11), batch(declared).map { it.reps.size }, "against 12 performed each")
    }

    @Test
    fun `the live path cancels the same way`() {
        // After issue 102 the live tracker consumes driveIsPositive, so the
        // cancellation has to hold in both paths or the two would disagree
        // about the same set.
        captures.forEach { capture ->
            fun live(d: LiftDirection): Pair<Int, List<Double>> {
                val tracker = StreamingSetTracker.forLift(d)
                var last = LiveSetState()
                load(capture).forEach { last = tracker.feed(it) }
                return last.repCount to last.repMeanVelocities
            }
            assertEquals(live(declared), live(fallback), "$capture: declared against fallback")
        }
        assertEquals(
            listOf(8, 6, 9),
            captures.map { c ->
                val tracker = StreamingSetTracker.forLift(declared)
                var last = LiveSetState()
                load(c).forEach { last = tracker.feed(it) }
                last.repCount
            },
            "live counts on the three captures",
        )
    }

    @Test
    fun `declaring the direction without the mount collapses rep detection`() {
        // The reverted experiment, reproducible instead of anecdotal. This is
        // what any id-based inference of concentricUp would do, however
        // carefully its ids were matched, because it declares one half of a
        // cancelling pair.
        assertEquals(listOf(12, 13, 11), batch(declared).map { it.reps.size }, "both halves declared")
        assertEquals(listOf(12, 13, 11), batch(fallback).map { it.reps.size }, "neither declared")
        // 5, 5, 7 until issue #125. Two of those detections are refused by
        // RepRefusal, which is the only pin in this file that rule moves, and
        // it moves it in the direction this test is about: the degraded
        // geometry produces a reconstruction whose ranges disagree with each
        // other by more than 4.5x, and the collapse this test names is what
        // produces them. The declared and fallback figures above are
        // untouched, which is the claim that matters here.
        assertEquals(listOf(4, 4, 7), batch(directionOnly).map { it.reps.size }, "direction only")
    }
}
