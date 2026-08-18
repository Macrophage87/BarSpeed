package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
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
 * reading." The measurement agrees. Median bottom-turnaround dwell over the
 * session, from the app's own export:
 *
 * - single leg press  1.18 s
 * - leg press         0.16 s
 * - seated leg curl   **0.04 s**
 *
 * Forty milliseconds. The hamstrings stay loaded through the reversal, so the
 * stack never settles and velocity crosses zero without stopping there.
 *
 * That matters for what the failing assertion below is EVIDENCE OF. Phase
 * boundaries land where |v| crosses the dead band, and RepSegmentation already
 * declares that the clipping grows with the prescribed duration. What nobody
 * has separated is how much of the clip is that, and how much is a reversal
 * with no dwell to key on at all. This fixture does not settle it and no
 * assertion here should be read as settling it. See #92, which files the
 * modelling gap: nothing in the geometry model can express whether the load
 * comes off at the turnaround, and a bottomPause_s of 0.04 on a machine that
 * cannot pause is an absence rendered as a measurement.
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
    fun `the turnaround dwell this machine allows is effectively zero`() {
        // Not a defect in itself, and the reason the fixture is hard rather than
        // merely wrong. Asserted from the pipeline because there is nothing else
        // to ask, and asserted loosely because it is a statement about the
        // machine, not about a threshold: every set turns around in well under
        // the 0.16 s the leg press takes and the 1.18 s the single-leg press does.
        sets.forEach { (name, _, kg) ->
            val pauses = analyze(name, kg).reps.map { it.bottomPauseS }.sorted()
            assertTrue(
                pauses[pauses.size / 2] < 0.10,
                "$name: median bottom-turnaround dwell ${pauses[pauses.size / 2]} s",
            )
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
        // dead band, and the clipping grows with the prescribed duration -- and
        // this machine supplies a second, a 0.04 s turnaround that never
        // unloads. They are confounded on this capture. See #47.
        val measured = sets.flatMap { (name, _, kg) -> analyze(name, kg).reps.mapNotNull { it.eccS } }
        assertEquals(22, measured.size, "reps that resolved an eccentric, of 36 segmented and 36 performed")
        assertEquals(1.439, measured.average(), 5e-4, "mean resolved eccentric; the cue track says 3.003 s")
        // The gap itself, so that a change which moves the mean without closing
        // the gap cannot pass quietly. A fix takes this to zero.
        assertEquals(1.564, 3.003 - measured.average(), 1e-3, "seconds of eccentric the analyzer does not see")
    }
}
