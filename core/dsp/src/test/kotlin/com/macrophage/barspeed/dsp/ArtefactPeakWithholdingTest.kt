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
 */
class ArtefactPeakWithholdingTest {
    private fun case(fixture: String) = ArtefactCorpus.cases.first { it.fixture == fixture }

    private fun analyse(fixture: String) = ArtefactCorpus.analyse(case(fixture))

    private fun publishedPeakPowerW(a: SetAnalysis) =
        AccelArtefact.peakEligible(a.reps).mapNotNull { it.peakPowerW }.maxOrNull()

    private fun publishedPeakConVelMps(a: SetAnalysis) =
        AccelArtefact.peakEligible(a.reps).maxOfOrNull { it.peakConVelMps }

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
            "the survivors, at the figures they still publish",
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
                it.fixture to AccelArtefact.terminalPeakLossPct(analyse(it.fixture).reps)?.let(ArtefactCorpus::round3)
            },
            "the percentage the chart prints beside the set's best peak velocity",
        )
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
        // And the figures that are NOT peaks are untouched by the rule.
        assertEquals(a.reps.map { it.romM }, allMarked.map { it.romM }, "rom_m is not a peak and does not move")
        assertEquals(a.velocityLossPct, SetAnalyzer.velocityLossPct(allMarked), "velocity loss is mean-based")
    }
}
