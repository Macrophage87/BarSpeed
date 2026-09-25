package com.macrophage.barspeed.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Candidate (a) of issue #301's design round, measured: the leaky integrator,
 * over the nine captures the proposal tabled and over the whole corpus truth
 * set.
 *
 * See [LeakyTracker] for the clone, the equation and why the clone exists at
 * all, and [CandidateCorpus] for where each capture's truth comes from.
 *
 * The result the design round rests on is the pair of totals in `the corpus at
 * tau 2_0`: a leak buys reps on the nine and pays for them with invented reps
 * everywhere else. The per-rep splits below are the sharper version of the same
 * fact -- the sets it fixes, it fixes while also inventing a rep on them.
 *
 * ## What moved on re-derivation
 *
 * The rejection stands and its shape is unchanged; six cells of the tau table
 * moved and one total moved a long way. Round 1 published tau 1.0 ending 11, 9,
 * 1 and tau 1.5 ending 6, 1 where this measures 11, 11, 2 and 6, 2; its tau 2.0
 * row read 4, 5, 3, 5, 6, 7, 8, 5, 0 where this measures 4, 5, 4, 5, 6, 7, 10,
 * 6, 0; and its absolute-error column read 16, 13, 12, 20 against 17, 12, 12,
 * 20 here, so the best tau is 1.5 or 2.0 on a tie rather than 2.0 outright.
 * Its corpus row read **272 with an over-count of 36** against **248 and 24**
 * here, and the rear delt fly 26 against 23. (247 and 23 until issue #259's
 * three holds joined the corpus: the leak calls one rep on the second rope
 * dead hang, where nothing was lifted at all. Both figures are over the corpus
 * as it stood before issues #290 and #255 added six captures; over all 54 the
 * same row reads 279 and 26, pinned below.)
 *
 * **That correction changes an argument, not just a digit.** Counting reps
 * WITHIN each capture's truth -- `min(count, truth)` summed, pinned below -- the
 * leak matches **224 of 253** at an over-count of 23, against candidate (c)'s
 * **161 at 19** and the shipped path's **107 at 1**. (Those four rows were
 * measured over the 33 scored captures the corpus then held. Over the 46 it
 * holds since issue #259 committed three holds, each with a truth of 0,
 * issues #290 and #255 six field-42 captures and issue #278 four more base
 * captures, the same quantities re-measured at this tree read: truth
 * **338**, leak **296** matching **270** at an over-count of **26**,
 * candidate (c) **184 at 23**, shipped **137 at 1** -- the ORDER is
 * unchanged, which is what the argument below rests on.)
 * So on the committed corpus
 * the leak is the better recoverer, which is the opposite of what the proposal's
 * numbers implied, and the rejection cannot rest on the corpus total. What it
 * rests on is measured here too: the leak changes EVERY set including the ones
 * the velocity path already handles, it invents a rep on each of the three
 * deadlifts, and it still leaves reps uncounted there -- 3, 4 and 2 of 5 at tau
 * 2.0 -- where candidate (c) calls 5, 5 and 3 with no phantom. Round 1's
 * harness is gone; [LeakyTracker]'s KDoc states the equation this file
 * measures, which is the only thing that makes the difference checkable.
 *
 * NOTHING HERE IS WIRED TO ANYTHING. No production file on this branch changes.
 */
class LeakyIntegratorCandidateTest {
    private val taus = listOf(1.0, 1.5, 2.0, 2.5)

    private fun clone(fixture: String, tau: Double? = null, perRunReset: Boolean = false) = LeakyTracker(
        direction = CandidateCorpus.capture(fixture).direction,
        leakTauS = tau,
        anchorAtEveryRunBoundary = perRunReset,
    )

    private fun count(fixture: String, tau: Double? = null, perRunReset: Boolean = false): Int =
        clone(fixture, tau, perRunReset).count(LiveCountCandidates.load(fixture))

    private fun shipped(fixture: String): Int =
        LiveCountCandidates.shippedCount(fixture, CandidateCorpus.capture(fixture).direction)

    /**
     * THE LICENCE FOR EVERY OTHER FIGURE IN THIS FILE. With the leak off and the
     * per-run reset off, the clone is the shipped tracker on all 54 committed
     * captures.
     *
     * Without this the tables below would be a model of a model. It is asserted
     * over the whole classpath rather than over the nine, because a clone that
     * agreed on nine barbell captures and diverged on a stack lift would make
     * the corpus row -- the row the candidate is rejected on -- meaningless.
     */
    @Test
    fun `the clone reproduces the shipped count on every committed capture with both switches off`() {
        val divergent = CandidateCorpus.ALL.filter { count(it.fixture) != shipped(it.fixture) }
        assertEquals(emptyList(), divergent.map { it.fixture }, "captures where the clone and the shipped path differ")
    }

    /** The tau sweep over the nine, with the absolute error against truth. */
    @Test
    fun `the tau table re-derives from the branch`() {
        val truth = CandidateCorpus.NINE.map { CandidateCorpus.truth(it).reps!! }
        println("tau | ${CandidateCorpus.NINE.joinToString(" | ")} | sum abs err")
        println("truth | ${truth.joinToString(" | ")} | -")
        val shippedRow = CandidateCorpus.NINE.map { shipped(it) }
        println("shipped | ${shippedRow.joinToString(" | ")} | ${error(shippedRow, truth)}")
        val rows = taus.associateWith { tau -> CandidateCorpus.NINE.map { count(it, tau) } }
        rows.forEach { (tau, row) -> println("$tau | ${row.joinToString(" | ")} | ${error(row, truth)}") }
        assertEquals(38, error(shippedRow, truth), "the shipped path's absolute error over the nine")
        assertEquals(
            mapOf(
                1.0 to listOf(6, 6, 6, 10, 8, 8, 11, 11, 2),
                1.5 to listOf(5, 5, 4, 10, 8, 7, 10, 6, 2),
                2.0 to listOf(4, 5, 4, 5, 6, 7, 10, 6, 0),
                2.5 to listOf(4, 3, 3, 3, 4, 4, 6, 4, 0),
            ),
            rows,
            "the leak's count at each tau, capture by capture",
        )
        assertEquals(
            listOf(17, 12, 12, 20),
            taus.map { error(rows.getValue(it), truth) },
            "absolute error at tau 1.0, 1.5, 2.0, 2.5",
        )
    }

