package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.VoiceCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measured shape of a set abandoned during its lead-in, and of the set
 * beside it that was not (#216).
 *
 * ## Provenance
 *
 * Session field-37, read from `meta.json` of the archive the app itself wrote:
 * `epoch` `2026-09-02T09:20:45.365Z`, `appVersion` `0.1.48`, `timeZoneId`
 * `America/New_York`, `utcOffsetMinutes` -240, `sensorModel`
 * `WitMotion WT901BLECL`, `csvHeaderCues` `timestamp_ms,cue`, `csvHeaderPrep`
 * `prep_started_ms,work_started_ms`. Three files are copied here BYTE FOR BYTE
 * from `field-37/extracted`, renamed only:
 *
 * - `field-abandoned-in-prep-s37-set13-cues.csv` -- `set13_rope_dead_hang_cues.csv`.
 * - `field-completed-prep-s37-set11-cues.csv` -- `set11_rope_dead_hang_cues.csv`.
 * - `field-completed-prep-s37-set11-prep.csv` -- `set11_rope_dead_hang_prep.csv`.
 *
 * Sets 11, 12 and 13 are the same exercise, `rope_dead_hang`, on the same day,
 * each with `plannedPrep_s` 12 and `prep_s` 12.
 *
 * ## What the archive says about set 13, quoted from its own descriptor
 *
 * `"duration_s": 0`, `"reps": 0`, `"failed": true`, `"prep_s": 12`,
 * `"plannedPrep_s": 12`, `"startedAt_ms": 1788343012005`,
 * `"endedAt_ms": 1788343018340`. It is the ONLY set of the thirteen whose
 * descriptor carries neither `prepStartedAt_ms` nor `workStartedAt_ms`, and
 * the only one with no `_prep.csv` and no `_reps.csv` in the archive. That is
 * `PrepWindowPolicy.of`'s `workStartedAtMs == null` case behaving as designed:
 * the prep was still running when the set ended, so the interval never closed
 * and no window was stored.
 *
 * The owner confirmed on 2026-09-02 that set 13 was a FABRICATED slot -- the
 * week-3 plan held two dead hangs and the third row is #195's re-armed slot --
 * so nothing here is evidence about what a lifter chose to do. It is evidence
 * about what the writer publishes for a set that ends before its work begins,
 * which is the same write path a squat abandoned in its prep takes.
 *
 * ## What this file pins and what it cannot
 *
 * It pins the CAPTURE: that the cue track of a set abandoned in its lead-in
 * reaches no lead-in word at all, so the export is the only place the fact can
 * live. It does not execute Room, SQLite or Android, and it says nothing about
 * what the sensor or the lifter did.
 */
class AbandonedInPrepFixtureTest {
    private fun text(name: String): String =
        checkNotNull(AbandonedInPrepFixtureTest::class.java.getResourceAsStream("/$name")) {
            "missing fixture $name"
        }.readBytes().decodeToString()

    private fun cues(name: String): List<VoiceCue> = CueCsv.decode(text(name))

    private val abandoned get() = cues("field-abandoned-in-prep-s37-set13-cues.csv")
    private val completed get() = cues("field-completed-prep-s37-set11-cues.csv")

    /** Set 13's tap, from its archive descriptor's `startedAt_ms`. */
    private val abandonedTapMs = 1_788_343_012_005L

    /** Set 13's end, from its archive descriptor's `endedAt_ms`. */
    private val abandonedEndMs = 1_788_343_018_340L

    /** Set 11's tap, from its archive descriptor's `startedAt_ms`. */
    private val completedTapMs = 1_788_342_762_411L

    /**
     * The set lasted 6.335 s of a 12 s prep, so it cannot have held anything.
     *
     * The figure a reader would otherwise have to take on trust from the issue
     * text, computed here from the two instants the archive published.
     */
    @Test
    fun `the abandoned set ran 6335 ms of a 12 second prep`() {
        assertEquals(6_335L, abandonedEndMs - abandonedTapMs, "the set's own span is not what the archive published")
    }

    /**
     * The whole cue track is five bare digits and not one word.
     *
     * This is what makes the export the only witness. `LeadInPlan` fixes the
     * spoken launch phrase to the END of the prep, so a prep cut short leaves
     * no prep word behind at all -- there is nothing in the capture for a
     * reader to reconstruct the cut from.
     */
    @Test
    fun `the abandoned set's cue track holds five bare digits and no word`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), abandoned.map { it.cue }, "the cue track is not five bare digits")
        assertTrue(abandoned.all { it.cue.toIntOrNull() != null }, "a word survived in the abandoned set's cue track")
    }

    /**
     * Every one of those cues falls inside the set, ahead of where the guide
     * would have spoken.
     *
     * Set 11, same exercise and the same 12 s prep, says `Ready` at +10.007 s
     * from its own tap. Set 13 ended at +6.335 s, so it stopped nearly four
     * seconds before its lead-in reached its first word.
     */
    @Test
    fun `the abandoned set ended before its lead-in reached its first word`() {
        val readyAt = completed.first { it.cue == "Ready" }.timestampMs - completedTapMs
        assertEquals(10_007L, readyAt, "the completed set does not say Ready where the archive puts it")
        val last = abandoned.maxOf { it.timestampMs } - abandonedTapMs
        assertTrue(last < readyAt, "the abandoned track reached the lead-in word after all: $last")
        assertTrue(
            abandoned.all { it.timestampMs in abandonedTapMs..abandonedEndMs },
            "a cue falls outside the set it was captured in",
        )
    }

    /**
     * The set that DID finish its prep stored a closed window, and the seconds
     * it carries are the seconds the export publishes.
     *
     * The positive control. `prep_s` is trustworthy on twelve of these
     * thirteen sets -- it matches the measured window to within 16 ms -- and
     * that is exactly why a reader is entitled to read it as elapsed, and
     * exactly why publishing it on the thirteenth is a false statement rather
     * than a harmless one.
     */
    @Test
    fun `the completed set's stored prep window matches the twelve seconds it published`() {
        val csv = text("field-completed-prep-s37-set11-prep.csv")
        val window = checkNotNull(PrepWindowCsv.decode(csv)) { "the completed set's window did not decode" }
        assertEquals(completedTapMs, window.startedAtMs, "the stored window does not open at the set's own tap")
        assertEquals(12_011L, window.workStartedAtMs - window.startedAtMs, "the measured prep is not 12.011 s")
    }
}
