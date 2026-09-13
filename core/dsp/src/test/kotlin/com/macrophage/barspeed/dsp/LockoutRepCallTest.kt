package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.RepCallNotePolicy
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VelocityLossRegime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rep number at the END OF THE DRIVE, on the plans with no free second at
 * the start of the rep (#266).
 *
 * ## Provenance
 *
 * `field-39/f7ad0cb9-BarSpeedv0.1.5020260905_080515raw.zip`, whose `meta.json`
 * gives `"epoch": "2026-09-05T12:05:15.479Z"`, `"appVersion": "0.1.50"`,
 * `"sensorModel": "WitMotion WT901BLECL"`,
 * `"csvHeaderCues": "timestamp_ms,cue"`, and ten sets. The two files here are
 * that archive's `set04_seated_overhead_press_cues.csv` and
 * `set09_lat_pulldown_cues.csv`, copied byte for byte. Each set's geometry
 * below is read from the same `meta.json` row -- `tempoPrescribed`,
 * `startsWith`, `concentric`, `plane`, `sensorOnStack`, `sensorInverted`,
 * `plannedReps` -- so the plans reconstructed here are the plans those sets
 * were paced on.
 *
 * | set | exercise | tempo | starts with | concentric | on stack | reps |
 * |---|---|---|---|---|---|---|
 * | 4 | seated OHP | 1010 | concentric | up | no | 6 of 6 |
 * | 9 | lat pulldown | 1010 | concentric | up | yes | 6 of 6 |
 *
 * They are the session's only two `1010` sets, and they were the only two sets
 * of the ten whose plan spoke no rep number at all. That is the complaint: the
 * owner heard six reps of each called `Up, Down` and counted for himself.
 *
 * ## The rule, in the owner's words
 *
 * *"When you can't have a rep call or an up or down, have the end of the
 * concentric phase be the rep number. Have a note for that during prep
 * phase."* And, on why that beat: *"People are used to the reps being counted
 * at lockout, so this would be an easy cue."* Reaffirmed 2026-09-12, *"Rep
 * count is at lockout"*, against #293's start-of-rep placement, which does not
 * apply here.
 *
 * So a `1010` press goes `Up, Rep 1, Up, Rep 2, ...`: the number takes the
 * second that opens as the drive ends, in place of the word that second
 * carried.
 *
 * ## Which plans the rule can reach, and which it cannot
 *
 * The beat that opens as the concentric ends has to EXIST in the cycle. It does
 * when the concentric is the stroke the rep OPENS with -- the next beat is the
 * mid-rep pause if the prescription has one, and the other stroke if it does
 * not -- and it does not when the concentric is the stroke the rep ENDS on,
 * because a plan in this family has no closing pause and the next beat belongs
 * to the NEXT rep.
 *
 * On those, three things would be true at once, so they are left silent and the
 * reason is measured below rather than asserted:
 *
 * - the number would name a rep that had FINISHED, on a schedule where #243 and
 *   #252 put every numbered call on the rep in hand and `RingVoiceAgreementTest`
 *   pins the voice equal to the ring's caption, which reads `rep N+1 of M` at
 *   that instant. That frame error is what #173 was filed for.
 * - `Last rep` would get no beat at all: the last rep's own call falls on the
 *   instant `Done` is said, because #265 leaves no beat after the concentric
 *   here.
 * - naming the rep IN HAND on that beat instead is #293's start-of-rep
 *   placement, which the owner ruled against for exactly these plans.
 *
 * Every geometry field-39 recorded is concentric-first, so both measured sets
 * are reached; the unreachable shape is pinned synthetically and named in the
 * commit body as narrower than the issue's "1010 on every lift".
 *
 * ## What is pinned here
 *
 * The two archives are FACTS and are asserted as recorded. The commit before
 * this one asserted that today's script reproduces them row for row, and it
 * did -- that is the licence for moving it. Every row the script now says
 * differently is named below, in both directions.
 */
class LockoutRepCallTest {
    /** meta.json set 4: seated_overhead_press, conc-first, drive up, vertical, off-stack. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** meta.json set 9: lat_pulldown, conc-first, drive UP as declared, vertical, on-stack, inverted. */
    private val latPulldown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /**
     * A pulldown declared the way the owner's own note says a plan should
     * declare one -- concentric DOWN (#263) -- which is the geometry that
     * leaves `1110`'s digit-2 pause inside the rep with the concentric in front
     * of it. Synthetic: no captured session has it.
     */
    private val pulldownDrivesDown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** An eccentric-first press: the geometry whose concentric ENDS the rep. */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private val set04 = "field-ohp-1010-6rep-s39-set04"
    private val set09 = "field-latpulldown-1010-6rep-s39-set09"

    /** Both sets were prescribed six reps and performed six. */
    private val reps = 6

    private fun schedule(tempo: String, direction: LiftDirection) = TempoSchedule.of(Tempo.parse(tempo), direction)

    private fun plan(tempo: String, direction: LiftDirection) = CadencePlan.of(schedule(tempo, direction))

    /** The lead-in's own rows, which the cadence script does not model. */
    private val leadIn = setOf("Ready", "Brace")

    /**
     * A track's rows as (second of the cadence, row), the first movement call
     * being second zero -- the same origin [CadenceVoice.script] counts from.
     *
     * Stated here rather than shared with `MergedCallCueTrackTest`,
     * `ClosingPauseLastRepTest` and `RepCallPlacementTest`, each of which keeps
     * its own copy for the reason the second of those gives: a helper shared
     * between files scoring different sessions lets a change to one silently
     * rescore the others.
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

    /** The cue rows the plan says a set of [count] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, count: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, count).flatMap { call -> call.recorded.map { call.atSecond to it } }

    private fun isCall(row: String) = row == CadencePlan.LAST_REP || row.startsWith(CadencePlan.REP_CALL_PREFIX)

    @Test
    fun `these two tracks recorded exactly these rows`() {
        // Facts about two files. Nothing in this repository can change them,
        // and every expectation below is read against them.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down",
                2 to "Up", 3 to "Down",
                4 to "Up", 5 to "Down",
                6 to "Up", 7 to "Down",
                8 to "Up", 9 to "Down",
                10 to "Up", 11 to "Down",
                12 to "Done",
            ),
            cadenceRows(set04),
            "field-39 set 4, 1010 concentric-first, six reps of a two-second cycle",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down",
                2 to "Up", 3 to "Down",
                4 to "Up", 5 to "Down",
                6 to "Up", 7 to "Down",
                8 to "Up", 9 to "Down",
                10 to "Up", 11 to "Down",
                12 to "Done",
            ),
            cadenceRows(set09),
            "field-39 set 9, the same prescription on a stack-mounted pulldown",
        )
    }

    @Test
    fun `neither track names a rep, which is the complaint`() {
        // Measured off the tracks rather than derived from the plan: the owner's
        // report is that a 1010 set counts nothing aloud, and these are the two
        // sets it is about. Twelve movement rows and a terminal word, no number.
        listOf(set04, set09).forEach { fixture ->
            val rows = cadenceRows(fixture).map { it.second }
            assertEquals(emptyList(), rows.filter { isCall(it) }, "$fixture: a call the archive does not carry")
            assertEquals(13, rows.size, "$fixture: twelve stroke words and one Done")
        }
    }

    @Test
    fun `both sets now count at the end of the drive, on the archive's own seconds`() {
        // What a lifter hears on each, per second: Up, Rep 1, Up, Rep 2, ... .
        // The owner's example sentence, and the six numbers the archive has none
        // of. Rep 1 is named here where #293's plans leave it silent: the word
        // being replaced is the SECOND beat's, and rep 1 has one of those like
        // every other rep -- what rep 1 keeps on every plan is beat 0's word,
        // which is the start cue under #241's contract.
        val expected = listOf(
            0 to "Up", 1 to "Rep 1",
            2 to "Up", 3 to "Rep 2",
            4 to "Up", 5 to "Rep 3",
            6 to "Up", 7 to "Rep 4",
            8 to "Up", 9 to "Rep 5",
            10 to "Up", 11 to CadencePlan.LAST_REP,
            12 to CadenceVoice.DONE,
        )
        listOf(set04 to seatedOhp, set09 to latPulldown).forEach { (fixture, direction) ->
            val p = plan("1010", direction)
            assertEquals(expected, scriptRows(p, reps), "$fixture: what the guide says now")
        }
    }

    @Test
    fun `a word is replaced and nothing is added or moved, measured against both archives`() {
        // CadencePlanTest's obligation, stated on these two sets as a difference
        // rather than as a rule: the seconds are the archive's, the row COUNT is
        // the archive's, and the only change is six rows renamed from `Down` to
        // the number that now marks the same instant.
        listOf(set04 to seatedOhp, set09 to latPulldown).forEach { (fixture, direction) ->
            val p = plan("1010", direction)
            val archive = cadenceRows(fixture)
            val now = scriptRows(p, reps)
            assertEquals(archive.size, now.size, "$fixture: as many rows as the archive, neither more nor fewer")
            assertEquals(archive.map { it.first }, now.map { it.first }, "$fixture: on the archive's own seconds")
            assertEquals(
                listOf(1 to "Down", 3 to "Down", 5 to "Down", 7 to "Down", 9 to "Down", 11 to "Down"),
                archive - now.toSet(),
                "$fixture: rows the archive has that the guide no longer writes",
            )
            assertEquals(
                listOf(
                    1 to "Rep 1",
                    3 to "Rep 2",
                    5 to "Rep 3",
                    7 to "Rep 4",
                    9 to "Rep 5",
                    11 to CadencePlan.LAST_REP,
                ),
                now - archive.toSet(),
                "$fixture: rows it writes instead, at the same seconds",
            )
            assertEquals(6, now.count { it.second == "Up" }, "$fixture: and the drive is still called on every rep")
            assertEquals(0, now.count { it.second == "Down" }, "$fixture: while the return's word is spent")
        }
    }

    @Test
    fun `the beat the number takes is the one that opens as the drive ends`() {
        // The placement as a plan fact, on the measured pair and on the
        // synthetic 1110 geometry. `beatsOfRepLeftWhenAnnounced` is the
        // measurement that says it is not the start of the rep: the whole rep is
        // ahead of a #293 call, and only the beats after the drive are ahead of
        // this one.
        val press = plan("1010", seatedOhp)
        assertEquals(1, press.announceOnBeat, "1010 conc-first: the beat after the drive")
        assertEquals("Down", press.beats[1].spokenLabel, "whose word the number replaces")
        assertEquals(1, press.beatsOfRepLeftWhenAnnounced, "one beat of the rep left: the return")
        assertEquals(true, press.announcesAtConcentricEnd)

        // 1110 on a pulldown declared concentric-DOWN: the pause between the
        // strokes is the beat that opens as the drive ends, so the word the
        // number replaces is `Hold` and not a stroke word at all. The hold's
        // SECOND is untouched -- the prescription's isometric is still played --
        // and the lifter is told the rep is counted at the instant they reach
        // the position they are holding.
        val paused = plan("1110", pulldownDrivesDown)
        assertEquals(listOf("DOWN" to 1, CadencePlan.HOLD to 1, "UP" to 1), paused.beats.map { it.label to it.seconds })
        assertEquals(1, paused.announceOnBeat, "1110 conc-first: the mid-rep pause")
        assertEquals("Hold", paused.beats[1].spokenLabel, "so the word spent here is the hold's")
        assertEquals(2, paused.beatsOfRepLeftWhenAnnounced, "the hold and the return are still to come")
        assertEquals(
            listOf(
                0 to "Down", 1 to "Rep 1", 2 to "Up",
                3 to "Down", 4 to "Rep 2", 5 to "Up",
                6 to "Down", 7 to CadencePlan.LAST_REP, 8 to "Up",
                9 to CadenceVoice.DONE,
            ),
            scriptRows(paused, 3),
            "three reps of 1110 on that geometry, nine seconds, no beat moved",
        )
    }

    @Test
    fun `the geometry whose drive ends the rep still says nothing, and why`() {
        // The shape the rule cannot reach. Both are the same family -- two
        // one-second strokes, no closing pause -- and on both the concentric is
        // the stroke the rep ENDS on, so the beat that opens as the drive ends
        // belongs to the next rep.
        listOf("1010" to 2, "1110" to 3).forEach { (tempo, cycle) ->
            val p = plan(tempo, benchPress)
            assertNull(p.announceOnBeat, "$tempo ecc-first has no beat of its own at the drive's end")
            assertEquals(false, p.announcesAtConcentricEnd, "$tempo: so it draws no prep note either")
            assertEquals(cycle, p.deliveredCycleS)
            assertEquals(p.repCompleteAfterBeat, p.beats.lastIndex, "$tempo: nothing follows the drive in the cycle")
            assertEquals(true, p.beats[p.repCompleteAfterBeat].isStroke, "$tempo: and that last beat IS the drive")
            assertNull(p.announcementFor(reps, reps), "$tempo: the last rep is not named, as before")
            // Silence is total: every word the prescription has is still spoken
            // on every rep, which is what makes the absence of a number
            // readable rather than ambiguous.
            assertEquals(
                emptyList(),
                scriptRows(p, reps).filter { isCall(it.second) },
                "$tempo ecc-first: no call anywhere in the set",
            )
        }
        // Two of the three consequences the class KDoc names, as numbers rather
        // than prose. At the instant an ecc-first 1010 rep's drive ends the rep
        // is COMPLETE -- that is the beat `onRepCounted` fires on -- so a number
        // spoken there would name a finished rep, and the last rep's number
        // would fall on Done's own second.
        val p = plan("1010", benchPress)
        assertEquals(1, p.repCompleteAfterBeat, "the drive is beat 1 and the rep is over after it")
        assertEquals(
            reps * p.deliveredCycleS,
            scriptRows(p, reps).last().first,
            "so the second after the last drive is Done's, with no room for a number",
        )
    }

    @Test
    fun `the prep note is drawn exactly on the plans that count at the drive's end`() {
        // The coupling `:core:model` cannot state for itself: RepCallNotePolicy
        // takes a Boolean and this side owns the answer. Named cases here, the
        // sweep below, so a rule returning the note for everything or for
        // nothing reds in one of the two.
        assertEquals(
            RepCallNotePolicy.AT_DRIVE_END,
            RepCallNotePolicy.noteFor(plan("1010", seatedOhp).announcesAtConcentricEnd),
            "field-39 set 4's plan draws the line",
        )
        assertEquals(
            RepCallNotePolicy.AT_DRIVE_END,
            RepCallNotePolicy.noteFor(plan("1110", pulldownDrivesDown).announcesAtConcentricEnd),
            "so does the 1110 geometry whose pause sits after the drive",
        )
        assertNull(
            RepCallNotePolicy.noteFor(plan("3010", seatedOhp).announcesAtConcentricEnd),
            "a plan that names the rep at its start explains nothing",
        )
        assertNull(
            RepCallNotePolicy.noteFor(plan("1010", benchPress).announcesAtConcentricEnd),
            "and a plan that says no number at all must not promise one",
        )
    }

    @Test
    fun `the drive-end placement fires exactly where the rep opens on the drive with no free second`() {
        // The rule as a sweep rather than as examples, over every whole-second
        // notation on six geometries. The oracle is restated from the SCHEDULE
        // -- a cycle with a closing pause, or a stroke of two seconds or more,
        // has a free second at the start of the rep and #293 takes it -- rather
        // than read back off the plan, which would check nothing.
        val lifts = listOf(
            seatedOhp,
            latPulldown,
            pulldownDrivesDown,
            benchPress,
            LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = false),
            LiftDirection(plane = MovementPlane.HORIZONTAL),
        )
        var driveEnd = 0
        var checked = 0
        (0..4).forEach { d1 ->
            (0..2).forEach { d2 ->
                (0..4).forEach { d3 ->
                    (0..2).forEach { d4 ->
                        lifts.forEach { lift ->
                            val s = schedule("$d1$d2$d3$d4", lift)
                            val firstS = (s.first.seconds ?: 1.0).toInt().coerceAtLeast(1)
                            val secondS = (s.second.seconds ?: 1.0).toInt().coerceAtLeast(1)
                            val dense = s.pauseAfterSecondS.toInt() < CadencePlan.ANNOUNCE_BEAT_S &&
                                firstS < CadencePlan.CALL_MIN_STROKE_S &&
                                secondS < CadencePlan.CALL_MIN_STROKE_S
                            val expected = dense && s.first.isConcentric
                            val p = CadencePlan.of(s)
                            val where = "$d1$d2$d3$d4 on ${lift.plane}/${lift.startsWith}"
                            assertEquals(expected, p.announcesAtConcentricEnd, where)
                            if (expected) {
                                assertEquals(1, p.announceOnBeat, "$where: on the beat after the drive")
                                driveEnd++
                            }
                            checked++
                        }
                    }
                }
            }
        }
        assertEquals(225 * 6, checked, "(tempo, lift) pairs checked")
        // The partition is not vacuous in either direction: a rule that never
        // fired, or one that always did, fails one of these two.
        //
        // 36 is MEASURED, and the commit that wrote this line guessed 12 -- a
        // number asserted without being run, which is the defect class this
        // repository names most often. 36 is 12 per concentric-first lift times
        // the three of the six here that are one: the four (d1, d3) pairs whose
        // strokes both coerce under CALL_MIN_STROKE_S, times the three values of
        // the pause that does NOT land last, with the one that does pinned at
        // zero. The three eccentric-first lifts -- the bench press, the
        // drive-down ecc-first lift and the horizontal one, whose LiftDirection
        // default startsWith is ECCENTRIC -- contribute none.
        assertEquals(36, driveEnd, "pairs that count at the drive's end")
    }

    @Test
    fun `1010 is still a controlled tempo set, which this changes nothing about`() {
        // #250's regime reads the tempo STRING and the geometry, never the
        // cadence plan, so nothing here can move it -- asserted rather than
        // assumed, because the owner's scope sentence is explicit: "Keep 1010 as
        // a tempo session with the end of the concentric replaced by rep
        // number."
        listOf(true, false).forEach { up ->
            assertEquals(
                VelocityLossRegime.CONTROLLED,
                VelocityLossRegime.of("1010", up, horizontal = false, kind = ExerciseKind.DYNAMIC),
                "1010 with the drive ${if (up) "up" else "down"}",
            )
        }
        // And it is still counted the way #248 measured: two one-second strokes,
        // so no interior second to count aloud, which this does not touch.
        val p = plan("1010", seatedOhp)
        assertEquals(2, p.beats.size, "two beats, both strokes")
        assertEquals(
            emptyList(),
            scriptRows(p, reps).filter { it.second.toIntOrNull() != null },
            "no bare digit on a 1010 set, before this change or after it",
        )
    }
}
