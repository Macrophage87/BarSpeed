package com.macrophage.barspeed.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT THE ELEVEN CAPTURES PUBLISH FOR `rom_m` AND `romSpread_pct` TODAY, and
 * the measurement that disqualifies the repair issue #291 was expected to
 * receive. Characterization only; nothing is fixed by this commit.
 *
 * ## The defect, in the figures the lifter is shown
 *
 * `RepSegmenter.displacement` integrates |v| over the rep's DRIVE run, and
 * `SetAnalyzer.romSpreadPct` takes the population deviation of those figures as
 * a percentage of their mean. A bench press publishes a 1.592 m rep and a
 * 98.1 % spread; a seated cable row publishes 1.880 m; an assisted pull-up
 * publishes 1.938 m. `ArtefactCorpus` states each capture's provenance,
 * geometry and load once, and `ArtefactCorpusBaselineTest`'s KDoc carries the
 * licence for using them.
 *
 * ## The repair that does NOT work, measured rather than asserted
 *
 * The obvious reading is that the integrator carries a per-rep drift, so a
 * detrend anchored on the rep's own two rest points would remove it: a rep
 * starts and ends at rest, so its velocity should begin and end at zero and its
 * displacement should return to where it started.
 *
 * Two forms of that repair are computed here and BOTH fail on the inflated
 * reps:
 *
 * - [twoPointDetrendedRomM] subtracts the straight line through the velocity at
 *   the rep span's two endpoints.
 * - [symmetrisedRomM] removes the constant offset that makes the rep's NET
 *   displacement zero and then averages the two phases' corrected travels,
 *   which is the strongest form available from the rep's own span.
 *
 * The figures each produces are pinned below, and they DISAGREE: on field-42
 * set 7's worst rep the detrend reads 1.544 m and the symmetrisation 1.780 m
 * against a published 1.592 m, and on field-42 set 9's worst rep the detrend
 * reads 0.666 m and the symmetrisation 1.880 m against a published 1.880 m.
 * Two arithmetics whose answers move in opposite directions on two reps are
 * not a rule.
 *
 * The reason is also pinned. On set 7's worst rep BOTH phases are inflated
 * together and by nearly the same distance -- 1.977 m of lowering against
 * 1.592 m of pressing -- which is not an offset a per-rep detrend can remove:
 * it is a velocity that is wrong while the bar moves and right while it rests,
 * which is what an integrator with no accepted zero-velocity anchor inside the
 * working window produces. On set 9's worst rep the span's own endpoints are
 * moving at 1.031 and -1.363 m/s, so THE REP HAS NO TWO REST POINTS and the
 * detrend is anchored on nothing.
 *
 * `RomDispersionTest` reaches the same wall from the other side and says it
 * plainly: nothing in this corpus can separate a mismeasured set from a lifter
 * whose range varied, because the only machine here with an independently known
 * travel is the leg-curl rail.
 *
 * ## The issue's own claim that `rom_m` multiplies into power is FALSE
 *
 * #291 states that "rom_m also multiplies into meanConPower_w and peakPower_w,
 * so the two defects compound on the same reps". Read at
 * `SetAnalyzer.repMetrics`, the power figures are `load * (g + a/ratio) *
 * (v/ratio)` over the drive window and carry no displacement term at all.
 * The last test here recomputes them from the load, the acceleration and the
 * velocity alone and reproduces them exactly. The two figures are corrupted by
 * the SAME velocity, which is a different statement and the one a reader should
 * act on.
 */
class RomDriftBaselineTest {
    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    /** The series the analyzer runs on, in the lifter's frame. */
    private fun series(case: ArtefactCorpus.Case) =
        VelocityEstimator.estimate(ArtefactCorpus.load(case.fixture), DspConfig(), case.direction.measuredPlane)
            .mappedToLifter(case.direction.sensorToLifter)

    private fun spans(case: ArtefactCorpus.Case) = RepSegmenter.segment(series(case), case.direction, DspConfig())

