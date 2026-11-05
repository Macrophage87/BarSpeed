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
 * sign. `SetAnalyzer.orient` resolves it by asking which way the first
 * movement run points, and until issue 28's fix that was the FIRST non-still
 * run of any size, so any movement before the first rep decided the
 * orientation of the whole set. The fix asks the deciding run to clear
 * `DspConfig.minRomM` as well -- the floor the pairing stage already uses to
 * call a run a phase -- so a sub-rep settle no longer decides it.
 *
 * ## Nothing looked wrong when it fired
 *
 * The published velocity is NOT inverted in sign. `concentricSign` is +1 for
 * horizontal work, so the flip swaps which run is which rather than negating
 * the reported number: the figures stay positive and plausible. **The rep count
 * is identical either way**, so the obvious check misses it entirely.
 *
 * What actually changes when it fires is that the eccentric and the
 * concentric swap outright. A 1 s drive was reported as 1.87 s and a 2 s
 * return as 0.96 s, and velocity and power then described the return stroke
 * and roughly halved. Tempo compliance graded each digit against the wrong
 * stroke, which reaches the lifter through a different surface than velocity
 * does. A settle of a rep's worth still produces all of this, below.
 *
 * ## Why this is synthetic
 *
 * No committed capture can exercise it. Reaching `orient` needs
 * `plane == HORIZONTAL` AND `!sensorOnStack`, and no committed CAPTURE is
 * scored with both. "Nothing committed declares the first" stood here and is
 * deleted: `plane = MovementPlane.HORIZONTAL` is written at sites all over
 * this classpath -- `LiftDirectionTest`, `CueTrackOriginTest`,
 * `PhaseTempoTargetTest`, `TempoScheduleTest` and this file among them -- and
 * every one is a hand-built `LiftDirection` in a test, over synthetic samples
 * or with `sensorOnStack = true`. What no committed CAPTURE has is a scored
 * geometry carrying both halves. The three captures of
 * horizontal exercises --
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
 * about a real set. That is why no threshold is FITTED to them: "No fix is
 * fitted to them" stood here, and the fix that has since landed fits nothing
 * either. It reuses `minRomM`, a constant this file did not choose, at the
 * value it already holds for pairing. What this file can show is that the
 * 3 cm settle stops deciding the set; whether a real handle-mounted settle is
 * smaller than 0.10 m is a `[Field]` question no committed capture answers,
 * and `a settle of a rep's worth still decides the axis` pins what the gate
 * cannot do.
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
    fun `a three-centimetre settle before the first rep no longer inverts the set`() {
        // Issue 28. Same six reps, preceded by a 0.03 m movement the other
        // way, which is what a lifter settling into the seat produces.
        //
        // Before the fix this set came out INVERTED with an identical rep
        // count -- the drive read 1.87 s, the return 0.96 s, mean "drive"
        // velocity 0.212 m/s and peak power 154.2 W, all positive and
        // plausible, so nothing looked wrong. Those figures are kept here as
        // the record of what the defect published; the assertions are now the
        // clean start's, to the digit.
        val a = SetAnalyzer.analyze(sixReps(0.03), horizontalHandle, loadKg = 50.0)
        assertEquals(6, a.reps.size, "reps segmented")
        assertEquals(0.96, a.reps.map { it.conS }.average(), 5e-3, "drive seconds, prescribed 1.0")
        assertEquals(1.87, a.reps.first().eccS!!, 5e-3, "return seconds, prescribed 2.0")
        val meanVel = a.reps.map { it.meanConVelMps }.average()
        assertEquals(0.412, meanVel, 5e-3, "mean drive velocity")
        assertTrue(meanVel > 0.0, "positive either way, which is why the sign never showed the defect")
        assertEquals(314.0, a.reps.first().peakPowerW!!, 0.05, "peak power, W")
    }

    /**
     * A settle of a REP'S WORTH still decides the axis, and this is pinned so
     * that no reading of issue 28's fix can claim otherwise.
     *
     * The hardening issue 28 proposes asks the deciding run to clear
     * `DspConfig.minRomM`, 0.10 m -- the floor the pairing stage already uses
     * to call a run a phase. A 12 cm movement the other way clears it, so it
     * is a real phase by every rule this segmenter has, and it orients the
     * set: the eccentric of the first rep holds the 0.96 s drive. Nothing
     * declared distinguishes a 12 cm settle from a 12 cm rep, which is why no
     * absolute displacement floor can; a set-relative test -- the deciding
     * run against the set's own runs -- could separate this synthetic case,
     * and was not tried, because its fraction could only be fitted to these
     * synthetic samples. The settle is counted as a seventh rep here as
     * well, so the count moves on this one -- unlike the 3 cm case.
     *
     * Synthetic, like everything in this file.
     */
    @Test
    fun `a settle of a rep's worth still decides the axis`() {
        val a = SetAnalyzer.analyze(sixReps(0.12), horizontalHandle, loadKg = 50.0)
        assertEquals(7, a.reps.size, "reps segmented -- the settle is one of them")
        assertEquals(0.96, a.reps.first().eccS!!, 5e-3, "the first rep's return seconds hold a drive")
        assertEquals(1.657, a.reps.map { it.conS }.average(), 5e-3, "drive seconds, mostly the return's")
    }

    @Test
    fun `the committed horizontal captures cannot reach this code at all`() {
        // Why the two tests above are synthetic. These three captures of
        // horizontal exercises are scored everywhere on this classpath as
        // LiftDirection(startsWith = CONCENTRIC), whose plane defaults to
        // VERTICAL, so orient never runs. Forced onto the horizontal axis they
        // yield no movement at all.
        //
        // "every one is stack-mounted" stood here and is deleted, not
        // reworded: no committed byte declares the mount for any of the three.
        // The commit "Name the plane and the frame the live caller does not
        // share" said deleting the same claim from this file's class KDoc left
        // one statement standing; it left two, and this was the other.
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
        // The differential is the twitch, and issue 28's fix moved which end
        // of the ratio range carries it. Before the fix the settle decided
        // the set at every ratio and a converted startThresholdMps at ratio
        // 3.0 (0.30 m/s) would have stopped it doing so. Now the settle is
        // held out by `minRomM`, and a CONVERTED floor at ratio 0.25 is
        // 0.025 m -- under the 0.03 m settle -- so reading the wrong frame
        // there would let the settle decide again and invert the set. The set
        // comes out the right way round here and must do so at every ratio.
        listOf(0.25, 0.5, 1.0, 2.0, 3.0).forEach { ratio ->
            val a = SetAnalyzer.analyze(sixReps(0.03), horizontalHandle.copy(travelRatio = ratio), loadKg = 50.0)
            assertEquals(6, a.reps.size, "ratio $ratio: reps segmented")
            assertEquals(0.96, a.reps.map { it.conS }.average(), 5e-3, "ratio $ratio: drive seconds")
            assertEquals(1.87, a.reps.first().eccS!!, 5e-3, "ratio $ratio: return seconds")
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
