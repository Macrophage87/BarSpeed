package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression fixtures from real gym sessions. Two failure modes are pinned:
 *
 * 1. 10 Hz factory-default rate (the app failed to raise it — see
 *    WitmotionCommands.unlock): heavy attenuation, live tracker used to lock
 *    into a phantom "Lowering" phase forever.
 * 2. 100 Hz with bursty BLE arrivals: many frames share one arrival timestamp
 *    (median dt = 0 ms), so integrating against arrival times collapsed most
 *    of the signal, and rotation-induced gravity-projection bias drifted the
 *    integrator past the ZUPT rejection band.
 *
 * Rep counts here are pinned EXACTLY, against the count the lifter performed
 * named beside them. They used to be ranges — `reps.size in 4..9` for a set
 * the same line called "(8 real)" — and a band that wide cannot fail: it
 * passed at 4 of 8, so any candidate improvement to segmentation would have
 * been graded against an assertion that ratified the defect. An exact pin
 * records what the analyzer does TODAY and nothing about what it should do;
 * a fix is expected to red it, and to replace it with its inversion.
 */
class FieldDataRegressionTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    @Test
    fun `batch analysis segments slow squats from the 10 Hz field set`() {
        val samples = load("field-backsquat-10hz.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.ECCENTRIC, loadKg = 47.6)
        // The lifter did exactly 5 slow-tempo reps. The analyzer finds 4: the
        // file ends mid-re-rack, which smears the final rep. Pinned exactly,
        // because the old `in 4..6` band could not tell 4 from 5 and so could
        // not tell a fix from a coincidence.
        assertEquals(4, analysis.reps.size, "segmented reps; the lifter performed 5")
        val sane = analysis.reps.count { (it.eccS ?: 0.0) in 2.5..8.0 && it.romM < 1.5 }
        assertEquals(4, sane, "reps showing the slow-tempo character, of ${analysis.reps.size} segmented")
    }

    @Test
    fun `streaming tracker follows the 10 Hz field set without locking up`() {
        val samples = load("field-backsquat-10hz.csv")
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        // Live counting is best-effort at 10 Hz (grinder concentrics measure
        // near the noise floor); the batch analyzer is authoritative for the
        // stored count. What must never happen is the field failure mode:
        // a phantom phase lock-in or runaway velocity.
        assertEquals(4, last.repCount, "live rep count; the lifter performed 5")
        // Deliberately still a bound: this one asks "did the integrator run
        // away", which is a one-sided catastrophe question, not a measurement.
        assertTrue(abs(last.velocityMps) < 0.25, "velocity drifted: ${last.velocityMps}")
    }

    @Test
    fun `two quiet minutes on the rack stay anchored with no phantom reps (set 5)`() {
        val samples = load("field-backsquat-10hz-set5.csv")
        val startMs = samples.first().timestampMs
        val tracker = StreamingSetTracker(StartPhase.ECCENTRIC)
        var quietMaxV = 0.0
        var repsDuringQuiet = 0
        samples.forEach { sample ->
            val state = tracker.feed(sample)
            if (sample.timestampMs - startMs < 120_000) {
                quietMaxV = maxOf(quietMaxV, abs(state.velocityMps))
                repsDuringQuiet = state.repCount
            }
        }
        assertTrue(quietMaxV < 0.1, "velocity drifted to $quietMaxV during a quiet stretch")
        assertEquals(0, repsDuringQuiet, "phantom reps while the bar sat still")
    }

    @Test
    fun `batch analysis recovers the true rate and reps from bursty 100 Hz arrivals`() {
        val samples = load("field-ohp-100hz-bursty.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = 29.5)
        // 8 real overhead-press reps; arrival timestamps come in ~90 ms bursts
        // with median dt = 0, so only a span-based rate estimate works.
        assertTrue(
            analysis.sampleRateHz in 90.0..110.0,
            "measured rate ${analysis.sampleRateHz} (sensor streamed 100 Hz)",
        )
        assertEquals(7, analysis.reps.size, "segmented reps; the lifter performed 8")
        // Continuous cycling leaves stretches with no ZUPT anchor, so some reps
        // ride on residual drift; only a core of these look kinematically like
        // presses. (Before this fix the analyzer produced single "reps"
        // spanning 50 s and 150 m of ROM.) Pinned exactly: `sane >= 3` was true
        // of any outcome from 3 upwards and so measured nothing.
        val sane = analysis.reps.count { it.conS in 0.2..2.5 && it.romM in 0.2..1.2 }
        assertEquals(4, sane, "reps that look like real presses, of ${analysis.reps.size} segmented")
        // Deliberately still bounds: one-sided runaway-drift guards, not
        // measurements of the lift.
        analysis.reps.forEach { rep ->
            assertTrue(rep.conS < 10.0, "concentric ${rep.conS}s is runaway drift")
            assertTrue(rep.romM < 4.0, "ROM ${rep.romM}m is runaway drift")
        }
    }

    @Test
    fun `slow-eccentric seated press counts on the drive alone`() {
        // 2 real reps of seated OHP at tempo 3010: the ~5 s lowering averages
        // ~0.08 m/s — under the run threshold — so batch pairing that REQUIRED
        // a down run used to find 0 reps here. Con-first counting keys on the
        // drive; the eccentric is optional metric data. The second press sits
        // inside end-of-set re-rack drift (a 9 m phantom run, discarded by the
        // displacement cap), so batch recovers the first press only.
        val samples = load("field-seated-ohp-2rep.csv")
        val analysis = SetAnalyzer.analyze(samples, StartPhase.CONCENTRIC, loadKg = 20.4)
        assertEquals(1, analysis.reps.size, "segmented reps; the lifter performed 2 presses")
        analysis.reps.forEach { rep ->
            assertTrue(rep.conS in 0.2..2.5, "concentric ${rep.conS}s is implausible")
            assertTrue(rep.romM in 0.1..1.2, "ROM ${rep.romM}m is implausible")
        }

        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(2, last.repCount, "live count should match the 2 real presses")
    }

    @Test
    fun `streaming tracker counts presses live despite bursty arrivals`() {
        val samples = load("field-ohp-100hz-bursty.csv")
        // Presses from the rack position drive up first — concentric-first
        // counting is what makes the reps land (ecc-first pairing found 1 of 8).
        val tracker = StreamingSetTracker(StartPhase.CONCENTRIC)
        var last = LiveSetState()
        samples.forEach { last = tracker.feed(it) }
        assertEquals(5, last.repCount, "live rep count; the lifter performed 8")
        assertTrue(abs(last.velocityMps) < 0.25, "velocity drifted: ${last.velocityMps}")
    }
}
