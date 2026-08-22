package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Session 32, set 6: what the analyzer makes of the stream that keeps
 * running after the metronome said the set was over. Issue #125.
 *
 * ## Provenance
 *
 * Chest-supported rear delt fly, 12 reps performed at 20 lb, recorded on app
 * 0.1.41 on 2026-08-21 and rated RPE 7 by the lifter. `field-reardeltfly-s32-set06.csv`
 * and its `-cues.csv` are that set's two raw streams copied byte for byte out of
 * the session's raw export; nothing was re-encoded, resampled or trimmed.
 *
 * The geometry below is not a guess and was not swept for. This session's export
 * carries a `geometry` block per set (schema 1.2), and set 6's reads
 * concentric-first, drive up, vertical, sensor on the implement, travel ratio
 * 1.0. With it, [SetAnalyzer.analyze] reproduces the set's published
 * `repMetrics`, `velocityLoss_pct` and `summary` to the last digit -- which is
 * what makes this fixture usable as evidence about the shipped app rather than
 * about this test.
 *
 * ## What the capture shows
 *
 * The published `velocityLoss_pct` is 82.6 with `velocityLossBasis: "measured"`,
 * the app's strongest assertion that a figure is trustworthy. The fifth and last
 * detection begins 3.48 s AFTER the set's own `Done` cue and carries a 5.07 s
 * drive against a 1 s prescription -- tempo 2011 with the drive up, so digit 3
 * is the drive. Best-to-last is measured against it.
 *
 * The tail is not an accident of this set. The eleven sets of the capture that
 * carry both a `Done` cue and an IMU stream keep recording for 4.3 to 13.7 s
 * past the cue, measured as last sample minus cue; five of the seventeen never
 * say `Done` at all and are not bounded by this rule. So there is usually
 * somewhere for a spurious detection to land, and whether one does is luck.
 * Set 6 is pinned here; set 14 shows the same shape in the same session's
 * export and has no fixture committed. Sets 8 and 10 carry the same published
 * signature and were not analysed.
 *
 * ## What these pins do NOT say
 *
 * They pin what the analyzer computes, not what the lifter did. The lifter
 * performed 12 reps; the analyzer resolves 5, and of those 5 the third and
 * fourth carry 1.835 m and 1.930 m of range on a movement whose real travel is a
 * fraction of that. Segmentation quality on this capture is issues #72, #87 and
 * #94 and is deliberately untouched here -- a rule that judged a detection by
 * its range or its duration would be the tuned threshold this window rule exists
 * to avoid.
 */
class SetEndWindowTest {
    private fun load(name: String) =
        ImuCsv.decode(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    private val fixture = "field-reardeltfly-s32-set06"

    /** Set 6's exported geometry block, read off the session's own export. */
    private val rearDeltFly = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = false,
        travelRatio = 1.0,
        plane = MovementPlane.VERTICAL,
        sensorOnStack = false,
    )

    /** The set's exported `load_kg`, which is 20.0 lb. */
    private val loadKg = 9.071847400200708

    /**
     * One row of the published `repMetrics` array, in the export's own units.
     * Only the fields this fixture is cited for are carried.
     */
    private data class PublishedRep(
        val eccS: Double?,
        val conS: Double,
        val meanConVelMps: Double,
        val peakConVelMps: Double,
        val romM: Double,
        val peakPowerW: Double,
    )

    /** `repMetrics` exactly as v0.1.41 published it for this set. */
    private val published = listOf(
        PublishedRep(0.54, 1.19, 0.768, 1.522, 0.918, 214.4),
        PublishedRep(0.47, 0.52, 0.381, 0.556, 0.203, 50.6),
        PublishedRep(null, 2.80, 0.654, 2.085, 1.835, 244.9),
        PublishedRep(null, 5.23, 0.368, 0.622, 1.930, 58.9),
        PublishedRep(null, 5.07, 0.134, 0.306, 0.679, 27.8),
    )

