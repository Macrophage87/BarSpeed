package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
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
        FieldCorpus.onClasspath()
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
        // field-ohp-3010-8rep-s37-set01 and it needs one pass; issue #245
        // committed field-ohp-3010-8rep-s38-set05 and
        // field-inclinepress-3010-12rep-s38-set02 and each needs one too;
        // issue #72 committed field-ohp-3010-8rep-s38-set04 and
        // field-latpulldown-1120-12rep-s38-set14 and each needs one as well.
        // So the only bucket that has moved across all five is 1, and the
        // worst capture in the corpus is unchanged at four passes.
        // #259 committed three holds: two of them carry no runaway at all and
        // land in bucket 0, and the third needs one pass. Issue #301's three
        // field-43 deadlifts need 1, 2 and 1 passes, and issues #290 and #255'
        // six field-42 sets need 3, 2, 3, 2, 2 and 1 -- sets 2, 5, 7, 9, 11
        // and 13 -- so the six add one to bucket 1, three to bucket 2 and two
        // to bucket 3, and the worst capture in the corpus is still unchanged
        // at four. The six are the corpus's first captures at a 43-44 Hz
        // analysed rate (field-42 role a), and they do not push the bound
        // either.
        // And issue #278 committed seven pairs, four of whose base captures
        // were not already walked here. Re-measured at this tree, the buckets
        // move from {0=15, 1=29, 2=6, 3=3, 4=1} to {0=16, 1=31, 2=7, 3=3,
        // 4=1}: the triceps pushdown, whose armed unit sat on the stack,
        // carries no runaway at all and lands in bucket 0, two of the four
        // need one pass and one needs two. The worst capture in the corpus is
        // still unchanged at four passes. Issue #305's five field-44
        // deadlifts then move them to {0=17, 1=34, 2=8, 3=3, 4=1}: set 1
        // carries no runaway, sets 2, 4 and 5 need one pass and set 3 two.
        assertEquals(
            mapOf(0 to 17, 1 to 34, 2 to 8, 3 to 3, 4 to 1),
            passesNeeded.values.groupingBy { it }.eachCount().toSortedMap(),
            "captures by passes needed",
        )
        assertEquals(63, passesNeeded.size, "committed captures walked")
        assertEquals(
            mapOf(
                "field-bench-3010-6rep-s42-set05" to 2,
                "field-bench-3010-6rep-s42-set07" to 3,
                // The one of issue #278's four new captures needing a second
                // pass: a seated cable row, handle-side under a stack
                // declaration.
                "field-cablerow-3010-8rep-s42-set08" to 2,
                "field-cablerow-3010-8rep-s42-set09" to 2,
                "field-deadlift-straight-5rep-s43-set05" to 2,
                // Issue #305's one field-44 deadlift needing a second pass:
                // set 3, the 102 kg set whose stream opens with 119 s of plate
                // handling.
                "field-deadlift-straight-5rep-s44-set03" to 2,
                "field-ohp-3010-7rep-s42-set02" to 3,
                "field-ohp-prepinflated-s37-set03" to 3,
                "field-pullup-3010-8rep-s42-set11" to 2,
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
                // The one of issue #305's five field-44 deadlifts whose
                // anchored series holds no runaway: set 1, the 61 kg warm-up.
                // The other four each carry at least one.
                "field-deadlift-straight-5rep-s44-set01",
                "field-facepull-static-12rep",
                "field-legcurl-1030-12rep",
                "field-legcurl-1030-12rep-b",
                "field-legcurl-1030-12rep-c",
                "field-legpress-single-2011-8rep-s36-set07",
                "field-pallof-static-12rep",
                // The one capture of issue #278's seven whose series holds no
                // runaway: the triceps pushdown, the corpus's first capture
                // whose ARMED unit was the one on the stack. Its gyro median
                // is 0.173 deg/s and its roll sweeps 0.28 deg over the working
                // window -- a stream that barely moves in any axis.
                "field-pushdown-1120-14rep-s41-set16",
                "field-rdl-3010-10rep",
                "field-ropedeadhang-hold20-s37-set11",
                // Two of the three holds #259 committed. The third, field-38
                // set 18, DOES carry a runaway and is not here -- two hangs
                // recorded minutes apart on the same rope differ on this,
                // which is why the list is measured and not reasoned about.
                "field-ropedeadhang-hold45-s38-set17",
                "field-ropefarmershold-hold30-s42-set16",
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
