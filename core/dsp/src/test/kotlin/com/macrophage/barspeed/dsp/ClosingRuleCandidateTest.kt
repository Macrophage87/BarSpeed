package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.LiveCounter
import com.macrophage.barspeed.model.RepCounter
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #305's design round: every closing-rule candidate, measured on the
 * eight deadlift sets rep by rep, with the floor contacts removed, and over
 * the corpus. See `ClosingRuleCandidates.kt` for the frames and the rules,
 * `DeadliftTruth.kt` for the windows and the scoring rule.
 *
 * Every figure is printed as a table and the ones the proposal quotes are
 * asserted, so a change to a rule, a constant, a window or the scoring reds
 * here rather than leaving a published table standing unsupported.
 */
class ClosingRuleCandidateTest {
    private data class Row(val label: String, val make: () -> ClosingCandidate)

    private val rows = listOf(
        Row("shipped 1.0/.12/2.0/1.0") { DriveImpulseRow() },
        Row("L2 .4/.20/.6/1.0") { DriveImpulseRow(DriveImpulseRow.L2) },
        Row("(a) lockout") { LockoutCandidate() },
        Row("(b) cycle") { CycleCandidate() },
        Row("(c) height") { HeightCandidate() },
        Row("(d) contact") { ContactCandidate() },
        Row("(e1) cycle+height") { CycleCandidate(minHeightM = 0.25) },
        Row("(e2) cycle, set-relative drive") { CycleCandidate(relativeDrive = 0.35) },
        Row("(b) without the duration bound") { CycleCandidate(DspConfig(cycleMinCycleS = 0.0)) },
        // A zero need switches the descent clause off: the integral starts at
        // the brake run's own, at most -cycleBrakeLossMps, and only falls.
        Row("(b) without the descent clause") { CycleCandidate(DspConfig(cycleDescentMps = 0.0)) },
        Row("(b) without the fall rejection") { CycleCandidate(fallRejects = false) },
    )

    private val framesCache = HashMap<String, List<LiveFrame>>()

    private fun frames(fixture: String): List<LiveFrame> = framesCache.getOrPut(fixture) {
        ClosingFrames.of(LiveCountCandidates.load(fixture), CandidateCorpus.capture(fixture).direction)
    }

    private fun perRep(row: Row, contactFree: Boolean): List<DeadliftTruth.Score> = DeadliftTruth.SETS.map { set ->
        val f = if (contactFree) ClosingFrames.contactFree(frames(set)) else frames(set)
        DeadliftTruth.score(set, ClosingFrames.calls(row.make(), f))
    }

    /**
     * The licence for the two drive-impulse rows. [DriveImpulseRow] over the
     * harness's frames calls at exactly the instants the production
     * `DriveImpulseCounter` does when fed by `StreamingSetTracker.forLift` --
     * the app's tracker -- on all eight sets, 5, 5, 5, 0, 0 and 5, 5, 3.
     *
     * Built BY NAME, `LiveRepCounters.of(DRIVE_IMPULSE)`, not through
     * `forCounted(SENSOR)`: the row is the impulse detector's whatever the
     * policy arms, so this licence must not follow the policy when it moves.
     * Until #305's implement round it read `forCounted(SENSOR)`, which armed
     * that detector, so the two expressions built the same counter.
     */
    @Test
    fun `the harness reproduces the drive-impulse counter's live path on all eight deadlift sets`() {
        val counts = DeadliftTruth.SETS.map { set ->
            val direction = CandidateCorpus.capture(set).direction
            val app = appCalls(LiveRepCounters.of(LiveCounter.DRIVE_IMPULSE, direction), set, direction)
            assertEquals(app, ClosingFrames.calls(DriveImpulseRow(), frames(set)).map { it.atS }, "$set: call instants")
            app.size
        }
        assertEquals(listOf(5, 5, 5, 0, 0, 5, 5, 3), counts, "live calls, field-44 sets 1-5 then field-43 sets 4-6")
    }

