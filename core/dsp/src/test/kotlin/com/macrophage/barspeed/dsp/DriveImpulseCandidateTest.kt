package com.macrophage.barspeed.dsp

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Candidate (c) of issue #301's design round, measured: the velocity-free
 * drive-impulse counter, over the nine captures the proposal tabled and over
 * the whole corpus truth set.
 *
 * Every table in this file re-derives one in that proposal. See
 * [DriveImpulseCandidate] for the rule and for why its four constants are
 * test-local, and [CandidateCorpus] for where each capture's truth comes from.
 *
 * ## What moved on re-derivation, and what did not
 *
 * The result the recommendation rests on HELD: 5, 5 and 3 on the three
 * deadlifts, thirteen of fifteen reps with no phantom, and the set-up pull
 * excluded by the peak term alone.
 *
 * What moved is the cost side, in the direction that matters. Round 1 published
 * 7, 6, 5, 9, 9, 1 for the six non-deadlift captures of the nine and a corpus
 * row of 168 with an over-count of 13. Measured here: 8, 8, 7, 10, 11, 2, and a
 * corpus row of **180 with an over-count of 19**. Every moved cell moved UP, so
 * candidate (c) over-counts the committed corpus by half again as much as the
 * proposal claimed. The proposal's corpus shipped row, 112, does not re-derive
 * either; the shipped tracker's own `repCount` over this truth set totals
 * **108**. Round 1's harness is gone, so what caused each difference cannot be
 * established -- these are the figures with code behind them.
 *
 * ## These tables now measure the PRODUCTION class
 *
 * Two sentences stood here and are DELETED rather than reworded, because the
 * owner chose candidate (c) and they are no longer true: *"NOTHING HERE IS WIRED
 * TO ANYTHING. No production file on this branch changes."* The rule is
 * [DriveImpulseCounter] in `:core:dsp`'s main source set and its four constants
 * are [DspConfig] properties. Every assertion below is unchanged from the commit
 * that measured the model, and that is the point: the extraction is licensed by
 * these numbers re-deriving against the production class without one of them
 * moving.
 *
 * What is still true is that WHICH SETS this counter counts is decided
 * elsewhere -- `LiveCounterPolicy` in `:core:model` -- and nothing in this file
 * asserts anything about that.
 */
class DriveImpulseCandidateTest {
    private fun candidate(fixture: String): List<Double> {
        val capture = CandidateCorpus.capture(fixture)
        return DriveImpulseCandidate.callsAtS(LiveCountCandidates.load(fixture), capture.direction)
    }

    private fun shipped(fixture: String): Int =
        LiveCountCandidates.shippedCount(fixture, CandidateCorpus.capture(fixture).direction)

    /**
     * The corpus list is every committed capture, and 33 of the 45 carry a
     * truth.
     *
     * Asserted against the resource directory rather than a hand-kept number,
     * for the reason `RepRefusalCorpusTest` gives for its own list: a capture
     * committed without a row here would sit outside every total below and
     * nothing would say so.
     */
    @Test
    fun `the candidate corpus is every committed capture, 33 of them with a truth`() {
        assertEquals(FieldCorpus.onClasspath(), CandidateCorpus.ALL.map { it.fixture }.sorted())
        assertEquals(45, CandidateCorpus.ALL.size, "captures on the classpath")
        val scored = CandidateCorpus.scored()
        assertEquals(33, scored.size, "captures with a truth")
        assertEquals(
            listOf(5, 12, 16),
            listOf(
                scored.count { it.second.basis == CandidateCorpus.Basis.STATED },
                scored.count { it.second.basis == CandidateCorpus.Basis.MARKS },
                scored.count { it.second.basis == CandidateCorpus.Basis.CUES },
            ),
            "captures by truth basis: stated, marks, cue-called",
        )
    }

    /** The nine-capture table: hand count, what the sensor said, what (c) would have said. */
    @Test
    fun `the nine-capture table re-derives from the branch`() {
        val rows = CandidateCorpus.NINE.map { fixture ->
            val truth = CandidateCorpus.truth(fixture)
            listOf(
                fixture,
                truth.reps.toString(),
                truth.basis.name,
                shipped(fixture).toString(),
                candidate(fixture).size.toString(),
            )
        }
        println("capture | truth | basis | shipped live | candidate (c)")
        rows.forEach { println(it.joinToString(" | ")) }
        assertEquals(
            listOf(5, 5, 5, 6, 7, 5, 8, 8, 2),
            CandidateCorpus.NINE.map { CandidateCorpus.truth(it).reps },
            "truth, capture by capture",
        )
        assertEquals(
            listOf(3, 1, 2, 0, 3, 1, 0, 2, 1),
            CandidateCorpus.NINE.map { shipped(it) },
            "the shipped live count, capture by capture",
        )
        assertEquals(
            listOf(5, 5, 3, 8, 8, 7, 10, 11, 2),
            CandidateCorpus.NINE.map { candidate(it).size },
            "candidate (c), capture by capture",
        )
    }

