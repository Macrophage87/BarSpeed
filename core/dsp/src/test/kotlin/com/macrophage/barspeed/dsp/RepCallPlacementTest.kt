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
 * CHARACTERIZATION at this commit: every expectation below is what the shipped
 * guide says, and the three archives are what it said. Nothing here is a
 * proposal.
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
    fun `the plans these three sets were paced on, which two tempo strings do not say`() {
        val ohpPlan = plan(tracks[0])
        assertEquals(listOf("UP" to 1, "DOWN" to 3), ohpPlan.beats.map { it.label to it.seconds }, "field-41 set 1")
        assertEquals(1, ohpPlan.announceOnBeat, "the call rides the rep's own last stroke")

        val benchPlan = plan(tracks[1])
        assertEquals(listOf("DOWN" to 3, "UP" to 1), benchPlan.beats.map { it.label to it.seconds }, "field-42 set 5")
        assertEquals(0, benchPlan.announceOnBeat, "the same four digits, and the call rides the rep's first stroke")

        val pullUpPlan = plan(tracks[2])
        assertEquals(listOf("UP" to 1, "DOWN" to 4), pullUpPlan.beats.map { it.label to it.seconds }, "field-42 set 13")
        assertEquals(1, pullUpPlan.announceOnBeat, "a four-second lowering, carrying the call")
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
    fun `the shipped guide scripts all three tracks row for row`() {
        // The licence for changing the script at all: it reproduces what the
        // app said, on real sets, before anything here moves. These three have
        // no closing pause, so #265 -- the last rep's restored pause -- does
        // not separate the archives from the current script either.
        tracks.forEach { track ->
            assertEquals(
                cadenceRows(track.fixture),
                scriptRows(plan(track), track.reps),
                "${track.fixture}: ${track.tempo}, ${track.reps} reps",
            )
        }
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
    fun `what the lifter hears, second by second, on the 3010 bench press`() {
        // The utterances rather than the rows, which is the only place the
        // merge is visible: one second carries two words, spoken as one
        // utterance because TTS runs with QUEUE_FLUSH.
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "2", 3 to "Up",
                4 to "Down, Rep 2", 6 to "2", 7 to "Up",
                8 to "Down, Rep 3", 10 to "2", 11 to "Up",
                12 to "Down, Rep 4", 14 to "2", 15 to "Up",
                16 to "Down, Rep 5", 18 to "2", 19 to "Up",
                20 to "Down, Last rep", 22 to "2", 23 to "Up",
                24 to "Done",
            ),
            CadenceVoice.script(plan(tracks[1]), 6).map { it.atSecond to it.utterance },
            "field-42 set 5: the rep number rides the stroke word and the stroke's first count is given up",
        )
    }

    @Test
    fun `the rep number arrives one stroke into the rep on the two one-second openers`() {
        // What the owner's "at the start of the rep" is measured against. On
        // the two concentric-first sets the call lands on the rep's SECOND
        // stroke -- a second into a four-second rep on the overhead press, a
        // second into a five-second rep on the pull-up -- and the stroke it
        // rides is the lowering, so the drive is already over when the number
        // arrives.
        listOf(Track(ohp, "3010", seatedOhp, 8), Track(pullUp, "4010", assistedPullUp, 8)).forEach { track ->
            val p = plan(track)
            val cycle = p.deliveredCycleS
            val calls = scriptRows(p, track.reps)
                .filter { it.second == CadencePlan.LAST_REP || it.second.startsWith(CadencePlan.REP_CALL_PREFIX) }
            assertEquals(track.reps - 1, calls.size, "${track.fixture}: one call per rep after the first")
            calls.forEachIndexed { index, (second, row) ->
                val repStarts = (index + 1) * cycle
                assertEquals(repStarts + 1, second, "${track.fixture}: $row lands a second into its own rep")
            }
        }
        // And on the eccentric-first bench press it already lands on the
        // rep's first second, beside the stroke word rather than in place of
        // it. Same four digits as the overhead press.
        val benchPlan = plan(tracks[1])
        assertEquals(
            listOf(4, 8, 12, 16, 20),
            scriptRows(benchPlan, 6)
                .filter { it.second == CadencePlan.LAST_REP || it.second.startsWith(CadencePlan.REP_CALL_PREFIX) }
                .map { it.first },
            "field-42 set 5: every call on the first second of the rep it names",
        )
        assertEquals(2, benchPlan.beatsOfRepLeftWhenAnnounced, "and both beats of that rep are still to come")
    }
}
