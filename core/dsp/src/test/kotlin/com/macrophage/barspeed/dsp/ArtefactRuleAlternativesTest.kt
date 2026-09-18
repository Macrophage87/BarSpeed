package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE TWO RULES THAT WERE NOT SHIPPED, MEASURED.
 *
 * Every design decision in `AccelArtefact` that could have gone another way is
 * argued from a figure, and this is where the figures are taken. Without it the
 * KDoc's claims -- "substituting moves the rep count", "the exact interval
 * withholds every peak" -- are assertions in prose, which is the defect class
 * this repository keeps re-learning.
 *
 * ## Alternative 1: SUBSTITUTE the sample before the integration
 *
 * The obvious repair, and what issue #290 asked for. Replace every sample above
 * the bound, either by holding the last in-range accelerometer reading or by
 * interpolating between the neighbours, and re-run the unmodified analyzer.
 * `ArtefactRepTest` already carries the hold-last variant as a DIAGNOSTIC on
 * two field-37 captures; what is measured here is what it does to the corpus.
 *
 * It fails on both halves of what a repair has to do:
 *
 * - it MOVES THE REP COUNT, on NINE of the eleven captures, and
 * - it does not recover a RAILED sample, because a reading at the sensor's own
 *   full scale carries no information about how large the real acceleration
 *   was.
 *
 * ## Alternative 2: withhold over the EXACT residue interval
 *
 * `AccelArtefact.corruptedSpan` derives, from what `applyZupt` does, the exact
 * extent over which an artefact leaves a residue in the corrected velocity.
 * Withholding on that interval reaches all five figures containment leaves
 * standing -- and withholds EVERY peak on eight of the eleven captures, because
 * these captures accept few anchors and the interval is then most of the
 * stream. Publishing no peak pair on most of the corpus is a product decision;
 * it is raised, and the measurement is here rather than in a sentence.
 */
class ArtefactRuleAlternativesTest {
    /** Every sample above the bound replaced by the last in-range reading; gyro and timestamps untouched. */
    private fun holdLastInRange(raw: List<ImuSample>): List<ImuSample> {
        var lastGood = raw.first()
        return raw.map { s ->
            if (AccelArtefact.exceedsBound(s)) {
                s.copy(axG = lastGood.axG, ayG = lastGood.ayG, azG = lastGood.azG)
            } else {
                lastGood = s
                s
            }
        }
    }

    /** Every RUN of samples above the bound replaced by a linear ramp between its in-range neighbours. */
    private fun interpolated(raw: List<ImuSample>): List<ImuSample> {
        val bad = BooleanArray(raw.size) { AccelArtefact.exceedsBound(raw[it]) }
        val out = raw.toMutableList()
        var i = 0
        while (i < raw.size) {
            if (!bad[i]) {
                i++
                continue
            }
            var j = i
            while (j < raw.size && bad[j]) j++
            val lo = if (i - 1 >= 0) raw[i - 1] else raw[minOf(j, raw.size - 1)]
            val hi = if (j < raw.size) raw[j] else lo
            for (k in i until j) {
                val t = (k - i + 1).toDouble() / (j - i + 1).toDouble()
                out[k] = raw[k].copy(
                    axG = lo.axG + (hi.axG - lo.axG) * t,
                    ayG = lo.ayG + (hi.ayG - lo.ayG) * t,
                    azG = lo.azG + (hi.azG - lo.azG) * t,
                )
            }
            i = j
        }
        return out
    }

    private fun peakPowerW(reps: List<RepAnalysis>) = reps.mapNotNull { it.peakPowerW }.maxOrNull()

    /**
     * Detections the two rules withhold, counted over the SEGMENTER's own span
     * list so the two are the same population: total spans, spans the exact
     * residue interval reaches, spans containment reaches.
     *
     * The span list, not the published rep list. Segmentation runs before the
     * terminal-cue and work-start bounds, so it is longer than what a set
     * publishes -- field-42 set 13 resolves 12 spans and publishes 9 -- and
     * bounding it here would mean re-deriving both rules against a second
     * population for no gain. The published figures under the SHIPPED rule are
     * `ArtefactPeakWithholdingTest`'s.
     */
    private fun withheldPerRule(case: ArtefactCorpus.Case): List<Int> {
        val samples = ArtefactCorpus.load(case.fixture)
        val series = VelocityEstimator.estimate(samples, DspConfig(), case.direction.measuredPlane)
            .mappedToLifter(case.direction.sensorToLifter)
        val artefacts = AccelArtefact.indices(samples)
        val spans = RepSegmenter.segmentDetailed(series, case.direction, DspConfig()).spans
        val exact = spans.count { span ->
            val rep = AccelArtefact.spanOf(span)
            artefacts.any { i ->
                val corrupted = AccelArtefact.corruptedSpan(i, series.anchorIndices, series.size)
                corrupted.first <= rep.last && corrupted.last >= rep.first
            }
        }
        val containment = spans.count { AccelArtefact.countIn(artefacts, AccelArtefact.spanOf(it)) > 0 }
        return listOf(spans.size, exact, containment)
    }

