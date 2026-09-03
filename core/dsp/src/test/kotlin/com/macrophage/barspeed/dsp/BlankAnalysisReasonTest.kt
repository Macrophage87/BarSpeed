package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NoRepsReason] -- the decision itself, then the corpus. Issue #138.
 *
 * The two halves are deliberately separate and neither substitutes for the
 * other.
 *
 * **The decision** is a pure function of a [SegmentationCensus] and one
 * integer, so every branch of it is reachable from a hand-written census and
 * every branch is pinned that way below. That is complete coverage of the
 * RULE, and it is not evidence about any capture.
 *
 * **The corpus** says which reasons real captures actually produce. Two, at
 * this commit, out of seven. The other five are pinned only by the synthetic
 * censuses, and saying otherwise would be a claim stronger than the evidence:
 * nothing here has seen a field capture emptied by the minimum-ROM floor or by
 * a set-end cue.
 *
 * ## Where the census comes from
 *
 * [RepSegmenter.segmentDetailed] counts while the shipped classification and
 * pairing run; there is no second walk over the series and no reimplementation
 * of either. `the census agrees with the shipped segmenter on every capture`
 * is what holds that true.
 */
class BlankAnalysisReasonTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private fun series(fixture: String): VelocitySeries =
        VelocityEstimator.estimate(load(fixture), DspConfig(), MovementPlane.VERTICAL)

    private val corpus: List<String> by lazy {
        File(javaClass.getResource("/field-still-0rep.csv")!!.toURI()).parentFile.list()!!
            .filter { it.startsWith("field-") && it.endsWith(".csv") && !it.endsWith("-cues.csv") }
            .map { it.removeSuffix(".csv") }
            .sorted()
    }

    /** A census with every count zero, to be copied with only the field under test set. */
    private val empty = SegmentationCensus(
        movementRuns = 0,
        overDisplacementCap = 0,
        belowStartThreshold = 0,
        shorterThanMinPhase = 0,
        qualifyingRuns = 0,
        pairsBelowMinRom = 0,
        spans = 0,
    )

    @Test
    fun `a set that resolved a rep has no reason at all`() {
        // Absence stays absence, and it is asserted from BOTH sides: a
        // surviving span means null however the census reads, including a
        // census that would otherwise report a runaway.
        assertNull(NoRepsReason.of(empty.copy(spans = 3), spansWithinSetEnd = 3), "three spans, none excluded")
        assertNull(
            NoRepsReason.of(empty.copy(movementRuns = 4, overDisplacementCap = 3, spans = 1), spansWithinSetEnd = 1),
            "one rep survives a census that would otherwise read as a runaway",
        )
    }

    @Test
    fun `the cue bound is the one reason that does not mean segmentation failed`() {
        assertEquals(
            NoRepsReason.AFTER_SET_END_CUE,
            NoRepsReason.of(empty.copy(movementRuns = 8, qualifyingRuns = 6, spans = 2), spansWithinSetEnd = 0),
            "spans resolved and the set's own end cue excluded all of them",
        )
    }

    @Test
    fun `every other branch of the decision, from a census that isolates it`() {
        assertEquals(
            NoRepsReason.NO_MOVEMENT,
            NoRepsReason.of(empty, spansWithinSetEnd = 0),
            "nothing left the pause band",
        )
        // The majority test, both sides. Three of four is a runaway; one of
        // seven is not, and the second case is field-seated-ohp-2rep's, whose
        // emptiness has a different cause.
        assertEquals(
            NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP,
            NoRepsReason.of(empty.copy(movementRuns = 4, overDisplacementCap = 3, qualifyingRuns = 1), 0),
            "three of four runs over the cap",
        )
        assertEquals(
            NoRepsReason.PHASES_UNPAIRED,
            NoRepsReason.of(empty.copy(movementRuns = 7, overDisplacementCap = 1, qualifyingRuns = 3), 0),
            "one of seven runs over the cap is not a runaway",
        )
        // Exactly half is NOT a majority, which is the boundary the rule turns on.
        assertEquals(
            NoRepsReason.PHASES_UNPAIRED,
            NoRepsReason.of(empty.copy(movementRuns = 4, overDisplacementCap = 2, qualifyingRuns = 2), 0),
            "two of four is half, not a majority",
        )
        // No run survived demotion: whichever term hit more runs names it, and
        // a tie reads as too slow rather than too brief. Both are stated
        // choices, and neither is exercised by any capture in this corpus.
        assertEquals(
            NoRepsReason.RUNS_BELOW_START_THRESHOLD,
            NoRepsReason.of(empty.copy(movementRuns = 5, belowStartThreshold = 5, shorterThanMinPhase = 2), 0),
            "more runs too slow than too brief",
        )
        assertEquals(
            NoRepsReason.RUNS_TOO_BRIEF,
            NoRepsReason.of(empty.copy(movementRuns = 5, belowStartThreshold = 1, shorterThanMinPhase = 5), 0),
            "more runs too brief than too slow",
        )
        assertEquals(
            NoRepsReason.RUNS_BELOW_START_THRESHOLD,
            NoRepsReason.of(empty.copy(movementRuns = 5, belowStartThreshold = 3, shorterThanMinPhase = 3), 0),
            "a tie reads as too slow",
        )
        assertEquals(
            NoRepsReason.DRIVE_BELOW_MIN_ROM,
            NoRepsReason.of(empty.copy(movementRuns = 6, qualifyingRuns = 6, pairsBelowMinRom = 3), 0),
            "pairs formed and every drive fell under the floor",
        )
    }

    @Test
    fun `the vocabulary the export publishes is the vocabulary this enum owns`() {
        // The hop `:core:model` cannot make: SessionExport mirrors these names
        // because the dependency runs the other way, and this is the side that
        // can see both. SchemaNoRepsReasonContractTest pins the same constant
        // against the published schema. A rename that moved only one of the
        // three reds exactly one of these two tests.
        assertEquals(
            SessionExport.VALID_NO_REPS_REASONS,
            NoRepsReason.wireNames,
            "the exported vocabulary drifted from the NoRepsReason enum",
        )
        assertEquals(
            NoRepsReason.entries.size,
            NoRepsReason.wireNames.size,
            "two values share a wire name, so one is unpublishable",
        )
        // And the serialized form is the wire name too, so the value frozen
        // into a stored analysis and the value written to the export are one
        // vocabulary rather than two that have to be kept in step.
        NoRepsReason.entries.forEach {
            assertEquals(
                "\"${it.wireName}\"",
                Json.encodeToString(NoRepsReason.serializer(), it),
                "${it.name} serializes to something other than its wire name",
            )
        }
    }

    @Test
    fun `the census agrees with the shipped segmenter on every capture`() {
        // segmentDetailed is the only implementation and segment delegates to
        // it, so this cannot fail by construction TODAY -- it exists to fail
        // the day someone gives the census its own walk over the series, which
        // is how the counts would start describing a run that never happened.
        val config = DspConfig()
        corpus.forEach { fixture ->
            StartPhase.entries.forEach { startsWith ->
                val s = series(fixture)
                val direction = LiftDirection(startsWith)
                val detailed = RepSegmenter.segmentDetailed(s, direction, config)
                assertEquals(
                    RepSegmenter.segment(s, direction, config),
                    detailed.spans,
                    "$fixture $startsWith: segment and segmentDetailed disagree",
                )
                assertEquals(
                    detailed.spans.size,
                    detailed.census.spans,
                    "$fixture $startsWith: the census miscounts its own spans",
                )
                assertEquals(
                    RepSegmenter.classifyRuns(s, config).count { it.type != RunType.STILL },
                    detailed.census.qualifyingRuns,
                    "$fixture $startsWith: the census miscounts qualifying runs",
                )
                assertTrue(
                    detailed.census.qualifyingRuns <= detailed.census.movementRuns,
                    "$fixture $startsWith: more runs survived demotion than existed",
                )
                // A span needs a qualifying run and a qualifying run needs a
                // movement run, so no set can have spans and no movement. That
                // implication is why the order of NoRepsReason.of's
                // AFTER_SET_END_CUE and NO_MOVEMENT tests is unobservable, and
                // it is asserted rather than argued: swapping those two lines
                // reds nothing, and this is what says that is a property of the
                // pipeline rather than a hole in the pins.
                if (detailed.census.movementRuns == 0) {
                    assertEquals(0, detailed.census.spans, "$fixture $startsWith: spans without movement")
                }
            }
        }
    }

    @Test
    fun `the census of the two captures that resolve nothing either way`() {
        // The same figures BlankAnalysisTest measures with its own inline walk
        // over the raw runs, read here off the segmenter's own counters. The
        // two agreeing is what says the counters were placed where the
        // demotion happens.
        val rdl = RepSegmenter.segmentDetailed(
            series("field-rdl-3010-10rep-s36-set05"),
            LiftDirection(StartPhase.ECCENTRIC),
            DspConfig(),
        ).census
        assertEquals(
            SegmentationCensus(
                movementRuns = 4,
                overDisplacementCap = 3,
                belowStartThreshold = 0,
                shorterThanMinPhase = 0,
                qualifyingRuns = 1,
                pairsBelowMinRom = 0,
                spans = 0,
            ),
            rdl,
            "field-rdl-3010-10rep-s36-set05, eccentric-first",
        )
        val still = RepSegmenter.segmentDetailed(
            series("field-still-0rep"),
            LiftDirection(StartPhase.ECCENTRIC),
            DspConfig(),
        ).census
        assertEquals(empty, still, "field-still-0rep, eccentric-first")
        // And the capture that reaches zero the other way.
        val ohp = RepSegmenter.segmentDetailed(
            series("field-seated-ohp-2rep"),
            LiftDirection(StartPhase.ECCENTRIC),
            DspConfig(),
        ).census
        assertEquals(7, ohp.movementRuns, "field-seated-ohp-2rep: raw movement runs")
        assertEquals(1, ohp.overDisplacementCap, "field-seated-ohp-2rep: runs over the cap")
        assertEquals(3, ohp.qualifyingRuns, "field-seated-ohp-2rep: runs surviving demotion")
        assertEquals(0, ohp.pairsBelowMinRom, "field-seated-ohp-2rep: pairs discarded for ROM")
    }

    @Test
    fun `which reasons this corpus actually produces, and which it does not`() {
        // Two of seven. Stated as a measurement, not as a claim that the other
        // five are unreachable: the corpus is 29 captures from seven sessions,
        // and a set emptied by its own end cue or by the minimum-ROM floor is a
        // thing the pipeline can produce and this corpus has not.
        val config = DspConfig()
        val produced = corpus.flatMap { fixture ->
            StartPhase.entries.mapNotNull { startsWith ->
                val detailed = RepSegmenter.segmentDetailed(series(fixture), LiftDirection(startsWith), config)
                NoRepsReason.of(detailed.census, detailed.spans.size)
            }
        }.toSet()
        assertEquals(
            setOf(NoRepsReason.NO_MOVEMENT, NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP, NoRepsReason.PHASES_UNPAIRED),
            produced,
            "the reasons this corpus produces",
        )
        // Named individually, because a set-equality assertion says nothing
        // about WHICH capture produced which.
        fun reasonFor(fixture: String, startsWith: StartPhase): NoRepsReason? {
            val detailed = RepSegmenter.segmentDetailed(series(fixture), LiftDirection(startsWith), config)
            return NoRepsReason.of(detailed.census, detailed.spans.size)
        }
        assertEquals(
            NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP,
            reasonFor("field-rdl-3010-10rep-s36-set05", StartPhase.ECCENTRIC),
            "the Romanian deadlift issue #87 could not reach",
        )
        assertEquals(
            NoRepsReason.NO_MOVEMENT,
            reasonFor("field-still-0rep", StartPhase.ECCENTRIC),
            "a sensor that was on and did not move",
        )
        assertEquals(
            NoRepsReason.PHASES_UNPAIRED,
            reasonFor("field-seated-ohp-2rep", StartPhase.ECCENTRIC),
            "runs survived and none paired",
        )
        // The three sets #138 named that issue #87 moved off zero now resolve
        // reps, so they carry no reason -- the key's absence is the record
        // that #87 reached them.
        assertNull(reasonFor("field-ohp-3010-6rep-s37-set02", StartPhase.CONCENTRIC), "ohp s37 set02, 4 of 6")
        assertNull(reasonFor("field-bench-3010-6rep-s37-set05", StartPhase.ECCENTRIC), "bench s37 set05, 1 of 6")
        assertNull(reasonFor("field-bench-3010-6rep-s37-set06", StartPhase.ECCENTRIC), "bench s37 set06, 3 of 6")
        // And the neighbour that resolves 1 of 10 through 123.64 m of runaway
        // carries none either. That is the limit, asserted rather than
        // described: this key answers emptiness and nothing else.
        assertNull(
            reasonFor("field-rdl-3010-10rep-s36-set04", StartPhase.ECCENTRIC),
            "the RDL that resolves one rep of ten states no reason",
        )
    }

    @Test
    fun `a blank analysis on a healthy stream is one capture in this corpus, not the norm`() {
        // #138's ask, as a contract: a healthy stream publishing nothing must
        // be the exception. Measured over every capture under its declared
        // start phase where one is known and under both where it is not, this
        // counts the (capture, phase) pairs that segment to zero.
        val config = DspConfig()
        val blank = corpus.flatMap { fixture ->
            StartPhase.entries.mapNotNull { startsWith ->
                "$fixture/$startsWith".takeIf {
                    RepSegmenter.segment(series(fixture), LiftDirection(startsWith), config).isEmpty()
                }
            }
        }
        assertEquals(
            listOf(
                "field-rdl-3010-10rep-s36-set05/ECCENTRIC",
                "field-rdl-3010-10rep-s36-set05/CONCENTRIC",
                "field-seated-ohp-2rep/ECCENTRIC",
                "field-still-0rep/ECCENTRIC",
                "field-still-0rep/CONCENTRIC",
            ),
            blank,
            "the capture-and-phase pairs that segment to nothing",
        )
        // The half of the bargain that a bare count cannot state: every one of
        // them now has a reason to publish, so no blank result in this corpus
        // is silent about why.
        blank.forEach { pair ->
            val (fixture, phase) = pair.split("/")
            val detailed = RepSegmenter.segmentDetailed(
                series(fixture),
                LiftDirection(StartPhase.valueOf(phase)),
                config,
            )
            assertNotNull(
                NoRepsReason.of(detailed.census, detailed.spans.size),
                "$pair segments to nothing and states no reason",
            )
        }
    }

    // ---- through SetAnalyzer, which is where a recorded set gets its reason --

    @Test
    fun `SetAnalyzer publishes the reason on every capture that resolves nothing`() {
        // The wiring, not the rule. NoRepsReason.of is pinned above from
        // censuses; this asserts that the analysis a recorded set actually
        // carries reaches the same answer, because the census the analyzer
        // hands it comes from its own segmentation pass and nothing else
        // checks that it hands over the right one.
        fun reasonOf(fixture: String, startsWith: StartPhase): NoRepsReason? =
            SetAnalyzer.analyze(load(fixture), LiftDirection(startsWith), loadKg = 52.163122551154075).noRepsReason
        assertEquals(
            NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP,
            reasonOf("field-rdl-3010-10rep-s36-set05", StartPhase.ECCENTRIC),
            "the Romanian deadlift issue #87 could not reach",
        )
        assertEquals(
            NoRepsReason.RUNS_EXCEED_DISPLACEMENT_CAP,
            reasonOf("field-rdl-3010-10rep-s36-set05", StartPhase.CONCENTRIC),
            "and the same set read the other way round",
        )
        assertEquals(
            NoRepsReason.NO_MOVEMENT,
            reasonOf("field-still-0rep", StartPhase.ECCENTRIC),
            "a sensor that was on and did not move",
        )
        assertEquals(
            NoRepsReason.PHASES_UNPAIRED,
            reasonOf("field-seated-ohp-2rep", StartPhase.ECCENTRIC),
            "runs survived and none paired",
        )
    }

    @Test
    fun `SetAnalyzer publishes no reason on a capture that resolves anything`() {
        // The other half, and the one that stops the fix being "set the field
        // unconditionally". Four captures, including the three #87 moved off
        // zero and the neighbour that resolves 1 of 10 through 123.64 m of
        // runaway. This test is GREEN before the wiring and must stay green
        // after it.
        fun reasonOf(fixture: String, startsWith: StartPhase): NoRepsReason? =
            SetAnalyzer.analyze(load(fixture), LiftDirection(startsWith), loadKg = 24.948).noRepsReason
        assertNull(reasonOf("field-ohp-3010-6rep-s37-set02", StartPhase.CONCENTRIC), "ohp s37 set02, 4 of 6")
        assertNull(reasonOf("field-bench-3010-6rep-s37-set05", StartPhase.ECCENTRIC), "bench s37 set05, 1 of 6")
        assertNull(reasonOf("field-bench-3010-6rep-s37-set06", StartPhase.ECCENTRIC), "bench s37 set06, 3 of 6")
        assertNull(reasonOf("field-rdl-3010-10rep-s36-set04", StartPhase.ECCENTRIC), "the RDL that resolves 1 of 10")
    }

    @Test
    fun `a set whose every detection began after its own end cue says so`() {
        // The one reason that does NOT mean segmentation failed, reached
        // through the real analyzer rather than a census. The cue track is
        // synthetic and says so: a terminal cue at the set's first instant
        // puts every drive after the end of the set, which is the shape
        // SetEnd.startedWithinSet excludes. No capture in this corpus is in
        // that state, so nothing here claims a lifter has produced one.
        val samples = load("field-rdl-3010-10rep")
        val ended = listOf(VoiceCue(samples.first().timestampMs, SetEnd.STOPPED))
        val bounded = SetAnalyzer.analyze(
            samples,
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
            cues = ended,
        )
        assertEquals(emptyList(), bounded.reps, "the cue bound did not empty the set, so this pins nothing")
        assertEquals(
            NoRepsReason.AFTER_SET_END_CUE,
            bounded.noRepsReason,
            "an emptied-by-the-cue set is reported as a segmentation failure",
        )
        // Unbounded, the same stream resolves eleven and states no reason.
        val unbounded = SetAnalyzer.analyze(
            samples,
            LiftDirection(StartPhase.ECCENTRIC),
            loadKg = 52.163122551154075,
        )
        assertEquals(11, unbounded.reps.size, "the same stream unbounded")
        assertNull(unbounded.noRepsReason, "the unbounded set states a reason for reps it published")
    }

    @Test
    fun `the minimum-ROM pair count, on the captures that exercise it`() {
        // FOUND BY MUTATION TESTING, not by reading. Deleting both
        // `belowMinRom++` sites in RepSegmenter left the whole suite green:
        // every capture the reason tests reach carries 0 there, so the counter
        // that drives DRIVE_BELOW_MIN_ROM was decoration. It reds now.
        //
        // The captures below are the ones where the pairing walk formed a pair
        // and the DspConfig.minRomM floor discarded it. None of them resolves
        // ZERO reps, so none of them publishes DRIVE_BELOW_MIN_ROM -- what is
        // pinned is the COUNT reaching the census correctly, on both pairing
        // walks. The eccentric-first and concentric-first walks each keep
        // their own counter and each is covered.
        val config = DspConfig()
        fun rejects(fixture: String, startsWith: StartPhase): Int =
            RepSegmenter.segmentDetailed(series(fixture), LiftDirection(startsWith), config).census.pairsBelowMinRom
        assertEquals(2, rejects("field-legcurl-1030-12rep", StartPhase.ECCENTRIC), "leg curl, ecc-first walk")
        assertEquals(6, rejects("field-legcurl-1030-12rep", StartPhase.CONCENTRIC), "leg curl, con-first walk")
        assertEquals(2, rejects("field-backsquat-4011-6rep-s36-set01", StartPhase.ECCENTRIC), "squat, ecc-first")
        assertEquals(4, rejects("field-backsquat-4011-6rep-s36-set01", StartPhase.CONCENTRIC), "squat, con-first")
        assertEquals(1, rejects("field-bench-3010-6rep-s37-set05", StartPhase.ECCENTRIC), "bench s37 set05, ecc-first")
        // And the captures that form no rejected pair at all, so the count is
        // not simply "some number that grows with the set".
        assertEquals(0, rejects("field-rdl-3010-10rep", StartPhase.ECCENTRIC), "the 2026-08 RDL rejects none")
        assertEquals(0, rejects("field-still-0rep", StartPhase.ECCENTRIC), "a sensor that did not move")
    }
}