    /**
     * The per-rep split on the three deadlifts at BOTH taus, which is a
     * correction to issue #301's proposal.
     *
     * That proposal gave (a)'s per-rep split at tau 2.5 while its own best row
     * and its corpus figure were tau 2.0 -- the wrong pair, arguing a rejection
     * from a column the rejection was not measured in. The tau 2.0 split is the
     * one the rejection rests on and it is measured here.
     */
    @Test
    fun `the leak invents a rep on the sets it fixes, at tau 2_0 and at tau 2_5`() {
        val fixtures = CandidateCorpus.DEADLIFT_WINDOWS.keys.sorted()
        val splits = mutableMapOf<Double, List<CandidateCorpus.PerRep>>()
        for (tau in listOf(2.0, 2.5)) {
            val rows = fixtures.map { fixture ->
                val calls = clone(fixture, tau).callsAtS(LiveCountCandidates.load(fixture))
                println("tau $tau $fixture calls at ${calls.map { (it * 100).toInt() / 100.0 }}")
                CandidateCorpus.perRep(fixture, calls)
            }
            rows.forEachIndexed { i, split -> println("tau $tau ${fixtures[i]} -> $split") }
            splits[tau] = rows
        }
        assertEquals(
            listOf(
                CandidateCorpus.PerRep(counted = 3, missed = 2, phantom = 1),
                CandidateCorpus.PerRep(counted = 4, missed = 1, phantom = 1),
                CandidateCorpus.PerRep(counted = 2, missed = 3, phantom = 2),
            ),
            splits.getValue(2.0),
            "tau 2.0: counted / missed / phantom, set 4 then 5 then 6",
        )
        assertEquals(
            listOf(
                CandidateCorpus.PerRep(counted = 3, missed = 2, phantom = 1),
                CandidateCorpus.PerRep(counted = 3, missed = 2, phantom = 0),
                CandidateCorpus.PerRep(counted = 2, missed = 3, phantom = 1),
            ),
            splits.getValue(2.5),
            "tau 2.5: counted / missed / phantom, set 4 then 5 then 6",
        )
    }

    /**
     * The corpus at tau 2.0 -- the row candidate (a) is rejected on. It buys the
     * nine and pays for them with invented reps across the rest of the corpus,
     * the rear delt fly worst of all.
     */
    @Test
    fun `the corpus at tau 2_0 over-counts where the shipped path does not`() {
        var truthTotal = 0
        var leakTotal = 0
        var leakOver = 0
        var leakMatched = 0
        println("capture | truth | basis | shipped | leak tau 2.0")
        for ((capture, truth) in CandidateCorpus.scored()) {
            val reps = truth.reps!!
            val leak = count(capture.fixture, tau = 2.0)
            println("${capture.fixture} | $reps | ${truth.basis} | ${shipped(capture.fixture)} | $leak")
            truthTotal += reps
            leakTotal += leak
            leakOver += max(0, leak - reps)
            leakMatched += minOf(leak, reps)
        }
        println("truth $truthTotal leak $leakTotal over $leakOver matched $leakMatched")
        // Re-measured over the 58 committed captures, 46 of them scored,
        // after issue #278's four new base captures: truth 296 -> 338, leak
        // 279 -> 296, its over-count unchanged at 26, matched 253 -> 270.
        // Then issue #305's five field-44 deadlifts (4/3/8/4/3 against
        // 5/5/5/4/2): truth 338 -> 359, leak 296 -> 318, over-count 26 -> 30,
        // matched 270 -> 288.
        assertEquals(359, truthTotal, "reps the corpus truth set holds")
        assertEquals(318, leakTotal, "reps the leak reports over them")
        assertEquals(30, leakOver, "reps the leak reports beyond a capture's truth")
        assertEquals(288, leakMatched, "reps the leak reports within a capture's truth")
        val fly = "field-reardeltfly-s32-set06"
        assertEquals(
            listOf(12, 0, 23),
            listOf(CandidateCorpus.truth(fly).reps, shipped(fly), count(fly, tau = 2.0)),
            "$fly: truth, shipped, leak at tau 2.0",
        )
    }

    /**
     * The per-run reset variant, on the nine.
     *
     * A RE-MEASUREMENT, NOT A REPRODUCTION. Round 1 published 4, 1, 2, 0, 3, 1,
     * 0, 1, 1 for "a per-run reset instead of a leak" and did not record what
     * the reset's rule was. [LeakyTracker]'s KDoc states the rule this file
     * measures -- every run-classifier transition is taken as a zero, with no
     * stillness test -- and the row below is that rule's result. Where the two
     * disagree, this one is the one with code behind it.
     */
    @Test
    fun `the per-run reset variant, measured under a stated rule`() {
        val row = CandidateCorpus.NINE.map { count(it, perRunReset = true) }
        println("per-run reset | ${row.joinToString(" | ")}")
        assertEquals(listOf(3, 2, 1, 0, 1, 1, 0, 1, 0), row, "the per-run reset count, capture by capture")
    }

    private fun error(row: List<Int>, truth: List<Int>): Int = row.indices.sumOf { abs(row[it] - truth[it]) }
}
