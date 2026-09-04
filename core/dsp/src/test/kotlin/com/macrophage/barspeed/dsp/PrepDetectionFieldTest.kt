package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two field-38 sets issue #245 was filed on, and the licence to reason
 * about them here: analysed with no work-start instant, this module reproduces
 * what the app published, to the last digit.
 *
 * ## Provenance
 *
 * Both captures are the `imu-a` stream -- the role the session's own
 * `meta.json` records as `analysedRole` -- of field session 38, recorded
 * 2026-09-04T09:52:33.623Z by app 0.1.50, sensor `WitMotion WT901BLECL`, zone
 * `America/New_York` (UTC-04:00), dual-armed with roles `a` and `b`. Every
 * figure below is read from that archive's `session.json` (export schema
 * 1.18); every geometry and instant from its `meta.json`.
 *
 * - `field-ohp-3010-8rep-s38-set05` -- set 5, seated overhead press,
 *   13.607771100301063 kg (30.0 lb), tempo `3010`, 8 reps counted by the
 *   lifter (`repsManual: true`), RPE 8, prep 10 s, concentric-first, drive up,
 *   vertical, not stack-mounted, not inverted, travel ratio 1.0. 4844 samples.
 *   `startedAt_ms` 1788516173944, `workStartedAt_ms` 1788516183953.
 * - `field-inclinepress-3010-12rep-s38-set02` -- set 2, dumbbell incline
 *   press, 27.215542200602126 kg (60.0 lb), tempo `3010`, 12 reps counted by
 *   the lifter, RPE 6, prep 10 s, eccentric-first, drive up, vertical. 6852
 *   samples. `startedAt_ms` 1788515701922, `workStartedAt_ms` 1788515711934.
 *
 * Each ships with its own `-cues.csv` and `-prep.csv` from the same archive,
 * unedited. The `-prep.csv` files carry the instant this issue is about, and
 * nothing in this file passes one to the analyzer: these are the pins that say
 * the fixtures are faithful, and they are written so they stay true after a
 * head-of-stream bound exists, because a set analysed without that instant is
 * not bounded at its head at all.
 *
 * ## What they show, which is why they are here
 *
 * Set 5 publishes `peakPower_w` 402.5 from its THIRD detection, and its
 * `velocityLoss_pct` of 62.1 is measured against that same detection's
 * `meanConVel_mps` of 1.26 -- the fastest of the set. Set 2's 27.4 is measured
 * against its FIRST detection at 0.802. Whether those detections are reps of
 * their sets is what issue #245 asks; nothing in this file answers it.
 */
