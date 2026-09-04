package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RunawayDrift] on constructed series, where the answer is known by
 * construction rather than by what the corpus happens to do. Issue #94.
 *
 * The corpus scoring lives in [BatchCueCoverageTest]; this file is the
 * mechanism. Two of its assertions are the contract that bounds the change's
 * blast radius -- a series with no runaway is returned as the SAME OBJECT, and
 * the committed captures that contain no runaway are named -- so a reviewer can
 * see which captures the change is even able to touch without re-running
 * anything.
 *
 * [RunawayDrift] is wired in at [VelocityEstimator.estimate], so every figure
 * the batch pipeline publishes runs through it; the corpus scoring of that
 * change is in [BatchCueCoverageTest]. This KDoc claimed the opposite --
 * "NOTHING HERE IS WIRED INTO THE ANALYZER YET" -- which was true when the
 * symbol landed at de30594db0bcb18fe8451944efb95772334807d5 (`Give the
 * segmenter a name for a stretch that is neither phase nor pause`) and false
 * from a4e90d939ba5e25b96771d4098483e89a247196a (`Stop reading a drifted
 * stretch of real reps as one long pause`), where the wiring landed and the
 * sentence was left standing. It is corrected here, not reworded.
 */
class RunawayDriftTest {
    private val config = DspConfig()

    /**
     * A square-wave rep pattern at 100 Hz, [reps] cycles of one second down and
     * one second up at [speedMps], plus a constant [offsetMps] of drift.
     *
     * Every stroke displaces `speedMps` metres in one second, so at 0.5 m/s
     * each clears [DspConfig.minRomM] four times over, lasts five times
     * [DspConfig.minPhaseS] and peaks five times [DspConfig.startThresholdMps].
     * Nothing about the reps is marginal; the only thing being tested is what
     * the offset does to them.
     */
    private fun square(reps: Int, speedMps: Double, offsetMps: Double): VelocitySeries {
        val hz = 100.0
        val perStroke = hz.toInt()
        val n = reps * 2 * perStroke
        val velocity = DoubleArray(n) {
            val down = (it / perStroke) % 2 == 0
            (if (down) -speedMps else speedMps) + offsetMps
        }
        return VelocitySeries(DoubleArray(n) { it / hz }, DoubleArray(n), velocity, hz)
    }

    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    /** The anchored series, before any second-stage correction: see [VelocityEstimator.estimateAnchored]. */
    private val corpus: List<String> by lazy {
        File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter {
                it.startsWith("field-") && it.endsWith(".csv") &&
                    !it.endsWith("-cues.csv") && !it.endsWith("-prep.csv")
            }
            .map { it.removeSuffix(".csv") }
            .sorted()
    }

    @Test
    fun `a series with no run beyond the cap is returned untouched, as the same object`() {
        // The blast-radius contract, and identity rather than equality on
        // purpose: a copy that happens to hold equal numbers would satisfy an
        // equality check while still being a new array the next stage could
        // diverge on. Six clean reps at 0.5 m/s displace 0.5 m per stroke, so
        // no run comes anywhere near the 2.0 m cap.
        val clean = square(reps = 6, speedMps = 0.5, offsetMps = 0.0)
        assertSame(clean, RunawayDrift.corrected(clean, config), "a clean series must not be rebuilt")
        assertEquals(emptyList(), RunawayDrift.runaways(clean.velocityMps, clean.timeS, config), "runaways found")
        assertEquals(
            6,
            RepSegmenter.segment(clean, LiftDirection(StartPhase.ECCENTRIC), config).size,
            "the control resolves its six reps with no correction",
        )
    }

    @Test
    fun `an offset that never lets velocity cross the dead band swallows every rep`() {
        // The defect, constructed. At +0.6 m/s of residual offset the same six
        // reps never come back through the +/-0.03 m/s dead band, so the whole
        // twelve seconds is ONE positive run displacing 7.2 m. The segmenter
        // declares that impossible -- correctly, no phase travels 7.2 m -- and
        // then calls it STILLNESS, which is what loses the reps.
        val drifted = square(reps = 6, speedMps = 0.5, offsetMps = 0.6)
        assertEquals(
            listOf(0..1199),
            RunawayDrift.runaways(drifted.velocityMps, drifted.timeS, config),
            "the whole series is one runaway",
        )
        assertEquals(
            0,
            RepSegmenter.segment(drifted, LiftDirection(StartPhase.ECCENTRIC), config).size,
            "six reps, none resolved, with nothing wrong but an offset",
        )
        // And what the correction recovers. The offset is the run's own mean
        // because the bar returns to where it started every cycle, so removing
        // the mean is removing exactly the drift.
        val fixed = RunawayDrift.corrected(drifted, config)
        assertEquals(
            6,
            RepSegmenter.segment(fixed, LiftDirection(StartPhase.ECCENTRIC), config).size,
            "all six reps come back",
        )
        val clean = square(reps = 6, speedMps = 0.5, offsetMps = 0.0)
        // Tolerance rather than equality: the mean is taken over the trapezoid
        // intervals, which is one interval short of the sample count, so a
        // 1200-sample run recovers the offset to about 4e-4 m/s rather than
        // exactly. That residual is a thirtieth of the dead band and cannot
        // move a classification.
        fixed.velocityMps.indices.forEach { i ->
            assertEquals(clean.velocityMps[i], fixed.velocityMps[i], 1e-3, "sample $i is the undrifted value")
        }
    }

