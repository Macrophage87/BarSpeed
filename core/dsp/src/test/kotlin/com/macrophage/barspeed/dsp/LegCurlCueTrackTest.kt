package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seated leg curl at prescribed 1030, three sets, 2026-08-18 (app 0.1.39), with
 * the METRONOME CUE TRACK beside each capture.
 *
 * This is the first fixture in the corpus whose eccentric duration is known
 * independently of the pipeline. Everything else here is graded against a
 * per-set hand count, which can say how many reps happened and nothing about
 * how long any phase took; the cue track says when the app told the lifter to
 * move, to the millisecond.
 *
 * ## The cue track, and the one trap in reading it
 *
 * Tempo 1030 is down-bottom-up-top, so with `concentric: down` the drive is 1 s
 * and the lowering is 3 s. The metronome delivered exactly that -- but it emits
 * one EXTRA beat per rep for the rep announcement, so the interval between
 * movement cues is the prescribed phase plus about a second:
 *
 * - "Up" to the next "Rep N": 3.003 s, median across all three sets. This is
 *   the eccentric the lifter was counted through.
 * - "Up" to the next "Down": 4.005 s. This is the counted eccentric PLUS the
 *   announcement beat, and reading it as the prescription understates the true
 *   velocity by 25 per cent.
 * - Cycle: 5.005 s against a prescribed 4.
 *
 * Both numbers are asserted below so nobody has to choose between them from
 * memory. Which one carries the motion was settled by measurement rather than
 * preference: the filtered world-vertical linear acceleration, taken upstream
 * of the integrator and of the ZUPT stage and averaged across reps against the
 * cue, shows the eccentric impulse at the cue, a trough through 1.0-2.5 s, and
 * the concentric impulse at 3.75-4.0 s. There is no deceleration event at the
 * 3.0 s announcement, so the lowering occupies most of the interval to the next
 * movement cue. The extra beat itself is a GuidedCadenceRunner defect and is
 * not addressed here.
 *
 * ## Why this capture is also the corpus's HARDEST case
 *
 * The lifter, on this machine: "my legs are always pushing down on the machine,
 * which will affect the eccentric/concentric readings and result in a 'mushy'
 * reading."
 *
 * An earlier version of this KDoc said the measurement agreed, and gave
 * per-machine turnaround dwells of 1.18, 0.16 and 0.04 s read off the exported
 * bottomPause_s. That comparison is WITHDRAWN. Those are pipeline outputs, not
 * measurements of a turnaround. The test below pins the discrepancy instead,
 * which is the more useful fact: across these three sets bottomPause_s reads a
 * median of 0.04 s where an estimator that never touches the integrator reads
 * 0.131 s, and the same field reaches 3.01 s on a set whose entire prescribed
 * cadence is five seconds. See #93.
 *
 * So nothing here establishes that this machine settles less than any other.
 * What survives is narrower and still enough: whatever the reversal does, it
 * lasts on the order of a tenth of a second, which is short against a 3 s
 * phase however it compares to a leg press.
 *
 * That matters for what the eccentric pin below is EVIDENCE OF. Phase
 * boundaries land where |v| crosses the dead band, and RepSegmentation declares
 * the clipping grows with the prescribed duration. Whether a reversal with
 * little dwell contributes as well is NOT separable on this corpus -- the dwell
 * axis has no spread in it to separate on -- so no assertion here should be
 * read as attributing the clip to either. See #47 for that analysis and #92 for
 * the modelling gap underneath it.
 *
 * ## This recording predates the cadence fix
 *
 * The extra beat described above is issue 106, and it is fixed in the commit
 * series that added this note. The intervals here stay valid as a recording of
 * what the metronome did on 2026-08-18; a capture made afterwards will not look
 * like this.
 */
class LegCurlCueTrackTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString()

    private fun load(n: String) = ImuCsv.decode(res(n))

    /**
     * The cue track, as two columns. Deliberately local to this test and about
     * six lines long: one consumer does not earn a codec in :core:data, and the
     * format is the app's own `csvHeaderCues`, `timestamp_ms,cue`.
     */
    private fun cues(n: String): List<Pair<Long, String>> = res(n).lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("timestamp_ms") }
        .map {
            val f = it.split(',')
            f[0].toLong() to f.drop(1).joinToString(",")
        }
        .toList()

    /** The three sets, in session order, with the count the lifter performed. */
    private val sets = listOf(
        Triple("field-legcurl-1030-12rep", 12, 34.019427750752655),
        Triple("field-legcurl-1030-12rep-b", 12, 34.019427750752655),
        Triple("field-legcurl-1030-12rep-c", 12, 34.019427750752655),
    )

    /** Stack-mounted and inverted: the drive pulls the stack up as the lifter curls down. */
    private val direction = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private fun analyze(name: String, loadKg: Double) =
        SetAnalyzer.analyze(load("$name.csv"), direction, loadKg = loadKg)

    @Test
    fun `the cue track states the prescription independently of anything the pipeline computes`() {
        sets.forEach { (name, _, _) ->
            val cs = cues("$name-cues.csv")
            val moves = cs.filter { it.second == "Up" || it.second == "Down" }
            val announces = cs.filter { it.second.startsWith("Rep ") }
            val counted = mutableListOf<Double>()
            val toNextMove = mutableListOf<Double>()
            moves.filter { it.second == "Up" }.forEach { (t, _) ->
                val rep = announces.firstOrNull { it.first > t }
                val next = moves.firstOrNull { it.first > t }
                if (rep != null && next != null) {
                    counted += (rep.first - t) / 1000.0
                    toNextMove += (next.first - t) / 1000.0
                }
            }
            assertEquals(10, counted.size, "$name: cued eccentrics with an announcement after them")
            // The prescription, delivered. 3 s, to three milliseconds.
            assertEquals(3.003, counted.average(), 2e-3, "$name: cued eccentric, Up to the rep announcement")
            // And the same window read the way that would understate velocity.
            assertEquals(4.005, toNextMove.average(), 2e-3, "$name: Up to the next movement cue")
        }
    }

    @Test
    fun `bottomPause_s and the raw signal disagree about the turnaround (pre-fix)`() {
        // This used to assert that bottomPause_s stayed under 0.10 s and read
        // that as the machine never settling. bottomPause_s is not a
        // measurement of a turnaround, so the assertion is replaced by the
        // comparison that shows why -- which is also the stronger pin, because
        // it fails if EITHER quantity moves.
        //
        // The estimator below is deliberately upstream of everything under
        // suspicion: filtered world-vertical linear acceleration, no
        // integration, no ZUPT, no segmentation, no dead band. The longest
        // stretch of not-accelerating around the cue-defined end of the
        // eccentric is what a settling implement looks like. Validated on
        // synthetic sets whose dwell is set by construction, where it recovers
        // 0.00, 0.25, 0.50, 1.00 and 2.00 s to within 30 ms.
        val raw = sets.flatMap { (name, _, _) -> rawTurnaroundDwellS(name) }.sorted()
        // The seated leg curl starts concentric with the concentric going
        // DOWN, so startsAtTop is true and bottomPause_s is the turnaround
        // between the rep's two phases -- the field #93 keeps, not the one it
        // removes. Every rep that resolves both phases publishes it.
        val pipeline = sets
            .flatMap { (name, _, kg) -> analyze(name, kg).reps.map { it.bottomPauseS } }
            .filterNotNull()
            .sorted()
        assertEquals(33, raw.size, "cued eccentrics with a turnaround after them")
        assertEquals(0.131, raw[raw.size / 2], 5e-3, "median turnaround, from the raw signal")
        // 0.05, not the 0.04 this read before #93: the drive-only detections
        // used to contribute a fabricated 0.0 to this list and now contribute
        // nothing, so the list is shorter and the median moves up. It is still
        // less than half the raw signal's 0.131 s, which is the disagreement
        // this test exists to record and which #93 does not close.
        assertEquals(0.05, pipeline[pipeline.size / 2], 5e-3, "median bottomPause_s over the same reps")
        // And why the median hides it: even bounded by the rep's own phases,
        // this field is only as good as the segmentation, and on a set whose
        // whole prescribed cadence is 5 s one detection reports a turnaround of
        // 3.01 s -- a true statement about a detection that merged reps. See
        // #93, which fixes the interval and explicitly does not fix this.
        assertEquals(3.01, pipeline.max(), 5e-3, "largest bottomPause_s across the three sets")
        // The other end is the rep boundary and publishes nothing.
        val tops = sets.flatMap { (name, _, kg) -> analyze(name, kg).reps.map { it.topPauseS } }
        assertTrue(tops.isNotEmpty(), "reps resolved")
        assertTrue(tops.all { it == null }, "topPause_s is not published on a lift that turns at the bottom")
    }

    /**
     * Longest stretch around the cue-defined end of each eccentric where the
     * filtered world-vertical linear acceleration stays inside a narrow band,
     * in seconds. Upstream of the integrator, so it is independent of every
     * defect this fixture is an instrument for.
     */
    private fun rawTurnaroundDwellS(name: String): List<Double> {
        val c = DspConfig()
        val samples = load("$name.csv")
        val hz = VelocityEstimator.measureSampleRate(
            samples.size,
            (samples.last().timestampMs - samples.first().timestampMs) / 1000.0,
        )
        val filter = Biquad.lowPass(c.lowPassCutoffHz, hz)
        val acc = DoubleArray(samples.size) {
            filter.process(FrameTransform.verticalLinearAccelMps2(samples[it], c.gravityMps2))
        }
        val t0 = samples.first().timestampMs
        val moves = cues("$name-cues.csv").filter { it.second == "Up" || it.second == "Down" }
        return moves.filter { it.second == "Up" }.mapNotNull { e ->
            val next = moves.firstOrNull { it.first > e.first } ?: return@mapNotNull null
            val end = (next.first - t0) / 1000.0
            var best = 0
            var run = 0
            for (i in acc.indices) {
                val t = i / hz
                if (t < end - 0.2 || t > end + 1.0) continue
                if (abs(acc[i]) < 0.15) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            best / hz
        }
    }

    @Test
    fun `the machine has one travel and the reported ROM does not (pre-fix)`() {
        // WHAT IS PINNED HERE IS WRONG TOO, and in a way the machine settles.
        //
        // A seated leg curl's stack travel is a property of the machine, not of
        // the lifter: the same distance every rep, every set, and on this
        // machine it is 0.4-0.5 m. The reported figures run 0.102 m to 1.674 m
        // across the three sets -- a spread of sixteen times on a fixed rail.
        // 1.674 m is not a large rep, it is an impossible one: nearly four
        // times the travel the rail has. 0.102 m is a quarter of it.
        //
        // So the value a fix must produce is a distribution that sits inside
        // 0.4-0.5 m with the variation of a machine and not of an integrator.
        // The measured extremes are pinned instead, because they are what the
        // pipeline does today and a green suite is worth more than a red
        // assertion of the right answer. No plausibility WINDOW is declared as
        // a threshold anywhere: #74 is explicit that the 0.05-1.2 m window is a
        // downstream tool's declaration and not this repo's, and this repo has
        // not measured its own.
        //
        // (The app published 0.148 to 1.674 on the day. The floor moved with
        // the anchor change two commits back and the ceiling did not, which is
        // consistent with the ceiling being unanchored drift rather than
        // anything the accept rule controls.)
        //
        // The count is 36 for 36 performed, the only figure on this capture
        // that agrees with the lifter, and it agrees by cancellation: 12, 13
        // and 11 against 12, 12 and 12.
        val roms = sets.flatMap { (name, _, kg) -> analyze(name, kg).reps.map { it.romM } }
        assertEquals(36, roms.size, "reps segmented across the three sets; the lifter performed 36")
        assertEquals(0.102, roms.min(), 5e-4, "smallest reported ROM, metres")
        assertEquals(1.674, roms.max(), 5e-4, "largest reported ROM, metres")
    }

    @Test
    fun `the eccentric resolves at 1 point 4 s against a cue-verified 3 s (pre-fix)`() {
        // WHAT IS PINNED HERE IS WRONG, AND THIS IS THE ONE PLACE IN THE CORPUS
        // WHERE HOW WRONG IS KNOWN RATHER THAN SUSPECTED.
        //
        // The prescription was delivered at 3.003 s -- asserted from the cue
        // track above, not assumed -- and the lifter followed a metronome.
        // Across the three sets the analyzer resolves an eccentric on 22 of 36
        // segmented reps, and their mean is 1.439 s. That is a 52 per cent clip
        // on a phase whose true duration is independently known, and it is what
        // the lifter and the coaching model are told.
        //
        // 1.439 is pinned because a pin that fails is not a pin: this repo has
        // no baseline or ignore mechanism, so a red assertion here would make
        // "is main green" unanswerable for every branch that followed, and the
        // suite would stop being able to report the next regression. The value
        // a fix must move this to is 3.003 s, and it is written here rather
        // than left to be re-derived.
        //
        // Nothing about WHICH defect owns the clip is asserted. RepSegmentation
        // declares one mechanism -- phase boundaries land where |v| crosses the
        // dead band, and the clipping grows with the prescribed duration. A
        // reversal with little dwell is a candidate second one. The two cannot
        // be told apart on this corpus, which carries no spread on the dwell
        // axis to tell them apart with, so this pin attributes the clip to
        // neither. See #47.
        val measured = sets.flatMap { (name, _, kg) -> analyze(name, kg).reps.mapNotNull { it.eccS } }
        assertEquals(22, measured.size, "reps that resolved an eccentric, of 36 segmented and 36 performed")
        assertEquals(1.439, measured.average(), 5e-4, "mean resolved eccentric; the cue track says 3.003 s")
        // The gap itself, so that a change which moves the mean without closing
        // the gap cannot pass quietly. A fix takes this to zero.
        assertEquals(1.564, 3.003 - measured.average(), 1e-3, "seconds of eccentric the analyzer does not see")
    }
}
