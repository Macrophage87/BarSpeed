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

    /**
     * True when a row is one of the guide's rep calls.
     *
     * `isSubsequence` and `surplus` used to live here, matching an archive into
     * the script as an ordered subsequence. They are deleted with the claim
     * they served: from #293 the script CONTRADICTS a row every one of these
     * tracks carries -- the first stroke word of every rep after the first --
     * so "the recording is accounted for by the plan" is no longer a true
     * statement to test, and a helper that computes a leftover under a broken
     * premise reads as though it still were.
     */
    private fun isCall(row: String) = row == CadencePlan.LAST_REP || row.startsWith(CadencePlan.REP_CALL_PREFIX)

    /** The cue rows the plan says a set of [reps] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, reps: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, reps).flatMap { call -> call.recorded.map { call.atSecond to it } }

    @Test
    fun `the tempo string still does not say what the call replaces, or what it costs`() {
        // The correction above, as an assertion. The three plans now put the
        // call in the SAME place -- beat 0, the rep's first stroke -- and the
        // (tempo, lift) pair still decides everything else about it: WHICH word
        // it replaces, and whether that stroke has counts to renumber.
        //
        // Before #293 the pair decided the home itself: beat 0 on set 1, beat 1
        // on set 5, beat 2 on set 13, from two tempo strings.
        val eccFirst = plan("3010", inclinePress)
        assertEquals(listOf("DOWN" to 3, "UP" to 1), eccFirst.beats.map { it.label to it.seconds }, "set 1")
        assertEquals(0, eccFirst.announceOnBeat, "set 1: the call opens the rep, as it already did")
        assertEquals(1, eccFirst.repCompleteAfterBeat, "which is not the beat the rep completes after")

        val concFirst = plan("3010", seatedOhp)
        assertEquals(listOf("UP" to 1, "DOWN" to 3), concFirst.beats.map { it.label to it.seconds }, "set 5")
        assertEquals(0, concFirst.announceOnBeat, "set 5: the same four digits, and the call moves off beat 1")
        assertEquals(1, concFirst.repCompleteAfterBeat, "which IS the beat the rep completes after")

        val fourSecond = plan("1120", pushdown)
        assertEquals(
            listOf("DOWN" to 1, CadencePlan.HOLD to 1, "UP" to 2),
            fourSecond.beats.map { it.label to it.seconds },
            "set 13",
        )
        assertEquals(0, fourSecond.announceOnBeat, "set 13: off beat 2, which is where its rep ends")
        assertEquals(2, fourSecond.repCompleteAfterBeat)
        // The whole of the named rep is ahead of the lifter on all three, which
        // is what the placement is for.
        listOf(eccFirst, concFirst, fourSecond).forEach { p ->
            assertEquals(
                p.repCompleteAfterBeat + 1,
                p.beatsOfRepLeftWhenAnnounced,
                "${p.beats.map { it.label to it.seconds }}: the call opens the rep it names",
            )
        }
        // And the word it replaces is the pair's, not the tempo's: `Down` on
        // the eccentric-first press, `Up` on the concentric-first overhead
        // press, from the same `3010`.
        assertEquals("Down", eccFirst.beats[0].spokenLabel, "set 1's first stroke word")
        assertEquals("Up", concFirst.beats[0].spokenLabel, "set 5's, from the same four digits")
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
    fun `the guide no longer accounts for every row these three sets recorded`() {
        // What #176 could assert and #293 ends. The script used to CONTAIN each
        // 0.1.43 track as a subsequence -- it added the calls the app spoke and
        // never wrote, and contradicted no row it did write. It cannot now: the
        // number replaces the first stroke's word, so a word each archive
        // carries on every rep is not spoken any more.
        //
        // Both directions, per set, written out. What LEAVES each archive is one
        // stroke word per rep after the first -- plus, on set 1, the `2` that
        // stroke now says a second earlier. What ARRIVES is the calls, which are
        // #176's subject, and the counts the carrying stroke used to give up.
        assertEquals(
            listOf(
                4 to "Down", 6 to "2", 8 to "Down", 10 to "2", 12 to "Down", 14 to "2",
                16 to "Down", 18 to "2", 20 to "Down", 22 to "2", 24 to "Down", 26 to "2",
                28 to "Down", 30 to "2", 32 to "Down", 34 to "2", 36 to "Down", 38 to "2",
            ),
            cadenceRows(set01) - scriptRows(plan("3010", inclinePress), 10).toSet(),
            "set 1: nine Down words the number replaces, and the count each of those strokes renumbers",
        )
        assertEquals(
            listOf(4 to "Up", 8 to "Up", 12 to "Up", 16 to "Up", 20 to "Up", 24 to "Up", 28 to "Up"),
            cadenceRows(set05) - scriptRows(plan("3010", seatedOhp), 8).toSet(),
            "set 5: seven Up words and nothing else -- its lowering keeps its own word and its counts",
        )
        assertEquals(
            listOf(
                4 to "Down", 8 to "Down", 12 to "Down", 16 to "Down", 20 to "Down", 24 to "Down",
                28 to "Down", 32 to "Down", 36 to "Down", 40 to "Down", 44 to "Down",
            ),
            cadenceRows(set13) - scriptRows(plan("1120", pushdown), 12).toSet(),
            "set 13: eleven Down words",
        )
        // And the row counts, so the population is readable rather than
        // inferred from two lists.
        listOf(
            Triple(set01, scriptRows(plan("3010", inclinePress), 10), 32 to 41),
            Triple(set05, scriptRows(plan("3010", seatedOhp), 8), 26 to 33),
            Triple(set13, scriptRows(plan("1120", pushdown), 12), 38 to 49),
        ).forEach { (fixture, scripted, counts) ->
            val (archived, now) = counts
            assertEquals(archived, cadenceRows(fixture).size, "$fixture: rows in the 0.1.43 archive")
            assertEquals(now, scripted.size, "$fixture: rows the guide writes for the same set now")
        }
    }

    @Test
    fun `the calls set 1 spoke and never wrote are named now, at the same nine instants`() {
        // Issue 176 against a real track: the ten-rep incline press said a rep
        // call at the start of every rep after the first, and its cue track
        // names none of them. These are the instants, on the cadence clock, that
        // the archive is missing -- and on THIS plan #293 does not move them,
        // because its call already opened the rep. What moves is that the `Down`
        // those nine instants carried is not spoken, and the stroke is counted
        // from the number instead.
        //
        // The WORDS are not 0.1.43's either: #243 moved the number onto the rep
        // being called for, so where that session heard "Rep 1" at second 4 the
        // guide now says "Rep 2" there.
        val scripted = scriptRows(plan("3010", inclinePress), 10)
        assertEquals(
            listOf(
                4 to "Rep 2", 8 to "Rep 3", 12 to "Rep 4", 16 to "Rep 5", 20 to "Rep 6",
                24 to "Rep 7", 28 to "Rep 8", 32 to "Rep 9", 36 to CadencePlan.LAST_REP,
            ),
            scripted.filter { isCall(it.second) },
            "the calls the guide adds to set 1's archive",
        )
        assertTrue(
            cadenceRows(set01).none { isCall(it.second) },
            "$set01 names a rep call, which no 0.1.43 track does",
        )
    }

    @Test
    fun `set 13's calls open the rep, two seconds before the silence the archive shows`() {
        // The same on the 1120 pushdown, whose call rode the LAST stroke of the
        // rep it announced. It opens the rep now: second 4 rather than 6 for rep
        // 2, in place of the `Down` the archive has at 4, and the `Up` stroke
        // keeps the `1` it used to give up -- which the archive shows missing on
        // every rep but the first.
        val scripted = scriptRows(plan("1120", pushdown), 12)
        assertEquals(
            (2..11).map { (it - 1) * 4 to "Rep $it" } + listOf(44 to CadencePlan.LAST_REP),
            scripted.filter { isCall(it.second) },
            "set 13's calls, one per rep after the first, each opening its rep",
        )
        assertEquals(
            12,
            scripted.count { it.second == "1" },
            "and the count that stroke gave up is spoken on all twelve reps",
        )
        assertEquals(
            1,
            cadenceRows(set13).count { it.second == "1" },
            "where the archive carries it once, on rep 1",
        )
    }

    @Test
    fun `the final rep of all three plans opens on its warning`() {
        // Issue 176 makes what the final rep says OBSERVABLE, and #173 then
        // #243 decided that it is said at all. #293 decides WHERE: the warning
        // opens the final rep in place of its first stroke word, so the lifter
        // hears it with the whole rep in front of them on every one of the
        // three. #173's report -- "It sometimes says last rep, done, with no rep
        // in between" -- was about a warning that arrived with one beat left.
        //
        // Each row is (the last rep's window in the 0.1.43 archive, the same
        // window as the guide scripts it now).
        listOf(
            Triple(
                Triple(set13, plan("1120", pushdown), 12),
                listOf(44 to "Down", 45 to "Hold", 46 to "Up", 48 to CadenceVoice.DONE),
                listOf(
                    44 to CadencePlan.LAST_REP,
                    45 to "Hold",
                    46 to "Up",
                    47 to "1",
                    48 to CadenceVoice.DONE,
                ),
            ),
            Triple(
                Triple(set05, plan("3010", seatedOhp), 8),
                listOf(28 to "Up", 29 to "Down", 31 to "2", 32 to CadenceVoice.DONE),
                listOf(
                    28 to CadencePlan.LAST_REP,
                    29 to "Down",
                    30 to "1",
                    31 to "2",
                    32 to CadenceVoice.DONE,
                ),
            ),
            Triple(
                Triple(set01, plan("3010", inclinePress), 10),
                listOf(36 to "Down", 38 to "2", 39 to "Up", 40 to CadenceVoice.DONE),
                listOf(
                    36 to CadencePlan.LAST_REP,
                    37 to "2",
                    38 to "3",
                    39 to "Up",
                    40 to CadenceVoice.DONE,
                ),
            ),
        ).forEach { (input, archived, scripted) ->
            val (fixture, p, reps) = input
            val finalRepStarts = (reps - 1) * p.deliveredCycleS
            assertEquals(archived, cadenceRows(fixture).filter { it.first >= finalRepStarts }, "$fixture, recorded")
            assertEquals(scripted, scriptRows(p, reps).filter { it.first >= finalRepStarts }, "$fixture, scripted")
            assertEquals(
                1,
                scriptRows(p, reps).count { it.second == CadencePlan.LAST_REP },
                "$fixture: the set names its last rep once",
            )
            assertEquals(
                finalRepStarts to CadencePlan.LAST_REP,
                scriptRows(p, reps).first { it.second == CadencePlan.LAST_REP },
                "$fixture: and says it on the final rep's first second",
            )
        }
    }

    @Test
    fun `what 0_1_43 left after the call on the final rep, which is what #293 moves`() {
        // Issue #173, stated in the rows rather than in the KDoc that deferred
        // it to a session. ARCHIVE ONLY: every figure here is read from a
        // recording and none of it moves. On 0.1.43 the call rode the last
        // stroke word of the LAST rep on these two sets, so between hearing it
        // and hearing "Done" the lifter had that stroke and no further rep --
        // two seconds on set 13, three on set 5. The guide now opens the final
        // rep with the warning instead, which `the final rep of all three plans
        // opens on its warning` above pins on the scripted side.
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
        // The eccentric-first 3010 is the counter-case even in 0.1.43: its call
        // rode the opening stroke of the rep it named, so a whole rep followed
        // it, and the two seconds before "Done" carry the rest of that rep's own
        // cadence. That is the placement #293 gives the other two.
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
