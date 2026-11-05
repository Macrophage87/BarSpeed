package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE AFTER HALF OF THE CORPUS TABLE for issues #290 and #255:
 * what each of the eleven captures publishes once a rep whose own span carries
 * an out-of-range sample is kept out of the set's peak pair.
 * `ArtefactCorpusBaselineTest` is the before half and carries every capture's
 * provenance and licence.
 *
 * Three things are asserted here and the third is the one a reviewer should
 * check hardest.
 *
 * 1. EVERY REP COUNT IS UNCHANGED, on all eleven. Nothing is substituted, so
 *    the velocity series the segmenter runs on is bit-identical and no
 *    detection can move. That is asserted against the baseline file's own
 *    counts rather than argued.
 * 2. The published peak pair moves on five of the eleven, and each figure is
 *    named with what it was.
 * 3. IT DOES NOT MOVE ON THE OTHER SIX, and five of those six are still
 *    implausible. They are listed as survivors rather than left out of the
 *    table. `AccelArtefact.corruptedSpan`'s KDoc carries why the narrower rule
 *    ships anyway, and `ArtefactRuleAlternativesTest` measures the alternative.
 *
 * ## After #306: the in-span clause's own table, and what each set publishes
 *
 * Issue #306 added two clauses to the rule -- the guard band before a span
 * and the displacement bound -- and not one rep of these eleven is bounded,
 * so every capture here now publishes NO peak pair and no terminal-loss
 * figure. A table of eleven absences would pin nothing about the clause this
 * file was written for. So the #290 figures are asserted with the other two
 * inputs masked ([inSpanOnly]): they are what the in-span clause alone keeps,
 * and deleting that clause reds them. What each set actually publishes is
 * asserted beside them.
 */
class ArtefactPeakWithholdingTest {
    private fun case(fixture: String) = ArtefactCorpus.cases.first { it.fixture == fixture }

    private fun analyse(fixture: String) = ArtefactCorpus.analyse(case(fixture))

    /** [reps] with #306's band and bound masked, so the in-span clause alone decides. */
    private fun inSpanOnly(reps: List<RepAnalysis>) = reps.map { it.copy(guardArtefactSamples = 0, romBounded = true) }

    private fun publishedPeakPowerW(a: SetAnalysis) =
        AccelArtefact.peakEligible(inSpanOnly(a.reps)).mapNotNull { it.peakPowerW }.maxOrNull()

    private fun publishedPeakConVelMps(a: SetAnalysis) =
        AccelArtefact.peakEligible(inSpanOnly(a.reps)).maxOfOrNull { it.peakConVelMps }

    private fun beforePeakPowerW(a: SetAnalysis) = a.reps.mapNotNull { it.peakPowerW }.maxOrNull()

    private fun beforePeakConVelMps(a: SetAnalysis) = a.reps.maxOfOrNull { it.peakConVelMps }

    /**
     * The rep count of every one of the eleven, before and after, in one
     * assertion. A count that moved would mean something upstream of the
     * segmenter moved, which nothing in this rule touches.
     */
    @Test
    fun `no rep count in the corpus moves`() {
        assertEquals(
            mapOf(
                "field-ohp-3010-7rep-s42-set02" to 9,
                "field-bench-3010-6rep-s42-set05" to 5,
                "field-bench-3010-6rep-s42-set07" to 6,
                "field-cablerow-3010-8rep-s42-set09" to 4,
                "field-pullup-3010-8rep-s42-set11" to 9,
                "field-pullup-4010-8rep-s42-set13" to 9,
                "field-deadlift-straight-5rep-s43-set04" to 9,
                "field-deadlift-straight-5rep-s43-set05" to 7,
                "field-deadlift-straight-5rep-s43-set06" to 7,
                "field-assistedpullup-3010-s37-set08" to 7,
                "field-ohp-prepinflated-s37-set03" to 11,
            ),
            ArtefactCorpus.cases.associate { it.fixture to ArtefactCorpus.analyse(it).reps.size },
            "detections per capture -- identical to ArtefactCorpusBaselineTest's",
        )
    }