    /**
     * The licence for every (b) row, sweep and ablation. [CycleCandidate]
     * over the harness's frames calls at exactly the instants the production
     * [CycleRepCounter] does when fed by the app's tracker, and ends on the
     * same [CycleRepCounter.called] -- over all 63 committed captures, not only
     * the eight deadlifts, because the corpus row scores every one of them.
     *
     * Two computations are compared, not one read twice: the harness takes the
     * raw-sample magnitude and quiet flag from the sample itself
     * ([ClosingFrames.of]), and the production path reads them off the
     * [LiveSetState] the tracker publishes. And equal `called` totals rule out
     * a frame on which the rule closed two reps at once, which the harness
     * would score as one.
     */
    @Test
    fun `the cycle candidate reproduces the production cycle counter on every committed capture`() {
        var calls = 0
        for (capture in CandidateCorpus.ALL) {
            val counter = LiveRepCounters.of(LiveCounter.CYCLE, capture.direction) as CycleRepCounter
            val app = appCalls(counter, capture.fixture, capture.direction)
            val harness = ClosingFrames.calls(CycleCandidate(), frames(capture.fixture)).map { it.atS }
            assertEquals(app, harness, "${capture.fixture}: call instants")
            assertEquals(app.size, counter.called, "${capture.fixture}: one call per spoken number")
            calls += app.size
        }
        assertEquals(CYCLE_CORPUS_CALLS, calls, "cycle calls across all ${CandidateCorpus.ALL.size} captures")
    }

    /** The reconstructed-clock instants [counter] speaks at over [fixture], fed by the app's own tracker. */
    private fun appCalls(counter: LiveRepCounter, fixture: String, direction: LiftDirection): List<Double> {
        val tracker = StreamingSetTracker.forLift(direction)
        return LiveCountCandidates.load(fixture).mapNotNull { sample ->
            val live = tracker.feed(sample)
            live.elapsedS.takeIf { counter.feed(live, sample.timestampMs) is RepCall.Speak }
        }
    }

    /**
     * Field-44's windows are the batch path's spans, not numbers typed from a
     * report. Every bound in [DeadliftTruth.FIELD_44] is a span start or end
     * the segmenter produces on role a, to the hundredth of a second, and every
     * span the segmenter produces is inside exactly one window.
     */
    @Test
    fun `field-44's windows are the batch spans, labelled`() {
        val deadlift = CandidateCorpus.capture(DeadliftTruth.SETS[0]).direction
        DeadliftTruth.FIELD_44.forEach { (set, windows) ->
            val samples = LiveCountCandidates.load(set)
            val raw = VelocityEstimator.estimate(samples, DspConfig(), deadlift.measuredPlane)
            val series = raw.mappedToLifter(deadlift.sensorToLifter)
            fun at(i: Int) = Math.round(series.timeS[i] * 100) / 100.0
            val spans = RepSegmenter.segmentDetailed(series, deadlift, DspConfig()).spans.map {
                at(it.conStartIdx) to at(it.conEndIdx)
            }
            val starts = spans.map { it.first }.toSet()
            val ends = spans.map { it.second }.toSet()
            windows.forEach { w ->
                assertTrue(w.startS in starts && w.endS in ends, "$set: window $w is built from batch spans $spans")
            }
            spans.forEach { (a, b) ->
                val holding = windows.count { a >= it.startS && b <= it.endS }
                assertEquals(1, holding, "$set: span $a-$b in exactly one window")
            }
        }
        assertEquals(
            listOf(5, 5, 5, 4, 2),
            DeadliftTruth.FIELD_44.values.map { list -> list.count { it.kind == DeadliftTruth.Kind.REP } },
            "completed reps per field-44 set, the owner's settled count",
        )
    }

