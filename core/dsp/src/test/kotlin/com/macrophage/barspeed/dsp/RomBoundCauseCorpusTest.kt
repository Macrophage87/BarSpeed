package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHY [RomBound] REFUSES EACH COMMITTED REP, clause by clause, and whether a
 * floor-cycle span from [CycleRepCounter] would bound one instead. Issue #316,
 * a design round; `docs/design/316-bounded-rom.md` is the note these figures
 * belong to.
 *
 * A PURE CHARACTERIZATION PIN. It asserts what the shipped pipeline does on
 * the committed corpus and nothing about what it should do: no figure here is a
 * target, and a change that moves one is a change to re-measure, not a
 * regression by definition. Nothing in `RomBound`, the segmenter, #306's
 * gating or any `DspConfig` constant is changed by the round that adds it.
 *
 * ## The clauses, as `RomBound.bounded` states them
 *
 * A span is bounded only when an accepted anchor lies at or after its end
 * (here AFTER_LAST when none does), every interval between the bracketing
 * anchors was closed by an anchor `anchorAcceptable` accepted (UNCAPPED when a
 * starvation anchor closed one), and no other span lies wholly inside the
 * bracket (SHARED). The first clause -- an anchor at or before the start --
 * always holds, because the origin is an anchor. This file re-derives the
 * three flags separately rather than reading the one Boolean, so it can say
 * WHICH clause refused; the licence below is that its own verdict agrees with
 * `SetAnalyzer.analyze`'s `romBounded` on every capture.
 *
 * ## The erased-in-span figure
 *
 * The displacement the piecewise-linear offset removed INSIDE the span: the
 * ramp each crossed interval subtracts, measured from that interval's opening
 * anchor and integrated over the span's own samples in absolute value. The raw
 * velocity is re-integrated from the series' acceleration with
 * `applyZupt`'s trapezoid, as `AnchorRouteTest` does and licenses.
 *
 * ## The floor-cycle spans
 *
 * [CycleRepCounter]'s calls on the eight deadlift sets, through
 * `ClosingFrames` and `CycleCandidate` exactly as `ClosingRuleCandidateTest`
 * licenses them. A frame is one sample, so a call's instants map to the batch
 * series by index. Each span runs from the call's DRIVE START -- the bar on the
 * floor before a dead-stop pull, on the counter's own premise -- to the first
 * sample at or after the call that `VelocityEstimator.isQuietSample` accepts,
 * bounded by the next call's drive start. Over it the batch acceleration is
 * integrated from zero, the velocity detrended linearly to zero at the end,
 * and integrated again. Both ends are taken as rest; the true velocity change
 * across the span is then zero, so the raw `dv` is integration error.
 *
 * - erased: `0.5 * |dv| * T`, the quantity `anchorAcceptable` caps at
 *   [DspConfig.minRomM].
 * - closure: where the bar ends against where it began. A floor-to-floor
 *   cycle that is measured right returns to zero; RISE minus LOWERING.
 *
 * Re-derives by
 *
 * ```
 * ./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.RomBoundCauseCorpusTest"
 * ```
 */
class RomBoundCauseCorpusTest {
    private val cfg = DspConfig()

    private data class SpanCause(
        val afterLast: Boolean,
        val uncapped: Boolean,
        val shared: Boolean,
        val erasedInSpanM: Double,
        val conStartS: Double,
        val conEndS: Double,
    ) {
        val bounded: Boolean get() = !afterLast && !uncapped && !shared
    }

    private data class Capture(val fixture: String, val spans: List<SpanCause>, val publishedBounded: Int)

    /** `SetAnalyzer.orient`, which is private: a horizontal lift is turned so its first move reads as declared. */
    private fun orient(series: VelocitySeries, direction: LiftDirection): VelocitySeries {
        if (direction.measuredPlane != MovementPlane.HORIZONTAL) return series
        val thresholds = RunThresholds.sensorFrame(cfg)
        val firstMove = RepSegmenter.classifyRuns(series, thresholds).firstOrNull {
            it.type != RunType.STILL &&
                RepSegmenter.displacement(series, it.startIdx, it.endIdx) >= thresholds.minRomM
        } ?: return series
        val expected =
            if (direction.startsWith == StartPhase.CONCENTRIC) direction.concentricRun else direction.eccentricRun
        return if (firstMove.type == expected) series else series.mappedToLifter(-1.0)
    }

