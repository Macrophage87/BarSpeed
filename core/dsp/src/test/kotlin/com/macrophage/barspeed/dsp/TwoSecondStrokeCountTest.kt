package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a two-second stroke is counted out loud on every rep, or on two of
 * them (#248).
 *
 * ## What the owner asked for
 *
 * After hearing field-39's ten tempos, 2026-09-05, on the `2011` and `2010`
 * sets where the tempo count was spoken on reps 1 and 6 and nowhere between:
 * *"I'd want a number."* And, on the same issue: *"the count on a 2 s stroke is
 * wanted on every rep, so the merged rep call and the count have to share the
 * stroke ... rather than the call eating the count."*
 *
 * `1010` is deliberately NOT in scope and nothing here gates it. The owner, the
 * next comment: *"Let people set it that way if they want, but it's not
 * something to be concerned about."* It has no stroke of
 * [CadencePlan.CALL_MIN_STROKE_S] seconds, so the corpus rule below does not
 * reach it and it is carried here only to show that it does not.
 *
 * ## CHARACTERIZATION, and no red preceded it
 *
 * **#248 is already discharged on this branch, by #293, and this file is what
 * says so.** No behaviour changes in the commit that adds it, so nothing moved
 * for these assertions to red against -- the same footing
 * `CadenceVoiceTest.an X stroke is called as a one-second beat` states for
 * #250. What this file adds is the evidence and the guard: the archives #248
 * was measured on, the rows the guide writes over the same plans now, and a
 * rule that reds if a two-second stroke ever falls silent again.
 *
 * The mechanism, read off the two commits rather than inferred: #248's silence
 * was `CadenceBeat.suppressFirstCount`, set on whichever stroke carried a
 * merged call, and read by `CadenceVoice.countCall` as *"drop this stroke's
 * count at second 1"*. A two-second stroke has exactly one interior second, so
 * the flag took its only count. #293 deleted the flag with the merge: a call
 * REPLACES its stroke's word rather than riding it, and that stroke's counts
 * are renumbered from the call instead of being dropped. Nothing is given up
 * to make room, so there is nothing left for #248 to take away.
 *
 * ## What a 2011 rep sounds like, per second
 *
 * Seated overhead press, concentric-first, drive up -- field-39 set 1. The
 * cycle is `Up` 1 s, `Hold` 1 s, `Down` 2 s, and the two-second stroke is the
 * lowering:
 *
 * | second of the rep | before | after |
 * |---|---|---|
 * | 0 | `Rep 3` was not said here | `Rep 3` |
 * | 0 | `Up` | -- (the number took the word) |
 * | 1 | `Hold` | `Hold` |
 * | 2 | `Down, Rep 3` as one utterance | `Down` |
 * | 3 | silence -- the count the merge ate | `1` |
 *
 * "Before" is MEASURED twice, not reasoned: as the shipped `0.1.50` archive
 * pinned below, and as the script this branch forked from --
 * `35f0862a004f8c2cb581cd17c0c6e3229a8834ce`, whose `CadenceVoice.script` over
 * this plan and six reps returns
 * `0:Up, 1:Hold, 2:Down, Rep 2, 4:Up, 5:Hold, 6:Down, Rep 3, ... 20:Up,
 * 21:Hold, 22:Down, Last rep, 24:Done` -- one `1` in the whole set, on rep 1.
 * The owner's table on #248 derives the same figure for 0.1.51 and calls it
 * out: 1 of 6.
 *
 * ## Provenance
 *
 * Two archives, both app `0.1.50`, both `"sensorModel": "WitMotion
 * WT901BLECL"`, both `"csvHeaderCues": "timestamp_ms,cue"`.
 *
 * `field-38/BarSpeed-v0.1.50-20260904_055233-raw.zip`, `"epoch":
 * "2026-09-04T09:52:33.623Z"`, eighteen sets. Its
 * `set06_chest_supported_rear_delt_fly_cues.csv` and
 * `set10_seated_biceps_curl_cues.csv` are committed here as
 * `field-reardeltfly-2011-12rep-s38-set06-cues.csv` and
 * `field-bicepscurl-2010-12rep-s38-set10-cues.csv`, copied byte for byte.
 *
 * `field-39/f7ad0cb9-BarSpeedv0.1.5020260905_080515raw.zip`, `"epoch":
 * "2026-09-05T12:05:15.479Z"`, ten sets. Five of its cue tracks are committed
 * here, likewise byte for byte:
 *
 * | source | committed as |
 * |---|---|
 * | `set01_seated_overhead_press_cues.csv` | `field-ohp-2011-6rep-s39-set01` |
 * | `set02_seated_overhead_press_cues.csv` | `field-ohp-2010-6rep-s39-set02` |
 * | `set06_seated_overhead_press_cues.csv` | `field-ohp-20x0-6rep-s39-set06` |
 * | `set08_lat_pulldown_cues.csv` | `field-latpulldown-2011-6rep-s39-set08` |
 * | `set10_lat_pulldown_cues.csv` | `field-latpulldown-20x0-6rep-s39-set10` |
 *
 * Sets 6, 8 and 10 arrived in round 1 of review, which found the list below
 * claiming to be "the sets #248 names" while holding six of them. They carry
 * what field-39 added to the issue that field-38 could not: `20X0` is a tempo
 * no other fixture in this source set holds, and set 8 is `2011` on the
 * on-stack inverted geometry rather than set 1's upright one. The fixture
 * names spell `X` lowercase because every other resource here is lowercase;
 * the tempo strings in the tracks below are the `meta.json` spelling, `20X0`.
 *
 * Four more fixtures already in this source set are read rather than re-added:
 * `field-latpulldown-1120-12rep-s38-set14`, `field-ohp-3010-8rep-s38-set04`,
 * `field-latpulldown-1120-6rep-s39-set07` and `field-ohp-3010-6rep-s39-set03`.
 * Their rows belong to the files that pinned them --
 * `ClosingPauseLastRepTest` owns set 7's and set 3's -- so what is asserted of
 * them here is the digit population and nothing else, which is the quantity
 * #248 is about.
 *
 * Every geometry below is that set's own `meta.json` row: `tempoPrescribed`,
 * `startsWith`, `concentric`, `plane`, `sensorOnStack`, `sensorInverted`,
 * `plannedReps`. Only the sets whose performed reps equal their planned reps
 * are used, because the script models a prescription and an over-performed set
 * is not one.
 */
class TwoSecondStrokeCountTest {
    /** field-38 sets 6 and 10, field-39 sets 1, 2, 3 and 6: conc-first, drive up, vertical, off-stack. */
    private val driveUp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** field-38 set 14: lat_pulldown, conc-first, drive DOWN, vertical, on-stack, inverted. */
    private val pulldownDrivesDown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** field-39 sets 7, 8 and 10: lat_pulldown, conc-first, drive UP as declared, vertical, on-stack, inverted. */
    private val pulldownDeclaredUp = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = true,
        sensorOnStack = true,
    )

    private val s38set06 = "field-reardeltfly-2011-12rep-s38-set06"
    private val s38set10 = "field-bicepscurl-2010-12rep-s38-set10"
    private val s38set14 = "field-latpulldown-1120-12rep-s38-set14"
    private val s38set04 = "field-ohp-3010-8rep-s38-set04"
    private val s39set01 = "field-ohp-2011-6rep-s39-set01"
    private val s39set02 = "field-ohp-2010-6rep-s39-set02"
    private val s39set03 = "field-ohp-3010-6rep-s39-set03"
    private val s39set06 = "field-ohp-20x0-6rep-s39-set06"
    private val s39set07 = "field-latpulldown-1120-6rep-s39-set07"
    private val s39set08 = "field-latpulldown-2011-6rep-s39-set08"
    private val s39set10 = "field-latpulldown-20x0-6rep-s39-set10"

    private data class Track(
        val fixture: String,
        val tempo: String,
        val direction: LiftDirection,
        val reps: Int,
    )

    /**
     * The sets scored as affected, with the rep count each set's `meta.json`
     * planned and performed: field-38 sets 6, 10 and 14, and field-39 sets 1,
     * 2, 6, 8 and 10 -- every set of that session the issue's own field-39
     * table puts at 2 spoken digits -- plus field-39 set 7, the discriminator
     * it puts at 6.
     *
     * It is NOT every set #248 names, and the residue is written down rather
     * than left to be inferred from the tables below. #248's field-38 table
     * names eleven sets. Five of them -- 7, 9, 11, 13 and 16 -- over-performed
     * or failed against their prescription and are excluded by the rule the
     * class comment states. The other three match it and are simply not
     * committed here: set 8 is a `2011` seated lateral raise, sets 12 and 15
     * are `1120` triceps pushdown and lat pulldown, and each repeats a tempo
     * and a geometry this list already carries, so what the guide says over
     * them is read from `CadencePlan.of` rather than measured off an archive.
     */
    private val affected = listOf(
        Track(s38set06, "2011", driveUp, 12),
        Track(s38set10, "2010", driveUp, 12),
        Track(s38set14, "1120", pulldownDrivesDown, 12),
        Track(s39set01, "2011", driveUp, 6),
        Track(s39set02, "2010", driveUp, 6),
        Track(s39set06, "20X0", driveUp, 6),
        Track(s39set07, "1120", pulldownDeclaredUp, 6),
        Track(s39set08, "2011", pulldownDeclaredUp, 6),
        Track(s39set10, "20X0", pulldownDeclaredUp, 6),
    )

    /** The 3010 sets, which this commit must leave exactly as it found them. */
    private val unaffected = listOf(
        Track(s38set04, "3010", driveUp, 8),
        Track(s39set03, "3010", driveUp, 6),
    )

    /**
     * The track for a fixture, by name. Positional lookups into [affected] and
     * [unaffected] were how four of these assertions addressed their set, and
     * `affected.last()` silently re-aimed the moment the list grew.
     */
    private fun track(fixture: String) = (affected + unaffected).first { it.fixture == fixture }

    private fun plan(track: Track) = CadencePlan.of(TempoSchedule.of(Tempo.parse(track.tempo), track.direction))

    /** The lead-in's own rows, which the cadence script does not model. */
    private val leadIn = setOf("Ready", "Brace")

    /**
     * A track's rows as (second of the cadence, row), the first movement call
     * being second zero -- the same origin [CadenceVoice.script] counts from.
     *
     * Its own copy, not a shared helper, for the reason `ClosingPauseLastRepTest`
     * gives: one helper across files scoring different sessions lets a change
     * to one silently rescore the others.
     */
    private fun cadenceRows(fixture: String): List<Pair<Int, String>> {
        val rows = CueTrack.read(fixture).filter { it.label !in leadIn }
        val origin = rows.first().timestampMs
        return rows.map { row ->
            val offsetMs = row.timestampMs - origin
            val second = Math.round(offsetMs / 1000.0).toInt()
            assertTrue(
                kotlin.math.abs(offsetMs - second * 1000L) < 100,
                "$fixture: ${row.label} at $offsetMs ms is not within 100 ms of a whole second",
            )
            second to row.label
        }
    }

    /** The cue rows the plan says a set of [reps] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, reps: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, reps).flatMap { call -> call.recorded.map { call.atSecond to it } }

    /** Rows that are a bare tempo digit -- the utterance #248 counts. */
    private fun digits(rows: List<Pair<Int, String>>) = rows.filter { it.second.all(Char::isDigit) }

    @Test
    fun `what the two shipped archives recorded on the sets the owner heard`() {
        // Facts about two files. Nothing in this repository can change them,
        // and the expectations further down are read against them. 0.1.50 is
        // before #243, so a `Rep N` row here names the rep just FINISHED.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Hold", 2 to "Down", 3 to "1",
                4 to "Up", 5 to "Hold", 6 to "Down", 6 to "Rep 1",
                8 to "Up", 9 to "Hold", 10 to "Down", 10 to "Rep 2",
                12 to "Up", 13 to "Hold", 14 to "Down", 14 to "Rep 3",
                16 to "Up", 17 to "Hold", 18 to "Down", 18 to "Rep 4",
                20 to "Up", 21 to "Hold", 22 to "Down", 23 to "1",
                24 to "Done",
            ),
            cadenceRows(s39set01),
            "field-39 set 1, 2011, six reps of a four-second cycle: one `1` at each end and ten silent seconds",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1",
                3 to "Up", 4 to "Down", 4 to "Rep 1",
                6 to "Up", 7 to "Down", 7 to "Rep 2",
                9 to "Up", 10 to "Down", 10 to "Rep 3",
                12 to "Up", 13 to "Down", 13 to "Rep 4",
                15 to "Up", 16 to "Down", 17 to "1",
                18 to "Done",
            ),
            cadenceRows(s39set02),
            "field-39 set 2, 2010, six reps of a three-second cycle: the same two digits",
        )
        // The last rep of each carries its `1` and no call: 0.1.50 is inside
        // #173's withholding, which #243 reversed. That is why the digit count
        // is two rather than one -- the rep with no call kept its count.
        assertEquals(
            listOf(3 to "1", 23 to "1"),
            digits(cadenceRows(s39set01)),
            "field-39 set 1: reps 1 and 6, which is the pair the owner heard",
        )
        assertEquals(listOf(2 to "1", 17 to "1"), digits(cadenceRows(s39set02)), "field-39 set 2: the same pair")
    }

    @Test
    fun `the digits each affected set spoke as shipped, against the reps it was prescribed`() {
        // The whole of #248 as one table: what the lifter was counted through.
        // Read from the archives rather than restated from the issue body.
        val archived = affected.associate { it.fixture to (digits(cadenceRows(it.fixture)).size to it.reps) }
        assertEquals(
            mapOf(
                s38set06 to (2 to 12),
                s38set10 to (2 to 12),
                s38set14 to (2 to 12),
                s39set01 to (2 to 6),
                s39set02 to (2 to 6),
                s39set06 to (2 to 6),
                s39set07 to (6 to 6),
                s39set08 to (2 to 6),
                s39set10 to (2 to 6),
            ),
            archived,
            "digits spoken per set, against reps performed, on the shipped 0.1.50 build",
        )
        // Set 7 is the discriminator the owner's own comment named, and it is
        // why the fault was never "a 2 s stroke": its 1120 has a two-second
        // stroke too and speaks on all six reps, because its closing pause
        // carried the call and the stroke gave up nothing.
        assertEquals(
            1,
            plan(track(s39set07)).beats.count { it.isStroke && it.seconds >= CadencePlan.CALL_MIN_STROKE_S },
            "field-39 set 7 has a two-second stroke as surely as the eight that went quiet",
        )
    }

    @Test
    fun `what a 2011 rep sounds like now, second by second`() {
        // The owner's set, written out rather than derived, so a rule and this
        // list cannot agree by sharing an expression. Rep 1 keeps its stroke
        // word and counts from one; every rep after it opens on its number and
        // the lowering is counted on all six.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Hold", 2 to "Down", 3 to "1",
                4 to "Rep 2", 5 to "Hold", 6 to "Down", 7 to "1",
                8 to "Rep 3", 9 to "Hold", 10 to "Down", 11 to "1",
                12 to "Rep 4", 13 to "Hold", 14 to "Down", 15 to "1",
                16 to "Rep 5", 17 to "Hold", 18 to "Down", 19 to "1",
                20 to "Last rep", 21 to "Hold", 22 to "Down", 23 to "1",
                24 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set01)), 6).map { it.atSecond to it.utterance },
            "field-39 set 1: six `1` counts where the archive has two, and the set is the same length",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1",
                3 to "Rep 2", 4 to "Down", 5 to "1",
                6 to "Rep 3", 7 to "Down", 8 to "1",
                9 to "Rep 4", 10 to "Down", 11 to "1",
                12 to "Rep 5", 13 to "Down", 14 to "1",
                15 to "Last rep", 16 to "Down", 17 to "1",
                18 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set02)), 6).map { it.atSecond to it.utterance },
            "field-39 set 2: the same, on a three-second cycle",
        )
        // And the utterance is one word, so no second is asked to carry two
        // under QUEUE_FLUSH -- the shape that made a merged call cost a count
        // in the first place.
        affected.forEach { track ->
            CadenceVoice.script(plan(track), track.reps).forEach { call ->
                assertEquals(
                    listOf(call.utterance),
                    call.recorded,
                    "${track.fixture}: \"${call.utterance}\" at ${call.atSecond}s is one word, one row",
                )
            }
        }
    }

    @Test
    fun `what the three sets round 1 added sound like, before and after`() {
        // 20X0 is the shape the corpus was missing, and the archive is what it
        // sounded like: a three-second cycle, `1` on rep 1 and rep 6, four
        // silent reps between. The explosive stroke is delivered as a
        // one-second beat, which is why the cycle is three and not two.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1",
                3 to "Up", 4 to "Down", 4 to "Rep 1",
                6 to "Up", 7 to "Down", 7 to "Rep 2",
                9 to "Up", 10 to "Down", 10 to "Rep 3",
                12 to "Up", 13 to "Down", 13 to "Rep 4",
                15 to "Up", 16 to "Down", 17 to "1",
                18 to "Done",
            ),
            cadenceRows(s39set06),
            "field-39 set 6, 20X0, as 0.1.50 recorded it",
        )
        // And now, on all three. Written out rather than derived, so a rule and
        // these lists cannot agree by sharing an expression.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1",
                3 to "Rep 2", 4 to "Down", 5 to "1",
                6 to "Rep 3", 7 to "Down", 8 to "1",
                9 to "Rep 4", 10 to "Down", 11 to "1",
                12 to "Rep 5", 13 to "Down", 14 to "1",
                15 to "Last rep", 16 to "Down", 17 to "1",
                18 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set06)), 6).map { it.atSecond to it.utterance },
            "field-39 set 6, 20X0: the number takes the explosive stroke's word and the lowering counts",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Hold", 2 to "Down", 3 to "1",
                4 to "Rep 2", 5 to "Hold", 6 to "Down", 7 to "1",
                8 to "Rep 3", 9 to "Hold", 10 to "Down", 11 to "1",
                12 to "Rep 4", 13 to "Hold", 14 to "Down", 15 to "1",
                16 to "Rep 5", 17 to "Hold", 18 to "Down", 19 to "1",
                20 to "Last rep", 21 to "Hold", 22 to "Down", 23 to "1",
                24 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set08)), 6).map { it.atSecond to it.utterance },
            "field-39 set 8, 2011 on-stack and inverted: the same rows set 1 gets off-stack and upright",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1",
                3 to "Rep 2", 4 to "Down", 5 to "1",
                6 to "Rep 3", 7 to "Down", 8 to "1",
                9 to "Rep 4", 10 to "Down", 11 to "1",
                12 to "Rep 5", 13 to "Down", 14 to "1",
                15 to "Last rep", 16 to "Down", 17 to "1",
                18 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set10)), 6).map { it.atSecond to it.utterance },
            "field-39 set 10, 20X0 on-stack and inverted: the same rows set 6 gets off-stack",
        )
    }

    @Test
    fun `the digit population of every affected set, before and after`() {
        // A POPULATION rather than a placement: what changes on these nine sets
        // is how many seconds of the set say a number at all. Both columns are
        // computed, neither is restated from the issue.
        assertEquals(
            listOf(2, 2, 2, 2, 2, 2, 6, 2, 2),
            affected.map { digits(cadenceRows(it.fixture)).size },
            "digits per set as recorded by 0.1.50",
        )
        assertEquals(
            listOf(12, 12, 12, 6, 6, 6, 6, 6, 6),
            affected.map { digits(scriptRows(plan(it), it.reps)).size },
            "digits per set as the guide writes them now: one per rep, on every set",
        )
        assertEquals(
            affected.map { it.reps },
            affected.map { digits(scriptRows(plan(it), it.reps)).size },
            "one digit per rep is exactly what `I'd want a number` asked for",
        )
    }

    @Test
    fun `every rep of every set pinned in this file is counted out loud`() {
        // The guard, as a rule over the corpus rather than a row list per set:
        // over the tracks [affected] and [unaffected] name, each first checked
        // to carry a stroke of at least CALL_MIN_STROKE_S seconds, every rep
        // window holds at least one bare digit. This is the assertion that reds
        // if a call ever eats a count again.
        //
        // It is a rule over THOSE tracks and not over every plan a lifter could
        // write. What it covers is five tempos -- 2011, 2010, 20X0, 1120, 3010
        // -- on three geometries, and what it says about any other prescription
        // is nothing.
        (affected + unaffected).forEach { track ->
            val p = plan(track)
            assertTrue(
                p.beats.any { it.isStroke && it.seconds >= CadencePlan.CALL_MIN_STROKE_S },
                "${track.fixture}: the corpus rule only claims to cover plans with a long stroke",
            )
            val spoken = digits(scriptRows(p, track.reps))
            repeat(track.reps) { index ->
                val from = index * p.deliveredCycleS
                val inRep = spoken.filter { it.first >= from && it.first < from + p.deliveredCycleS }
                assertTrue(
                    inRep.isNotEmpty(),
                    "${track.fixture}: rep ${index + 1} of ${track.reps} says no number between $from s and " +
                        "${from + p.deliveredCycleS} s",
                )
            }
        }
        // `1010` is the plan with no long stroke, and the rule deliberately
        // does not reach it. The owner ruled it out of this issue in those
        // words, so nothing here gates it and it still speaks its two stroke
        // words and no number at all.
        val straightReps = CadencePlan.of(TempoSchedule.of(Tempo.parse("1010"), driveUp))
        assertTrue(
            straightReps.beats.none { it.isStroke && it.seconds >= CadencePlan.CALL_MIN_STROKE_S },
            "1010 has no stroke this rule covers",
        )
        assertNull(straightReps.announceOnBeat, "and it announces nothing, which #266 owns and #248 does not")
        assertEquals(
            emptyList(),
            digits(scriptRows(straightReps, 6)),
            "1010 speaks no digit and this file does not ask it to",
        )
    }

    @Test
    fun `the 3010 sets are untouched by this commit`() {
        // #248's fix must not reach the family that was already counted, and
        // the cheapest way to say so is to pin what it says. Both sets are
        // concentric-first, so the three-second lowering is the rep's SECOND
        // stroke and its word is never the one a number replaces.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2",
                4 to "Rep 2", 5 to "Down", 6 to "1", 7 to "2",
                8 to "Rep 3", 9 to "Down", 10 to "1", 11 to "2",
                12 to "Rep 4", 13 to "Down", 14 to "1", 15 to "2",
                16 to "Rep 5", 17 to "Down", 18 to "1", 19 to "2",
                20 to "Last rep", 21 to "Down", 22 to "1", 23 to "2",
                24 to "Done",
            ),
            CadenceVoice.script(plan(track(s39set03)), 6).map { it.atSecond to it.utterance },
            "field-39 set 3, 3010: `1 2` on the lowering of every rep, as it already was",
        )
        assertEquals(
            listOf(16, 12),
            unaffected.map { digits(scriptRows(plan(it), it.reps)).size },
            "field-38 set 4 over eight reps and field-39 set 3 over six: two digits per rep on both",
        )
    }

    @Test
    fun `lowering COUNT_ALOUD_FROM_S is not the fix, because a one-second stroke has no interior second`() {
        // #248's body offers *"lower COUNT_ALOUD_FROM_S and give the 2 s stroke
        // a count at second 1 that the merge does not consume"* as one of two
        // candidate fixes. The first clause of it is inert and this pin is what
        // says so, because a reader who tried it would see nothing change and
        // conclude the count is decided somewhere they had not looked.
        //
        // The last second of a stroke is the next beat's word, so
        // `CadenceVoice.countCall` refuses `second >= beat.seconds` BEFORE it
        // consults the threshold. A one-second stroke has only that second, so
        // it is silent at a threshold of 1 exactly as it is at 2.
        val oneSecondStroke = CadenceBeat(label = "UP", seconds = 1, spokenLabel = "Up", isStroke = true)
        assertNull(
            CadenceVoice.countCall(oneSecondStroke, announcement = null, second = 1),
            "a one-second stroke's only second is the next beat's word, whatever the threshold says",
        )
        assertEquals(2, GuidedCadence.COUNT_ALOUD_FROM_S, "the value the issue names")
        // What the threshold does decide is the two-second stroke, in the other
        // direction: it is the ceiling that would put #248 back. Raising it to
        // 3 silences this call, which is a mutation run rather than an
        // assertion -- the commit body carries the table.
        val twoSecondStroke = CadenceBeat(label = "DOWN", seconds = 2, spokenLabel = "Down", isStroke = true)
        assertNotNull(
            CadenceVoice.countCall(twoSecondStroke, announcement = null, second = 1),
            "and a two-second stroke does have an interior second, which is the count #248 is about",
        )
        // The count is renumbered rather than dropped when the stroke's word is
        // replaced. That is the whole of what #293 changed here, and it is why
        // there is no longer a suppression flag for #248 to defeat.
        assertEquals(
            "2",
            CadenceVoice.countCall(twoSecondStroke, announcement = "Rep 3", second = 1)?.utterance,
            "the number stood where the word stood, so the stroke counts on from it",
        )
    }
}