    /**
     * The table the proposal publishes: counted / missed / phantom per set, and
     * `F1` where the failed attempt was CALLED. 36 completed reps and one
     * failed attempt across the eight sets.
     *
     * Read the (b) rows first. The cycle counts 35 of 36 with one phantom --
     * field-43 set 6's set-up pull, the one the shipped counter excludes only
     * by its 2.0 m/s^2 peak term -- and refuses the failed attempt. Its three
     * ablations say which clause does what: without the duration bound the
     * failed attempt is called; without the descent clause two set-downs are;
     * without the fall rejection the contact still refuses the failed attempt
     * as recorded, and nothing refuses it once the contacts are removed.
     */
    @Test
    fun `the per-rep table on the eight deadlift sets, with and without floor contacts`() {
        val totals = mutableMapOf<Pair<String, Boolean>, String>()
        val perSet = mutableMapOf<Pair<String, Boolean>, List<String>>()
        for (contactFree in listOf(false, true)) {
            println(if (contactFree) "CONTACT-FREE" else "AS RECORDED")
            println("candidate | f44 s1 | s2 | s3 | s4 | s5 | f43 s4 | s5 | s6 | total c/m/p F | median lag s")
            rows.forEach { row ->
                val scores = perRep(row, contactFree)
                val total = scores.fold(DeadliftTruth.Score.ZERO) { a, b -> a + b }
                val lags = total.lagsS.sorted()
                val median = if (lags.isEmpty()) Double.NaN else lags[lags.size / 2]
                println("${row.label} | ${scores.joinToString(" | ")} | $total | ${"%.2f".format(median)}")
                totals[row.label to contactFree] = total.toString()
                perSet[row.label to contactFree] = scores.map { it.toString() }
            }
        }
        val expected = mapOf(
            "shipped 1.0/.12/2.0/1.0" to ("28/8/0" to "19/17/0"),
            "L2 .4/.20/.6/1.0" to ("34/2/1 F1" to "30/6/1 F1"),
            "(a) lockout" to ("10/26/2" to "9/27/1"),
            "(b) cycle" to ("35/1/1" to "31/5/2"),
            "(c) height" to ("27/9/9" to "27/9/5"),
            "(d) contact" to ("30/6/2 F1" to "0/36/0"),
            "(e1) cycle+height" to ("28/8/1" to "26/10/1"),
            "(e2) cycle, set-relative drive" to ("35/1/1" to "31/5/1"),
            "(b) without the duration bound" to ("35/1/1 F1" to "31/5/2 F1"),
            "(b) without the descent clause" to ("35/1/3" to "31/5/3"),
            "(b) without the fall rejection" to ("35/1/1" to "31/5/2 F1"),
        )
        expected.forEach { (label, pair) ->
            assertEquals(pair.first, totals[label to false], "$label: total as recorded")
            assertEquals(pair.second, totals[label to true], "$label: total contact-free")
        }
        assertEquals(
            listOf("5/0/0", "5/0/0", "5/0/0", "0/4/0", "0/2/0", "5/0/0", "5/0/0", "3/2/0"),
            perSet["shipped 1.0/.12/2.0/1.0" to false],
            "the shipped counter set by set -- field-43's three reproduce #301's per-rep figures",
        )
        assertEquals(
            listOf("5/0/0", "5/0/0", "5/0/0", "4/0/0", "2/0/0", "5/0/0", "5/0/0", "4/1/1"),
            perSet["(b) cycle" to false],
            "the cycle set by set, as recorded",
        )
        assertEquals(
            listOf("4/1/0", "4/1/0", "4/1/1", "4/0/0", "2/0/0", "4/1/0", "5/0/0", "4/1/1"),
            perSet["(b) cycle" to true],
            "the cycle set by set, contact-free",
        )
    }

    /**
     * The corpus row: all 51 scored captures, truth 359, and separately the
     * eight deadlift sets -- the only scored captures `LiveCounterPolicy` routes
     * to the live counter today (rep-based, no prescribed tempo, IMU), truth
     * 36. Every other scored capture is tempo'd and counted by the metronome,
     * so the lifter did not hear a live counter on it. But the routing does not
     * need to widen to reach those exercises: `LiveCounterPolicy` sends every
     * [RepCounter.SENSOR] set -- IMU connected, not timed, a DYNAMIC or EXPLOSIVE
     * exercise with no tempo (an EXPLOSIVE one even with a tempo) -- to the live
     * counter whatever the exercise, so a candidate that replaced DRIVE_IMPULSE
     * would count an untempo'd leg curl today. The tempo'd rows are the only
     * non-deadlift evidence; untempo'd machine sets are unmeasured (#303).
     * The routed "over" is at count level; the per-rep
     * table is what shows a phantom hiding behind a miss.
     */
    @Test
    fun `the corpus over-count, over every scored capture and over the sets routed to the live counter`() {
        println("candidate | corpus truth | reported | over | matched | routed truth | reported | over")
        val corpus = mutableMapOf<String, List<Int>>()
        rows.forEach { row ->
            var truth = 0
            var reported = 0
            var over = 0
            var matched = 0
            var routedTruth = 0
            var routedReported = 0
            var routedOver = 0
            for ((capture, t) in CandidateCorpus.scored()) {
                val reps = t.reps!!
                val n = ClosingFrames.calls(row.make(), frames(capture.fixture)).size
                truth += reps
                reported += n
                over += max(0, n - reps)
                matched += minOf(n, reps)
                if (capture.fixture in DeadliftTruth.SETS) {
                    routedTruth += reps
                    routedReported += n
                    routedOver += max(0, n - reps)
                }
            }
            println(
                "${row.label} | $truth | $reported | $over | $matched | $routedTruth | $routedReported | $routedOver",
            )
            corpus[row.label] = listOf(reported, over, matched, routedReported, routedOver)
        }
        val expected = mapOf(
            "shipped 1.0/.12/2.0/1.0" to listOf(222, 23, 199, 28, 0),
            "L2 .4/.20/.6/1.0" to listOf(350, 54, 296, 36, 0),
            "(a) lockout" to listOf(227, 25, 202, 12, 0),
            "(b) cycle" to listOf(310, 16, 294, 36, 0),
            "(c) height" to listOf(237, 12, 225, 36, 2),
            "(d) contact" to listOf(165, 6, 159, 33, 2),
            "(e1) cycle+height" to listOf(204, 3, 201, 29, 0),
            "(e2) cycle, set-relative drive" to listOf(309, 17, 292, 36, 0),
            "(b) without the duration bound" to listOf(369, 59, 310, 37, 1),
            "(b) without the descent clause" to listOf(369, 45, 324, 38, 2),
            "(b) without the fall rejection" to listOf(311, 17, 294, 36, 0),
        )
        expected.forEach { (label, figures) ->
            assertEquals(figures, corpus[label], "$label: corpus reported/over/matched, routed reported/over")
        }
    }