    private fun rawV(s: VelocitySeries): DoubleArray {
        val dt = 1.0 / s.sampleRateHz
        val v = DoubleArray(s.size)
        for (i in 1 until s.size) v[i] = v[i - 1] + 0.5 * (s.accelMps2[i] + s.accelMps2[i - 1]) * dt
        return v
    }

    private val corpusCache = HashMap<String, Capture>()

    private fun measure(capture: CandidateCorpus.Capture): Capture = corpusCache.getOrPut(capture.fixture) {
        val samples = LiveCountCandidates.load(capture.fixture)
        val d = capture.direction
        val raw = VelocityEstimator.estimate(samples, cfg, d.measuredPlane)
        val series = orient(raw, d).mappedToLifter(d.sensorToLifter)
        val spans = RepSegmenter.segmentDetailed(series, d, cfg).spans
        val v = rawV(raw)
        val anchors = raw.anchorIndices
        val capped = raw.anchorCapped
        val dt = 1.0 / raw.sampleRateHz
        fun lo(s: RepSpan) = minOf(s.eccStartIdx, s.conStartIdx)
        fun hi(s: RepSpan) = maxOf(s.eccEndIdx, s.conEndIdx)
        val causes = spans.map { span ->
            val firstAt = anchors.indices.last { anchors[it] <= lo(span) }
            val lastAt = anchors.indices.firstOrNull { anchors[it] >= hi(span) }
            var erased = 0.0
            for (k in lo(span) until hi(span)) {
                val at = anchors.indices.last { anchors[it] <= k }
                if (at + 1 < anchors.size) {
                    val c = anchors[at]
                    val n = anchors[at + 1]
                    erased += abs((v[n] - v[c]) * (raw.timeS[k] - raw.timeS[c]) / (raw.timeS[n] - raw.timeS[c])) * dt
                }
            }
            val bracketEnd = if (lastAt == null) Int.MAX_VALUE else anchors[lastAt]
            SpanCause(
                afterLast = lastAt == null,
                uncapped = lastAt != null && (firstAt + 1..lastAt).any { !capped[it] },
                shared = spans.any { o ->
                    (lo(o) != lo(span) || hi(o) != hi(span)) && lo(o) >= anchors[firstAt] && hi(o) <= bracketEnd
                },
                erasedInSpanM = erased,
                conStartS = Math.round(series.timeS[span.conStartIdx] * 100) / 100.0,
                conEndS = Math.round(series.timeS[span.conEndIdx] * 100) / 100.0,
            )
        }
        val published = SetAnalyzer.analyze(samples, d).reps.count { it.romBounded == true }
        Capture(capture.fixture, causes, published)
    }

    private fun corpus() = CandidateCorpus.ALL.map { measure(it) }

    @Test
    fun `the clause-by-clause verdict agrees with the shipped romBounded on every committed capture`() {
        // THE LICENCE for every figure below: a re-derivation that disagreed
        // with RomBound would be measuring a rule that does not ship. Spans
        // whose AFTER_LAST bracket has no closing anchor are judged against
        // the stream's end for SHARED, which RomBound never reaches: it
        // refuses on the missing anchor first.
        corpus().forEach { c ->
            assertEquals(c.publishedBounded, c.spans.count { it.bounded }, "${c.fixture} bounded spans")
        }
        assertEquals(CandidateCorpus.ALL.size, corpus().size, "captures measured")
    }

