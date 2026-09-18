package com.macrophage.barspeed.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHICH ROUTE EACH ACCEPTED ANCHOR TOOK, and what the drift correction actually
 * erased over the intervals a route-blind [RomBound] called bounded. Issue #291,
 * round 1 finding 1.
 *
 * `VelocityEstimator.applyZupt` accepts a flat window on
 * `stable && (nearPrev || starved)`. Only `nearPrev` -- `anchorAcceptable` --
 * caps the displacement the piecewise-linear offset erases over the interval
 * ending at that anchor, at `DspConfig.minRomM`, 0.10 m. `starved` is the escape
 * taken when nothing has been acceptable for longer than `ANCHOR_STARVATION_S`,
 * and it applies no cap at all. [VelocitySeries.anchorCapped] is the record of
 * which route each anchor took; this file is what says the record is right and
 * what the uncapped intervals cost.
 *
 * ## Why the reconstruction below is here, and how it is kept honest
 *
 * The erased displacement of one interval is `0.5 * dv * dt` on the RAW
 * integral, and the raw integral is internal to `applyZupt` -- the series
 * carries the corrected velocity. So this file re-integrates
 * [VelocitySeries.accelMps2] with the same trapezoid rule to recover it.
 *
 * A reconstruction that drifts from the shipped pipeline measures nothing, so
 * the first test below is the guard: on every link of every capture, the
 * reconstruction's `dv` and `dt` fed through the SHIPPED `anchorAcceptable`
 * must return exactly what `applyZupt` recorded in `anchorCapped`. If the
 * integration, the filter or the acceptance rule moves, that agreement breaks
 * here rather than quietly changing the figures in the tests underneath it.
 */
class AnchorRouteTest {
    private val cfg = DspConfig()

    /** The interval between two consecutive accepted anchors. */
    private data class Link(val from: Int, val to: Int, val dvMps: Double, val dtS: Double, val capped: Boolean) {
        /** The area of the ramp the offset subtracts across it -- the displacement it erases. */
        val erasedM: Double get() = 0.5 * dvMps * dtS
    }

    private fun rawV(s: VelocitySeries): DoubleArray {
        val dt = 1.0 / s.sampleRateHz
        val v = DoubleArray(s.size)
        for (i in 1 until s.size) v[i] = v[i - 1] + 0.5 * (s.accelMps2[i] + s.accelMps2[i - 1]) * dt
        return v
    }

    private fun links(s: VelocitySeries): List<Link> {
        val raw = rawV(s)
        return (1 until s.anchorIndices.size).map { at ->
            val from = s.anchorIndices[at - 1]
            val to = s.anchorIndices[at]
            Link(from, to, abs(raw[to] - raw[from]), s.timeS[to] - s.timeS[from], s.anchorCapped[at])
        }
    }

    private fun anchored(fixture: String): VelocitySeries {
        val case = ArtefactCorpus.cases.first { it.fixture == fixture }
        return VelocityEstimator.estimate(ArtefactCorpus.load(fixture), cfg, case.direction.measuredPlane)
    }

    private fun spansOf(fixture: String): List<RepSpan> {
        val case = ArtefactCorpus.cases.first { it.fixture == fixture }
        val series = anchored(fixture).mappedToLifter(case.direction.sensorToLifter)
        return RepSegmenter.segment(series, case.direction, cfg)
    }

    @Test
    fun `the recorded route agrees with anchorAcceptable on every link of every capture`() {
        // The drift guard for every figure below, and for RomBound's derivation.
        var checked = 0
        ArtefactCorpus.cases.forEach { case ->
            val series = anchored(case.fixture)
            assertEquals(
                series.anchorIndices.size,
                series.anchorCapped.size,
                "${case.fixture} carries a route record of a different length from its anchor list",
            )
            assertTrue(series.anchorCapped[0], "${case.fixture} origin anchor")
            links(series).forEach { link ->
                assertEquals(
                    VelocityEstimator.anchorAcceptable(link.dvMps, link.dtS, cfg),
                    link.capped,
                    "${case.fixture} link ${link.from}->${link.to}",
                )
                checked++
            }
        }
        assertEquals(236, checked, "links checked across the eleven captures")
    }

