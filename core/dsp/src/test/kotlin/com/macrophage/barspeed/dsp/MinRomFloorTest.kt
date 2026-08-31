package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [DspConfig.minRomM] is actually holding back, as opposed to what its
 * name suggests.
 *
 * The floor is documented as a bump-and-re-rack filter whose pressure is
 * downward, because low sample rates attenuate measured ROM. On the three
 * cue-tracked leg-curl captures it is doing something else: it is the only gate
 * stopping reps whose displacement reconstruction has failed outright.
 *
 * These pins exist so that lowering the floor -- the obvious move against an
 * under-count, and issue 94 IS an under-count -- reds something first.
 *
 * ## What cannot be pinned here
 *
 * That admitting these runs would put bad velocity on the screen. No test on
 * the CI path renders anything, here or in `:app`. What is pinned is the
 * input to that consequence: the runs exist, they clear every other gate, and
 * their displacement is a small fraction of the same set's batch ROM.
 */
class MinRomFloorTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val captures = listOf(
        "field-legcurl-1030-12rep",
        "field-legcurl-1030-12rep-b",
        "field-legcurl-1030-12rep-c",
    )

    /** Displacement of each drive run, paired with whether it cleared the floor. */
    private fun driveRuns(fixture: String, c: DspConfig): List<Pair<Double, Boolean>> {
        val samples = load(fixture)
        val tracker = StreamingSetTracker.forLift(legCurl, c)
        val dt = 1.0 / VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        var type = 0
        var displacement = 0.0
        var n = 0
        var peak = 0.0
        val out = mutableListOf<Pair<Double, Boolean>>()
        samples.forEach { sample ->
            val v = tracker.feed(sample).velocityMps
            val k = if (v > c.pauseBandMps) 1 else if (v < -c.pauseBandMps) -1 else 0
            if (k == type) {
                if (k != 0) {
                    displacement += abs(v) * dt
                    n++
                    peak = maxOf(peak, abs(v))
                }
                return@forEach
            }
            val isDrive = (type == 1) == legCurl.driveIsPositive
            val clearsOthers = peak >= c.startThresholdMps && n * dt >= c.minPhaseS &&
                displacement <= c.maxRunDisplacementM
            if (type != 0 && isDrive && clearsOthers) {
                out += displacement to (displacement >= c.minRomM)
            }
            type = k
            displacement = if (k != 0) abs(v) * dt else 0.0
            n = if (k != 0) 1 else 0
            peak = if (k != 0) abs(v) else 0.0
        }
        return out
    }

    private fun blocked(c: DspConfig) = captures.flatMap { driveRuns(it, c) }.filterNot { it.second }.map { it.first }

    private fun admitted(c: DspConfig) = captures.flatMap { driveRuns(it, c) }.filter { it.second }.map { it.first }

    private fun batchMedianRom(fixture: String, c: DspConfig): Double {
        val roms = SetAnalyzer.analyze(load(fixture), legCurl, loadKg = 20.0, config = c)
            .reps
            .map { it.romM }
            .sorted()
        return roms[roms.size / 2]
    }

    @Test
    fun `the floor is the only gate stopping eight reconstructed-short drives`() {
        val c = DspConfig()
        val stopped = blocked(c)
        assertEquals(8, stopped.size, "drive runs stopped only by minRomM")
        assertEquals(0.031, stopped.min(), 5e-3, "smallest, metres")
        assertEquals(0.067, stopped.max(), 5e-3, "largest, metres")
    }

    @Test
    fun `what they are blocked against is a seventh to a fifteenth of the truth`() {
        // The batch path resolves these sets on the leg curl's known 0.4-0.5 m
        // rail, so it is an honest reference for how far the lifter moved.
        val c = DspConfig()
        val reference = captures.map { batchMedianRom(it, c) }
        reference.forEach { assertTrue(it in 0.35..0.55, "batch median ROM off the rail: $it") }
        val median = reference.sorted()[reference.size / 2]
        assertEquals(0.489, median, 5e-3, "batch median rep ROM across the three, metres")

        val stopped = blocked(c)
        assertTrue(stopped.max() / median < 0.15, "largest blocked run is under 15% of truth")
        assertTrue(stopped.min() / median > 0.05, "smallest is over 5%, so none is pure noise")
    }

    @Test
    fun `the floor separates two populations rather than cutting a continuum`() {
        // Nothing counted sits between the largest blocked run and the smallest
        // admitted one. That gap is why 0.10 works, and nothing guarantees it
        // stays open -- which is the whole reason this file exists.
        val c = DspConfig()
        val stopped = blocked(c)
        val kept = admitted(c)
        assertEquals(0.101, kept.min(), 5e-3, "smallest drive run that does count, metres")
        assertTrue(stopped.max() < kept.min(), "the two populations do not overlap")
        assertTrue(kept.min() - stopped.max() > 0.03, "and the gap is not marginal")
    }

    @Test
    fun `lowering the floor changes the reconstruction, not just the gate`() {
        // The move this file exists to stop, and it is worse than it looks.
        //
        // minRomM is not only a rep gate. VelocityEstimator.anchorAcceptable
        // derives both of its caps from it and startThresholdMps, so the floor
        // is an input to the DRIFT CORRECTION -- lowering it changes which quiet
        // windows are accepted as zero-velocity anchors, which changes the
        // velocity series, which changes every run formed from it.
        //
        // So this is not "the same runs, one gate relaxed". At 0.03 m nothing
        // is stopped by the floor, and the admitted set is 30 rather than the
        // 31 that simply relaxing a gate would give: the reconstruction itself
        // moved underneath. Anyone lowering this to recover missed reps is
        // altering the measurement they were trying to fix.
        val shipped = DspConfig()
        val lowered = DspConfig(minRomM = 0.03)
        assertEquals(0, blocked(lowered).size, "nothing is stopped by a 0.03 m floor")
        assertEquals(23, admitted(shipped).size, "drive runs admitted at the shipped floor")
        assertEquals(30, admitted(lowered).size, "and at 0.03 -- not 31, because the series moved")
    }
}
