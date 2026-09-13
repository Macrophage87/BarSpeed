package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHERE in the rep the guide says the rep number, read off three tracks the
 * shipped app recorded (#293).
 *
 * ## Provenance
 *
 * Two archives, both app 0.1.52, so both were paced by the schedule #243 and
 * #176 left behind: the call names the rep now due, and a merged call writes
 * its own row beside the stroke word it rides.
 *
 * `field-41/48cd3a41-BarSpeed-v0.1.52-2026-09-11_062149-raw.zip`, whose
 * `meta.json` gives `"epoch": "2026-09-11T10:21:49.960Z"`,
 * `"appVersion": "0.1.52"`, `"sensorModel": "WitMotion WT901BLECL"`,
 * `"csvHeaderCues": "timestamp_ms,cue"`, and 21 sets. Its
 * `set01_seated_overhead_press_cues.csv` is committed here as
 * `field-ohp-3010-8rep-s41-set01-cues.csv`, copied byte for byte.
 *
 * `field-42/d498a697-BarSpeed-v0.1.52-2026-09-07_053939-raw_1.zip`, whose
 * `meta.json` gives `"epoch": "2026-09-07T09:39:39.465Z"`,
 * `"appVersion": "0.1.52"`, the same sensor and cue header, and 16 sets. Its
 * `set05_bench_press_cues.csv` and `set13_assisted_pull_up_cues.csv` are
 * committed as `field-bench-3010-6rep-s42-set05-cues.csv` and
 * `field-pullup-4010-8rep-s42-set13-cues.csv`, likewise byte for byte.
 *
 * Each set's geometry below is that set's own `meta.json` row --
 * `tempoPrescribed`, `startsWith`, `concentric`, `plane`, `sensorOnStack`,
 * `sensorInverted`, `plannedReps` -- so the plans reconstructed here are the
 * plans those sets were paced on.
 *
 * | fixture | capture | tempo | starts with | first stroke | where the call rode |
 * |---|---|---|---|---|---|
 * | OHP | field-41 set 1 | 3010 | concentric | `UP`, 1 s | the rep's own last stroke |
 * | bench | field-42 set 5 | 3010 | eccentric | `DOWN`, 3 s | the rep's first stroke |
 * | pull-up | field-42 set 13 | 4010 | concentric | `UP`, 1 s | the rep's own last stroke |
 *
 * Three because they are three different shapes of the same question: a call
 * merged into a one-second opener's PARTNER stroke at two lengths, and a call
 * merged into a three-second opener. The tempo string does not say which --
 * `CadencePlanTest` states that as a rule and these are two `3010` sets that
 * land in different cases on the same four digits.
 *
 * ## Why this file exists
 *
 * The owner, after the field-41 report asked whether the 3010 overhead press
 * really lowered for ~3 s: *"I'm not sure. That could be the case. I think we
 * might need to change the timing. It's hard to follow. Have the rep number be
 * at the start of the rep, and replace the relevant up or down, etc."* And, the
 * same day: *"I'm not often able to look at the reps ring count while
 * exersizing"* -- the voice is the live channel, so where in the rep a word
 * lands is the whole of what the lifter gets.
 *
 * ## What is pinned here, and against what
 *
 * The three archives are FACTS and are asserted as recorded; the script side is
 * what the guide says after #293. The commit before this one asserted the two
 * were equal, which they were: the shipped script reproduced all three tracks
 * row for row, and that is the licence for moving it. Every row the script now
 * says differently is named below, in both directions -- the archive rows it no
 * longer writes and the rows it writes instead.
 */
class RepCallPlacementTest {
    /** field-41 set 1: seated_overhead_press, CONC-first, drive up, vertical, off-stack. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** field-42 set 5: bench_press, ECC-first, drive up, vertical, off-stack. */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-42 set 13: assisted_pull_up, conc-first, drive up, vertical, ON-stack. */
    private val assistedPullUp = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorOnStack = true,
    )

    private val ohp = "field-ohp-3010-8rep-s41-set01"
    private val bench = "field-bench-3010-6rep-s42-set05"
    private val pullUp = "field-pullup-4010-8rep-s42-set13"

    private data class Track(
        val fixture: String,
        val tempo: String,
        val direction: LiftDirection,
        val reps: Int,
    )

    /** The three tracks, with the rep count each set's `meta.json` planned. */
    private val tracks = listOf(
        Track(ohp, "3010", seatedOhp, 8),
        Track(bench, "3010", benchPress, 6),
        Track(pullUp, "4010", assistedPullUp, 8),
    )

    private fun plan(track: Track) = CadencePlan.of(TempoSchedule.of(Tempo.parse(track.tempo), track.direction))

    /** The lead-in's own rows, which the cadence script does not model. */
    private val leadIn = setOf("Ready", "Brace")

    /**
     * A track's rows as (second of the cadence, row), the first movement call
     * being second zero -- the same origin [CadenceVoice.script] counts from.
     *
     * Stated here rather than shared with `MergedCallCueTrackTest` and
     * `ClosingPauseLastRepTest`, which each keep their own copy for the reason
     * the second of those gives: a helper shared between files scoring
     * different sessions lets a change to one silently rescore the others.
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

    @Test
    fun `the call opens the rep on all three plans, whichever stroke the tempo puts first`() {
        // The beats do not move -- they are the prescription's, and
        // `CadencePlanTest` pins that against every tempo any plan can express.
        // What moves is WHERE the call rides: beat 0, the rep's first stroke,
        // on all three. Two of the three used to ride the rep's own LAST
        // stroke, which is a second into the rep on the overhead press and a
        // second into the pull-up.
        val ohpPlan = plan(tracks[0])
        assertEquals(listOf("UP" to 1, "DOWN" to 3), ohpPlan.beats.map { it.label to it.seconds }, "field-41 set 1")
        assertEquals(0, ohpPlan.announceOnBeat, "the call opens the rep, where it rode beat 1 before")

        val benchPlan = plan(tracks[1])
        assertEquals(listOf("DOWN" to 3, "UP" to 1), benchPlan.beats.map { it.label to it.seconds }, "field-42 set 5")
        assertEquals(0, benchPlan.announceOnBeat, "the same four digits, and the same beat as before")

        val pullUpPlan = plan(tracks[2])
        assertEquals(listOf("UP" to 1, "DOWN" to 4), pullUpPlan.beats.map { it.label to it.seconds }, "field-42 set 13")
        assertEquals(0, pullUpPlan.announceOnBeat, "likewise, and the four-second lowering keeps its own word")
        // The whole rep is ahead of the lifter when they hear its number, on
        // every one of the three. That is the point of #293 stated as a number.
        listOf(ohpPlan, benchPlan, pullUpPlan).forEach { p ->
            assertEquals(
                p.repCompleteAfterBeat + 1,
                p.beatsOfRepLeftWhenAnnounced,
                "${p.beats.map { it.label to it.seconds }}: every beat of the named rep is still to come",
            )
        }
    }

    @Test
    fun `these three tracks recorded exactly these rows`() {
        // Facts about three files. Nothing in this repository can change them,
        // and every expectation below is read against them.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2",
                4 to "Up", 5 to "Down", 5 to "Rep 2", 7 to "2",
                8 to "Up", 9 to "Down", 9 to "Rep 3", 11 to "2",
                12 to "Up", 13 to "Down", 13 to "Rep 4", 15 to "2",
                16 to "Up", 17 to "Down", 17 to "Rep 5", 19 to "2",
                20 to "Up", 21 to "Down", 21 to "Rep 6", 23 to "2",
                24 to "Up", 25 to "Down", 25 to "Rep 7", 27 to "2",
                28 to "Up", 29 to "Down", 29 to "Last rep", 31 to "2",
                32 to "Done",
            ),
            cadenceRows(ohp),
            "field-41 set 1, 3010 concentric-first, eight reps of a four-second cycle",
        )
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "2", 3 to "Up",
                4 to "Down", 4 to "Rep 2", 6 to "2", 7 to "Up",
                8 to "Down", 8 to "Rep 3", 10 to "2", 11 to "Up",
                12 to "Down", 12 to "Rep 4", 14 to "2", 15 to "Up",
                16 to "Down", 16 to "Rep 5", 18 to "2", 19 to "Up",
                20 to "Down", 20 to "Last rep", 22 to "2", 23 to "Up",
                24 to "Done",
            ),
            cadenceRows(bench),
            "field-42 set 5, 3010 ECCENTRIC-first, six reps of a four-second cycle",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2", 4 to "3",
                5 to "Up", 6 to "Down", 6 to "Rep 2", 8 to "2", 9 to "3",
                10 to "Up", 11 to "Down", 11 to "Rep 3", 13 to "2", 14 to "3",
                15 to "Up", 16 to "Down", 16 to "Rep 4", 18 to "2", 19 to "3",
                20 to "Up", 21 to "Down", 21 to "Rep 5", 23 to "2", 24 to "3",
                25 to "Up", 26 to "Down", 26 to "Rep 6", 28 to "2", 29 to "3",
                30 to "Up", 31 to "Down", 31 to "Rep 7", 33 to "2", 34 to "3",
                35 to "Up", 36 to "Down", 36 to "Last rep", 38 to "2", 39 to "3",
                40 to "Done",
            ),
            cadenceRows(pullUp),
            "field-42 set 13, 4010 concentric-first, eight reps of a five-second cycle",
        )
    }

    @Test
    fun `the guide now writes these rows over the same three plans`() {
        // Every row of the new script, at the second it lands on, against the
        // archives pinned above. Written out rather than derived, so a rule and
        // this table cannot agree by sharing an expression.
        //
        // Rep 1 is untouched on all three: it keeps its stroke word, its own
        // counts and its place. Every rep after it opens on its number.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2",
                4 to "Rep 2", 5 to "Down", 6 to "1", 7 to "2",
                8 to "Rep 3", 9 to "Down", 10 to "1", 11 to "2",
                12 to "Rep 4", 13 to "Down", 14 to "1", 15 to "2",
                16 to "Rep 5", 17 to "Down", 18 to "1", 19 to "2",
                20 to "Rep 6", 21 to "Down", 22 to "1", 23 to "2",
                24 to "Rep 7", 25 to "Down", 26 to "1", 27 to "2",
                28 to "Last rep", 29 to "Down", 30 to "1", 31 to "2",
                32 to "Done",
            ),
            scriptRows(plan(tracks[0]), 8),
            "field-41 set 1: the number takes the `Up` slot and the lowering is counted on every rep",
        )
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "2", 3 to "Up",
                4 to "Rep 2", 5 to "2", 6 to "3", 7 to "Up",
                8 to "Rep 3", 9 to "2", 10 to "3", 11 to "Up",
                12 to "Rep 4", 13 to "2", 14 to "3", 15 to "Up",
                16 to "Rep 5", 17 to "2", 18 to "3", 19 to "Up",
                20 to "Last rep", 21 to "2", 22 to "3", 23 to "Up",
                24 to "Done",
            ),
            scriptRows(plan(tracks[1]), 6),
            "field-42 set 5: the owner's own example -- `Rep 3, 2, 3, Up`",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2", 4 to "3",
                5 to "Rep 2", 6 to "Down", 7 to "1", 8 to "2", 9 to "3",
                10 to "Rep 3", 11 to "Down", 12 to "1", 13 to "2", 14 to "3",
                15 to "Rep 4", 16 to "Down", 17 to "1", 18 to "2", 19 to "3",
                20 to "Rep 5", 21 to "Down", 22 to "1", 23 to "2", 24 to "3",
                25 to "Rep 6", 26 to "Down", 27 to "1", 28 to "2", 29 to "3",
                30 to "Rep 7", 31 to "Down", 32 to "1", 33 to "2", 34 to "3",
                35 to "Last rep", 36 to "Down", 37 to "1", 38 to "2", 39 to "3",
                40 to "Done",
            ),
            scriptRows(plan(tracks[2]), 8),
            "field-42 set 13: a four-second lowering counted `1 2 3` on every rep",
        )
        // And no set gets longer or shorter for it: `Done` still lands at reps
        // x the delivered cycle, which is the obligation `CadencePlanTest`
        // states as a rule over every tempo.
        tracks.forEach { track ->
            val p = plan(track)
            assertEquals(
                track.reps * p.deliveredCycleS to CadenceVoice.DONE,
                scriptRows(p, track.reps).last(),
                "${track.fixture}: the set is as long as its prescription",
            )
        }
    }

    @Test
    fun `the rows that vanish from each archive, and the rows that arrive`() {
        // The difference in both directions, per set, so neither half can be
        // read off a single list. What LEAVES is one stroke word per rep after
        // the first -- the word the number replaces -- and, on the two
        // one-second openers, the merged call row at its old second. What
        // ARRIVES is the number at the rep's own first second, and the tempo
        // count the carrying stroke used to give up.
        tracks.forEach { track ->
            val archived = cadenceRows(track.fixture)
            val scripted = scriptRows(plan(track), track.reps)
            assertEquals(
                archived.size,
                scripted.size,
                "${track.fixture}: a word is replaced and a count restored, so the row COUNT does not move",
            )
        }
        // field-41 set 1: seven `Up` rows and the eighth rep's go, with the
        // eight merged calls that rode `Down`; the numbers arrive four seconds
        // earlier each, and seven `1` counts arrive on the lowering.
        assertEquals(
            listOf(
                4 to "Up", 5 to "Rep 2", 8 to "Up", 9 to "Rep 3", 12 to "Up", 13 to "Rep 4", 16 to "Up",
                17 to "Rep 5", 20 to "Up", 21 to "Rep 6", 24 to "Up", 25 to "Rep 7", 28 to "Up",
                29 to "Last rep",
            ),
            cadenceRows(ohp) - scriptRows(plan(tracks[0]), 8).toSet(),
            "field-41 set 1: rows the archive has and the guide no longer writes",
        )
        assertEquals(
            listOf(
                4 to "Rep 2", 6 to "1", 8 to "Rep 3", 10 to "1", 12 to "Rep 4", 14 to "1", 16 to "Rep 5",
                18 to "1", 20 to "Rep 6", 22 to "1", 24 to "Rep 7", 26 to "1", 28 to "Last rep", 30 to "1",
            ),
            scriptRows(plan(tracks[0]), 8) - cadenceRows(ohp).toSet(),
            "field-41 set 1: rows the guide writes that the archive does not have",
        )
        // field-42 set 5: the eccentric-first bench press, where the call was
        // already on the rep's first second. Five `Down` rows leave, and the
        // counts on that stroke move up by one.
        assertEquals(
            listOf(
                4 to "Down", 6 to "2", 8 to "Down", 10 to "2", 12 to "Down",
                14 to "2", 16 to "Down", 18 to "2", 20 to "Down", 22 to "2",
            ),
            cadenceRows(bench) - scriptRows(plan(tracks[1]), 6).toSet(),
            "field-42 set 5: the stroke word the number replaces, and the one count that stroke used to speak",
        )
        assertEquals(
            listOf(
                5 to "2", 6 to "3", 9 to "2", 10 to "3", 13 to "2",
                14 to "3", 17 to "2", 18 to "3", 21 to "2", 22 to "3",
            ),
            scriptRows(plan(tracks[1]), 6) - cadenceRows(bench).toSet(),
            "field-42 set 5: that stroke counted from the number -- two counts where the archive has one",
        )
    }

    @Test
    fun `the whole vocabulary of each track, so a row appearing or vanishing cannot be missed`() {
        // Counted rather than listed, because what #293 changes is a
        // POPULATION: one word per rep stops being written and one tempo count
        // per rep starts. A row list can be read as a placement change; these
        // numbers cannot.
        assertEquals(
            mapOf("Up" to 8, "Down" to 8, "1" to 1, "2" to 8, "Rep 2" to 1, "Rep 3" to 1, "Rep 4" to 1) +
                mapOf("Rep 5" to 1, "Rep 6" to 1, "Rep 7" to 1, "Last rep" to 1, "Done" to 1),
            cadenceRows(ohp).groupingBy { it.second }.eachCount(),
            "field-41 set 1: one `1` in the whole set, on rep 1, and the lowering is silent after it",
        )
        assertEquals(
            mapOf("Down" to 6, "1" to 1, "2" to 6, "Up" to 6, "Rep 2" to 1, "Rep 3" to 1) +
                mapOf("Rep 4" to 1, "Rep 5" to 1, "Last rep" to 1, "Done" to 1),
            cadenceRows(bench).groupingBy { it.second }.eachCount(),
            "field-42 set 5: the same one `1`, and six `Down` rows",
        )
        assertEquals(
            mapOf("Up" to 8, "Down" to 8, "1" to 1, "2" to 8, "3" to 8, "Rep 2" to 1, "Rep 3" to 1) +
                mapOf("Rep 4" to 1, "Rep 5" to 1, "Rep 6" to 1, "Rep 7" to 1, "Last rep" to 1, "Done" to 1),
            cadenceRows(pullUp).groupingBy { it.second }.eachCount(),
            "field-42 set 13: a four-second lowering counted `2 3` after its word, never `1 2 3`",
        )
    }

    @Test
    fun `the population each track would have if it were recorded again`() {
        // The same three vocabularies, from the script rather than the archive.
        // The stroke word the number replaces drops to ONE row per set -- rep
        // 1's, which keeps it -- and the count that stroke used to give up is
        // spoken on every rep.
        assertEquals(
            mapOf("Up" to 1, "Down" to 8, "1" to 8, "2" to 8, "Rep 2" to 1, "Rep 3" to 1, "Rep 4" to 1) +
                mapOf("Rep 5" to 1, "Rep 6" to 1, "Rep 7" to 1, "Last rep" to 1, "Done" to 1),
            scriptRows(plan(tracks[0]), 8).groupingBy { it.second }.eachCount(),
            "field-41 set 1: one `Up` row in the set, and eight `1` counts where the archive has one",
        )
        assertEquals(
            mapOf("Down" to 1, "1" to 1, "2" to 6, "3" to 5, "Up" to 6, "Rep 2" to 1, "Rep 3" to 1) +
                mapOf("Rep 4" to 1, "Rep 5" to 1, "Last rep" to 1, "Done" to 1),
            scriptRows(plan(tracks[1]), 6).groupingBy { it.second }.eachCount(),
            "field-42 set 5: one `Down` row, and a `3` the archive never carried",
        )
        assertEquals(
            mapOf("Up" to 1, "Down" to 8, "1" to 8, "2" to 8, "3" to 8, "Rep 2" to 1, "Rep 3" to 1) +
                mapOf("Rep 4" to 1, "Rep 5" to 1, "Rep 6" to 1, "Rep 7" to 1, "Last rep" to 1, "Done" to 1),
            scriptRows(plan(tracks[2]), 8).groupingBy { it.second }.eachCount(),
            "field-42 set 13: the four-second lowering counted `1 2 3` on all eight reps",
        )
        // What this costs a consumer counting rep starts off stroke words, and
        // it is the reason #293 carries an export entry: `CueTrack.calledReps`
        // counts `Down` rows, so on the eccentric-first bench press it would
        // read ONE rep for a set of six. The rep is still datable -- the call
        // rows land on the rep's first second, one per rep after the first --
        // but not by that rule.
        assertEquals(
            1,
            scriptRows(plan(tracks[1]), 6).count { it.second == "Down" },
            "field-42 set 5: `Down` rows in a six-rep set, which used to be six",
        )
        assertEquals(
            6,
            cadenceRows(bench).count { it.second == "Down" },
            "and what the archive of that same set carries",
        )
    }

    @Test
    fun `what the lifter hears, second by second, on the 3010 bench press`() {
        // The utterances rather than the rows, because the utterance is where
        // the owner's sentence is readable: one second, one word, where the
        // archive carried two.
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "2", 3 to "Up",
                4 to "Rep 2", 5 to "2", 6 to "3", 7 to "Up",
                8 to "Rep 3", 9 to "2", 10 to "3", 11 to "Up",
                12 to "Rep 4", 13 to "2", 14 to "3", 15 to "Up",
                16 to "Rep 5", 17 to "2", 18 to "3", 19 to "Up",
                20 to "Last rep", 21 to "2", 22 to "3", 23 to "Up",
                24 to "Done",
            ),
            CadenceVoice.script(plan(tracks[1]), 6).map { it.atSecond to it.utterance },
            "field-42 set 5: the owner's `Rep 3, 2, 3, Up` against the archive's `Down, Rep 3` then `2`",
        )
        // One second, one utterance, on all three plans. A merged call put two
        // words in one second -- "Down, Rep 3" -- and TTS runs with
        // QUEUE_FLUSH, so the second word is what the lifter hears the start
        // of. Nothing the guide says carries two words now.
        tracks.forEach { track ->
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
    fun `the rep number opens the rep it names, on every rep after the first`() {
        // The owner's "at the start of the rep", as a rule over all three
        // rather than three row lists: every call lands on the FIRST second of
        // the rep it names, and there is exactly one per rep after the first.
        // Two of the three used to arrive a second later, on the lowering, with
        // the drive already over.
        tracks.forEach { track ->
            val p = plan(track)
            val calls = scriptRows(p, track.reps)
                .filter { it.second == CadencePlan.LAST_REP || it.second.startsWith(CadencePlan.REP_CALL_PREFIX) }
            assertEquals(track.reps - 1, calls.size, "${track.fixture}: one call per rep after the first")
            calls.forEachIndexed { index, (second, row) ->
                assertEquals(
                    (index + 1) * p.deliveredCycleS,
                    second,
                    "${track.fixture}: $row opens the rep it names",
                )
            }
            // Rep 1 says nothing about itself and keeps the word the prep
            // countdown showed it (#241). The first thing the lifter hears
            // after the countdown is still the stroke.
            assertEquals(
                p.beats[0].spokenLabel,
                scriptRows(p, track.reps).first().second,
                "${track.fixture}: rep 1 opens on its stroke word",
            )
        }
        // Where the archive put the same calls, so the move is a measurement
        // rather than a claim: a second later on the two one-second openers,
        // and on the same second on the eccentric-first bench press.
        listOf(ohp to 4, pullUp to 5, bench to 4).forEach { (fixture, cycle) ->
            val firstCall = cadenceRows(fixture).first { it.second.startsWith(CadencePlan.REP_CALL_PREFIX) }
            val expected = if (fixture == bench) cycle else cycle + 1
            assertEquals(expected, firstCall.first, "$fixture: where 0.1.52 spoke the call for rep 2")
        }
    }
}
