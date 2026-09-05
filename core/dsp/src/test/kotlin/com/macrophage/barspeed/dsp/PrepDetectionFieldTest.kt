package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two field-38 sets issue #245 was filed on, and the licence to reason
 * about them here: analysed with no work-start instant, this module reproduces
 * what the app published, to the last digit -- on SET 5. It no longer does on
 * set 2, and the difference is the point rather than a defect. Set 2 is
 * eccentric-first, and issue #72's slow-eccentric fallback publishes reps
 * whose lowering never became a phase where the previous rule DELETED them.
 * Set 2 therefore publishes two reps the archive's `session.json` does not
 * carry, both with `ecc_s` absent, and `set 2 publishes two reps its session
 * deleted for lowering too slowly` is what states the pair.
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

    /** The same set, handed the work-start instant its own `-prep.csv` carries. */
    private fun bounded(f: String, d: LiftDirection, kg: Double) =
        SetAnalyzer.analyze(load(f), d, kg, SetTargets(), DspConfig(), track(f), prep(f).second)

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

    /**
     * Set 2 against the eleven detections its own `session.json` published,
     * and the two this branch adds to them.
     *
     * ISSUE #72 MOVED THIS PIN AND IT IS NOT A DEFECT. The set is
     * eccentric-first, and `RepSegmenter.pairEccentricFirst`'s `loweredSince`
     * fallback publishes a drive whose lowering never became a phase instead
     * of deleting the rep. Two of set 2's drives are in that state, so the
     * analyzer publishes THIRTEEN where app 0.1.50 published eleven. Both
     * added reps carry `ecc_s` ABSENT, which is the export change the eleventh
     * 1.19 entry describes and the only committed field evidence for it: both
     * captures issue #72 added are concentric-first and never reach this code.
     *
     * The lifter counted TWELVE by hand. Unbounded, the analyzer now
     * overshoots by one where it undershot by one; bounded at the head it
     * publishes exactly twelve, pinned in `set 2 stops dividing its velocity
     * loss by a drive from the countdown`. That is one capture and is not a
     * claim that the fallback counts correctly.
     *
     * The eleven figures the archive carries are unchanged in value and in
     * order; the two new reps are inserted at index 4 and index 9, and
     * `velocityLoss_pct` and `summary.peakPower_w` do not move at all.
     */
    @Test
    fun `set 2 publishes two reps its session deleted for lowering too slowly`() {
        val a = analyse(press, pressDirection, pressKg)
        assertEquals(13, a.reps.size, "detections published")
        assertEquals(99.37626921961126, a.sampleRateHz, 1e-12, "sampleRate_hz")
        assertEquals(
            listOf(1.517, 0.32, 0.238, 0.16, 0.359, 0.395, 0.503, 1.826, 0.576, 0.886, 0.454, 0.356, 1.376),
            a.reps.map { it.romM },
            "rom_m",
        )
        assertEquals(
            listOf(0.802, 0.491, 0.376, 0.284, 0.249, 0.307, 0.512, 0.658, 0.526, 0.404, 0.485, 0.432, 0.582),
            a.reps.map { it.meanConVelMps },
            "meanConVel_mps",
        )
        assertEquals(
            listOf(555.0, 211.9, 148.0, 103.2, 190.5, 223.9, 247.0, 437.8, 246.6, 211.8, 207.3, 584.0, 435.1),
            a.reps.map { it.peakPowerW },
            "peakPower_w",
        )
        // WHICH reps are new, named rather than counted: the two the fallback
        // publishes are exactly the two with no measured eccentric, and every
        // other rep still carries one. A change that added a third rep WITH an
        // eccentric would pass a count assertion and fail this one.
        assertEquals(
            listOf(4, 9),
            a.reps.indices.filter { a.reps[it].eccS == null },
            "the reps published on the drive alone, by index",
        )
        assertEquals(
            listOf(0.359, 0.886),
            a.reps.filter { it.eccS == null }.map { it.romM },
            "rom_m of the two the archive does not carry",
        )
        // The eleven the archive DOES carry, in its order, so the insertion
        // cannot be read as a re-segmentation of the whole set.
        assertEquals(
            listOf(1.517, 0.32, 0.238, 0.16, 0.395, 0.503, 1.826, 0.576, 0.454, 0.356, 1.376),
            a.reps.filter { it.eccS != null }.map { it.romM },
            "rom_m of the reps session.json published, unchanged and in order",
        )
        assertEquals(27.4, a.velocityLossPct!!, 1e-12, "velocityLoss_pct does not move")
        assertEquals(584.0, a.reps.mapNotNull { it.peakPowerW }.max(), 1e-12, "summary.peakPower_w does not move")
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
     * How many detections each set resolved during its own prep countdown,
     * counted the moment the analyzer is handed the instant.
     *
     * A GREEN pin on a count that did not exist before the commit adding it,
     * not a differential. Set 5 finished three drives before its work began and
     * set 2 one; a fourth drive on set 5 began before the instant and was still
     * running when it passed, and [WorkStart] keeps it -- 19 detections across
     * field-38 began early and 14 ended early, and the five that differ are
     * straddlers of exactly this kind.
     *
     * Null rather than 0 when no instant is offered, which is every one of this
     * file's other cases and every caller in this repository that has no prep
     * row to hand.
     */
    @Test
    fun `each set counts the detections that finished before its work began`() {
        assertEquals(3, bounded(ohp, ohpDirection, ohpKg).detectionsBeforeWorkStart, "set 5")
        assertEquals(1, bounded(press, pressDirection, pressKg).detectionsBeforeWorkStart, "set 2")
        assertNull(
            analyse(ohp, ohpDirection, ohpKg).detectionsBeforeWorkStart,
            "set 5 with no instant offered",
        )
        assertNull(
            analyse(press, pressDirection, pressKg).detectionsBeforeWorkStart,
            "set 2 with no instant offered",
        )
    }

    /**
     * THE DIFFERENTIAL. What set 5 publishes once the detections that finished
     * before its work began are not reps of it.
     *
     * Every figure here is the published one recomputed over the surviving
     * detections and nothing else: `velocityLoss_pct` 62.1 -> 40.3, because the
     * 1.26 m/s drive it divided by happened during the countdown and the
     * fastest survivor runs 0.801; `summary.peakPower_w` 402.5 -> 320.1, a 25.7%
     * overstatement of the set's peak. The last rep is unchanged at 0.478 m/s,
     * so what moves is the BASIS and not the athlete's last effort.
     *
     * Ten detections rather than thirteen, and the lifter counted eight by hand.
     * The rule does not claim to fix the count -- three of the ten still range
     * over 0.8 m on a seated dumbbell overhead press -- only to stop the
     * countdown setting the headline. Under-counting and over-segmentation on
     * this session are not this issue.
     */
    @Test
    fun `set 5 stops publishing the countdown as its fastest and most powerful rep`() {
        val a = bounded(ohp, ohpDirection, ohpKg)
        assertEquals(10, a.reps.size, "detections that are reps of the set")
        assertEquals(40.3, a.velocityLossPct!!, 1e-12, "velocityLoss_pct")
        assertEquals(320.1, a.reps.mapNotNull { it.peakPowerW }.max(), 1e-12, "summary.peakPower_w")
        assertEquals(
            listOf(1.274, 0.461, 0.202, 0.35, 0.415, 0.813, 0.61, 1.405, 0.533, 0.683),
            a.reps.map { it.romM },
            "rom_m of the survivors, renumbered from zero",
        )
        assertEquals(0.478, a.reps.last().meanConVelMps, 1e-12, "the last rep does not move")
        assertEquals(0.801, a.reps.maxOf { it.meanConVelMps }, 1e-12, "the fastest survivor is the new basis")
    }

    /**
     * The same differential on set 2, where one detection is at stake rather
     * than three and it is the FIRST.
     *
     * 27.4 -> 11.6. Set 2's `summary.peakPower_w` does not move -- its 584.0 is
     * on a detection the lifter performed -- which is what makes the pair
     * worth having: the rule moves the loss basis on both sets and the peak
     * power on only one, so a test that passed by moving every figure at once
     * would be wrong here.
     *
     * TWELVE survivors, not ten. The count read ten until issue #72's
     * slow-eccentric fallback added two reps to this set upstream of the head
     * bound; neither of the two is removed by it, because both finish after
     * the work began. Twelve is also the count the lifter recorded by hand for
     * this set. That agreement is ONE capture and is reported, not relied on:
     * the head bound and the fallback were written for different reasons and
     * nothing makes them agree in general.
     */
    @Test
    fun `set 2 stops dividing its velocity loss by a drive from the countdown`() {
        val a = bounded(press, pressDirection, pressKg)
        assertEquals(12, a.reps.size, "detections that are reps of the set")
        assertEquals(11.6, a.velocityLossPct!!, 1e-12, "velocityLoss_pct")
        assertEquals(584.0, a.reps.mapNotNull { it.peakPowerW }.max(), 1e-12, "summary.peakPower_w does not move")
        assertEquals(0.658, a.reps.maxOf { it.meanConVelMps }, 1e-12, "the fastest survivor is the new basis")
        assertEquals(0.582, a.reps.last().meanConVelMps, 1e-12, "the last rep does not move")
        // The one the head bound removes is the FIRST, and it is one the
        // archive published: the fallback's two are both still here.
        assertEquals(
            listOf(0.32, 0.238, 0.16, 0.359, 0.395, 0.503, 1.826, 0.576, 0.886, 0.454, 0.356, 1.376),
            a.reps.map { it.romM },
            "rom_m of the survivors, renumbered from zero",
        )
        assertEquals(2, a.reps.count { it.eccS == null }, "both drive-only reps survive the head bound")
    }

    /**
     * The corpus sweep: what the head bound does to every OTHER committed
     * capture that carries a work-start instant.
     *
     * Only four captures carry a `-prep.csv` at all, and a capture without one
     * offers no instant, so `WorkStart.Unknown` bounds nothing and the other
     * thirty-eight are unchanged by construction rather than by luck. The two
     * that are not this issue's resolve NOTHING before their own instant --
     * field-37 was recorded with the sensor already in position -- so their
     * published figures do not move, and this is what says so rather than a
     * sentence claiming it.
     *
     * `field-ohp-prepinflated-s37-set03`'s and `-set04`'s velocity losses are
     * read from `SetAnalyzer` twice, with and without the instant, rather than
     * quoted: a quoted figure would go stale the next time anything upstream
     * of the analyzer moved.
     */
    @Test
    fun `the other two captures carrying a prep instant resolve nothing before it`() {
        listOf("field-ohp-prepinflated-s37-set03", "field-ohp-prepinflated-s37-set04").forEach { f ->
            val d = LiftDirection(startsWith = StartPhase.CONCENTRIC)
            val kg = 22.67961850050177
            val unbounded = analyse(f, d, kg)
            val withInstant = bounded(f, d, kg)
            assertEquals(0, withInstant.detectionsBeforeWorkStart, "$f: detections before work start")
            assertEquals(unbounded.reps, withInstant.reps, "$f: the rep list moved")
            assertEquals(unbounded.velocityLossPct, withInstant.velocityLossPct, "$f: velocityLoss_pct moved")
            assertEquals(unbounded.noRepsReason, withInstant.noRepsReason, "$f: noRepsReason moved")
        }
    }

    /**
     * Analysed with no work-start instant, both sets are bounded at the TAIL
     * and at neither head -- the asymmetry issue #245 was filed on, kept as a
     * measurement of what the cue bound alone does. `SetAnalyzer` bounds the
     * head from #245 on; this pin deliberately withholds the instant.
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