    private fun net(s: VelocitySeries, a: Int, b: Int): Double {
        var d = 0.0
        for (i in a + 1..b) d += s.velocityMps[i] * (s.timeS[i] - s.timeS[i - 1])
        return d
    }

    /** `rom_m` as shipped: |v| integrated over the drive run. */
    private fun publishedRomM(s: VelocitySeries, sp: RepSpan): Double =
        RepSegmenter.displacement(s, sp.conStartIdx, sp.conEndIdx)

    /** Repair one: subtract the line through the velocity at the rep span's two endpoints. */
    private fun twoPointDetrendedRomM(s: VelocitySeries, sp: RepSpan): Double {
        val lo = minOf(sp.eccStartIdx, sp.conStartIdx)
        val hi = maxOf(sp.eccEndIdx, sp.conEndIdx)
        val ta = s.timeS[lo]
        val slope = if (s.timeS[hi] > ta) (s.velocityMps[hi] - s.velocityMps[lo]) / (s.timeS[hi] - ta) else 0.0
        var d = 0.0
        for (i in sp.conStartIdx + 1..sp.conEndIdx) {
            val v = s.velocityMps[i] - (s.velocityMps[lo] + slope * (s.timeS[i] - ta))
            d += abs(v) * (s.timeS[i] - s.timeS[i - 1])
        }
        return d
    }

    /** Repair two: force the rep's net displacement to zero, then average the two phases. */
    private fun symmetrisedRomM(s: VelocitySeries, sp: RepSpan, sign: Double): Double {
        val lo = minOf(sp.eccStartIdx, sp.conStartIdx)
        val hi = maxOf(sp.eccEndIdx, sp.conEndIdx)
        val total = s.timeS[hi] - s.timeS[lo]
        val drift = if (total > 0) net(s, lo, hi) * sign / total else 0.0
        val con = net(s, sp.conStartIdx, sp.conEndIdx) * sign -
            drift * (s.timeS[sp.conEndIdx] - s.timeS[sp.conStartIdx])
        if (!sp.hasEccentric) return abs(con)
        val ecc = net(s, sp.eccStartIdx, sp.eccEndIdx) * sign -
            drift * (s.timeS[sp.eccEndIdx] - s.timeS[sp.eccStartIdx])
        return abs(con - ecc) / 2.0
    }

    private fun case(fixture: String) = ArtefactCorpus.cases.first { it.fixture == fixture }

    private data class Published(
        val fixture: String,
        val maxRomM: Double,
        val spreadPct: Double,
        val travelM: Double,
    )

    @Test
    fun `the sets issue 291 names publish a rom_m no lift can travel (pre-fix)`() {
        // The per-set maxima and spreads, from the captures rather than from the
        // issue body. Each lift's plausible one-way travel is beside it, and is
        // a judgement about the exercise rather than a measurement: no machine
        // in this corpus but the leg-curl rail has an independently known
        // travel, which RomDispersionTest already records.
        val expected = listOf(
            Published("field-cablerow-3010-8rep-s42-set09", 1.88, 76.7, 0.5),
            Published("field-pullup-4010-8rep-s42-set13", 1.938, 62.1, 0.6),
            Published("field-pullup-3010-8rep-s42-set11", 1.773, 96.9, 0.6),
            Published("field-bench-3010-6rep-s42-set07", 1.592, 98.1, 0.45),
            Published("field-bench-3010-6rep-s42-set05", 1.102, 87.4, 0.45),
            Published("field-deadlift-straight-5rep-s43-set06", 1.507, 97.6, 0.6),
            Published("field-deadlift-straight-5rep-s43-set05", 1.761, 53.4, 0.6),
        )
        expected.forEach { p ->
            val a = ArtefactCorpus.analyse(case(p.fixture))
            assertEquals(p.maxRomM, a.reps.maxOf { it.romM }, "${p.fixture} largest published rom_m")
            assertEquals(p.spreadPct, SetAnalyzer.romSpreadPct(a.reps), "${p.fixture} romSpread_pct")
            assertTrue(
                p.maxRomM > 2.0 * p.travelM,
                "${p.fixture} publishes ${p.maxRomM} m where the lift travels about ${p.travelM} m",
            )
        }
        // The third deadlift is under twice its travel and is listed apart for
        // that reason: its worst rep is 0.872 m against about 0.6 m, and its
        // spread is still 50.9 %. Reading the seven above as "the inflated
        // sets" and this as a clean one is the mistake the number invites.
        val four = ArtefactCorpus.analyse(case("field-deadlift-straight-5rep-s43-set04"))
        assertEquals(0.872, four.reps.maxOf { it.romM }, "set 4's largest rom_m")
        assertEquals(50.9, SetAnalyzer.romSpreadPct(four.reps), "set 4 romSpread_pct")
    }