    @Test
    fun `which clause refuses, over the whole committed corpus`() {
        val all = corpus().flatMap { it.spans }
        val tally = mapOf(
            "spans" to all.size,
            "bounded" to all.count { it.bounded },
            "afterLast" to all.count { it.afterLast },
            "uncapped" to all.count { it.uncapped },
            "shared" to all.count { it.shared && !it.afterLast },
            "uncappedOnly" to all.count { it.uncapped && !it.shared },
            "sharedOnly" to all.count { it.shared && !it.uncapped && !it.afterLast },
            "uncappedErasedUnderMinRom" to all.count { it.uncapped && it.erasedInSpanM <= cfg.minRomM },
        )
        println("RomBoundCauseCorpusTest corpus tally $tally")
        assertEquals(CORPUS_TALLY, tally)
    }

    @Test
    fun `per capture, on the deadlift and back-squat days`() {
        val rows = corpus().filter { "deadlift" in it.fixture || "s46" in it.fixture }.associate { c ->
            c.fixture to listOf(
                c.spans.size,
                c.spans.count { it.bounded },
                c.spans.count { it.afterLast },
                c.spans.count { it.uncapped },
                c.spans.count { it.shared && !it.afterLast },
            )
        }
        rows.forEach { println("RomBoundCauseCorpusTest row $it") }
        assertEquals(LIFT_ROWS, rows)
    }

    @Test
    fun `every completed deadlift rep sits in an interval a starvation anchor or no anchor closed`() {
        // The per-rep half of the measurement on field-43 and field-44, with
        // each span labelled by DeadliftTruth's windows. Segmentation is not
        // what refuses the completed reps: a span labelled REP fails the same
        // drift clauses a NOT_REP span does, with metres erased inside it.
        val byKind = HashMap<String, MutableList<SpanCause>>()
        DeadliftTruth.SETS.forEach { fixture ->
            val c = measure(CandidateCorpus.capture(fixture))
            val windows = DeadliftTruth.WINDOWS.getValue(fixture)
            c.spans.forEach { s ->
                val kind = windows.firstOrNull { s.conStartS >= it.startS - 1e-9 && s.conEndS <= it.endS + 1e-9 }
                    ?.kind?.name ?: "UNLABELLED"
                byKind.getOrPut(kind) { mutableListOf() } += s
            }
        }
        val summary = byKind.mapValues { (_, list) ->
            listOf(
                list.size,
                list.count { it.bounded },
                list.count { it.afterLast },
                list.count { it.uncapped },
                list.count { it.shared && !it.afterLast },
            )
        }.toSortedMap()
        println("RomBoundCauseCorpusTest deadlift by kind $summary")
        assertEquals(DEADLIFT_BY_KIND, summary.toMap())
        val reps = byKind.getValue("REP").filter { it.uncapped }
        val minErased = Math.round(reps.minOf { it.erasedInSpanM } * 1000) / 1000.0
        val maxErased = Math.round(reps.maxOf { it.erasedInSpanM } * 1000) / 1000.0
        println("RomBoundCauseCorpusTest REP erased-in-span $minErased..$maxErased")
        assertEquals(REP_ERASED_RANGE, minErased to maxErased, "erased inside a completed rep's own span, m")
        assertTrue(minErased > cfg.minRomM, "no completed rep in a starvation interval had under minRomM erased")
    }

    private data class Cycle(
        val fixture: String,
        val call: Int,
        val dvMps: Double,
        val erasedM: Double,
        val riseM: Double,
        val lowerM: Double,
        val closureM: Double,
    )