class PrepDetectionFieldTest {
    private fun load(f: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$f.csv")!!.readBytes().decodeToString())

    private fun track(f: String): List<VoiceCue> = CueTrack.read(f).map { VoiceCue(it.timestampMs, it.label) }

    /** The set's own prep row, `prep_started_ms,work_started_ms`. */
    private fun prep(f: String): Pair<Long, Long> {
        val row = javaClass.getResourceAsStream("/$f-prep.csv")!!
            .readBytes().decodeToString().trim().lines()[1].split(",")
        return row[0].trim().toLong() to row[1].trim().toLong()
    }

    private val ohp = "field-ohp-3010-8rep-s38-set05"
    private val press = "field-inclinepress-3010-12rep-s38-set02"

    private val ohpDirection = LiftDirection(startsWith = StartPhase.CONCENTRIC)
    private val pressDirection = LiftDirection(startsWith = StartPhase.ECCENTRIC)
    private val ohpKg = 13.607771100301063
    private val pressKg = 27.215542200602126

    private fun analyse(f: String, d: LiftDirection, kg: Double) =
        SetAnalyzer.analyze(load(f), d, kg, SetTargets(), DspConfig(), track(f))

    @Test
    fun `set 5 reproduces every rep figure its session published`() {
        val a = analyse(ohp, ohpDirection, ohpKg)
        assertEquals(13, a.reps.size, "detections published")
        assertEquals(99.34970357150183, a.sampleRateHz, 1e-12, "sampleRate_hz")
        assertEquals(
            listOf(0.611, 0.358, 0.86, 1.274, 0.461, 0.202, 0.35, 0.415, 0.813, 0.61, 1.405, 0.533, 0.683),
            a.reps.map { it.romM },
            "rom_m, in the order session.json lists them",
        )
        assertEquals(
            listOf(0.715, 0.187, 1.26, 0.425, 0.473, 0.366, 0.263, 0.476, 0.646, 0.801, 0.514, 0.361, 0.478),
            a.reps.map { it.meanConVelMps },
            "meanConVel_mps",
        )
        assertEquals(
            listOf(176.1, 46.3, 402.5, 107.6, 99.2, 79.0, 90.0, 117.4, 221.4, 169.8, 320.1, 75.5, 109.9),
            a.reps.map { it.peakPowerW },
            "peakPower_w",
        )
        assertEquals(62.1, a.velocityLossPct!!, 1e-12, "velocityLoss_pct")
        assertEquals(402.5, a.reps.mapNotNull { it.peakPowerW }.max(), 1e-12, "summary.peakPower_w")
    }

    @Test
    fun `set 2 reproduces every rep figure its session published`() {
        val a = analyse(press, pressDirection, pressKg)
        assertEquals(11, a.reps.size, "detections published")
        assertEquals(99.37626921961126, a.sampleRateHz, 1e-12, "sampleRate_hz")
        assertEquals(
            listOf(1.517, 0.32, 0.238, 0.16, 0.395, 0.503, 1.826, 0.576, 0.454, 0.356, 1.376),
            a.reps.map { it.romM },
            "rom_m",
        )
        assertEquals(
            listOf(0.802, 0.491, 0.376, 0.284, 0.307, 0.512, 0.658, 0.526, 0.485, 0.432, 0.582),
            a.reps.map { it.meanConVelMps },
            "meanConVel_mps",
        )
        assertEquals(
            listOf(555.0, 211.9, 148.0, 103.2, 223.9, 247.0, 437.8, 246.6, 207.3, 584.0, 435.1),
            a.reps.map { it.peakPowerW },
            "peakPower_w",
        )
        assertEquals(27.4, a.velocityLossPct!!, 1e-12, "velocityLoss_pct")
        assertEquals(584.0, a.reps.mapNotNull { it.peakPowerW }.max(), 1e-12, "summary.peakPower_w")
    }

    /**
     * The instants the fixtures carry, so a later rule cannot be pinned against
     * a prep row that drifted from the archive it was lifted from.
     *
     * Issue #125's rule is inert on both: `refusedDetections` is 0 on each, so
     * neither set's published figures owe anything to the range bound, and a
     * head-of-stream rule is measured against the shipped analyzer rather than
     * against a set that had already moved.
     */
    @Test
    fun `both fixtures carry the prep row their session recorded, and neither refuses a detection`() {
        assertEquals(1788516173944L to 1788516183953L, prep(ohp), "set 5 prep row")
        assertEquals(1788515701922L to 1788515711934L, prep(press), "set 2 prep row")
        assertEquals(0, analyse(ohp, ohpDirection, ohpKg).refusedDetections, "set 5 refusals")
        assertEquals(0, analyse(press, pressDirection, pressKg).refusedDetections, "set 2 refusals")
    }

    /**
     * Both sets are bounded at the TAIL and neither is bounded at the head,
     * which is the asymmetry issue #245 is about, stated as a measurement
     * rather than as prose.
     *
     * Set 5's cue track ends the set two detections before the stream does; set
     * 2's ends it with nothing after.
     */
    @Test
    fun `the tail is bounded and the head is not`() {
        val a = analyse(ohp, ohpDirection, ohpKg)
        assertEquals(2, a.detectionsAfterSetEndCue, "set 5 detections after Done")
        val b = analyse(press, pressDirection, pressKg)
        assertEquals(0, b.detectionsAfterSetEndCue, "set 2 detections after Done")
        assertTrue(
            SetEnd.of(track(ohp)) is SetEnd.Cued && SetEnd.of(track(press)) is SetEnd.Cued,
            "both sets named their own end",
        )
    }
}
