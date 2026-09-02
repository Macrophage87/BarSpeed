package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `bottomPause_s` and `topPause_s` are measured between (issue #93).
 *
 * ## The fixture and where every fact about it comes from
 *
 * `field-legpress-single-2011-8rep-s36-set07.csv` and its `-cues.csv` are set
 * 07 of field session 36, copied byte for byte out of that session's raw
 * export -- `set07_single_leg_press_imu-a.csv` and
 * `set07_single_leg_press_cues.csv`, md5 92154ea88be3b88b5473518107ac9218 on
 * the IMU stream, unchanged from the archive. Nothing was re-encoded,
 * resampled or trimmed.
 *
 * Every declaration below is read from that session's own `meta.json`, not
 * recovered by sweeping direction flags the way the older fixtures in
 * `FieldDataRegressionTest` had to be: `appVersion` 0.1.47, sensor WitMotion
 * WT901BLECL, exercise `single_leg_press` (right side), `load_kg`
 * 65.77089365145514, `tempoPrescribed` "2011", `startsWith` concentric,
 * `concentric` up, `plane` vertical, `sensorOnStack` false, `sensorInverted`
 * false, `travelRatio` 1.0, `kind` dynamic, `reps` 8 with `repsManual` true --
 * the lifter counted, so the hand count is a known answer. The session's own
 * `session.json` publishes the same geometry with `source.startsWith`,
 * `source.concentric` and `source.plane` all `declared`.
 *
 * Two provenance facts that are NOT tidy and are recorded rather than smoothed:
 *
 * - `meta.json` says `sensorsArmed` 2 and `analysedRole` "b", but its
 *   `sensors` array lists only role "a" and only `imu-a.csv` reached the
 *   archive. The stream pinned here is role "a", the one that exists. What the
 *   app analysed live is therefore not necessarily what this file replays.
 * - The set publishes NO `repMetrics` and an empty `summary` in
 *   `session.json`, so unlike the older fixtures there are no app-published
 *   per-rep figures to reproduce. The numbers below are what THIS repository
 *   computes at the SHA that landed them, and they are not corroborated by an
 *   independent estimator.
 *
 * ## Why this set
 *
 * Tempo "2011" prescribes a 1 s pause at the TOP -- and on a lift whose
 * concentric goes up and which starts concentric, the top is the turnaround
 * INSIDE the rep. The metronome is the independent reference: its own
 * `Hold` -> `Down` interval is 1.001 s on all eight reps, asserted below from
 * the cue track rather than assumed from the digit. So this capture can ask
 * whether the published pause measures the hold the lifter was counted through.
 *
 * It is also the first leg-press fixture in this corpus that does NOT
 * undercount -- 8 detections for 8 called reps -- which matters because a
 * merged rep drags its own turnaround out to cover the reps it swallowed, and
 * that error is #72's, not this issue's.
 *
 * ## What this file pins
 *
 * A rep publishes ONE pause: the turnaround between its own two detected
 * phases. On this lift that is the TOP, and it is what the metronome held for
 * a second. The BOTTOM is where each rep begins and ends, so the stillness
 * there runs from the end of one rep's lowering to the start of the next
 * rep's drive and holds the lifter's rest -- it is not an interval inside the
 * rep and is no longer published as one.
 *
 * Before the fix, `bottomPauseS` carried exactly that outside interval and
 * read 0.02 / 0.10 / 1.57 / 0.33 / 1.29 / 0.06 / 0.08 / 0.01 s against a
 * prescribed bottom pause of ZERO, and `topPauseS` read a literal 0.0 on the
 * two reps that resolved no eccentric at all. Both figures are named in the
 * assertions below beside what replaces them, so the diff that made them
 * change is readable from the test.
 */
class RepPauseBoundaryTest {
    private val fixture = "field-legpress-single-2011-8rep-s36-set07"

    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    private fun track(name: String) = CueTrack.read(name).map { VoiceCue(it.timestampMs, it.label) }

    private fun analyze() = SetAnalyzer.analyze(
        load("$fixture.csv"),
        LiftDirection(startsWith = StartPhase.CONCENTRIC),
        loadKg = 65.77089365145514,
        cues = track(fixture),
    )

    /** Equality of measured seconds at three decimals, nulls compared as nulls. */
    private fun assertSeconds(expected: List<Double?>, actual: List<Double?>, label: String) {
        assertEquals(expected.size, actual.size, "$label: number of values")
        expected.indices.forEach { i ->
            val e = expected[i]
            val a = actual[i]
            if (e == null || a == null) {
                assertEquals<Double?>(e, a, "$label: rep ${i + 1}")
            } else {
                assertEquals(e, a, 1e-3, "$label: rep ${i + 1}")
            }
        }
    }

    @Test
    fun `the fixture is the set its meta_json describes, and the metronome held one second at the top`() {
        val analysis = analyze()
        assertEquals<Double>(
            99.35810680174781,
            analysis.sampleRateHz,
            "measured rate, against this set's own meta.json sampleRate_hz",
        )
        assertEquals(8, CueTrack.calledReps(fixture), "metronome Down-cues, corroborating meta.json's 8")
        assertEquals(8, analysis.reps.size, "segmented reps; the lifter counted 8")
        assertEquals(2, analysis.detectionsAfterSetEndCue, "detections after Done")
        // The prescription's third digit is a 1. This asserts the metronome
        // actually held that long, from the cue track's own timestamps.
        val cues = CueTrack.read(fixture)
        val holds = mutableListOf<Double>()
        var heldAt: Long? = null
        cues.forEach { c ->
            when (c.label) {
                "Hold" -> heldAt = c.timestampMs
                "Down" -> {
                    heldAt?.let { holds += (c.timestampMs - it) / 1000.0 }
                    heldAt = null
                }
                else -> Unit
            }
        }
        assertEquals(
            listOf(1.001, 1.0, 1.001, 1.001, 1.002, 1.001, 1.001, 1.002),
            holds.map { kotlin.math.round(it * 1000) / 1000.0 },
            "the metronome's own Hold -> Down interval, seconds",
        )
    }

    @Test
    fun `bottomPause_s is not published, because the bottom is where this rep begins and ends`() {
        val analysis = analyze()
        // It used to read 0.02 / 0.10 / 1.57 / 0.33 / 1.29 / 0.06 / 0.08 /
        // 0.01 s against a prescribed bottom pause of ZERO -- the gap from the
        // end of each rep's lowering to the start of the next drive. Rep 3's
        // 1.57 s was longer than the metronome's entire top hold. Absent, not
        // zero: the lifter may well have paused at the bottom and nothing in
        // this pipeline can say how long for.
        val bottoms: List<Double?> = analysis.reps.map { it.bottomPauseS }
        assertEquals(List<Double?>(8) { null }, bottoms, "bottomPause_s")
    }

    @Test
    fun `topPause_s is the turnaround the metronome held, and is absent where there is none`() {
        val analysis = analyze()
        // Reps 2 and 3 resolved no eccentric -- eccS is null, which this repo
        // already treats as "unmeasured, never zero". A rep with one phase has
        // no interval between two of them, so the pause is null on the same
        // rule. It read a literal 0.0 before.
        assertSeconds(
            listOf(2.01, null, null, 1.13, 0.66, 1.83, 1.68, 3.34),
            analysis.reps.map { it.eccS },
            "ecc_s",
        )
        assertSeconds(
            listOf(0.97, null, null, 1.25, 1.71, 1.1, 2.14, 0.04),
            analysis.reps.map { it.topPauseS },
            "topPause_s",
        )
    }

    @Test
    fun `the published pause tracks the metronome's second, where the older field did not`() {
        // The point of the fixture. Two quantities against the same known 1 s:
        // the turnaround now published, and the interval that used to be
        // published beside it as the OTHER pause of the same rep.
        //
        // This is not a fitted band. It compares two populations against one
        // reference and asserts their ORDER, so it fails if the fix is
        // reverted -- reverting swaps which quantity each variable holds and
        // inverts the comparison.
        val analysis = analyze()
        val turnarounds = analysis.reps.mapNotNull { it.topPauseS }.sorted()
        assertEquals(6, turnarounds.size, "reps that resolved both phases, and so have a turnaround")
        assertEquals(1.175, (turnarounds[2] + turnarounds[3]) / 2.0, 1e-3, "median turnaround, seconds")
        // The metronome's own figure, from the cue track, asserted above: 1.001 s.
        assertTrue(
            kotlin.math.abs(1.175 - 1.001) < 0.25,
            "the published pause lands within a quarter second of the second the lifter was held for",
        )
        // What the same reps published as their other pause before #93: the
        // intervals between reps, whose median is 0.09 s -- an order out from
        // the hold, on a set where nothing was prescribed at that end.
        assertEquals(
            List<Double?>(8) { null },
            analysis.reps.map { it.bottomPauseS },
            "and the interval that produced 0.09 s is no longer published as a pause at all",
        )
    }
}
