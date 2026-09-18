package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AccelArtefact` at the unit level: the bound, the window, the threshold, the
 * population a set's peak may be taken over, and the derivation of the residue
 * interval. Issues #290 and #255.
 *
 * The corpus figures are in `ArtefactPeakWithholdingTest` and the rules that
 * were rejected are in `ArtefactRuleAlternativesTest`. What is here is the
 * behaviour a caller can rely on, including the three cases that are easy to get
 * backwards: absence versus zero, the span versus the drive window, and what the
 * interval does at the ends of the stream.
 */
class ArtefactBoundTest {
    private fun sample(g: Double, t: Long = 0L) = ImuSample(
        timestampMs = t,
        axG = 0.0,
        ayG = 0.0,
        azG = g,
        wxDps = 0.0,
        wyDps = 0.0,
        wzDps = 0.0,
        rollDeg = 0.0,
        pitchDeg = 0.0,
        yawDeg = 0.0,
    )

    private fun rep(index: Int, artefactSamples: Int?, peakPowerW: Double?, peakConVelMps: Double) = RepAnalysis(
        index = index,
        eccS = 1.0,
        bottomPauseS = null,
        conS = 1.0,
        topPauseS = null,
        meanConVelMps = 0.3,
        peakConVelMps = peakConVelMps,
        meanEccVelMps = -0.3,
        peakEccVelMps = -0.4,
        romM = 0.4,
        peakPowerW = peakPowerW,
        meanConPowerW = 100.0,
        artefactSamples = artefactSamples,
    )

    /**
     * The bound is on TOTAL support acceleration, gravity included, so a
     * resting sensor reads 1 g and not 0. Getting that wrong by a g is the
     * arithmetic error the whole rule would be built on.
     */
    @Test
    fun `the bound is 4 g of total support acceleration, gravity included`() {
        assertEquals(4.0, AccelArtefact.BOUND_G, "the bound")
        assertEquals(16.0, AccelArtefact.SENSOR_RANGE_G, "the sensor's measured full scale")
        assertEquals(1.0, FrameTransform.accMagnitudeG(sample(1.0)), "a resting sensor reads 1 g")
        assertFalse(AccelArtefact.exceedsBound(sample(1.0)), "a resting sensor is not an artefact")
        assertFalse(AccelArtefact.exceedsBound(sample(2.0)), "1 g of net drive is ordinary tempo work")
        assertFalse(AccelArtefact.exceedsBound(sample(4.0)), "the bound itself is admitted, not refused")
        assertTrue(AccelArtefact.exceedsBound(sample(4.001)), "and anything above it is not")
        assertTrue(AccelArtefact.exceedsBound(sample(-16.0)), "the magnitude ignores the sign of the axis")
    }

    @Test
    fun `indices and count report the same samples`() {
        val samples = listOf(1.0, 0.9, 12.9, 1.0, 4.5, 4.0, 1.1).mapIndexed { i, g -> sample(g, i.toLong()) }
        assertEquals(listOf(2, 4), AccelArtefact.indices(samples), "out-of-range samples, by index")
        assertEquals(2, AccelArtefact.count(samples), "and the count agrees with the list")
        assertEquals(0, AccelArtefact.count(emptyList()), "an empty stream counts none")
    }

    /**
     * THE SPAN AND NOT THE DRIVE WINDOW, and the min/max is not decoration:
     * `RepSpan` orders its phases by the LIFT, so a concentric-first rep's
     * eccentric indices are ABOVE its concentric ones.
     */
    @Test
    fun `the qualified window is the whole rep span, in either phase order`() {
        val eccentricFirst =
            RepSpan(eccStartIdx = 10, eccEndIdx = 40, conStartIdx = 45, conEndIdx = 70, turnaroundPauseS = 0.1)
        assertEquals(10..70, AccelArtefact.spanOf(eccentricFirst), "eccentric-first: the eccentric opens the span")
        val concentricFirst =
            RepSpan(eccStartIdx = 45, eccEndIdx = 70, conStartIdx = 10, conEndIdx = 40, turnaroundPauseS = 0.1)
        assertEquals(10..70, AccelArtefact.spanOf(concentricFirst), "concentric-first: the drive opens it")
        // A rep counted on the drive alone carries a placeholder eccentric span
        // inside the drive's own range, so it contributes nothing.
        val driveOnly = RepSpan(
            eccStartIdx = 40,
            eccEndIdx = 40,
            conStartIdx = 10,
            conEndIdx = 40,
            turnaroundPauseS = null,
            hasEccentric = false,
        )
        assertEquals(10..40, AccelArtefact.spanOf(driveOnly), "a drive-only rep's span is its drive")
    }

    @Test
    fun `countIn counts only the artefacts inside the range`() {
        val artefacts = listOf(3, 17, 18, 90)
        assertEquals(0, AccelArtefact.countIn(artefacts, 4..16), "none inside")
        assertEquals(2, AccelArtefact.countIn(artefacts, 17..18), "both ends inclusive")
        assertEquals(1, AccelArtefact.countIn(artefacts, 3..3), "a one-sample range")
        assertEquals(4, AccelArtefact.countIn(artefacts, 0..100), "all of them")
        assertEquals(0, AccelArtefact.countIn(emptyList(), 0..100), "a clean stream counts none")
    }

    /**
     * THE THRESHOLD IS ONE, and it is derived rather than fitted: a peak is a
     * MAXIMUM over its window, so one reading above every real one is
     * sufficient to be the answer.
     */
    @Test
    fun `one artefact sample is enough to withhold, and zero is not`() {
        assertFalse(AccelArtefact.peaksWithheld(0), "a counted clean span publishes its peak")
        assertTrue(AccelArtefact.peaksWithheld(1), "one is enough -- a max has no averaging to dilute it")
        assertTrue(AccelArtefact.peaksWithheld(18), "and so is field-43 set 5's worst rep")
    }

    /**
     * ABSENCE IS NOT ZERO, in the direction that matters: a rep analysed before
     * the count existed carries null and is KEPT.
     *
     * Treating null as "artefact present" would make every set recorded before
     * this rule publish no peak at all, which is a worse outcome than the
     * defect. Treating it as 0 is what this does, and the difference is
     * invisible in the arithmetic and total in the archive.
     */
    @Test
    fun `peakEligible keeps a rep whose count is absent and drops one whose count is positive`() {
        val reps = listOf(
            rep(0, artefactSamples = 0, peakPowerW = 100.0, peakConVelMps = 0.5),
            rep(1, artefactSamples = 2, peakPowerW = 3606.3, peakConVelMps = 2.5),
            rep(2, artefactSamples = null, peakPowerW = 120.0, peakConVelMps = 0.6),
        )
        assertEquals(listOf(0, 2), AccelArtefact.peakEligible(reps).map { it.index }, "the reps a peak may cover")
        assertEquals(
            120.0,
            AccelArtefact.peakEligible(reps).mapNotNull { it.peakPowerW }.maxOrNull(),
            "the published peak power comes from the eligible reps",
        )
        assertEquals(
            0.6,
            AccelArtefact.peakEligible(reps).maxOfOrNull { it.peakConVelMps },
            "and so does the published peak velocity",
        )
        assertEquals(3606.3, reps.mapNotNull { it.peakPowerW }.maxOrNull(), "which the whole list would not have")
        assertEquals(emptyList(), AccelArtefact.peakEligible(emptyList()), "an empty list has nothing to keep")
        assertNull(
            AccelArtefact.peakEligible(listOf(reps[1])).maxOfOrNull { it.peakConVelMps },
            "a set whose only rep carries one publishes no peak rather than a low one",
        )
    }

    /**
     * THE TERMINAL-LOSS FIGURE, as `RecordScreen.PeakVelocityChart` computes it
     * TODAY: the best over EVERY rep, including one whose span carries a reading
     * the sensor cannot have measured.
     *
     * A CHARACTERIZATION and labelled as one. It is pinned before it is changed
     * because the expression lives in `:app`, where no test on the CI path
     * reaches it, so the only way to show what the change moves is to lift it
     * out first.
     */
    @Test
    fun `terminalPeakLossPct characterizes the chart's own expression`() {
        val reps = listOf(
            rep(0, artefactSamples = 0, peakPowerW = 100.0, peakConVelMps = 0.5),
            rep(1, artefactSamples = 2, peakPowerW = 3606.3, peakConVelMps = 2.5),
            rep(2, artefactSamples = 0, peakPowerW = 120.0, peakConVelMps = 0.4),
        )
        assertEquals(84.0, AccelArtefact.terminalPeakLossPct(reps)!!, 1e-9, "0.4 against the artefact rep's 2.5")
        assertNull(AccelArtefact.terminalPeakLossPct(emptyList()), "no rep, so no percentage")
        assertNull(
            AccelArtefact.terminalPeakLossPct(listOf(rep(0, 0, 100.0, 0.0))),
            "a best of zero is not something a loss can be a percentage of",
        )
        assertEquals(
            0.0,
            AccelArtefact.terminalPeakLossPct(listOf(rep(0, 0, 100.0, 0.7)))!!,
            1e-9,
            "a one-rep set's last rep IS its best",
        )
    }

    /**
     * THE RESIDUE INTERVAL, case by case. Derived from what `applyZupt` does --
     * see `AccelArtefact.corruptedSpan` -- and not shipped as the withholding
     * rule; `ArtefactRuleAlternativesTest` measures what shipping it would cost.
     */
    @Test
    fun `the residue interval is the inter-anchor interval the artefact sits in`() {
        val anchors = intArrayOf(0, 100, 250, 400)
        assertEquals(0..100, AccelArtefact.corruptedSpan(50, anchors, 500), "between the first two anchors")
        assertEquals(100..250, AccelArtefact.corruptedSpan(100, anchors, 500), "an artefact ON an anchor opens there")
        assertEquals(100..250, AccelArtefact.corruptedSpan(249, anchors, 500), "just before the next anchor")
        assertEquals(250..400, AccelArtefact.corruptedSpan(250, anchors, 500), "on the third anchor")
        // After the LAST anchor the offset is constant, so the residue runs to
        // the end of the stream.
        assertEquals(400..499, AccelArtefact.corruptedSpan(450, anchors, 500), "past the last anchor")
        // No anchors at all: the whole stream is one interval, which is the
        // conservative answer and the reason VelocitySeries defaults to empty.
        assertEquals(0..499, AccelArtefact.corruptedSpan(7, IntArray(0), 500), "no anchors, so nothing is bounded")
        assertEquals(IntRange.EMPTY, AccelArtefact.corruptedSpan(0, anchors, 0), "an empty stream has no interval")
    }

    /**
     * The anchor indices reach the series, ascending and starting at 0, on a
     * real capture. Without that `corruptedSpan` would be reasoning about an
     * empty array on every set.
     */
    @Test
    fun `a real capture's series carries its accepted anchors`() {
        val samples = ArtefactCorpus.load("field-assistedpullup-3010-s37-set08")
        val series = VelocityEstimator.estimate(samples, DspConfig(), MovementPlane.VERTICAL)
        assertEquals(34, series.anchorIndices.size, "anchors accepted on field-37 set 8")
        assertEquals(0, series.anchorIndices.first(), "the first anchor is the first sample")
        assertEquals(
            series.anchorIndices.toList().sorted(),
            series.anchorIndices.toList(),
            "anchors arrive in index order",
        )
        assertTrue(series.anchorIndices.last() < series.size, "and every one indexes the series")
        // The mapping into the lifter's frame moves no sample, so it moves no
        // anchor -- the property `AccelArtefact.corruptedSpan` relies on when
        // the analyzer hands it a mapped series.
        assertEquals(
            series.anchorIndices.toList(),
            series.mappedToLifter(0.5).anchorIndices.toList(),
            "scaling the frame does not move an anchor",
        )
    }
}
