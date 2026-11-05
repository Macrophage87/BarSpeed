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
 * searching no further than the next call's drive start. Over it the batch
 * acceleration is integrated from zero, the velocity detrended linearly to
 * zero at the end, and integrated again. Both ends are TREATED as rest, so the
 * raw `dv` is read as integration error. The end is at best one quiet sample,
 * not a [DspConfig.minStationaryS] run, and on 11 of the 36 calls it is not
 * quiet at all ([CycleEnd]). 6 spans run past the next drive's start: one of
 * the counter's closes is the next ARMED drive, and a drive arms after it
 * has started, so such a call lands inside the next pull.
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
        val shipped: Boolean,
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
                shipped = RomBound.bounded(span, spans, anchors, capped),
            )
        }
        val published = SetAnalyzer.analyze(samples, d).reps.count { it.romBounded == true }
        Capture(capture.fixture, causes, published)
    }

    private fun corpus() = CandidateCorpus.ALL.map { measure(it) }

    @Test
    fun `the clause-by-clause verdict agrees with the shipped romBounded on every committed capture`() {
        // THE LICENCE for every figure below: a re-derivation that disagreed
        // with RomBound would be measuring a rule that does not ship. It is
        // checked span by span against RomBound.bounded on the same spans and
        // anchors, not only as a per-capture count: with 6 bounded spans in
        // 528 most captures compare 0 to 0, so a count alone would pass a
        // re-derivation that refused too much. The count against
        // SetAnalyzer.analyze's published romBounded is kept as well, so the
        // spans and anchors used here are tied to the ones that ship. Spans
        // whose AFTER_LAST bracket has no closing anchor are judged against
        // the stream's end for SHARED, which RomBound never reaches: it
        // refuses on the missing anchor first.
        corpus().forEach { c ->
            c.spans.forEachIndexed { i, s -> assertEquals(s.shipped, s.bounded, "${c.fixture} span $i") }
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
        // Where the spans that fail on SHARED alone sit: none on a deadlift.
        val sharedOnly = corpus().associate { c ->
            c.fixture to c.spans.count { it.shared && !it.uncapped && !it.afterLast }
        }.filterValues { it > 0 }.toSortedMap().toMap()
        println("RomBoundCauseCorpusTest shared-only by capture $sharedOnly")
        assertEquals(SHARED_ONLY_BY_CAPTURE, sharedOnly)
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
        // drift clauses a NOT_REP span does. On the 34 UNCAPPED REP spans the
        // correction erased 0.216 to 5.728 m inside the span; the 7
        // AFTER_LAST ones have no closing anchor, so no erasure figure.
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
        val end: CycleEnd,
        val pastNextDrive: Boolean,
        val label: String,
    )

    /**
     * What closed a floor-cycle span. Only QUIET is the end the KDoc names.
     * AT_CALL: the call itself came at or after the next call's drive start,
     * so the forward search never ran and the span ends, not quiet, at the
     * call. NEXT_DRIVE and CAPTURE_END: the search reached that bound with no
     * quiet sample.
     */
    private enum class CycleEnd { QUIET, AT_CALL, NEXT_DRIVE, CAPTURE_END }

    /** The `DeadliftTruth` window nearest the call's drive (0 when they overlap): what the counter called on. */
    private fun labelOf(fixture: String, c: RepClosed): String {
        val nearest = DeadliftTruth.WINDOWS.getValue(fixture).minByOrNull { w ->
            when {
                c.driveEndS < w.startS -> w.startS - c.driveEndS
                c.driveStartS > w.endS -> c.driveStartS - w.endS
                else -> 0.0
            }
        }
        return nearest?.kind?.name ?: "UNLABELLED"
    }

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
            val atCall = idx(c.atS)
            var b = atCall
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
            val end = when {
                quiet[b] -> CycleEnd.QUIET
                b == atCall -> CycleEnd.AT_CALL
                ci + 1 < calls.size -> CycleEnd.NEXT_DRIVE
                else -> CycleEnd.CAPTURE_END
            }
            val pastNextDrive = ci + 1 < calls.size && b > stop
            Cycle(
                fixture, ci, r(dv), r(0.5 * abs(dv) * n * dt), r(top), r(top - x), r(x),
                end, pastNextDrive, labelOf(fixture, c),
            )
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
        // A span's end is a single quiet sample, not a minStationaryS run, and
        // on some calls there is none before the next drive or the capture's
        // end. The 0 of 36 above is on this definition.
        val ends = all.groupingBy { it.end }.eachCount().toSortedMap().toMap()
        println("RomBoundCauseCorpusTest cycle ends $ends")
        assertEquals(CYCLE_ENDS, ends, "what closed each floor-cycle span")
        val past = all.count { it.pastNextDrive }
        println("RomBoundCauseCorpusTest cycle spans running past the next drive's start $past")
        assertEquals(CYCLES_PAST_NEXT_DRIVE, past, "spans that end after the next call's drive started")
        // Calls, not reps: what each drive is nearest to in DeadliftTruth.
        val labels = all.groupingBy { it.label }.eachCount().toSortedMap().toMap()
        println("RomBoundCauseCorpusTest cycle call labels $labels")
        assertEquals(CYCLE_LABELS, labels, "truth label nearest each call's drive")
    }

    @Test
    fun `the one cycle inside the erasure budget runs past the next drive`() {
        // The one span whose rest-to-rest erasure is inside the budget. Its net
        // dv is small, yet its reconstructed displacement ends 1.094 m from its
        // start and never descends (lowering 0.000 m). It is NOT a clean
        // floor-to-floor cycle: the call came after the next call's drive had
        // started, so the span ends, not quiet, at the call and takes in part
        // of the next pull. It is pinned so that it is not read as evidence
        // either way about whether a small net dv implies a small closure.
        val one = cycles().single { it.erasedM <= cfg.minRomM }
        assertEquals(CANCELLED_CYCLE, one)
    }

    private data class RestPair(
        val fixture: String,
        val call: Int,
        val spanS: Double,
        val dvMps: Double,
        val erasedM: Double,
    )

    @Test
    fun `rest windows a concentric-only deadlift span would need`() {
        // The other floor-anchored span: the concentric alone, from the bar at
        // rest on the floor to the bar at rest at lockout. Rest is read the
        // way applyZupt reads it: VelocityEstimator.quietMask, every run of
        // anchor-candidate samples at least DspConfig.minStationaryS long. A
        // lone quiet sample is not rest, because an accelerometer cannot tell
        // rest from constant velocity (anchorAcceptable's KDoc). A call has a
        // FLOOR rest when a mask sample lies between the previous call (the
        // capture's start for the first) and this drive's start, and a TOP
        // rest when one lies between this drive's end and this call. What the
        // bar was doing in either window is the counter's premise, not
        // measured here.
        //
        // The second triple is the definition 8cbac5b8 pinned and retracted:
        // only the run NEAREST the drive on each side, judged by its length.
        // A short quiet stretch next to the drive ends that search, so it
        // misses a longer rest further out and undercounts. It is kept so the
        // two figures stay tied to their definitions.
        var both = 0
        var floorOnly = 0
        var topOnly = 0
        var nearBoth = 0
        var nearFloorOnly = 0
        var nearTopOnly = 0
        val pairs = mutableListOf<RestPair>()
        fun r(q: Double) = Math.round(q * 1000) / 1000.0
        DeadliftTruth.SETS.forEach { fixture ->
            val d = CandidateCorpus.capture(fixture).direction
            val samples = LiveCountCandidates.load(fixture)
            val frames = ClosingFrames.of(samples, d)
            val calls = ClosingFrames.calls(CycleCandidate(fallRejects = CycleRule.fallRejectsFor(d)), frames)
            val series = VelocityEstimator.estimate(samples, cfg, d.measuredPlane).mappedToLifter(d.sensorToLifter)
            val mask = VelocityEstimator.quietMask(samples, series.timeS, cfg)
            val quiet = samples.map { VelocityEstimator.isQuietSample(it, cfg) }
            fun idx(t: Double) = frames.indexOfFirst { it.timeS >= t - 1e-9 }
            fun runS(from: Int, to: Int) = frames[to].timeS - frames[from].timeS
            calls.forEachIndexed { ci, c ->
                val floorFrom = if (ci == 0) 0 else idx(calls[ci - 1].atS)
                val driveStart = idx(c.driveStartS)
                val driveEnd = idx(c.driveEndS)
                val call = idx(c.atS)
                val floorAt = (driveStart downTo floorFrom).firstOrNull { mask[it] }
                val topAt = (driveEnd..call).firstOrNull { mask[it] }
                when {
                    floorAt != null && topAt != null -> {
                        both++
                        val dt = 1.0 / series.sampleRateHz
                        var dv = 0.0
                        for (k in floorAt + 1..topAt) dv += 0.5 * (series.accelMps2[k] + series.accelMps2[k - 1]) * dt
                        val spanS = (topAt - floorAt) * dt
                        pairs += RestPair(fixture, ci, r(spanS), r(dv), r(0.5 * abs(dv) * spanS))
                    }
                    floorAt != null -> floorOnly++
                    topAt != null -> topOnly++
                }
                var q0 = driveStart
                while (q0 > floorFrom && !quiet[q0]) q0--
                var r0 = q0
                while (r0 > 0 && quiet[r0 - 1]) r0--
                var q1 = driveEnd
                while (q1 < call && !quiet[q1]) q1++
                var r1 = q1
                while (r1 < samples.size - 1 && quiet[r1 + 1]) r1++
                val floorRest = quiet[q0] && runS(r0, q0) >= cfg.minStationaryS
                val topRest = quiet[q1] && runS(q1, r1) >= cfg.minStationaryS
                when {
                    floorRest && topRest -> nearBoth++
                    floorRest -> nearFloorOnly++
                    topRest -> nearTopOnly++
                }
            }
        }
        println("RomBoundCauseCorpusTest rest pairs quietMask ${Triple(both, floorOnly, topOnly)}")
        println("RomBoundCauseCorpusTest rest pairs nearest-run ${Triple(nearBoth, nearFloorOnly, nearTopOnly)}")
        pairs.forEach { println("RomBoundCauseCorpusTest rest pair $it") }
        assertEquals(REST_PAIRS, Triple(both, floorOnly, topOnly), "calls with a floor rest and a top rest, quietMask")
        assertEquals(
            REST_PAIRS_NEAREST_RUN,
            Triple(nearBoth, nearFloorOnly, nearTopOnly),
            "calls with a floor rest and a top rest, nearest run only",
        )
        assertEquals(BOTH_REST_SPANS, pairs, "floor-rest to top-rest spans")
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
        val SHARED_ONLY_BY_CAPTURE = mapOf(
            "field-bench-rotating-6rep-ok" to 2,
            "field-rdl-3010-10rep" to 2,
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
        val CYCLE_ENDS = mapOf(CycleEnd.QUIET to 25, CycleEnd.AT_CALL to 5, CycleEnd.NEXT_DRIVE to 6)
        const val CYCLES_PAST_NEXT_DRIVE = 6
        val CYCLE_LABELS = mapOf("NOT_REP" to 1, "REP" to 35)
        val CYCLES_ERASED_UNDER_CAP = listOf("field-deadlift-straight-5rep-s44-set02" to 0)
        val CLOSURE_MIN_MEDIAN_MAX = Triple(0.143, 1.173, 2.987)
        val REST_PAIRS = Triple(4, 13, 4)
        val REST_PAIRS_NEAREST_RUN = Triple(0, 2, 3)
        val BOTH_REST_SPANS = listOf(
            RestPair("field-deadlift-straight-4rep-s44-set04", 0, 2.435, 1.744, 2.123),
            RestPair("field-deadlift-straight-2rep-s44-set05", 0, 1.407, 0.71, 0.499),
            RestPair("field-deadlift-straight-5rep-s43-set04", 0, 2.524, -0.187, 0.236),
            RestPair("field-deadlift-straight-5rep-s43-set06", 0, 1.618, 0.637, 0.516),
        )
        val CANCELLED_CYCLE = Cycle(
            "field-deadlift-straight-5rep-s44-set02", 0, -0.026, 0.054, 1.094, 0.0, 1.094,
            CycleEnd.AT_CALL, true, "REP",
        )
    }
}