    @Test
    fun `a run beyond the cap that is genuine one-way travel is flattened, not resurrected`() {
        // The counter-case, and the honest limit of the premise. The rule reads
        // a long same-sign run's mean as drift BECAUSE a lift returns to where
        // it started. Hand a run that really is one-way travel -- a constant
        // 0.5 m/s for twelve seconds, six metres in one direction -- and the
        // rule removes all of it and leaves nothing.
        //
        // That is the right answer for a re-rack and the wrong answer for a
        // farmer's carry, and this pipeline has no carry in its corpus to tell
        // them apart. Stated here rather than left for a reader to discover.
        val travel = VelocitySeries(
            DoubleArray(1200) { it / 100.0 },
            DoubleArray(1200),
            DoubleArray(1200) { 0.5 },
            100.0,
        )
        assertEquals(listOf(0..1199), RunawayDrift.runaways(travel.velocityMps, travel.timeS, config))
        val fixed = RunawayDrift.corrected(travel, config)
        assertTrue(fixed.velocityMps.all { kotlin.math.abs(it) < config.pauseBandMps }, "flattened into the dead band")
        assertEquals(
            0,
            RepSegmenter.segment(fixed, LiftDirection(StartPhase.ECCENTRIC), config).size,
            "and resolves no rep, which it also did before the correction",
        )
    }

    @Test
    fun `the iteration is bounded and the corpus needs half the bound`() {
        // Removing a runaway's mean can expose a shorter runaway inside it, so
        // one pass is not always enough and the loop needs a termination
        // guarantee. MAX_PASSES is that guarantee, not a tuning knob: measured
        // over every committed capture, the most any of them needs is four,
        // and that one capture is the worst in the corpus.
        assertEquals(8, RunawayDrift.MAX_PASSES, "the bound")
        val passesNeeded = corpus.associateWith { fixture ->
            val samples = load(fixture)
            var series = VelocityEstimator.estimateAnchored(samples, config, MovementPlane.VERTICAL)
            var passes = 0
            while (RunawayDrift.runaways(series.velocityMps, series.timeS, config).isNotEmpty()) {
                val next = series.velocityMps.copyOf()
                RunawayDrift.runaways(series.velocityMps, series.timeS, config).forEach { run ->
                    val durationS = series.timeS[run.last] - series.timeS[run.first]
                    var net = 0.0
                    for (k in run.first + 1..run.last) {
                        net += series.velocityMps[k] * (series.timeS[k] - series.timeS[k - 1])
                    }
                    val mean = net / durationS
                    for (k in run) next[k] = series.velocityMps[k] - mean
                }
                series = series.copy(velocityMps = next)
                passes++
                if (passes > RunawayDrift.MAX_PASSES) break
            }
            passes
        }
        assertEquals(4, passesNeeded.values.max(), "the most passes any committed capture needs")
        // 37 captures read {0=13, 1=20, 2=2, 3=1, 4=1}. Issue #125 committed
        // field-ohp-3010-8rep-s37-set01 and it needs one pass, so the only
        // bucket that moved is 1.
        assertEquals(
            mapOf(0 to 13, 1 to 21, 2 to 2, 3 to 1, 4 to 1),
            passesNeeded.values.groupingBy { it }.eachCount().toSortedMap(),
            "captures by passes needed",
        )
        assertEquals(38, passesNeeded.size, "committed captures walked")
        assertEquals(
            mapOf(
                "field-ohp-prepinflated-s37-set03" to 3,
                "field-rdl-3010-10rep-s36-set04" to 4,
                "field-rdl-3010-10rep-s36-set05" to 2,
                "field-rdl-wrapping-s36-set05" to 2,
            ),
            passesNeeded.filterValues { it >= 2 },
            "the captures needing more than one pass",
        )
    }

    @Test
    fun `which committed captures contain a runaway at all, and which cannot be touched`() {
        // The other half of the blast-radius contract. A capture with no
        // runaway is bit-identical through the correction, so this list is
        // exactly the set of captures any figure can move on. Measured on the
        // vertical series, which is the plane every capture here IS measured
        // in: LiftDirection.measuredPlane returns VERTICAL for each, either
        // because the capture declares a vertical plane or because it is
        // stack-mounted, which forces vertical whatever plane is declared.
        val untouched = corpus.filter { fixture ->
            val series = VelocityEstimator.estimateAnchored(load(fixture), config, MovementPlane.VERTICAL)
            RunawayDrift.runaways(series.velocityMps, series.timeS, config).isEmpty()
        }
        assertEquals(
            listOf(
                "field-assistedpullup-3010-s37-set08",
                "field-assistedpullup-3010-s37-set10",
                "field-backsquat-10hz-set5",
                "field-bench-rotating-6rep-ok",
                "field-facepull-static-12rep",
                "field-legcurl-1030-12rep",
                "field-legcurl-1030-12rep-b",
                "field-legcurl-1030-12rep-c",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-pallof-static-12rep",
                "field-rdl-3010-10rep",
                "field-ropedeadhang-hold20-s37-set11",
                "field-still-0rep",
            ),
            untouched,
            "captures with no runaway, which the correction cannot change",
        )
        corpus.filter { it !in untouched }.forEach { fixture ->
            val series = VelocityEstimator.estimateAnchored(load(fixture), config, MovementPlane.VERTICAL)
            assertTrue(
                !RunawayDrift.corrected(series, config).velocityMps.contentEquals(series.velocityMps),
                "$fixture has a runaway, so the correction must change its series",
            )
        }
    }
}
