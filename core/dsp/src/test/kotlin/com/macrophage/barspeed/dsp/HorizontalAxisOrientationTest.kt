package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a pre-rep twitch does to a horizontal-plane set, and why the corpus
 * cannot show it.
 *
 * The horizontal principal axis comes out of the covariance with an arbitrary
 * sign. `SetAnalyzer.orient` resolves it by asking which way the FIRST
 * non-still run points, so any movement before the first rep decides the
 * orientation of the whole set. Issue 28.
 *
 * ## Nothing looks wrong when it fires
 *
 * The published velocity is NOT inverted in sign. `concentricSign` is +1 for
 * horizontal work, so the flip swaps which run is which rather than negating
 * the reported number: the figures stay positive and plausible. **The rep count
 * is identical either way**, so the obvious check misses it entirely.
 *
 * What actually changes is that the eccentric and the concentric swap outright.
 * Below, a 1 s drive is reported as 1.87 s and a 2 s return as 0.96 s. Velocity
 * and power then describe the return stroke and roughly halve. Tempo compliance
 * grades each digit against the wrong stroke, which reaches the lifter through
 * a different surface than velocity does.
 *
 * ## Why this is synthetic
 *
 * No committed capture can exercise it. Reaching `orient` needs
 * `plane == HORIZONTAL` AND `!sensorOnStack`, and nothing committed declares
 * the first. The three captures of horizontal exercises --
 * `field-cablerow-static-8rep`, `field-facepull-static-12rep` and
 * `field-pallof-static-12rep` -- are scored everywhere on this classpath as
 * `LiftDirection(startsWith = CONCENTRIC)`, whose `plane` defaults to VERTICAL
 * and whose `sensorOnStack` defaults to FALSE.
 *
 * The sentence that stood here said those three "are all stack-mounted". It is
 * deleted rather than reworded: no committed byte declares that either, as
 * `BatchCueCoverageTest` sets out at length -- issue #72's own table calls the
 * three cable sets stack-mounted and the classpath does not. The mount is not
 * what disarms this defect on the committed corpus; the undeclared plane is.
 *
 * Forced through the horizontal path they produce NO movement runs at all,
 * because vertical acceleration exceeds the best horizontal axis by 20x to
 * 44x. That ratio is pinned below.
 *
 * So these samples are fabricated, and nothing here should be read as evidence
 * about a real set. No fix is fitted to them for exactly that reason: a
 * size threshold chosen from this file would be a constant calibrated against
 * the only data that exists, which is a mistake this repository has made
 * before.
 */
class HorizontalAxisOrientationTest {
    private val dt = 0.01