    private fun cycles(): List<Cycle> = DeadliftTruth.SETS.flatMap { fixture ->
        val d = CandidateCorpus.capture(fixture).direction
        val samples = LiveCountCandidates.load(fixture)
        val frames = ClosingFrames.of(samples, d)
        val calls = ClosingFrames.calls(CycleCandidate(fallRejects = CycleRule.fallRejectsFor(d)), frames)
        val s = VelocityEstimator.estimate(samples, cfg, d.measuredPlane).mappedToLifter(d.sensorToLifter)
        val quiet = samples.map { VelocityEstimator.isQuietSample(it, cfg) }
        fun idx(t: Double) = frames.indexOfFirst { it.timeS >= t - 1e-9 }
        calls.mapIndexed { ci, c ->
            val a = idx(c.driveStartS)
            val stop = calls.getOrNull(ci + 1)?.let { idx(it.driveStartS) } ?: (samples.size - 1)
            var b = idx(c.atS)
            while (b < stop && !quiet[b]) b++
            val dt = 1.0 / s.sampleRateHz
            val n = b - a
            val v = DoubleArray(n + 1)
            for (k in 1..n) v[k] = v[k - 1] + 0.5 * (s.accelMps2[a + k] + s.accelMps2[a + k - 1]) * dt
            val dv = v[n]
            var x = 0.0
            var top = 0.0
            for (k in 1..n) {
                x += 0.5 * ((v[k - 1] - dv * (k - 1) / n) + (v[k] - dv * k / n)) * dt
                top = maxOf(top, x)
            }
            fun r(q: Double) = Math.round(q * 1000) / 1000.0
            Cycle(fixture, ci, r(dv), r(0.5 * abs(dv) * n * dt), r(top), r(top - x), r(x))
        }
    }

    @Test
    fun `no floor-cycle span on the eight deadlift sets closes within minRomM`() {
        val all = cycles()
        all.forEach { println("RomBoundCauseCorpusTest cycle $it") }
        assertEquals(CYCLE_CALLS, all.size, "cycle calls, field-44 sets 1-5 then field-43 sets 4-6")
        assertEquals(0, all.count { abs(it.closureM) <= cfg.minRomM }, "cycles that return to the floor within minRomM")
        assertEquals(
            CYCLES_ERASED_UNDER_CAP,
            all.filter { it.erasedM <= cfg.minRomM }.map { it.fixture to it.call },
            "cycles whose rest-to-rest erasure is inside the anchorAcceptable budget",
        )
        val sorted = all.map { abs(it.closureM) }.sorted()
        val spread = Triple(sorted.first(), sorted[sorted.size / 2], sorted.last())
        println("RomBoundCauseCorpusTest |closure| min, median, max $spread")
        assertEquals(CLOSURE_MIN_MEDIAN_MAX, spread, "|closure| over the 36 cycles, m")
    }

    @Test
    fun `the one cycle inside the erasure budget is a metre off closing`() {
        // Why the erasure cap alone cannot license a floor-cycle span: its net
        // velocity error is small because two large ones cancel, and the
        // displacement it leaves is still metres. RomBound's own KDoc says a
        // bounded rep is not a correct one; this is the corpus case.
        val one = cycles().single { it.erasedM <= cfg.minRomM }
        assertEquals(CANCELLED_CYCLE, one)
    }