    /**
     * The set-level and per-rep artefact counts every capture publishes.
     *
     * The set count is over the WHOLE analysed stream and the per-rep counts
     * are over each rep's own span, so the per-rep counts need not sum to the
     * set's -- field-42 set 9 carries seven in the stream and none in any rep,
     * because all seven sit in the countdown movement its work-start instant
     * excludes.
     */
    @Test
    fun `what each capture counts, per set and per rep`() {
        assertEquals(
            mapOf(
                "field-ohp-3010-7rep-s42-set02" to (3 to listOf(0, 0, 0, 1, 0, 1, 0, 0, 0)),
                "field-bench-3010-6rep-s42-set05" to (4 to listOf(1, 0, 0, 1, 0)),
                "field-bench-3010-6rep-s42-set07" to (5 to listOf(0, 0, 0, 0, 0, 1)),
                "field-cablerow-3010-8rep-s42-set09" to (7 to listOf(0, 0, 0, 0)),
                "field-pullup-3010-8rep-s42-set11" to (5 to listOf(0, 0, 1, 0, 0, 0, 0, 0, 0)),
                "field-pullup-4010-8rep-s42-set13" to (2 to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0)),
                "field-deadlift-straight-5rep-s43-set04" to (5 to listOf(0, 1, 0, 0, 1, 0, 1, 1, 0)),
                "field-deadlift-straight-5rep-s43-set05" to (18 to listOf(0, 2, 1, 2, 3, 4, 6)),
                "field-deadlift-straight-5rep-s43-set06" to (10 to listOf(2, 0, 1, 2, 0, 3, 0)),
                "field-assistedpullup-3010-s37-set08" to (1 to listOf(0, 0, 0, 0, 0, 1, 0)),
                "field-ohp-prepinflated-s37-set03" to (7 to listOf(1, 0, 1, 0, 0, 0, 2, 0, 1, 0, 1)),
            ),
            ArtefactCorpus.cases.associate {
                val a = ArtefactCorpus.analyse(it)
                it.fixture to (a.artefactSamples to a.reps.map { rep -> rep.artefactSamples })
            },
            "artefactSamples, per set and per rep",
        )
    }

    /**
     * THE FIVE FIGURES THE RULE MOVES, each with what it was.
     *
     * field-42 set 2 is #290's headline and the largest movement: 3606.3 W on a
     * 24.9476 kg press becomes 329.4 W, which at 1.155 m/s implies 1.17 g of
     * support acceleration -- a number a 55 lb press can produce. field-43 set
     * 5 is the other headline: 4347.4 W at 83.9 kg becomes 722.3 W, which at
     * 0.862 m/s implies 1.02 g.
     */
    @Test
    fun `the published peak pair falls on five captures, from the same reps`() {
        val moved = mapOf(
            "field-ohp-3010-7rep-s42-set02" to listOf(3606.3, 329.4, 2.516, 1.155),
            "field-bench-3010-6rep-s42-set05" to listOf(1135.7, 271.4, 0.989, 0.56),
            "field-bench-3010-6rep-s42-set07" to listOf(1379.8, 556.4, 1.084, 0.948),
            "field-deadlift-straight-5rep-s43-set05" to listOf(4347.4, 722.3, 1.246, 0.862),
            "field-ohp-prepinflated-s37-set03" to listOf(783.2, 524.6, 1.396, 1.227),
        )
        assertEquals(
            moved,
            moved.keys.associateWith {
                val a = analyse(it)
                listOf(
                    beforePeakPowerW(a),
                    publishedPeakPowerW(a),
                    beforePeakConVelMps(a),
                    publishedPeakConVelMps(a),
                )
            },
            "peakPower_w before and after, then peakConVel_mps before and after",
        )
        // From #306 none of the five publishes either figure: no rep of any of
        // them is bounded.
        moved.keys.forEach {
            val a = analyse(it)
            assertNull(AccelArtefact.setPeakPowerW(a.reps), "$it peakPower_w published from #306")
            assertNull(AccelArtefact.setPeakConVelMps(a.reps), "$it peakConVel_mps published from #306")
        }
        // The implied support acceleration of the two headline figures, so
        // "believable" is a number rather than an adjective.
        val press = 329.4 / (1.155 * 24.94758035055195) / 9.80665
        assertTrue(press < 1.3, "field-42 set 2's published pair now implies $press g")
        val deadlift = 722.3 / (0.862 * 83.91458845185656) / 9.80665
        assertTrue(deadlift < 1.3, "field-43 set 5's published pair now implies $deadlift g")
    }

    /**
     * THE SIX IT DOES NOT MOVE, and the five of those that are still wrong.
     *
     * Named rather than omitted, because a table that showed only the movers
     * would read as a fix. In each of the five the out-of-range sample sits
     * OUTSIDE the span of the rep whose peak it inflated -- field-37 set 8's is
     * at sample index 4079, inside rep 5's eccentric, while the 407.4 W rep is
     * rep 6, whose drive opens at 4083. The residue reaches further than a rep
     * boundary; `AccelArtefact.corruptedSpan` derives how far and
     * `ArtefactRuleAlternativesTest` measures what withholding on that interval
     * would cost.
     *
     * field-42 set 13's 244.3 W at 1.026 m/s on 22.6 kg of assistance is the
     * sixth and is the one that needs no movement.
     */
    @Test
    fun `the published peak pair is unchanged on six captures, five of them still implausible`() {
        val unchanged = listOf(
            "field-cablerow-3010-8rep-s42-set09",
            "field-pullup-3010-8rep-s42-set11",
            "field-pullup-4010-8rep-s42-set13",
            "field-deadlift-straight-5rep-s43-set04",
            "field-deadlift-straight-5rep-s43-set06",
            "field-assistedpullup-3010-s37-set08",
        )
        unchanged.forEach { fixture ->
            val a = analyse(fixture)
            assertEquals(beforePeakPowerW(a), publishedPeakPowerW(a), "$fixture peakPower_w moved")
            assertEquals(beforePeakConVelMps(a), publishedPeakConVelMps(a), "$fixture peakConVel_mps moved")
        }
        assertEquals(
            mapOf(
                "field-cablerow-3010-8rep-s42-set09" to 651.7,
                "field-pullup-3010-8rep-s42-set11" to 634.3,
                "field-pullup-4010-8rep-s42-set13" to 244.3,
                "field-deadlift-straight-5rep-s43-set04" to 1203.1,
                "field-deadlift-straight-5rep-s43-set06" to 2386.5,
                "field-assistedpullup-3010-s37-set08" to 407.4,
            ),
            unchanged.associateWith { publishedPeakPowerW(analyse(it)) },
            "the survivors of the in-span clause, at the figures it leaves",
        )
        // And from #306 not one of the six is published: every rep of every
        // one is unbounded, which is what finally reaches the five
        // implausible figures the in-span clause could not.
        assertEquals(
            unchanged.associateWith { null },
            unchanged.associateWith { AccelArtefact.setPeakPowerW(analyse(it).reps) },
            "peakPower_w each survivor publishes from #306",
        )
        // The sample that inflates field-37 set 8's 407.4 W, and where it is
        // relative to the rep that publishes it -- the measurement the KDoc
        // above rests on.
        val eight = ArtefactCorpus.load("field-assistedpullup-3010-s37-set08")
        assertEquals(listOf(4079), AccelArtefact.indices(eight), "set 8's out-of-range samples, by index")
    }

    /**
     * THE SECOND FIGURE ON THE POST-SET CHART, over the whole corpus, measured
     * against the reps the set's published peak is measured over.
     *
     * The characterization this replaces read 69.277, 43.377, 0.0, 77.797,
     * 59.843, 65.497, 79.183, 10.273, 75.683, 0.0 and 19.628 -- the chart's own
     * expression, the maximum over EVERY rep. Five captures move and each says
     * something different to the lifter:
     *
     * - field-42 set 2: 69.277 to 33.074. The largest drawdown in the corpus
     *   halves once its best is a peak the sensor can have measured.
     * - field-42 set 5: 43.377 to 0.0. The screen reported a 43% velocity loss
     *   against a best the rule already withholds; the last rep IS the fastest
     *   of the reps that are bounded.
     * - field-42 set 7: 0.0 to absent. This is the inversion. The screen told
     *   the lifter their last rep was the set's fastest, on the strength of the
     *   one out-of-range sample inside that very rep.
     * - field-43 set 5: 10.273 to absent, and field-37 set 3: 19.628 to absent.
     *   Both sets end on a marked rep.
     *
     * Absent, not zero, on the three whose LAST rep is withheld: a rep whose
     * peak the set refuses to publish is not a rep a loss can be measured off.
     * The six unmoved figures are the six captures whose last rep is clean and
     * whose best was never an artefact.
     */
    @Test
    fun `the chart's terminal-loss figure, per capture`() {
        assertEquals(
            mapOf<String, Double?>(
                "field-ohp-3010-7rep-s42-set02" to 33.074,
                "field-bench-3010-6rep-s42-set05" to 0.0,
                "field-bench-3010-6rep-s42-set07" to null,
                "field-cablerow-3010-8rep-s42-set09" to 77.797,
                "field-pullup-3010-8rep-s42-set11" to 59.843,
                "field-pullup-4010-8rep-s42-set13" to 65.497,
                "field-deadlift-straight-5rep-s43-set04" to 79.183,
                "field-deadlift-straight-5rep-s43-set05" to null,
                "field-deadlift-straight-5rep-s43-set06" to 75.683,
                "field-assistedpullup-3010-s37-set08" to 0.0,
                "field-ohp-prepinflated-s37-set03" to null,
            ),
            ArtefactCorpus.cases.associate {
                it.fixture to AccelArtefact.terminalPeakLossPct(inSpanOnly(analyse(it.fixture).reps))
                    ?.let(ArtefactCorpus::round3)
            },
            "the percentage the in-span clause alone would let the chart print",
        )
        // What the chart prints from #306: nothing on any of the eleven, because
        // the set's best is withheld on every one.
        assertEquals(
            ArtefactCorpus.cases.associate { it.fixture to null },
            ArtefactCorpus.cases.associate {
                it.fixture to AccelArtefact.terminalPeakLossPct(analyse(it.fixture).reps)
            },
            "the percentage the chart prints from #306",
        )
    }

    /**
     * ISSUE #255 ITEM 1, ON ITS OWN TWO CAPTURES: the impossible sample inside
     * a real rep no longer reaches the set's published peak, and the clause
     * that withholds it is the ARTEFACT clause, not the bound.
     *
     * #255 named two figures. `field-assistedpullup-3010-s37-set08` publishes
     * 407.4 W from the rep at index 6, inflated by one 14.982 g sample at
     * sample index 4079 that sits in the previous rep's span, not its own; and
     * `field-ohp-prepinflated-s37-set03` publishes 783.2 W from the rep at
     * index 6, a 0.533 m rep with a 135.1 W mean drive power. Both figures are
     * withheld today. Every rep of both captures is also unbounded, so a
     * set-level "withheld" alone would survive deleting an artefact clause:
     * `RomBound` would hold the figure out on its own.
     *
     * For set 3 that was already covered: `the published peak pair falls on
     * five captures, from the same reps` masks the band and the bound and
     * pins 783.2 W falling to 524.6 W on the in-span clause alone. For set 8
     * it was not. Its 407.4 W survives the in-span clause, and deleting #306's
     * guard-band clause reds nothing else in this file or in
     * `AnchorSupplyByMountTest` (measured); `PeakEligibilityTest` pins that
     * clause on field-44 set 4, not on #255's capture. So before this test a
     * narrowed or deleted band would have left 407.4 W held out by `RomBound`
     * alone, and a later change that let `RomBound` bound set 8's reps would
     * then have republished it with nothing red. "That is a pin on the
     * wrong clause", which stood here of both captures, was true of set 8
     * only.
     *
     * So the bound is masked here and the artefact clauses alone decide:
     *
     * - set 8's inflated rep carries `artefactSamples` 0 and
     *   `guardArtefactSamples` 1. #290's in-span clause keeps it -- which is why
     *   the test above lists 407.4 W as a survivor of that clause -- and #306's
     *   guard band is what withholds it. The set's peak power over what is left
     *   is 69.1 W.
     * - set 3's inflated rep carries `artefactSamples` 2; the in-span clause
     *   withholds it, and only one rep of the eleven survives both artefact
     *   clauses, at 125.4 W.
     *
     * WITHHELD, NOT SUBSTITUTED, on the owner's rule that a figure the analysis
     * cannot bound is withheld or flagged. Substitution is measured and refused
     * in `ArtefactRuleAlternativesTest`. Each row keeps publishing what its own
     * window measured -- 407.4 W and 783.2 W are still on those rows -- with the
     * count beside it that says not to trust the pair; that per-row contract is
     * session export 1.21's and is not moved here.
     *
     * #255 items 2 and 3 -- what produced the 16 g readings, and whether the
     * 4.5 refusal bound holds past the committed corpus -- are `[Field]` and
     * nothing here answers them.
     */
    @Test
    fun `issue 255's two inflated peaks are withheld by the artefact clauses, with the bound masked`() {
        fun withoutBound(reps: List<RepAnalysis>) = reps.map { it.copy(romBounded = true) }

        val set08 = analyse("field-assistedpullup-3010-s37-set08").reps
        val inflated08 = set08.single { it.index == 6 }
        assertEquals(407.4, inflated08.peakPowerW, "set 8's inflated row, published as measured")
        assertEquals(0, inflated08.artefactSamples, "set 8: nothing above the bound inside its own span")
        assertEquals(1, inflated08.guardArtefactSamples, "set 8: the 14.982 g sample is in its guard band")
        assertEquals(listOf(0, 1, 2, 3, 4), AccelArtefact.peakEligible(withoutBound(set08)).map { it.index })
        assertEquals(69.1, AccelArtefact.setPeakPowerW(withoutBound(set08)), "set 8, artefact clauses alone")

        val set03 = analyse("field-ohp-prepinflated-s37-set03").reps
        val inflated03 = set03.single { it.index == 6 }
        assertEquals(783.2, inflated03.peakPowerW, "set 3's inflated row, published as measured")
        assertEquals(2, inflated03.artefactSamples, "set 3: two samples above the bound inside its own span")
        assertEquals(listOf(4), AccelArtefact.peakEligible(withoutBound(set03)).map { it.index })
        assertEquals(125.4, AccelArtefact.setPeakPowerW(withoutBound(set03)), "set 3, artefact clauses alone")

        // And what the two sets actually publish: nothing, on all three clauses.
        assertNull(AccelArtefact.setPeakPowerW(set08), "set 8's published peakPower_w")
        assertNull(AccelArtefact.setPeakPowerW(set03), "set 3's published peakPower_w")
    }

    /**
     * A set every one of whose reps carries an artefact publishes NO peak pair
     * rather than a low one, and nothing else about it changes.
     *
     * No committed capture is in that state, so this is asserted on the rep
     * list directly. Absence is the correct answer there: a zero would read as
     * a set the lifter moved no weight in.
     */
    @Test
    fun `a set with no artefact-free rep publishes no peak pair at all`() {
        val a = analyse("field-deadlift-straight-5rep-s43-set05")
        val allMarked = a.reps.map { it.copy(artefactSamples = 1) }
        assertEquals(emptyList(), AccelArtefact.peakEligible(allMarked), "no rep qualifies")
        assertNull(allMarked.let { AccelArtefact.peakEligible(it) }.mapNotNull { it.peakPowerW }.maxOrNull())
        assertNull(AccelArtefact.peakEligible(allMarked).maxOfOrNull { it.peakConVelMps })
        // And the per-rep figures are untouched by the rule.
        assertEquals(a.reps.map { it.romM }, allMarked.map { it.romM }, "rom_m is not a peak and does not move")
        // A line here asserted that velocity loss "is mean-based" and so did
        // not move. From #306 its reference is taken over the same eligible
        // reps, so a set with none publishes none.
        assertEquals(VelocityLoss.NoEligiblePair, VelocityLoss.of(allMarked), "velocity loss with no eligible rep")
    }
}