    @Test
    fun `every rep of field-42 set 7 and set 9, so neither reads as one bad rep`() {
        assertEquals(
            listOf(0.168, 0.758, 0.188, 0.124, 0.353, 1.592),
            ArtefactCorpus.analyse(case("field-bench-3010-6rep-s42-set07")).reps.map { it.romM },
            "set 7 rom_m per detection, on a bench press that travels about 0.45 m",
        )
        assertEquals(
            listOf(1.675, 1.88, 0.142, 0.342),
            ArtefactCorpus.analyse(case("field-cablerow-3010-8rep-s42-set09")).reps.map { it.romM },
            "set 9 rom_m per detection, on a seated row that travels about 0.5 m",
        )
    }

    @Test
    fun `a detrend anchored on the rep's own rest points does not remove the inflation`() {
        // THE MEASUREMENT THAT CHOSE THE FIX. Both repairs are computed on the
        // two worst reps in the corpus and neither brings either under the
        // lift's travel; one makes a figure worse.
        val seven = case("field-bench-3010-6rep-s42-set07")
        val sevenSeries = series(seven)
        val sevenWorst = spans(seven).maxBy { publishedRomM(sevenSeries, it) }
        assertEquals(1.592, round3(publishedRomM(sevenSeries, sevenWorst)), "set 7's worst rep as published")
        assertEquals(1.544, round3(twoPointDetrendedRomM(sevenSeries, sevenWorst)), "two-point detrend")
        assertEquals(
            1.78,
            round3(symmetrisedRomM(sevenSeries, sevenWorst, seven.direction.concentricSign)),
            "net-zero symmetrisation, which moves it UP",
        )

        val nine = case("field-cablerow-3010-8rep-s42-set09")
        val nineSeries = series(nine)
        val nineWorst = spans(nine).maxBy { publishedRomM(nineSeries, it) }
        assertEquals(1.88, round3(publishedRomM(nineSeries, nineWorst)), "set 9's worst rep as published")
        // The two repairs DISAGREE on this rep: the detrend pulls it to 0.666 m
        // and the symmetrisation leaves it at 1.880 m, the opposite of their
        // ordering on set 7. Neither is a rule; they are two arithmetics whose
        // answers move in different directions on different reps.
        assertEquals(0.666, round3(twoPointDetrendedRomM(nineSeries, nineWorst)), "two-point detrend")
        assertEquals(
            1.88,
            round3(symmetrisedRomM(nineSeries, nineWorst, nine.direction.concentricSign)),
            "symmetrisation changes nothing here",
        )
        // Why the detrend can move this rep at all, and why that is not a
        // repair: its span endpoints are not at rest. The velocity reads
        // 1.031 m/s where the rep begins and -1.363 m/s where it ends, so the
        // line the detrend subtracts is enormous and the rep has no two rest
        // points for anything to be anchored on. Set 7's worst rep DOES begin
        // and end inside the dead band, which is why the same arithmetic barely
        // touches it.
        assertEquals(1.031, round3(nineSeries.velocityMps[minOf(nineWorst.eccStartIdx, nineWorst.conStartIdx)]))
        assertEquals(-1.363, round3(nineSeries.velocityMps[maxOf(nineWorst.eccEndIdx, nineWorst.conEndIdx)]))
    }