    /**
     * REP COUNTS UNDER BOTH SUBSTITUTION VARIANTS, against the counts the same
     * captures publish untouched.
     *
     * NINE of the eleven move under at least one variant, and the movements are
     * not small: field-43 set 4 goes from 9 detections to 5 under either
     * variant, field-42 set 7 from 6 to 3 under hold-last, field-42 set 11 from
     * 9 to 7. A rule that repairs a peak by moving the count has traded a wrong
     * number for a wrong count, and the count is what the lifter checks.
     *
     * The two it does not move are field-42 set 2 -- #290's headline, whose
     * three out-of-range samples are all isolated singles -- and field-42 set
     * 13.
     */
    @Test
    fun `substituting the out-of-range samples moves the rep count on most of the corpus`() {
        val counts = ArtefactCorpus.cases.associate { case ->
            val raw = ArtefactCorpus.load(case.fixture)
            case.fixture to listOf(
                ArtefactCorpus.analyse(case, raw).reps.size,
                ArtefactCorpus.analyse(case, holdLastInRange(raw)).reps.size,
                ArtefactCorpus.analyse(case, interpolated(raw)).reps.size,
            )
        }
        assertEquals(
            mapOf(
                "field-ohp-3010-7rep-s42-set02" to listOf(9, 9, 9),
                "field-bench-3010-6rep-s42-set05" to listOf(5, 4, 4),
                "field-bench-3010-6rep-s42-set07" to listOf(6, 3, 6),
                "field-cablerow-3010-8rep-s42-set09" to listOf(4, 3, 3),
                "field-pullup-3010-8rep-s42-set11" to listOf(9, 7, 9),
                "field-pullup-4010-8rep-s42-set13" to listOf(9, 9, 9),
                "field-deadlift-straight-5rep-s43-set04" to listOf(9, 5, 5),
                "field-deadlift-straight-5rep-s43-set05" to listOf(7, 8, 7),
                "field-deadlift-straight-5rep-s43-set06" to listOf(7, 6, 6),
                "field-assistedpullup-3010-s37-set08" to listOf(7, 7, 6),
                "field-ohp-prepinflated-s37-set03" to listOf(11, 9, 10),
            ),
            counts,
            "detections as published, then under hold-last, then under interpolation",
        )
        val moved = counts.filterValues { it[1] != it[0] || it[2] != it[0] }
        assertEquals(9, moved.size, "captures at least one substitution variant moves")
    }

    /**
     * AND IT DOES NOT FIX THE RAILED CASE. field-43 set 5's accelerometer sits
     * at its own full scale, so holding the last in-range reading over the
     * two-to-four-sample run injects a sustained bias instead of recovering
     * anything; set 6's published peak gets WORSE under both variants.
     */
    @Test
    fun `substitution leaves the railed deadlifts implausible and makes one worse`() {
        assertEquals(
            mapOf(
                "field-deadlift-straight-5rep-s43-set05" to listOf(4347.4, 974.5, 1336.6),
                "field-deadlift-straight-5rep-s43-set06" to listOf(2386.5, 2869.1, 2674.3),
            ),
            listOf("field-deadlift-straight-5rep-s43-set05", "field-deadlift-straight-5rep-s43-set06")
                .associateWith { fixture ->
                    val case = ArtefactCorpus.cases.first { it.fixture == fixture }
                    val raw = ArtefactCorpus.load(fixture)
                    listOf(
                        peakPowerW(ArtefactCorpus.analyse(case, raw).reps),
                        peakPowerW(ArtefactCorpus.analyse(case, holdLastInRange(raw)).reps),
                        peakPowerW(ArtefactCorpus.analyse(case, interpolated(raw)).reps),
                    )
                },
            "peakPower_w as published, then under hold-last, then under interpolation",
        )
    }

    /**
     * THE EXACT RESIDUE INTERVAL, AND WHAT IT WOULD COST.
     *
     * Reps withheld per capture against the total each publishes. Eight of the
     * eleven lose every peak they have; the shipped containment rule withholds
     * between zero and six.
     */
    @Test
    fun `withholding over the exact residue interval takes every peak on eight captures`() {
        val exact = ArtefactCorpus.cases.associate { it.fixture to withheldPerRule(it) }
        assertEquals(
            mapOf(
                "field-ohp-3010-7rep-s42-set02" to listOf(9, 9, 2),
                "field-bench-3010-6rep-s42-set05" to listOf(6, 6, 3),
                "field-bench-3010-6rep-s42-set07" to listOf(7, 7, 2),
                "field-cablerow-3010-8rep-s42-set09" to listOf(5, 3, 0),
                "field-pullup-3010-8rep-s42-set11" to listOf(12, 12, 1),
                "field-pullup-4010-8rep-s42-set13" to listOf(12, 5, 0),
                "field-deadlift-straight-5rep-s43-set04" to listOf(9, 9, 4),
                "field-deadlift-straight-5rep-s43-set05" to listOf(7, 7, 6),
                "field-deadlift-straight-5rep-s43-set06" to listOf(7, 7, 4),
                "field-assistedpullup-3010-s37-set08" to listOf(7, 2, 1),
                "field-ohp-prepinflated-s37-set03" to listOf(11, 11, 5),
            ),
            exact,
            "spans resolved, then spans the exact interval withholds, then spans containment withholds",
        )
    }
}