    @Test
    fun `every capture but two takes at least one anchor through the starvation escape`() {
        // Per capture, how many of its accepted anchors applied NO cap to what
        // the correction erased. `applyZupt`'s own note says 0 to 7 per capture;
        // this is that figure measured, not relayed.
        val expected = mapOf(
            "field-ohp-3010-7rep-s42-set02" to 0,
            "field-bench-3010-6rep-s42-set05" to 1,
            "field-bench-3010-6rep-s42-set07" to 1,
            "field-cablerow-3010-8rep-s42-set09" to 3,
            "field-pullup-3010-8rep-s42-set11" to 2,
            "field-pullup-4010-8rep-s42-set13" to 2,
            "field-deadlift-straight-5rep-s43-set04" to 2,
            "field-deadlift-straight-5rep-s43-set05" to 0,
            "field-deadlift-straight-5rep-s43-set06" to 1,
            "field-assistedpullup-3010-s37-set08" to 3,
            "field-ohp-prepinflated-s37-set03" to 1,
        )
        expected.forEach { (fixture, uncapped) ->
            assertEquals(uncapped, links(anchored(fixture)).count { !it.capped }, "$fixture uncapped links")
        }
        assertEquals(2, expected.count { it.value == 0 }, "captures whose every anchor was acceptable")
    }

    @Test
    fun `the three spans a route-blind rule admitted were all closed by an uncapped interval`() {
        // THE MEASUREMENT THAT MADE RomBound'S FOURTH CLAUSE NECESSARY. The
        // first three clauses alone -- an anchor at or before the span, one at
        // or after it, no other span between them -- are what a route-blind
        // record produces here, and they admitted exactly three spans in the
        // whole corpus. Every one of the three sits in an interval a starvation
        // anchor closed, so the 0.10 m the derivation cites was not applied to
        // any of them; the erased column is what was applied instead.
        val cases = listOf(
            Triple("field-pullup-3010-8rep-s42-set11", 11, 2.3153),
            Triple("field-deadlift-straight-5rep-s43-set04", 7, 1.0180),
            Triple("field-assistedpullup-3010-s37-set08", 5, 5.0198),
        )
        cases.forEach { (fixture, spanIndex, erasedM) ->
            val series = anchored(fixture)
            val spans = spansOf(fixture)
            val routeBlind = BooleanArray(series.anchorIndices.size) { true }
            assertEquals(
                listOf(spanIndex),
                RomBound.boundedFlags(spans, series.anchorIndices, routeBlind)
                    .withIndex().filter { it.value }.map { it.index },
                "$fixture spans a route-blind rule admits",
            )
            assertEquals(
                emptyList(),
                RomBound.boundedFlags(spans, series.anchorIndices, series.anchorCapped)
                    .withIndex().filter { it.value }.map { it.index },
                "$fixture spans the rule admits once the route is read",
            )
            val span = spans[spanIndex]
            val lo = minOf(span.eccStartIdx, span.conStartIdx)
            val hi = maxOf(span.eccEndIdx, span.conEndIdx)
            val chain = links(series).filter { it.from < hi && it.to > lo }
            assertTrue(chain.any { !it.capped }, "$fixture span $spanIndex has an uncapped interval")
            assertEquals(
                erasedM,
                Math.round(chain.sumOf { it.erasedM } * 10000.0) / 10000.0,
                "$fixture span $spanIndex erased displacement, against the 0.10 m cap",
            )
        }
    }

    @Test
    fun `no capture in the corpus has a bounded span once the route is read`() {
        ArtefactCorpus.cases.forEach { case ->
            val series = anchored(case.fixture)
            assertEquals(
                0,
                RomBound.boundedFlags(spansOf(case.fixture), series.anchorIndices, series.anchorCapped)
                    .count { it },
                "${case.fixture} bounded spans",
            )
        }
    }
}