    @Test
    fun `both phases of an inflated rep are inflated together, which is why no detrend helps`() {
        val seven = case("field-bench-3010-6rep-s42-set07")
        val sevenSeries = series(seven)
        val sevenWorst = spans(seven).maxBy { publishedRomM(sevenSeries, it) }
        val sign = seven.direction.concentricSign
        assertEquals(
            -1.977,
            round3(net(sevenSeries, sevenWorst.eccStartIdx, sevenWorst.eccEndIdx) * sign),
            "set 7's worst rep lowers 1.977 m on a bench press",
        )
        assertEquals(
            1.592,
            round3(net(sevenSeries, sevenWorst.conStartIdx, sevenWorst.conEndIdx) * sign),
            "and presses 1.592 m back",
        )

        val nine = case("field-cablerow-3010-8rep-s42-set09")
        val nineSeries = series(nine)
        val nineWorst = spans(nine).maxBy { publishedRomM(nineSeries, it) }
        val nineSign = nine.direction.concentricSign
        val lo = minOf(nineWorst.eccStartIdx, nineWorst.conStartIdx)
        val hi = maxOf(nineWorst.eccEndIdx, nineWorst.conEndIdx)
        assertEquals(
            -1.88,
            round3(net(nineSeries, nineWorst.eccStartIdx, nineWorst.eccEndIdx) * nineSign),
            "set 9's worst rep travels 1.88 m each way",
        )
        assertEquals(0.0, round3(net(nineSeries, lo, hi) * nineSign), "and nets zero, so no offset is visible in it")
    }

    @Test
    fun `no accepted zero-velocity anchor lies inside any rep of the two worst sets`() {
        // The mechanism, stated as a count rather than as a hypothesis. The ZUPT
        // pass is the only stage that pins the integrator to zero, and on these
        // captures it accepts nothing between the first rep's start and the last
        // rep's end -- so the whole working window is one uncorrected interval.
        listOf("field-bench-3010-6rep-s42-set07", "field-cablerow-3010-8rep-s42-set09").forEach { fixture ->
            val c = case(fixture)
            val anchored =
                VelocityEstimator.estimate(ArtefactCorpus.load(c.fixture), DspConfig(), c.direction.measuredPlane)
            val lifterFrame = anchored.mappedToLifter(c.direction.sensorToLifter)
            val spans = RepSegmenter.segment(lifterFrame, c.direction, DspConfig())
            val inside = spans.sumOf { sp ->
                val lo = minOf(sp.eccStartIdx, sp.conStartIdx)
                val hi = maxOf(sp.eccEndIdx, sp.conEndIdx)
                anchored.anchorIndices.count { it in lo..hi }
            }
            assertEquals(0, inside, "$fixture accepted anchors inside a rep span")
        }
    }

    @Test
    fun `power carries no displacement term, so issue 291's compounding claim is wrong`() {
        // #291: "rom_m also multiplies into meanConPower_w and peakPower_w".
        // It does not. Recomputed here from the load, the acceleration and the
        // velocity alone -- the three terms SetAnalyzer.repMetrics actually uses
        // -- and reproduced to the published digit on every rep of a set whose
        // rom_m is inflated by a factor of three.
        val seven = case("field-bench-3010-6rep-s42-set07")
        val s = series(seven)
        val a = ArtefactCorpus.analyse(seven)
        val config = DspConfig()
        val ratio = seven.direction.travelRatio
        val sign = seven.direction.concentricSign
        val recomputed = spans(seven)
            .filter { sp -> a.reps.any { round3(publishedRomM(s, sp)) == it.romM } }
            .map { sp ->
                val power = (sp.conStartIdx..sp.conEndIdx).map { i ->
                    seven.loadKg * (config.gravityMps2 + s.accelMps2[i] / ratio) * (s.velocityMps[i] * sign / ratio)
                }
                Math.round(power.max() * 10.0) / 10.0
            }
        assertEquals(
            a.reps.mapNotNull { it.peakPowerW },
            recomputed,
            "peakPower_w from load, accel and velocity alone -- no rom term",
        )
    }
}