    private val horizontalHandle = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = false,
    )

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** A level sensor travelling along world x; `axG` carries all the motion. */
    private fun fromVelocity(v: List<Double>): List<ImuSample> {
        val g = 9.80665
        return v.indices.map { i ->
            val a = if (i + 1 < v.size) (v[i + 1] - v[i]) / dt else 0.0
            ImuSample(
                timestampMs = (i * dt * 1000).toLong(),
                axG = a / g, ayG = 0.0, azG = 1.0,
                wxDps = 0.0, wyDps = 0.0, wzDps = 0.0,
                rollDeg = 0.0, pitchDeg = 0.0, yawDeg = 0.0,
            )
        }
    }

    private fun halfSine(romM: Double, durS: Double, sign: Double): List<Double> {
        val n = (durS / dt).toInt()
        val peak = romM * PI / (2.0 * durS)
        return (0 until n).map { sign * peak * sin(PI * it * dt / durS) }
    }

    private fun still(durS: Double) = List((durS / dt).toInt()) { 0.0 }

    /** Six reps of a 0.40 m drive over 1 s and a 0.40 m return over 2 s. */
    private fun sixReps(leadingTwitchM: Double): List<ImuSample> {
        val v = mutableListOf<Double>()
        v += still(2.0)
        if (leadingTwitchM > 0.0) {
            v += halfSine(leadingTwitchM, 0.4, -1.0)
            v += still(1.0)
        }
        repeat(6) {
            v += halfSine(0.40, 1.0, +1.0)
            v += still(0.5)
            v += halfSine(0.40, 2.0, -1.0)
            v += still(0.5)
        }
        v += still(2.0)
        return fromVelocity(v)
    }

    @Test
    fun `a clean start orients the axis from the first drive`() {
        val a = SetAnalyzer.analyze(sixReps(0.0), horizontalHandle, loadKg = 50.0)
        assertEquals(6, a.reps.size, "reps segmented")
        assertEquals(0.96, a.reps.map { it.conS }.average(), 5e-3, "drive seconds, prescribed 1.0")
        assertEquals(1.87, a.reps.first().eccS!!, 5e-3, "return seconds, prescribed 2.0")
        assertEquals(0.412, a.reps.map { it.meanConVelMps }.average(), 5e-3, "mean drive velocity")
        assertEquals(314.0, a.reps.first().peakPowerW!!, 0.05, "peak power, W")
    }

    @Test
    fun `a three-centimetre settle before the first rep inverts the whole set`() {
        // The defect. Same six reps, preceded by a 0.03 m movement the other
        // way, which is what a lifter settling into the seat produces.
        val a = SetAnalyzer.analyze(sixReps(0.03), horizontalHandle, loadKg = 50.0)

        // The count is IDENTICAL. Nothing about the number of reps says a thing
        // went wrong, which is why this cannot be evaluated by rep count.
        assertEquals(6, a.reps.size, "reps segmented -- unchanged")

        // The phases have swapped. A 1 s drive is now reported as 1.87 s and a
        // 2 s return as 0.96 s, so tempo compliance grades each prescribed
        // digit against the opposite stroke.
        assertEquals(1.87, a.reps.map { it.conS }.average(), 5e-3, "drive seconds now hold the return")
        assertEquals(0.96, a.reps.first().eccS!!, 5e-3, "return seconds now hold the drive")

        // And the published figures stay POSITIVE and plausible. They describe
        // the return stroke, so they roughly halve, but nothing about their
        // sign or magnitude looks like an error.
        val meanVel = a.reps.map { it.meanConVelMps }.average()
        assertEquals(0.212, meanVel, 5e-3, "mean 'drive' velocity is the return's")
        assertTrue(meanVel > 0.0, "still positive, so nothing looks wrong")
        assertEquals(154.2, a.reps.first().peakPowerW!!, 0.05, "peak power, roughly halved")
    }

    @Test
    fun `the committed horizontal captures cannot reach this code at all`() {
        // Why the two tests above are synthetic. These three sets are the only
        // horizontal-plane exercises in the corpus and every one is
        // stack-mounted, so measuredPlane resolves to VERTICAL and orient never
        // runs. Forced onto the horizontal axis they yield no movement at all.
        val c = DspConfig()
        listOf(
            "field-cablerow-static-8rep" to 44.0,
            "field-facepull-static-12rep" to 21.0,
            "field-pallof-static-12rep" to 20.0,
        ).forEach { (fixture, minRatio) ->
            val samples = load(fixture)
            val horizontal = samples.map { FrameTransform.horizontalLinearAccelMps2(it, c.gravityMps2) }
            val (ux, uy) = VelocityEstimator.principalHorizontalAxis(horizontal)

            fun sd(v: List<Double>): Double {
                val mean = v.average()
                return kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
            }

            val onAxis = sd(horizontal.map { it.first * ux + it.second * uy })
            val vertical = sd(samples.map { FrameTransform.verticalLinearAccelMps2(it, c.gravityMps2) })
            assertTrue(
                vertical / onAxis >= minRatio,
                "$fixture: vertical/horizontal signal ratio ${vertical / onAxis}, expected >= $minRatio",
            )

            val raw = VelocityEstimator.estimate(samples, c, MovementPlane.HORIZONTAL)
            val moves = RepSegmenter.classifyRuns(raw, RunThresholds.sensorFrame(c)).count { it.type != RunType.STILL }
            assertEquals(0, moves, "$fixture: movement runs found on the horizontal axis")
        }
    }

    @Test
    fun `the axis is oriented in the sensor's frame, whatever ratio is declared`() {
        // orient() classifies the series BEFORE mappedToLifter is applied, so
        // it is the one place in the segmenter that must keep the run limits
        // unconverted. Issue #70's fix converts them for the two callers that
        // see a MAPPED series and leaves this one alone; nothing pinned that
        // until mutation M9 converted it here too and the whole suite stayed
        // green.
        //
        // The differential is the twitch. At `travelRatio` 3.0 a converted
        // startThresholdMps would be 0.30 m/s, above the 0.03 m settle's peak,
        // so the settle would stop deciding the orientation and the set would
        // come out the right way round -- a correct-looking answer produced by
        // reading the wrong frame. The set is inverted here and must STAY
        // inverted at every ratio.
        listOf(0.25, 0.5, 1.0, 2.0, 3.0).forEach { ratio ->
            val a = SetAnalyzer.analyze(sixReps(0.03), horizontalHandle.copy(travelRatio = ratio), loadKg = 50.0)
            assertEquals(6, a.reps.size, "ratio $ratio: reps segmented")
            assertEquals(1.87, a.reps.map { it.conS }.average(), 5e-3, "ratio $ratio: drive seconds hold the return")
            assertEquals(0.96, a.reps.first().eccS!!, 5e-3, "ratio $ratio: return seconds hold the drive")
        }
    }

    @Test
    fun `a stack-mounted horizontal exercise is measured vertically, which disarms this`() {
        // The reason the defect is hard to reach: declaring the plane honestly
        // for real cable work also declares the mount, and the mount wins.
        val declaredHonestly = LiftDirection(plane = MovementPlane.HORIZONTAL, sensorOnStack = true)
        assertEquals(MovementPlane.VERTICAL, declaredHonestly.measuredPlane, "the mount decides the axis")
        assertEquals(MovementPlane.HORIZONTAL, horizontalHandle.measuredPlane, "handle-mounted reaches it")
    }
}