    @Test
    fun `no deadlift rep has a rest window both on the floor before its pull and at its lockout`() {
        // The other floor-anchored span: the concentric alone, from the bar at
        // rest on the floor to the bar at rest at lockout. Rest is read the
        // way applyZupt reads it, a run of isQuietSample at least
        // DspConfig.minStationaryS long; a lone quiet sample is not rest,
        // because |a| near 1 g with little rotation is also a bar moving at
        // constant speed. The FLOOR run is the one ending at the last quiet
        // sample before the drive starts (after the previous call); the TOP
        // run starts at the first quiet sample after the drive ends, before
        // this call. Measured: of 36 calls none has both, 2 have only the
        // floor and 3 only the lockout -- the anchor supply, not the
        // arithmetic, is what is missing. Issue #316's 2026-10-03 comment
        // reports 8 of 36 deadlift reps with a 0.3 s window "on both sides";
        // its definition and population are not stated, so it is not
        // re-derived here and the two figures are not the same claim.
        var both = 0
        var floorOnly = 0
        var topOnly = 0
        DeadliftTruth.SETS.forEach { fixture ->
            val d = CandidateCorpus.capture(fixture).direction
            val samples = LiveCountCandidates.load(fixture)
            val frames = ClosingFrames.of(samples, d)
            val calls = ClosingFrames.calls(CycleCandidate(fallRejects = CycleRule.fallRejectsFor(d)), frames)
            val quiet = samples.map { VelocityEstimator.isQuietSample(it, cfg) }
            fun idx(t: Double) = frames.indexOfFirst { it.timeS >= t - 1e-9 }
            fun runS(from: Int, to: Int) = frames[to].timeS - frames[from].timeS
            calls.forEachIndexed { ci, c ->
                val floorFrom = if (ci == 0) 0 else idx(calls[ci - 1].atS)
                var q0 = idx(c.driveStartS)
                while (q0 > floorFrom && !quiet[q0]) q0--
                var r0 = q0
                while (r0 > 0 && quiet[r0 - 1]) r0--
                val call = idx(c.atS)
                var q1 = idx(c.driveEndS)
                while (q1 < call && !quiet[q1]) q1++
                var r1 = q1
                while (r1 < samples.size - 1 && quiet[r1 + 1]) r1++
                val floorRest = quiet[q0] && runS(r0, q0) >= cfg.minStationaryS
                val topRest = quiet[q1] && runS(q1, r1) >= cfg.minStationaryS
                when {
                    floorRest && topRest -> both++
                    floorRest -> floorOnly++
                    topRest -> topOnly++
                }
            }
        }
        assertEquals(REST_PAIRS, Triple(both, floorOnly, topOnly), "calls with a floor rest and a lockout rest")
    }

    private companion object {
        // Measured by this class's own command; see the KDoc.
        val CORPUS_TALLY = mapOf(
            "spans" to 528,
            "bounded" to 6,
            "afterLast" to 73,
            "uncapped" to 445,
            "shared" to 394,
            "uncappedOnly" to 55,
            "sharedOnly" to 4,
            "uncappedErasedUnderMinRom" to 30,
        )
        val LIFT_ROWS = mapOf(
            "field-backsquat-straight-s46-set01" to listOf(6, 0, 6, 0, 0),
            "field-backsquat-straight-s46-set02" to listOf(5, 0, 0, 5, 5),
            "field-backsquat-straight-s46-set03" to listOf(6, 0, 6, 0, 0),
            "field-backsquat-straight-s46-set04" to listOf(6, 0, 0, 6, 6),
            "field-backsquat-straight-s46-set05" to listOf(7, 0, 0, 7, 7),
            "field-deadlift-straight-2rep-s44-set05" to listOf(4, 0, 0, 4, 4),
            "field-deadlift-straight-4rep-s44-set04" to listOf(8, 0, 0, 8, 6),
            "field-deadlift-straight-5rep-s43-set04" to listOf(9, 0, 1, 8, 7),
            "field-deadlift-straight-5rep-s43-set05" to listOf(7, 0, 7, 0, 0),
            "field-deadlift-straight-5rep-s43-set06" to listOf(7, 0, 2, 5, 5),
            "field-deadlift-straight-5rep-s44-set01" to listOf(6, 0, 0, 6, 6),
            "field-deadlift-straight-5rep-s44-set02" to listOf(7, 0, 0, 7, 7),
            "field-deadlift-straight-5rep-s44-set03" to listOf(13, 0, 0, 13, 10),
        )
        val DEADLIFT_BY_KIND = mapOf(
            "FAILED" to listOf(1, 0, 0, 1, 1),
            "NOT_REP" to listOf(19, 0, 3, 16, 12),
            "REP" to listOf(41, 0, 7, 34, 32),
        )
        val REP_ERASED_RANGE = 0.216 to 5.728
        const val CYCLE_CALLS = 36
        val CYCLES_ERASED_UNDER_CAP = listOf("field-deadlift-straight-5rep-s44-set02" to 0)
        val CLOSURE_MIN_MEDIAN_MAX = Triple(0.143, 1.173, 2.987)
        val REST_PAIRS = Triple(0, 2, 3)
        val CANCELLED_CYCLE = Cycle("field-deadlift-straight-5rep-s44-set02", 0, -0.026, 0.054, 1.094, 0.0, 1.094)
    }
}
