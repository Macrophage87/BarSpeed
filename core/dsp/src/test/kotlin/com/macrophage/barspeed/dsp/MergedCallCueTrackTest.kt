package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Three metronome cue tracks from session 33, and what they show about the
 * calls the app SPOKE and never wrote down.
 *
 * ## Provenance
 *
 * `field-33/9e94ec9f-BarSpeedv0.1.4320260828_160041raw.zip`, whose `meta.json`
 * gives `"epoch": "2026-08-28T20:00:41.968Z"`, `"appVersion": "0.1.43"`,
 * `"csvHeaderCues": "timestamp_ms,cue"`, and sixteen sets. The three files here
 * are that archive's `set01_dumbbell_incline_press_cues.csv`,
 * `set05_seated_overhead_press_cues.csv` and `set13_triceps_pushdown_cues.csv`,
 * copied byte for byte. Each set's declared geometry below is read from the
 * same `meta.json` row -- `tempoPrescribed`, `startsWith`, `concentric`,
 * `plane`, `sensorOnStack` -- so the plans reconstructed here are the plans
 * those sets were actually paced on, not plausible ones.
 *
 * Three sets and not one because they are three DIFFERENT cases of the same
 * schedule, and the tempo string does not say which:
 *
 * | set | tempo | starts with | plan case | last stroke word to `Done` |
 * |---|---|---|---|---|
 * | 1 | 3010 | eccentric | merged into the NEXT rep's first stroke | 1.001 s |
 * | 5 | 3010 | concentric | merged into the rep's OWN last stroke | 3.002 s |
 * | 13 | 1120 | concentric | merged into the rep's OWN last stroke | 2.001 s |
 *
 * ## A correction to the measurement filed on issue #173
 *
 * That comment tabulates all five 3010 sets of this session at 1.00 s with no
 * silent slot, and reads the family as safe. Re-measured here from the same
 * archive: sets 1-3 are eccentric-first and do read 1.001 s, but set 5 is
 * CONCENTRIC-first, reads 3.002 s, and carries a silent slot exactly as the
 * eleven do. Set 4 is the same pair and cannot be measured at all because it
 * says no `Done` (issue #141, firing in the field). The 1.00 s figure for set 5
 * is the last cue ROW to `Done` -- its final `2` -- and not the last stroke
 * word.
 *
 * So "3010" names two different plans here, and only one of them is the safe
 * one. Which case a set is in belongs to the (tempo, lift) PAIR, exactly as
 * `CadencePlanTest` says of every other outcome in this file's subject.
 *
 * ## What a silent slot is, and what it is not
 *
 * The same comment reads the silent second as the unrecorded call itself. The
 * track refutes that in its own rows: on set 13 rep ONE carries a `1` at the
 * second second of its `Up` stroke and no later rep does. Nothing suppresses
 * that count except an announcement riding the same stroke
 * (`CadenceVoice.countCall` drops a stroke's first count only when an
 * announcement is non-null), and rep 1 has none pending yet. So the silence is the
 * GIVEN-UP TEMPO COUNT, and the call rode the stroke word one second earlier --
 * `"Up, Last rep"` as a single utterance, recorded as a bare `Up`.
 *
 * That leaves the conclusion of the measurement standing and sharpens it: the
 * missing counts are the fingerprint of the merged calls, so this track dates
 * and counts eleven spoken calls it does not name (issue #176), and the last of
 * them is a `"Last rep"` with nothing after it but the rep's final stroke
 * (issue #173).
 */
class MergedCallCueTrackTest {
    /** meta.json set 1: dumbbell_incline_press, ecc-first, drive up, vertical, off-stack. */
    private val inclinePress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** meta.json set 5: seated_overhead_press, CONC-first, drive up, vertical, off-stack. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** meta.json set 13: triceps_pushdown, conc-first, drive DOWN, vertical, on-stack. */
    private val pushdown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorOnStack = true,
    )

    private val set01 = "field-inclinepress-3010-10rep-s33-set01"
    private val set05 = "field-ohp-3010-8rep-s33-set05"
    private val set13 = "field-pushdown-1120-12rep-s33-set13"

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    /** Seconds from the last movement-stroke word of the track to its `Done`. */
    private fun lastStrokeToDoneS(fixture: String): Double {
        val rows = CueTrack.read(fixture)
        val done = rows.first { it.label == "Done" }
        val lastStroke = rows.last { it.label in setOf("Down", "Up", "Drive", "Return") }
        return (done.timestampMs - lastStroke.timestampMs) / 1000.0
    }

    private fun counts(fixture: String) = CueTrack.read(fixture).groupingBy { it.label }.eachCount()

    /** The lead-in's own rows, which the cadence script does not model. */
    private val leadIn = setOf("Ready", "Brace")

    /**
     * A track's rows as (second of the cadence, row), the first movement call
     * being second zero -- the same origin [CadenceVoice.script] counts from.
     *
     * The device speaks on wall-clock `delay(1_000)`, so a beat drifts by a
     * millisecond or two and forty beats accumulate tens; the drift is asserted
     * to stay small enough that rounding to the nearest second is exact.
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

    /** True when every element of [inner] appears in [outer], in order. */
    private fun <T> isSubsequence(inner: List<T>, outer: List<T>): Boolean {
        var i = 0
        for (e in outer) if (i < inner.size && inner[i] == e) i++
        return i == inner.size
    }

    /** The elements of [outer] left over once [inner] has been matched into it. */
    private fun <T> surplus(inner: List<T>, outer: List<T>): List<T> {
        var i = 0
        return outer.filter { e ->
            if (i < inner.size && inner[i] == e) {
                i++
                false
            } else {
                true
            }
        }
    }

    /** The cue rows the plan says a set of [reps] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, reps: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, reps).flatMap { call -> call.recorded.map { call.atSecond to it } }

    @Test
    fun `the tempo string does not say which plan case a set was paced on`() {
        // The whole of the correction above, as an assertion. Same four digits,
        // two lifts, two different homes for the rep call -- and the home is
        // what decides whether the call still has a rep in front of it.
        val eccFirst = plan("3010", inclinePress)
        assertEquals(listOf("DOWN" to 3, "UP" to 1), eccFirst.beats.map { it.label to it.seconds }, "set 1")
        assertEquals(0, eccFirst.announceOnBeat, "set 1 merges the call into the NEXT rep's opening stroke")
        assertEquals(1, eccFirst.repCompleteAfterBeat, "which is not the beat the rep completes after")

        val concFirst = plan("3010", seatedOhp)
        assertEquals(listOf("UP" to 1, "DOWN" to 3), concFirst.beats.map { it.label to it.seconds }, "set 5")
        assertEquals(1, concFirst.announceOnBeat, "set 5 merges it into the rep's own second stroke")
        assertEquals(1, concFirst.repCompleteAfterBeat, "which IS the beat the rep completes after")

        val fourSecond = plan("1120", pushdown)
        assertEquals(
            listOf("DOWN" to 1, CadencePlan.HOLD to 1, "UP" to 2),
            fourSecond.beats.map { it.label to it.seconds },
            "set 13",
        )
        assertEquals(2, fourSecond.announceOnBeat, "set 13 likewise")
        assertEquals(2, fourSecond.repCompleteAfterBeat)
        listOf(eccFirst, concFirst, fourSecond).forEach { assertEquals(true, it.announceMerged) }
    }

    @Test
    fun `the last stroke word to Done, measured on the archive rather than tabulated`() {
        // The figures the issue #173 comment reports, re-derived, with set 5
        // reading three seconds rather than the one its tempo string implied.
        assertEquals(1.001, lastStrokeToDoneS(set01), 0.05, "set 1, 3010 eccentric-first")
        assertEquals(3.002, lastStrokeToDoneS(set05), 0.05, "set 5, 3010 CONCENTRIC-first")
        assertEquals(2.001, lastStrokeToDoneS(set13), 0.05, "set 13, 1120")
    }

    @Test
    fun `no track names a rep, on a session where the lifter heard a rep call on every rep`() {
        // Issue #176. The vocabulary of all three tracks, whole, so that a row
        // appearing later cannot be missed: the guide's own announcement --
        // "Rep 4", "Last rep" -- is in none of them.
        assertEquals(
            mapOf("Ready" to 1, "Brace" to 1, "Down" to 10, "1" to 1, "2" to 10, "Up" to 10, "Done" to 1),
            counts(set01),
            "set 1, ten reps",
        )
        assertEquals(
            mapOf("Ready" to 1, "Brace" to 1, "Up" to 8, "Down" to 8, "1" to 1, "2" to 8, "Done" to 1),
            counts(set05),
            "set 5, eight reps",
        )
        assertEquals(
            mapOf("Ready" to 1, "Brace" to 1, "Down" to 12, "Hold" to 12, "Up" to 12, "1" to 1, "Done" to 1),
            counts(set13),
            "set 13, twelve reps",
        )
        listOf(set01, set05, set13).forEach { fixture ->
            assertTrue(
                CueTrack.read(fixture).none { it.label == "Last rep" || it.label.matches(Regex("Rep \\d+")) },
                "$fixture names a rep call, which no 0.1.43 track does",
            )
        }
    }

    @Test
    fun `the given-up tempo counts date and count the calls the track does not name`() {
        // The evidence that the silence is the suppressed COUNT and not the
        // call: the count is present on rep 1, which has no announcement
        // pending, and absent on every rep after it. One missing count per
        // merged call, so the count of calls is readable off a track that names
        // none of them.
        listOf(
            Triple(set01, 10, "Down"),
            Triple(set05, 8, "Down"),
            Triple(set13, 12, "Up"),
        ).forEach { (fixture, reps, carrier) ->
            val rows = CueTrack.read(fixture)
            val carriers = rows.filter { it.label == carrier }.map { it.timestampMs }
            assertEquals(reps, carriers.size, "$fixture: the stroke carrying the call, once per rep")
            assertEquals(1, rows.count { it.label == "1" }, "$fixture: the count it gives up, spoken on rep 1 only")
            assertEquals(
                1.001,
                (rows.first { it.label == "1" }.timestampMs - carriers.first()) / 1000.0,
                0.05,
                "$fixture: and it is rep 1's, one second into that stroke",
            )
            // Rep 2 onward: nothing at all is written a second into that
            // stroke, and one merged call was spoken there for each.
            val silent = carriers.drop(1).filter { c -> rows.none { it.timestampMs in (c + 500)..(c + 1_500) } }
            assertEquals(reps - 1, silent.size, "$fixture: calls spoken and written nowhere")
        }
    }

    @Test
    fun `the model of the guide accounts for every row three real sets recorded`() {
        // What holds CadenceVoice.script to the truth. The script is a model of
        // a loop in :app that no test can run; these are three tracks that loop
        // actually produced, on a phone, in a gym. Every row it wrote appears in
        // the script at the same second of the cadence, in the same order.
        //
        // INCLUSION rather than equality, deliberately: what issue 176 changes
        // is that the script starts accounting for rows the shipped app spoke
        // and did not write, so the script may exceed a 0.1.43 track and may
        // never contradict one. Which rows it adds is asserted where they are
        // added, not here.
        listOf(
            Triple(set01, plan("3010", inclinePress), 10),
            Triple(set05, plan("3010", seatedOhp), 8),
            Triple(set13, plan("1120", pushdown), 12),
        ).forEach { (fixture, p, reps) ->
            val recorded = cadenceRows(fixture)
            val script = scriptRows(p, reps)
            assertTrue(
                isSubsequence(recorded, script),
                "$fixture: the recording is not accounted for by the plan.\nrecorded: $recorded\nscript:   $script",
            )
            val (lastSecond, lastRow) = recorded.last()
            assertEquals(CadenceVoice.DONE, lastRow, "$fixture: the last row of a completed set")
            assertEquals(
                reps * p.deliveredCycleS,
                lastSecond,
                "$fixture: and its Done lands where the prescription says it does",
            )
        }
    }

    @Test
    fun `the rows the fix adds to set 1 are the nine calls it spoke and never wrote`() {
        // Issue 176 against a real track rather than a constructed plan: the
        // ten-rep incline press said a rep call at the start of every rep after
        // the first, and its cue track names none of them. These are the
        // instants, on the cadence clock, that the archive is missing.
        //
        // Set 1 is eccentric-first, so its whole set of calls is unaffected by
        // issue 173 and can be asserted here in full.
        val recorded = cadenceRows(set01)
        assertEquals(
            listOf(
                4 to "Rep 1",
                8 to "Rep 2",
                12 to "Rep 3",
                16 to "Rep 4",
                20 to "Rep 5",
                24 to "Rep 6",
                28 to "Rep 7",
                32 to "Rep 8",
                36 to "Last rep",
            ),
            surplus(recorded, scriptRows(plan("3010", inclinePress), 10)),
            "the calls set 1 spoke and did not write down",
        )
    }

    @Test
    fun `the rows the fix adds to set 13 ride the stroke word that silenced a count`() {
        // The same on the 1120 pushdown, whose call rides the LAST stroke of
        // the rep it announces. Restricted to the reps before the final one:
        // what the final rep's call should be is issue 173's question, asserted
        // with it, and this assertion must not answer it in passing.
        //
        // Each added row lands two seconds into a four-second rep, which is the
        // "Up" the track does record -- and one second before the slot the
        // track leaves silent, which is the count given up to make room.
        val recorded = cadenceRows(set13)
        val finalRepStarts = 11 * plan("1120", pushdown).deliveredCycleS
        assertEquals(
            (1..10).map { (it * 4 + 2) to "Rep $it" },
            surplus(recorded, scriptRows(plan("1120", pushdown), 12)).filter { it.first < finalRepStarts },
            "the calls set 13 spoke and did not write down, before its last rep",
        )
    }

    @Test
    fun `what issue 173 changes is readable in these tracks, as a warning gone and a count returned`() {
        // Issue 176 makes issue 173's fix OBSERVABLE, which is the whole
        // reason the two travel together: before it, a suppressed warning and
        // a spoken one wrote the same cue track and no session could tell
        // them apart.
        //
        // Each row here is (the last rep's window in the 0.1.43 archive, the
        // same window as the guide now scripts it). Two things change and both
        // are rows: the `Last rep` the app spoke at the start of the final
        // stroke is not spoken at all, and the tempo count that stroke had
        // given up to make room for it comes back -- landing on the exact
        // second the recorded track leaves silent.
        listOf(
            Triple(
                Triple(set13, plan("1120", pushdown), 12),
                listOf(44 to "Down", 45 to "Hold", 46 to "Up", 48 to CadenceVoice.DONE),
                listOf(44 to "Down", 45 to "Hold", 46 to "Up", 47 to "1", 48 to CadenceVoice.DONE),
            ),
            Triple(
                Triple(set05, plan("3010", seatedOhp), 8),
                listOf(28 to "Up", 29 to "Down", 31 to "2", 32 to CadenceVoice.DONE),
                listOf(28 to "Up", 29 to "Down", 30 to "1", 31 to "2", 32 to CadenceVoice.DONE),
            ),
        ).forEach { (input, archived, scripted) ->
            val (fixture, p, reps) = input
            val finalRepStarts = (reps - 1) * p.deliveredCycleS
            assertEquals(archived, cadenceRows(fixture).filter { it.first >= finalRepStarts }, "$fixture, recorded")
            assertEquals(scripted, scriptRows(p, reps).filter { it.first >= finalRepStarts }, "$fixture, scripted")
            assertTrue(
                scriptRows(p, reps).none { it.second == CadencePlan.LAST_REP },
                "$fixture: the guide warns on a set whose warning has no rep left to be about",
            )
        }
        // The counter-case, and it is the reason this is not a blanket rule:
        // set 1 is the same session and the same four digits, its call opens
        // the final rep's FIRST stroke, and it keeps the warning. What issue
        // 176 adds to its archive is that same warning, at second 36, where
        // the recorded track has the bare `Down` it rode.
        val set01Plan = plan("3010", inclinePress)
        val finalRep = 9 * set01Plan.deliveredCycleS
        assertEquals(
            listOf(36 to "Down", 38 to "2", 39 to "Up", 40 to CadenceVoice.DONE),
            cadenceRows(set01).filter { it.first >= finalRep },
            "$set01, recorded",
        )
        assertEquals(
            listOf(36 to "Down", 36 to CadencePlan.LAST_REP, 38 to "2", 39 to "Up", 40 to CadenceVoice.DONE),
            scriptRows(set01Plan, 10).filter { it.first >= finalRep },
            "$set01, scripted",
        )
    }

    @Test
    fun `the final rep of a merged-on-its-own-last-stroke set has one stroke left when the call lands`() {
        // Issue #173, stated in the rows rather than in the KDoc that deferred
        // it to a session. The call rides the last stroke word of the LAST rep,
        // so between hearing it and hearing "Done" the lifter has that stroke
        // and no further rep -- two seconds on set 13, three on set 5.
        //
        // What is between them is NOT nothing on set 5, and an earlier draft of
        // this test asserted that it was: the three-second eccentric counts
        // itself out, so a `2` lands one second before "Done". Set 13's two
        // seconds are silent because its own count is the one given up.
        listOf(Triple(set05, 3.002, listOf("2")), Triple(set13, 2.001, emptyList())).forEach { (fix, gap, between) ->
            val rows = CueTrack.read(fix)
            val done = rows.first { it.label == "Done" }
            val lastStroke = rows.last { it.label in setOf("Down", "Up") }
            assertEquals(gap, (done.timestampMs - lastStroke.timestampMs) / 1000.0, 0.05, fix)
            assertEquals(
                between,
                rows.filter { it.timestampMs > lastStroke.timestampMs && it.timestampMs < done.timestampMs }
                    .map { it.label },
                "$fix: what the app says between the last stroke call and Done",
            )
        }
        // The eccentric-first 3010 is the counter-case: its call lands on the
        // NEXT rep's opening stroke, so a whole rep follows it, and the two
        // seconds before "Done" carry the rest of that rep's own cadence.
        val rows = CueTrack.read(set01)
        val done = rows.first { it.label == "Done" }
        val lastStroke = rows.last { it.label in setOf("Down", "Up") }
        assertEquals(1.001, (done.timestampMs - lastStroke.timestampMs) / 1000.0, 0.05, set01)
        assertEquals(
            listOf("Down", "2", "Up"),
            rows.filter { it.timestampMs < done.timestampMs }.takeLast(3).map { it.label },
            "$set01: the last rep is called through after the announcement, not before it",
        )
    }
}