    /**
     * The instants. The cycle speaks as the bar lands: on field-44 set 4 at the
     * floor contact that ends each rep, 2.2-2.6 s after the drive. Where a
     * light rep lands without a contact, a still or the next drive closes it,
     * and on field-44 set 1 reps 1 and 3 are only spoken at the next rep's
     * brake -- the count is right and the number is a rep late.
     */
    @Test
    fun `the cycle's calls rep by rep, against the shipped counter's`() {
        listOf(rows[0], rows[3]).forEach { row ->
            DeadliftTruth.SETS.forEach { set ->
                val windows = DeadliftTruth.WINDOWS.getValue(set)
                val calls = ClosingFrames.calls(row.make(), frames(set))
                val line = calls.joinToString(" ") { c ->
                    val w = windows.minBy { w ->
                        maxOf(0.0, w.startS - c.driveEndS, c.driveStartS - w.endS)
                    }
                    "%.2f[drive %.2f-%.2f -> %s %.2f-%.2f]".format(
                        c.atS,
                        c.driveStartS,
                        c.driveEndS,
                        w.kind,
                        w.startS,
                        w.endS,
                    )
                }
                println("CALLS ${row.label} | $set | $line")
            }
        }
        val cycleAt = { set: String -> ClosingFrames.calls(CycleCandidate(), frames(set)).map { r2(it.atS) } }
        assertEquals(listOf(11.06, 15.98, 21.55, 50.87), cycleAt(DeadliftTruth.SETS[3]), "111 kg")
        assertEquals(listOf(8.71, 13.42), cycleAt(DeadliftTruth.SETS[4]), "120 kg: the third pull silent")
        assertEquals(
            listOf(8.39, 9.53, 14.35, 15.59, 18.75),
            cycleAt(DeadliftTruth.SETS[0]),
            "61 kg: reps 1 and 3 close only at the next rep's brake",
        )
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    /**
     * How near the edge each fitted constant sits, one at a time. No variant
     * here calls the failed attempt -- asserted -- while the completed-rep and
     * corpus figures move; the table is printed, not pinned, because it is the
     * evidence for the constants rather than a behaviour anything relies on.
     */
    @Test
    fun `the cycle's fitted constants, one at a time`() {
        val base = DspConfig()
        val sweeps = listOf(
            "cycleMinCycleS" to listOf(0.8, 1.0, 1.2, 1.4, 1.6).map { it to base.copy(cycleMinCycleS = it) },
            "cycleFallG" to listOf(0.25, 0.4, 0.55, 0.7).map { it to base.copy(cycleFallG = it) },
            "cycleDescentMps" to listOf(0.6, 0.9, 1.2, 1.4).map { it to base.copy(cycleDescentMps = it) },
            "cycleDriveGainMps" to listOf(0.08, 0.12, 0.16, 0.2, 0.3).map { it to base.copy(cycleDriveGainMps = it) },
            "cycleRunThresholdMps2" to listOf(0.1, 0.15, 0.25, 0.4).map { it to base.copy(cycleRunThresholdMps2 = it) },
            "cycleClipMps2" to listOf(0.5, 1.0, 2.0, 1000.0).map { it to base.copy(cycleClipMps2 = it) },
            "cycleMaxGapS" to listOf(1.0, 1.5, 2.0).map { it to base.copy(cycleMaxGapS = it) },
        )
        sweeps.forEach { (name, variants) ->
            variants.forEach { (value, config) ->
                val params = "= $value"
                val row = Row(name) { CycleCandidate(config) }
                val recorded = perRep(row, false).fold(DeadliftTruth.Score.ZERO) { a, b -> a + b }
                val free = perRep(row, true).fold(DeadliftTruth.Score.ZERO) { a, b -> a + b }
                var over = 0
                for ((capture, t) in CandidateCorpus.scored()) {
                    over += max(0, ClosingFrames.calls(row.make(), frames(capture.fixture)).size - t.reps!!)
                }
                println("SWEEP $name $params -> recorded $recorded | contact-free $free | corpus over $over")
                assertEquals(0, recorded.failedCalled + free.failedCalled, "$name $params calls the failed attempt")
            }
        }
    }

    /** Every scored capture where the cycle's count differs from the shipped counter's, printed. */
    @Test
    fun `where the cycle and the shipped counter part company on the corpus`() {
        for ((capture, t) in CandidateCorpus.scored()) {
            val shipped = ClosingFrames.calls(rows[0].make(), frames(capture.fixture)).size
            val cycle = ClosingFrames.calls(rows[3].make(), frames(capture.fixture)).size
            if (shipped != cycle) println("DIFF ${capture.fixture} | truth ${t.reps} | shipped $shipped | cycle $cycle")
        }
        val cycle = { f: String -> ClosingFrames.calls(CycleCandidate(), frames(f)).size }
        assertEquals(
            listOf(9, 11, 9, 6),
            listOf("10rep", "12rep", "12rep-b", "12rep-c").map { cycle("field-legcurl-1030-$it") },
            "leg curls 10/12/12/12: the cycle does not collapse on the stack the way the shipped counter does",
        )
        assertEquals(
            listOf(1, 1),
            listOf("field-ropedeadhang-hold45-s38-set17", "field-ropedeadhang-hold45-s38-set18").map(cycle),
            "one phantom on each of two rope dead hangs, where nothing is lifted",
        )
    }

    /**
     * WHY the cycle refuses field-44 set 5's third pull, on the event stream
     * itself. Its drive (17.23-17.72 s) gains 0.23 m/s -- more than rep 1's
     * 0.16 -- so no drive threshold separates it. What does is time: the
     * 5-frame mean magnitude falls under 0.4 g at 18.21 s, 0.49 s after the
     * drive ends, and the bar meets the floor at 18.41 s, 0.69 s after. Either
     * lands inside `minCycleS`, so the drive is dropped before its brake -- the
     * fall itself -- can arm it. The completed reps' drives arm only at the END
     * of a negative run that spans brake and lowering (8.03 and 13.28 s), and
     * close at the next floor event.
     *
     * A FALL here is a low 5-frame mean, and it also fires in the ringing after
     * a contact (18.56 s): rep 2's call at 13.42 s is closed by one.
     */
    @Test
    fun `the failed pull is dropped by the fall that follows its drive, before a brake can arm it`() {
        val set = DeadliftTruth.SETS[4]
        val log = mutableListOf<String>()
        val probe = object : DriveBrakeTracker(DspConfig()) {
            override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
                log += "armed %.2f-%.2f +%.2f at %.2f".format(armed.startS, armed.endS, armed.gainMps, t)
                return null
            }

            override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
                if (t in 17.0..18.6) log += "$event %.2f".format(t)
                return null
            }
        }
        frames(set).forEach { probe.feed(it) }
        println(log)
        assertEquals(
            listOf(
                "armed 5.35-5.64 +0.16 at 8.03",
                "armed 10.66-11.19 +0.35 at 13.28",
                "FALL 18.21",
                "CONTACT 18.41",
                "FALL 18.56",
                "armed 17.23-17.72 +0.23 at 18.75",
            ),
            log,
            "the event stream the cycle reads on the 120 kg set",
        )
        assertEquals(emptyList(), ClosingFrames.calls(CycleCandidate(), frames(set)).filter { it.driveStartS > 15.0 })
    }

    /**
     * What (a) can see, drive by drive. (a) looks for its stillness only once a
     * drive has ARMED, at the end of its brake run, and drops a drive that arms
     * more than `maxLockS` (2.0 s) after its own end. For every armed drive on
     * the eight sets, with (a)'s own 0.15 s still, this prints the window it
     * matches, the gap from the drive's end to its arming, whether a 0.15 s
     * still completed between the drive's end and the arming (which (a) never
     * sees), the first floor event after arming, and the gap from the drive's
     * end to the next CONTACT. The tallies are pinned so the breakdown the
     * proposal quotes re-derives here.
     */
    @Test
    fun `what the lockout model can see after each drive arms`() {
        data class Armed(val endS: Double, val atS: Double, val kind: String)
        val tally = sortedMapOf<String, Int>()
        val lagsBySet = mutableMapOf<String, List<Double>>()
        val repContactGaps = mutableListOf<Double>()
        DeadliftTruth.SETS.forEach { set ->
            val drives = mutableListOf<Armed>()
            val events = mutableListOf<Pair<Double, String>>()
            val windows = DeadliftTruth.WINDOWS.getValue(set)
            val probe = object : DriveBrakeTracker(DspConfig(cycleStillS = 0.15)) {
                override fun onArmed(armed: Drive, brakeIntegral: Double, t: Double): RepClosed? {
                    val w = windows.minBy { w -> maxOf(0.0, w.startS - armed.endS, armed.startS - w.endS) }
                    val gap = maxOf(0.0, w.startS - armed.endS, armed.startS - w.endS)
                    val kind = if (gap > CandidateCorpus.CALL_TOLERANCE_S) "UNMATCHED" else w.kind.name
                    drives += Armed(armed.endS, t, kind)
                    return null
                }

                override fun onEvent(event: FloorEvent, t: Double): RepClosed? {
                    events += t to event.name
                    return null
                }
            }
            frames(set).forEach { probe.feed(it) }
            val lines = drives.map { a ->
                val lag = r2(a.atS - a.endS)
                val stillBefore = events.any { (t, e) -> e == "STILL" && t > a.endS && t < a.atS }
                val next = events.firstOrNull { it.first >= a.atS }?.second ?: "none"
                val contact = events.firstOrNull { (t, e) -> e == "CONTACT" && t > a.endS }?.first?.minus(a.endS)
                if (a.kind == "REP" && contact != null) repContactGaps += r2(contact)
                val key = when {
                    lag > 2.0 -> "armed after 2.0 s"
                    stillBefore -> "still before arming"
                    else -> "first event after arming $next"
                }
                tally.merge("${a.kind}: $key", 1, Int::plus)
                "${a.kind} armed +%.2f still-before=$stillBefore next=$next contact +%s".format(
                    lag,
                    contact?.let { "%.2f".format(it) } ?: "none",
                )
            }
            lagsBySet[set] = drives.map { r2(it.atS - it.endS) }
            println("LOCKOUT $set | ${lines.joinToString(" ; ")}")
        }
        println("LOCKOUT tally $tally")
        println("LOCKOUT completed reps, drive end -> next contact, sorted ${repContactGaps.sorted()}")
        assertEquals(
            listOf(2.39, 2.09, 1.03),
            lagsBySet.getValue(DeadliftTruth.SETS[4]),
            "120 kg, as the event pin above",
        )
        assertEquals(LOCKOUT_TALLY, tally.toString(), "what (a) sees after each armed drive, eight sets")
    }

    private companion object {
        /**
         * The cycle counter's calls over all 63 committed captures, measured by
         * this class's own command. Not a score -- 12 of the 63 carry no
         * truth -- but a floor under the licence: an equality that compared
         * two empty lists 63 times would pass and say nothing.
         */
        const val CYCLE_CORPUS_CALLS = 373

        /**
         * Measured by this class's own command. Of the 35 completed reps whose
         * drive arms, 2 arm after 2.0 s (field-44 set 5), 12 finish a 0.15 s
         * still between the drive's end and the arming, and of the other 21 the
         * first event after arming is a CONTACT on 14 and a STILL on 7.
         */
        const val LOCKOUT_TALLY = "{FAILED: first event after arming FALL=1, " +
            "NOT_REP: first event after arming CONTACT=2, NOT_REP: first event after arming STILL=2, " +
            "REP: armed after 2.0 s=2, REP: first event after arming CONTACT=14, " +
            "REP: first event after arming STILL=7, REP: still before arming=12}"
    }
}
