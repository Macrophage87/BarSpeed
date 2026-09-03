package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which prescribed duration a phase is charged against, and what it cost on a
 * real set when that came out wrong. Issues #127 and #56.
 *
 * ## The defect
 *
 * Tempo digits are POSITIONAL: digit 1 is the DOWN stroke, digit 3 the UP
 * stroke. On almost every lift the drive goes up, so digit 1 is also the
 * eccentric and reading it as one is harmless. On a seated leg curl or a
 * pushdown the drive pulls DOWN, and then digit 1 is the CONCENTRIC and digit 3
 * is the eccentric. [TempoSchedule] has always resolved this correctly and
 * [SetAnalyzer] has always graded through it. Three sites in `:app` -- two of
 * them in RecordScreen -- read `tempo.downS` instead, and no test on the CI
 * path reaches a Compose screen, so none of the three could be run against.
 *
 * Two of those three render the number: the rest-screen chart and the history
 * chart. The third, the in-set ring, cannot -- `RecordScreen.InSetStage` sends
 * every set that carries a tempo to the guided-cadence branch before the ring
 * is reached, so its target is null in every reachable state. It is repointed
 * anyway, to stop the decision existing in three places.
 *
 * ## What is a differential here and what is not
 *
 * [PhaseTempoTarget] is the moved decision, and the assertions naming it are
 * differentials: they fail against the moved-unchanged body and pass against
 * the fixed one.
 *
 * The assertions over the fixture are NOT differentials and must not be read as
 * any. The tracker, the segmenter and the analyzer are untouched by this
 * change, so every measured number below is identical before and after; what
 * they establish is what the wrong digit did to a real set. `the metronome
 * called a three-second eccentric on this set` is narrower still -- it reads a
 * CSV and no production code at all, so nothing but editing the fixture can red
 * it.
 *
 * ## The fixture
 *
 * `field-legcurl-1030-10rep.csv` and its cue track: seated leg curl, set 11 of
 * a 13-set session recorded 2026-08-20 on app 0.1.40, WitMotion WT901BLECL. The
 * set's declared geometry, copied from the export rather than guessed at:
 * tempo 1030, concentric-first, concentric DOWN, vertical, sensor on the stack
 * and inverted, travel ratio 1, load 40.82 kg, 10 reps counted by the lifter.
 * `no longer reproduces the rep count its session exported, and this is what
 * it resolves` carries that provenance. It used to be called `reproduces the
 * analysis its session exported` and this KDoc said it "checks that this file
 * really is that set"; both are deleted, because issue #94's runaway
 * correction moved the rep count from the exported 11 to 12 and a count that
 * no longer matches cannot check identity. The eleven exported per-rep
 * figures are still asserted, with one inserted row beside them.
 *
 * It is not the corpus's first leg curl at 1030 with a cue track beside it --
 * see [LegCurlCueTrackTest], which holds three from 2026-08-18. Those predate
 * the #106 cadence fix, so their movement cues sit 4.002 to 4.006 s apart --
 * median 4.005 across their 33 intervals -- and cannot be read as a phase
 * duration; this one, recorded after it, can.
 *
 * ## What this does not depend on
 *
 * Issue #131: the opening phase is currently inferred from the exercise NAME
 * and gets at least the leg press backwards. `which digit is the eccentric does
 * not depend on the declared start phase` pins that the answer here survives
 * that, so this fix does not rest on #131 being right. Nothing here fixes #131.
 *
 * ## The near neighbour this does not touch
 *
 * Digit 2 is the pause at the BOTTOM and digit 4 the pause at the TOP, which is
 * exactly what [Phase.BOTTOM_PAUSE] and [Phase.TOP_PAUSE] are named after, so
 * the pause mapping is positional on both sides and agrees. What does not agree
 * is which of those two labels the tracker emits: `StreamingSetTracker` picks
 * it from `startsWith` alone and never consults `concentricUp`, so it calls
 * every still period on a squat a top pause -- including the one at the bottom
 * of the squat. That is a tracker defect, not a tempo-digit one, and it is not
 * addressed here.
 */
class PhaseTempoTargetTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString()

    private fun load(n: String) = ImuCsv.decode(res(n))

    /** The cue track as two columns, in the app's own `timestamp_ms,cue` format. */
    private fun cues(n: String): List<Pair<Long, String>> = res(n).lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("timestamp_ms") }
        .map {
            val f = it.split(',')
            f[0].toLong() to f.drop(1).joinToString(",")
        }
        .toList()

    private fun round2(v: Double) = (v * 100.0).roundToInt() / 100.0

    // ------------------------------------------------------------------
    // The four lift geometries that reach different branches.
    // ------------------------------------------------------------------

    /** The fixture's own declared geometry. */
    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = true,
    )

    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private val legPress = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private val facePull = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    private fun target(tempo: String, direction: LiftDirection, phase: Phase) =
        PhaseTempoTarget.secondsFor(Tempo.parse(tempo), direction, phase)

    // ------------------------------------------------------------------
    // The decision itself.
    // ------------------------------------------------------------------

    @Test
    fun `a drive-down 1030 charges the eccentric three seconds and the drive one`() {
        // The whole of #127 in two lines. The pull DOWN is digit 1 and takes a
        // second; the return UP is digit 3 and takes three.
        assertEquals(3.0, target("1030", legCurl, Phase.ECCENTRIC), "eccentric")
        assertEquals(1.0, target("1030", legCurl, Phase.CONCENTRIC), "concentric")
    }

    @Test
    fun `an eccentric-first vertical press is charged exactly as before`() {
        assertEquals(3.0, target("3010", benchPress, Phase.ECCENTRIC), "eccentric")
        assertEquals(1.0, target("3010", benchPress, Phase.CONCENTRIC), "concentric")
    }

    @Test
    fun `a concentric-first drive-up lift still charges digit 1 to the eccentric`() {
        // Leg press 2010. The strokes are PERFORMED in the other order, which
        // is what TempoSchedule.first and .second carry, and that reordering
        // must not reach the digits: the eccentric is still digit 1.
        assertEquals(2.0, target("2010", legPress, Phase.ECCENTRIC), "eccentric")
        assertEquals(1.0, target("2010", legPress, Phase.CONCENTRIC), "concentric")
    }

    @Test
    fun `horizontal work charges digit 1 to the eccentric`() {
        // A face pull has no up, so its digits are read by phase rather than by
        // position -- and by phase digit 1 is the eccentric, which is the same
        // answer. Pinned because the resolution runs a different branch.
        assertEquals(2.0, target("2011", facePull, Phase.ECCENTRIC), "eccentric")
        assertEquals(1.0, target("2011", facePull, Phase.CONCENTRIC), "concentric")
    }

    @Test
    fun `which digit is the eccentric does not depend on the declared start phase`() {
        // #131: the opening phase is inferred from the exercise name and gets
        // the leg press backwards. Whatever it says, the eccentric's digit is
        // the same, so this fix does not rest on that inference being right.
        assertEquals(3.0, target("1030", legCurl, Phase.ECCENTRIC), "leg curl, concentric-first")
        assertEquals(
            3.0,
            target("1030", legCurl.copy(startsWith = StartPhase.ECCENTRIC), Phase.ECCENTRIC),
            "the same leg curl declared eccentric-first",
        )
        assertEquals(3.0, target("3010", benchPress, Phase.ECCENTRIC), "bench, eccentric-first")
        assertEquals(
            3.0,
            target("3010", benchPress.copy(startsWith = StartPhase.CONCENTRIC), Phase.ECCENTRIC),
            "the same bench declared concentric-first",
        )
    }

    @Test
    fun `an explosive stroke leaves whichever phase it lands on with no target`() {
        // 30X0 on a drive-up lift: X is the concentric. On a drive-down lift the
        // same string makes X the ECCENTRIC, and an unprescribed phase has to
        // read as absent rather than as the other stroke's number.
        assertEquals(3.0, target("30X0", benchPress, Phase.ECCENTRIC), "bench eccentric")
        assertNull(target("30X0", benchPress, Phase.CONCENTRIC), "bench concentric")
        assertNull(target("30X0", legCurl, Phase.ECCENTRIC), "leg curl eccentric")
        assertEquals(3.0, target("30X0", legCurl, Phase.CONCENTRIC), "leg curl concentric")
    }

    @Test
    fun `the pause digits stay positional and idle has no target`() {
        // Digit 2 is the bottom pause and digit 4 the top pause on every lift.
        // Both Phase names are positional too, so nothing here is resolved
        // against the direction -- see the class KDoc for the tracker defect
        // that decides WHICH of the two labels arrives.
        assertEquals(2.0, target("1234", legCurl, Phase.BOTTOM_PAUSE), "leg curl bottom pause")
        assertEquals(4.0, target("1234", legCurl, Phase.TOP_PAUSE), "leg curl top pause")
        assertEquals(2.0, target("1234", benchPress, Phase.BOTTOM_PAUSE), "bench bottom pause")
        assertEquals(4.0, target("1234", benchPress, Phase.TOP_PAUSE), "bench top pause")
        assertNull(target("1234", legCurl, Phase.IDLE), "idle")
    }

    // ------------------------------------------------------------------
    // The set that proves it. See the class KDoc for provenance.
    // ------------------------------------------------------------------

    private val imu = "field-legcurl-1030-10rep.csv"
    private val cueTrack = "field-legcurl-1030-10rep-cues.csv"
    private val prescribed = Tempo.parse("1030")
    private val loadKg = 40.82331330090319

    /**
     * The tolerance a tempo phase is judged within. 0.5 s, which is
     * `SetTargets.toleranceS`'s default and also, spelled out again,
     * `RecordScreen.TEMPO_TOLERANCE_S` and `SessionDetailScreen`'s copy of the
     * same name. This line is the fourth statement of that number and can reach
     * none of the other three.
     */
    private val toleranceS = 0.5

    private fun analysis() = SetAnalyzer.analyze(
        load(imu),
        legCurl,
        loadKg = loadKg,
        targets = SetTargets(plannedReps = 10, countedReps = 10, tempo = prescribed, toleranceS = toleranceS),
    )

    /** Movement runs the LIVE tracker labelled, in order, with how long each lasted. */
    private fun liveRuns(): List<Pair<Phase, Double>> {
        val tracker = StreamingSetTracker.forLift(legCurl)
        val runs = mutableListOf<Pair<Phase, Double>>()
        var phase = Phase.IDLE
        var elapsed = 0.0
        load(imu).forEach { sample ->
            val state = tracker.feed(sample)
            if (state.phase != phase) {
                if (phase == Phase.ECCENTRIC || phase == Phase.CONCENTRIC) runs += phase to round2(elapsed)
                phase = state.phase
                elapsed = 0.0
            }
            elapsed = maxOf(elapsed, state.currentPhaseElapsedS)
        }
        if (phase == Phase.ECCENTRIC || phase == Phase.CONCENTRIC) runs += phase to round2(elapsed)
        return runs
    }

    @Test
    fun `no longer reproduces the rep count its session exported, and this is what it resolves`() {
        // Provenance, not behaviour: the per-rep figures below are the ones
        // this set's own export published. The COUNT is no longer among them,
        // so this test no longer establishes identity on its own.
        //
        // The rep COUNT moved with issue #94's runaway correction, 11 to 12
        // against the 10 the lifter counted. The per-rep figures asserted
        // below are what carry the provenance and they are unmoved; the count
        // is asserted here so the move is on the record rather than absorbed.
        val a = analysis()
        assertEquals(12, a.reps.size, "segmented reps; the lifter counted 10")
        // One detection is INSERTED, at index 6, and every other row is
        // unmoved -- which is what says the correction added a rep here rather
        // than re-segmenting the set. The inserted rep resolves no eccentric.
        assertEquals(
            listOf(null, 2.67, 2.35, 2.49, null, 0.98, null, 1.43, 0.53, 0.66, 0.45, null),
            a.reps.map { it.eccS },
            "ecc_s",
        )
        assertEquals(
            listOf(1.98, 0.77, 1.28, 1.51, 1.1, 0.47, 1.06, 1.15, 2.22, 1.2, 0.85, 1.18),
            a.reps.map { it.conS },
            "con_s",
        )
        val c = assertNotNull(a.tempoCompliance, "compliance")
        // The inserted rep is a 1.06 s drive against a 1 s target and no
        // eccentric, so it grades as fully compliant on all it resolved and
        // the ratio the lifter reads goes 3 of 11 to 4 of 12. A rep the
        // correction recovered improving the tempo score is not evidence the
        // tempo was better.
        assertEquals(4, c.repsFullyCompliant, "withinTolerance")
        assertEquals(12, c.repsEvaluated, "of")
    }

    @Test
    fun `the metronome called a three-second eccentric on this set`() {
        // The independent evidence, and the only assertion here that reads no
        // production code: what the app SAID out loud, to the millisecond.
        // "Down" opens the pull and "Up" opens the return, so the return runs
        // to the "Down" that opens the NEXT rep -- "Done" after the last one.
        // Not to the next cue of any kind: that is the count "1", about 1.001 s
        // after "Up", and reading it as the phase would understate it
        // threefold.
        val track = cues(cueTrack)
        val downs = track.filter { it.second == "Down" }.map { it.first }
        val ups = track.filter { it.second == "Up" }.map { it.first }
        val closes = downs.drop(1) + track.first { it.second == "Done" }.first
        assertEquals(10, downs.size, "Down cues")
        assertEquals(10, ups.size, "Up cues")
        assertEquals(
            listOf(1003L, 1001, 1001, 1002, 1002, 1001, 1001, 1001, 1001, 1002),
            downs.zip(ups) { d, u -> u - d },
            "Down to Up, ms -- the drive, digit 1",
        )
        assertEquals(
            listOf(3005L, 3002, 3001, 3002, 3003, 3004, 3004, 3003, 3004, 3003),
            ups.zip(closes) { u, c -> c - u },
            "Up to the Down that opens the next rep, ms -- the return, digit 3",
        )
    }

    @Test
    fun `the eccentric runs on this set fit digit 3 and not digit 1`() {
        // The size of the error on real data. These are the durations of the
        // runs the live tracker labelled ECCENTRIC; four of the twelve are more
        // than a tolerance longer than digit 1, and none is more than a
        // tolerance longer than digit 3, which is this lift's eccentric.
        val ecc = liveRuns().filter { it.first == Phase.ECCENTRIC }.map { it.second }
        assertEquals(
            listOf(3.35, 0.22, 1.38, 1.51, 3.05, 3.09, 0.98, 0.37, 0.89, 0.52, 0.69, 1.17),
            ecc,
            "seconds of each run the live tracker labelled ECCENTRIC",
        )
        assertEquals(
            4,
            ecc.count { it > prescribed.downS + toleranceS },
            "runs more than a tolerance past digit 1",
        )
        val resolved = assertNotNull(
            PhaseTempoTarget.secondsFor(prescribed, legCurl, Phase.ECCENTRIC),
            "resolved eccentric target",
        )
        assertEquals(
            0,
            ecc.count { it > resolved + toleranceS },
            "runs more than a tolerance past the eccentric's own digit",
        )
    }

    @Test
    fun `the post-set chart drew four of eight bars green against the wrong line`() {
        // #56 predicted this defect "colours every bar Amber". It does not, and
        // that is why it survived: segmentation clips these eccentrics short
        // enough that most of them land near the WRONG target. Against the
        // target the set was actually graded on, one bar of eight is green.
        val a = analysis()
        val measured = a.reps.mapNotNull { it.eccS }
        assertEquals(8, measured.size, "reps whose eccentric resolved, of 11 segmented")
        assertEquals(
            4,
            measured.count { abs(it - prescribed.downS) <= toleranceS },
            "bars drawn green against digit 1",
        )
        val stored = assertNotNull(assertNotNull(a.tempoCompliance).eccentricPrescribedS, "stored target")
        assertEquals(1, measured.count { abs(it - stored) <= toleranceS }, "bars green against the eccentric")
    }

    @Test
    fun `the resolver and the analyzer agree on this set eccentric`() {
        // The two post-set screens read the analyzer's stored answer rather than
        // resolving the digits again, so a chart cannot state a target the
        // compliance ratio printed beside it was not graded against. This spot-
        // checks that at ONE (tempo, geometry) pair -- 1030 against this
        // fixture's geometry. It is not a proof of the general equality; what
        // makes the two agree everywhere is that both read TempoSchedule.
        val stored = assertNotNull(assertNotNull(analysis().tempoCompliance).eccentricPrescribedS)
        assertEquals(3.0, stored, "what SetAnalyzer graded this set's eccentric against")
        assertEquals(
            stored,
            PhaseTempoTarget.secondsFor(prescribed, legCurl, Phase.ECCENTRIC),
            "what PhaseTempoTarget resolves for the same set",
        )
    }

    @Test
    fun `an unprescribed eccentric has no stored target either`() {
        val a = SetAnalyzer.analyze(
            load(imu),
            legCurl,
            loadKg = loadKg,
            targets = SetTargets(tempo = Tempo.parse("30X0"), toleranceS = toleranceS),
        )
        val c = assertNotNull(a.tempoCompliance)
        assertNull(c.eccentricPrescribedS, "X is digit 3, which on this lift is the eccentric")
        assertTrue(
            c.phases.none { it.phase == TempoComplianceResult.PHASE_ECCENTRIC && it.scored },
            "an unprescribed phase cannot be scored",
        )
    }
}