    /**
     * The fixture reproduces the shipped export, rep for rep.
     *
     * This is the pin that licenses every other claim made from this capture. It
     * is also the analysis of an UNCUED set -- no cue track is passed -- so it
     * states the other half of the window rule at the same time: with nothing on
     * the record saying when the set ended, no boundary is invented and every
     * detection is kept, including the one that is plainly post-set handling.
     */
    @Test
    fun `set 6 reproduces its published export rep for rep with no cue track`() {
        val analysis = SetAnalyzer.analyze(load("$fixture.csv"), rearDeltFly, loadKg = loadKg)
        assertEquals(published.size, analysis.reps.size, "detections resolved; the lifter performed 12 reps")
        analysis.reps.forEachIndexed { i, rep ->
            val want = published[i]
            assertEquals(want.eccS, rep.eccS, "rep ${i + 1} ecc_s")
            assertEquals(want.conS, rep.conS, "rep ${i + 1} con_s")
            assertEquals(want.meanConVelMps, rep.meanConVelMps, "rep ${i + 1} meanConVel_mps")
            assertEquals(want.peakConVelMps, rep.peakConVelMps, "rep ${i + 1} peakConVel_mps")
            assertEquals(want.romM, rep.romM, "rep ${i + 1} rom_m")
            assertEquals(want.peakPowerW, rep.peakPowerW, "rep ${i + 1} peakPower_w")
        }
        assertEquals(82.6, analysis.velocityLossPct, "published velocityLoss_pct")
        assertEquals(
            2.085,
            analysis.reps.maxOf { it.peakConVelMps },
            "published summary.peakConVel_mps",
        )
    }

    /**
     * The enabling condition, stated as a number: the stream runs on for ten and
     * a half seconds after the app stopped prescribing.
     *
     * A data pin over the two fixture files, and it asserts nothing about any
     * code path beyond [CueTrack.read] and [ImuCsv.decode]. It is here because
     * the rest of this class is meaningless without it -- a window rule on a
     * capture with no tail would be a rule with nothing to do.
     */
    @Test
    fun `set 6 keeps recording for ten and a half seconds after its own Done cue`() {
        val samples = load("$fixture.csv")
        val done = CueTrack.read(fixture).first { it.label == "Done" }
        assertEquals(1787340900506L, done.timestampMs, "the Done cue instant")
        assertEquals(1787340847464L, samples.first().timestampMs, "first sample")
        assertEquals(1787340911067L, samples.last().timestampMs, "last sample")
        assertEquals(10561L, samples.last().timestampMs - done.timestampMs, "tail after Done, ms")
    }

    /**
     * Where each detected drive BEGINS relative to the `Done` cue, in
     * milliseconds, negative before.
     *
     * Compared on the arrival clock at both ends: a `RepSpan` indexes the
     * velocity series, which [VelocityEstimator.estimate] builds index-parallel
     * to the sample list, so `samples[conStartIdx]` is the sample the drive
     * starts on and its own `timestampMs` is the same wall clock the cue was
     * stamped on. Nothing here converts through the reconstructed clock, so
     * [CueTrack.MAX_SKEW_MS] does not enter.
     *
     * Four of five start before the cue. The fifth starts 3.479 s after it, and
     * the fourth starts 1.829 s before it while ending 3.389 s after -- the
     * straddling case, which is the one the rule has to make a decision about.
     */
    @Test
    fun `four of set 6's five detected drives begin before the Done cue`() {
        val samples = load("$fixture.csv")
        val doneMs = CueTrack.read(fixture).first { it.label == "Done" }.timestampMs
        val series = VelocityEstimator.estimate(samples, DspConfig(), rearDeltFly.measuredPlane)
            .mappedToLifter(rearDeltFly.sensorToLifter)
        val spans = RepSegmenter.segment(series, rearDeltFly, DspConfig())
        assertEquals(
            listOf(-50247L, -33778L, -32731L, -1829L, 3479L),
            spans.map { samples[it.conStartIdx].timestampMs - doneMs },
            "drive start relative to Done, ms",
        )
        assertEquals(
            listOf(-49081L, -33237L, -29909L, 3389L, 8550L),
            spans.map { samples[it.conEndIdx].timestampMs - doneMs },
            "drive end relative to Done, ms",
        )
    }
}