    /**
     * Per rep on the three deadlifts, against the drive windows the session
     * analysis resolved -- see [CandidateCorpus.DEADLIFT_WINDOWS] for what that
     * truth is and is not.
     *
     * Set 6's two misses are its slowest pulls at 102 kg, which is the cost this
     * candidate carries into the field: an intent-threshold detector under-counts
     * a grind.
     */
    @Test
    fun `candidate c calls thirteen of the fifteen deadlift reps with no phantom`() {
        val perSet = CandidateCorpus.DEADLIFT_WINDOWS.keys.sorted().map { fixture ->
            val calls = candidate(fixture)
            println("$fixture calls at ${calls.map { (it * 100).toInt() / 100.0 }}")
            fixture to CandidateCorpus.perRep(fixture, calls)
        }
        perSet.forEach { println("${it.first} -> ${it.second}") }
        assertEquals(
            listOf(
                CandidateCorpus.PerRep(counted = 5, missed = 0, phantom = 0),
                CandidateCorpus.PerRep(counted = 5, missed = 0, phantom = 0),
                CandidateCorpus.PerRep(counted = 3, missed = 2, phantom = 0),
            ),
            perSet.map { it.second },
            "counted / missed / phantom, set 4 then 5 then 6",
        )
        assertEquals(13, perSet.sumOf { it.second.counted }, "deadlift reps called, of fifteen")
        assertEquals(0, perSet.sumOf { it.second.phantom }, "phantom calls")
    }

    /**
     * The set-up pull on set 6 is excluded by the peak term and by nothing else.
     *
     * Lowering [DspConfig.drivePeakAccelMps2] to 1.6 puts a call back on the
     * set-up pull and recovers neither of the two reps set 6 misses, so the term
     * is doing exactly one job and it is fitted to this session.
     *
     * The override is a [DspConfig] copy now that the constants live there, so
     * this test drives the same production state machine as every other row in
     * this file. It used to hold its own copy of the rule with the peak
     * parameterised -- a second statement of the decision, which is exactly what
     * moving the constants out of the harness removes.
     */
    @Test
    fun `dropping the peak term to 1_6 restores the phantom and recovers nothing`() {
        val fixture = "field-deadlift-straight-5rep-s43-set06"
        val capture = CandidateCorpus.capture(fixture)
        val relaxed = DriveImpulseCandidate.callsAtS(
            LiveCountCandidates.load(fixture),
            capture.direction,
            DspConfig(drivePeakAccelMps2 = 1.6),
        )
        println("$fixture at peak 1.6: ${relaxed.map { (it * 100).toInt() / 100.0 }}")
        val perRep = CandidateCorpus.perRep(fixture, relaxed)
        println("$fixture at peak 1.6 -> $perRep")
        assertEquals(1, perRep.phantom, "phantom calls once the peak term is relaxed")
        assertEquals(3, perRep.counted, "reps called -- unchanged, so the term recovers nothing")
    }

    /**
     * The whole corpus, 33 captures with a truth. This is the row that decides
     * the candidate cannot simply become the counter everywhere: it recovers a
     * great deal on barbell work and collapses on stack and machine work, where
     * its sensor-frame thresholds are applied through a pulley ratio.
     */
    @Test
    fun `the corpus table re-derives, and names where candidate c collapses`() {
        var truthTotal = 0
        var shippedTotal = 0
        var candidateTotal = 0
        var shippedOver = 0
        var candidateOver = 0
        var shippedMatched = 0
        var candidateMatched = 0
        println("capture | truth | basis | shipped | candidate (c)")
        for ((capture, truth) in CandidateCorpus.scored()) {
            val reps = truth.reps!!
            val live = shipped(capture.fixture)
            val drive = candidate(capture.fixture).size
            println("${capture.fixture} | $reps | ${truth.basis} | $live | $drive")
            truthTotal += reps
            shippedTotal += live
            candidateTotal += drive
            shippedOver += max(0, live - reps)
            candidateOver += max(0, drive - reps)
            shippedMatched += minOf(live, reps)
            candidateMatched += minOf(drive, reps)
        }
        println("truth $truthTotal shipped $shippedTotal/$shippedOver candidate $candidateTotal/$candidateOver")
        println("matched: shipped $shippedMatched candidate $candidateMatched of $truthTotal")
        assertEquals(253, truthTotal, "reps the corpus truth set holds")
        assertEquals(108, shippedTotal, "reps the shipped live counter reports over them")
        assertEquals(1, shippedOver, "reps the shipped counter reports beyond a capture's truth")
        assertEquals(180, candidateTotal, "reps candidate (c) reports")
        assertEquals(19, candidateOver, "reps candidate (c) reports beyond a capture's truth")
        assertEquals(107, shippedMatched, "reps the shipped counter reports within a capture's truth")
        assertEquals(161, candidateMatched, "reps candidate (c) reports within a capture's truth")
        val collapse = listOf(
            "field-legcurl-1030-10rep",
            "field-legcurl-1030-12rep",
            "field-legpress-single-2011-8rep-s36-set07",
            "field-pullup-3010-8rep-s37-set09",
        )
        assertEquals(
            listOf(10 to 2, 12 to 0, 8 to 0, 8 to 0),
            collapse.map { CandidateCorpus.truth(it).reps to candidate(it).size },
            "truth against candidate (c) on the captures it collapses on",
        )
    }
}
