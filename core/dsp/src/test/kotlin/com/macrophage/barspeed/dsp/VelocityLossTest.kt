package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When `velocityLoss_pct` is published and when it is withheld, on rep lists
 * small enough to check by hand and on the committed captures that reach the
 * withheld case, with a control beside them that does not.
 *
 * Only `meanConVelMps` matters to this figure. Every other field on
 * [RepAnalysis] is filled with a fixed, obviously-synthetic value so that a
 * reader is never tempted to read one of these lists as a real set.
 */
class VelocityLossTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    /**
     * The geometry the four committed leg-curl captures are analysed with
     * everywhere else in this module -- concentric first, drive DOWN, sensor
     * inverted on the weight stack. `loadKg` is left null: it feeds power and
     * nothing else, and no figure asserted here depends on it.
     */
    private val legCurl =
        LiftDirection(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            sensorInverted = true,
            plane = MovementPlane.VERTICAL,
            sensorOnStack = true,
        )

    private fun reps(vararg meanConVelMps: Double) = meanConVelMps.mapIndexed { i, v ->
        RepAnalysis(
            index = i,
            eccS = 2.0,
            bottomPauseS = 0.0,
            conS = 1.0,
            topPauseS = 0.0,
            meanConVelMps = v,
            peakConVelMps = v,
            meanEccVelMps = null,
            peakEccVelMps = null,
            romM = 0.5,
            peakPowerW = null,
        )
    }

    @Test
    fun `velocity loss is best rep to last rep`() {
        // 0.50 is the best and 0.30 is the last: (0.50 - 0.30) / 0.50 = 40%.
        // The middle rep is faster than the last and slower than the best, so a
        // best-to-WORST reading would give the same answer here -- which is why
        // the case below, where the two definitions differ, is also pinned.
        assertEquals(40.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.40, 0.30)))
    }

    @Test
    fun `a slow rep in the middle of the set is not the set's velocity loss`() {
        // best 0.50, worst 0.10, last 0.45. Best-to-last is 10%; best-to-worst
        // would be 80%. This is the pin that says which definition is in force.
        assertEquals(10.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.10, 0.45)))
    }

    @Test
    fun `velocity loss is undefined when the last rep is the set's fastest`() {
        // Was pinned at 0.0 by the commit that added this file. `best` is a
        // maximum taken over a list that CONTAINS `last`, so best - last can
        // never be negative and the quotient reaches exactly 0.0 only when the
        // last rep ties the maximum. 0.0 was a fact about the ORDER of this
        // list and never a measurement of fatigue -- and it is the same event
        // as a spurious final detection, so the reassuring reading and the
        // artefact could not be told apart.
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(reps(0.50, 0.40, 0.50)))
        assertNull(SetAnalyzer.velocityLossPct(reps(0.50, 0.40, 0.50)))
    }

    @Test
    fun `a tie for fastest at the last rep is undefined, not zero`() {
        // Was pinned at 0.0. The tie in its smallest form, and the reason it is
        // a test of its own: the natural way to write this rule -- ask whether
        // maxByOrNull lands on the last index -- answers NO here, because
        // maxByOrNull returns the FIRST maximum. That implementation leaves
        // this case publishing 0.0, the smallest case the rule exists to catch,
        // while every other assertion in this file goes on passing.
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(reps(0.50, 0.50)))
        assertNull(SetAnalyzer.velocityLossPct(reps(0.50, 0.50)))
    }

    /**
     * Green today and green after, unlike the two above.
     *
     * This is the only thing standing between "undefined when degenerate" and
     * "undefined always": a guard widened from `last >= best` to anything
     * carrying a margin turns every case below into null, and no other
     * assertion in this file would notice.
     */
    @Test
    fun `velocity loss is still reported when the last rep is not the fastest`() {
        assertEquals(VelocityLoss.Measured(20.0), VelocityLoss.of(reps(0.50, 0.40)))
        assertEquals<Double?>(20.0, SetAnalyzer.velocityLossPct(reps(0.50, 0.40)))
        // A near-tie is not a tie. 0.49 against 0.50 is a real 2% and must
        // survive: the rule is an ordering test, not a proximity test.
        assertEquals(VelocityLoss.Measured(2.0), VelocityLoss.of(reps(0.50, 0.49)))
    }

    @Test
    fun `a single rep has nothing to compare against`() {
        assertNull(SetAnalyzer.velocityLossPct(reps(0.50)))
        assertNull(SetAnalyzer.velocityLossPct(reps()))
    }

    @Test
    fun `a set with no positive drive velocity has no reference to divide by`() {
        assertNull(SetAnalyzer.velocityLossPct(reps(0.0, 0.0)))
        assertNull(SetAnalyzer.velocityLossPct(reps(-0.10, -0.20)))
    }

    /**
     * Four committed captures of the same exercise, tempo and mount, three of
     * which publish a green 0.0 today.
     *
     * Measured at df17bcb44957e5d84913a2d511b04bf62ef1c8ed by driving these
     * fixtures through [SetAnalyzer.analyze]: `-b` resolves 13 detections for
     * the 12 reps performed and its last carries 1.517 m/s against 0.311 for
     * the best of the others; `-c` resolves 11 and its last carries 0.648
     * against 0.553. Both published 0.0% -- a green chip on a seated leg curl
     * whose fastest measured movement was not a rep.
     *
     * `field-legcurl-1030-10rep.csv` is session 31 set 11, the set issue #126
     * was filed about -- "a 1.375 m ROM at 1.159 m/s, the fastest thing in the
     * set, and not a rep". It was committed by 55673f8 for an unrelated tempo
     * defect and reproduces both of the issue's figures exactly.
     *
     * The remaining capture is the negative control, and it is why this rule
     * carries no threshold. The excess of the last detection over the best of
     * the others spans +7.0% on the pallof press, +17.2% on `-c`, +96.4% on
     * `-10rep` and +387.8% on `-b`; any cut drawn between those is a number
     * fitted to this corpus, while the ordering is fitted to nothing.
     */
    @Test
    fun `the leg-curl captures whose last detection is fastest publish no figure`() {
        val b = SetAnalyzer.analyze(load("field-legcurl-1030-12rep-b.csv"), legCurl)
        assertEquals(13, b.reps.size, "segmented detections; the lifter performed 12")
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(b.reps))
        assertNull(b.velocityLossPct, "velocity loss reported to the lifter")

        val c = SetAnalyzer.analyze(load("field-legcurl-1030-12rep-c.csv"), legCurl)
        assertEquals(11, c.reps.size, "segmented detections; the lifter performed 12")
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(c.reps))
        assertNull(c.velocityLossPct, "velocity loss reported to the lifter")

        val d = SetAnalyzer.analyze(load("field-legcurl-1030-10rep.csv"), legCurl)
        // 11 before issue #94's runaway correction. The verdict below is
        // unchanged: the last detection is still the fastest, so the figure is
        // still withheld rather than published as a negative drawdown.
        assertEquals(12, d.reps.size, "segmented detections; the lifter performed 10")
        assertEquals<Double?>(1.375, d.reps.last().romM, "ROM of the extra detection, metres")
        assertEquals<Double?>(1.159, d.reps.last().meanConVelMps, "its drive velocity, m/s")
        assertEquals(VelocityLoss.TerminalRepIsFastest, VelocityLoss.of(d.reps))
        assertNull(d.velocityLossPct, "velocity loss reported to the lifter")

        val a = SetAnalyzer.analyze(load("field-legcurl-1030-12rep.csv"), legCurl)
        assertEquals(12, a.reps.size, "segmented detections; the lifter performed 12")
        assertEquals(VelocityLoss.Measured(36.5), VelocityLoss.of(a.reps))
        assertEquals<Double?>(36.5, a.velocityLossPct, "velocity loss reported to the lifter")
    }

    /**
     * The case names [VelocityLoss] can emit are exactly the ones the session
     * export publishes as its vocabulary.
     *
     * Asserted from `:core:dsp` because this is the only side that can see both
     * constants -- `:core:model` cannot see `:core:dsp`. Adding a case to
     * [VelocityLoss] is caught by the compiler rather than by the list below,
     * since `basis` is an exhaustive `when`; what this pins is that the
     * STRINGS on the two sides agree, which nothing else checks.
     */
    @Test
    fun `the published basis vocabulary is exactly the cases this type has`() {
        val cases =
            listOf(
                VelocityLoss.Measured(0.0),
                VelocityLoss.NotEnoughReps,
                VelocityLoss.NoReference,
                VelocityLoss.TerminalRepIsFastest,
            )
        val names = cases.map { it.basis }
        assertEquals(cases.size, names.toSet().size, "two cases share a basis name: $names")
        assertEquals(
            SessionExport.VALID_VELOCITY_LOSS_BASES,
            names.toSet(),
            "VelocityLoss and SessionExport.VALID_VELOCITY_LOSS_BASES disagree",
        )
    }
}
