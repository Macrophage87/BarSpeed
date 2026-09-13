package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the guide plays on the LAST rep of a tempo that ends in a pause, read
 * off three tracks from one session.
 *
 * ## Provenance
 *
 * `field-39/f7ad0cb9-BarSpeedv0.1.5020260905_080515raw.zip`, whose `meta.json`
 * gives `"epoch": "2026-09-05T12:05:15.479Z"`, `"appVersion": "0.1.50"`,
 * `"sensorModel": "WitMotion WT901BLECL"`,
 * `"csvHeaderCues": "timestamp_ms,cue"`, and ten sets. The three files here are
 * that archive's `set03_seated_overhead_press_cues.csv`,
 * `set05_seated_overhead_press_cues.csv` and `set07_lat_pulldown_cues.csv`,
 * copied byte for byte. Each set's geometry below is read from the same
 * `meta.json` row -- `tempoPrescribed`, `startsWith`, `concentric`, `plane`,
 * `sensorOnStack`, `sensorInverted` -- so the plans reconstructed here are the
 * plans those sets were paced on.
 *
 * | set | exercise | tempo | plan case | closing pause |
 * |---|---|---|---|---|
 * | 3 | seated OHP | 3010 | merged into the rep's own last stroke | none |
 * | 5 | seated OHP | 1110 | a closing pause carries the call | 1 s |
 * | 7 | lat pulldown | 1120 | a closing pause carries the call | 1 s |
 *
 * Two of the session's ten sets have a closing pause and they are exactly
 * these two; set 3 is here as the case that must not move.
 *
 * ## Why the recorded words are not the scripted words, and it is not this
 *
 * 0.1.50 is BEFORE #243, so every `Rep N` row in these tracks counts FINISHED
 * reps: the row the archive writes as `Rep 1` is the row the guide now speaks
 * as `Rep 2`, at the same second. [asCalledNow] applies that one shift and
 * nothing else, so a difference this file reports is a difference in PLACEMENT
 * rather than #243 being re-litigated here. `RepCallScheduleTest` owns the
 * numbering.
 */
class ClosingPauseLastRepTest {
    /** meta.json sets 3 and 5: seated_overhead_press, conc-first, drive up, vertical, off-stack. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** meta.json set 7: lat_pulldown, conc-first, drive UP as cued, vertical, on-stack, inverted. */
    private val latPulldown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /**
     * Three lifts that are not on this session, carried only by the corpus pin
     * below so that all four homes for the rep call are covered by it.
     *
     * `benchPress` is ECCENTRIC-first, which is the geometry that leaves
     * `1110`'s digit-2 pause inside the rep and `2011`'s at the end of it.
     */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    private val facePull = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    private val set03 = "field-ohp-3010-6rep-s39-set03"
    private val set05 = "field-ohp-1110-6rep-s39-set05"
    private val set07 = "field-latpulldown-1120-6rep-s39-set07"

    /** Every one of the three sets was prescribed six reps and performed six. */
    private val reps = 6

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    /** The lead-in's own rows, which the cadence script does not model. */
    private val leadIn = setOf("Ready", "Brace")

    /**
     * A track's rows as (second of the cadence, row), the first movement call
     * being second zero -- the same origin [CadenceVoice.script] counts from.
     *
     * The same rule `MergedCallCueTrackTest` reads its own tracks by, stated
     * again rather than shared: that file's copy is private to it, and a helper
     * shared between two files scoring two different sessions would let a
     * change to one silently rescore the other.
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

    /** The cue rows the plan says a set of [n] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, n: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, n).flatMap { call -> call.recorded.map { call.atSecond to it } }

    /**
     * A 0.1.50 track's rows with its rep numbers moved onto the rep the call
     * is now about (#243). `Rep 1` becomes `Rep 2`; `Last rep` and every
     * stroke word, count and `Done` are untouched.
     */
    private fun asCalledNow(rows: List<Pair<Int, String>>): List<Pair<Int, String>> {
        return rows.map { (second, label) ->
            val number = label.removePrefix(CadencePlan.REP_CALL_PREFIX).toIntOrNull()
            if (label.startsWith(CadencePlan.REP_CALL_PREFIX) && number != null) {
                second to "${CadencePlan.REP_CALL_PREFIX}${number + 1}"
            } else {
                second to label
            }
        }
    }

    /** Seconds from the track's first movement call to its `Done`, as recorded. */
    private fun spokenSetS(fixture: String): Double {
        val rows = CueTrack.read(fixture).filter { it.label !in leadIn }
        val done = rows.first { it.label == CadenceVoice.DONE }
        return (done.timestampMs - rows.first().timestampMs) / 1000.0
    }

    @Test
    fun `the archive ends a closing-pause set one closing pause short of its prescription`() {
        // The measurement behind #265, on the recordings rather than tabulated:
        // what the app SAID, first movement word to `Done`, against six
        // delivered cycles. No production code is read for the recorded side.
        // The owner's word on hearing it: "Yes, that very much feels short."
        assertEquals(24.029, spokenSetS(set03), 0.05, "set 3, 3010, no closing pause")
        assertEquals(17.023, spokenSetS(set05), 0.05, "set 5, 1110, a one-second closing pause")
        assertEquals(23.029, spokenSetS(set07), 0.05, "set 7, 1120, a one-second closing pause")
        assertEquals(4, plan("3010", seatedOhp).deliveredCycleS, "set 3's delivered cycle")
        assertEquals(3, plan("1110", seatedOhp).deliveredCycleS, "set 5's")
        assertEquals(4, plan("1120", latPulldown).deliveredCycleS, "set 7's")
        // 6 x 4 = 24 delivered in full; 6 x 3 = 18 delivered as 17; 6 x 4 = 24
        // delivered as 23. The shortfall is exactly one closing pause and it
        // falls on the two sets that have one.
        listOf(
            Triple(set03, plan("3010", seatedOhp), 0),
            Triple(set05, plan("1110", seatedOhp), 1),
            Triple(set07, plan("1120", latPulldown), 1),
        ).forEach { (fixture, p, shortfall) ->
            assertEquals(
                (reps * p.deliveredCycleS - shortfall).toDouble(),
                spokenSetS(fixture),
                0.05,
                "$fixture: seconds the app spoke, against $reps x ${p.deliveredCycleS} less $shortfall",
            )
        }
    }

    @Test
    fun `both closing-pause tracks recorded exactly these rows`() {
        // The archive, stated once so every expectation below can be read
        // against it. A fact about two files; nothing here can change it.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "Rep 1",
                3 to "Up", 4 to "Down", 5 to "Rep 2",
                6 to "Up", 7 to "Down", 8 to "Rep 3",
                9 to "Up", 10 to "Down", 11 to "Rep 4",
                12 to "Up", 13 to "Down", 14 to "Last rep",
                15 to "Up", 16 to "Down", 17 to "Done",
            ),
            cadenceRows(set05),
            "set 5, 1110, six reps of a three-second cycle -- and Done at 17 rather than 18",
        )
        assertEquals(
            listOf(
                0 to "Up", 1 to "1", 2 to "Down", 3 to "Rep 1",
                4 to "Up", 5 to "1", 6 to "Down", 7 to "Rep 2",
                8 to "Up", 9 to "1", 10 to "Down", 11 to "Rep 3",
                12 to "Up", 13 to "1", 14 to "Down", 15 to "Rep 4",
                16 to "Up", 17 to "1", 18 to "Down", 19 to "Last rep",
                20 to "Up", 21 to "1", 22 to "Down", 23 to "Done",
            ),
            cadenceRows(set07),
            "set 7, 1120, six reps of a four-second cycle -- and Done at 23 rather than 24",
        )
    }

    @Test
    fun `the guide scripts both tracks row for row, and moves only the terminal Done`() {
        // #265. The script reproduces every row of both archives, at the second
        // the archive has it, with ONE row moved: the terminal `Done`, which
        // 0.1.50 spoke at second 17 on set 5 and 23 on set 7 -- one closing
        // pause short -- and which now lands at 18 and 24. Nothing else moves,
        // no row is added and none is removed.
        //
        // The rep NUMBERS differ throughout and that is #243's and not this:
        // 0.1.50 counted finished reps, so the archive's `Rep 1` is the guide's
        // `Rep 2` at the same second. `asCalledNow` applies that one shift.
        listOf(
            Triple(set05, plan("1110", seatedOhp), 17),
            Triple(set07, plan("1120", latPulldown), 23),
        ).forEach { (fixture, p, archivedDoneAt) ->
            val archived = asCalledNow(cadenceRows(fixture))
            val scripted = scriptRows(p, reps)
            assertEquals(
                archived.dropLast(1),
                scripted.dropLast(1),
                "$fixture: every row but the ending, against the track it was recorded from",
            )
            assertEquals(
                archived.size,
                scripted.size,
                "$fixture: a restored pause moves a row and adds none",
            )
            assertEquals(
                archivedDoneAt to CadenceVoice.DONE,
                archived.last(),
                "$fixture: where 0.1.50 put Done",
            )
            assertEquals(
                (reps * p.deliveredCycleS) to CadenceVoice.DONE,
                scripted.last(),
                "$fixture: and where it lands once the last rep keeps its closing pause",
            )
        }
    }

    @Test
    fun `a set is as long as its prescription on every tempo, closing pause or not`() {
        // #265 as the general rule rather than two tracks: the last call of a
        // bounded set is `Done` at reps x deliveredCycleS, on all four homes
        // for the rep call and at one, two, six and twelve reps.
        //
        // This is the assertion the shipped guide fails. Before #265 the three
        // closing-pause rows below ended a second early -- the pause was
        // delivered on reps 1 to N-1 and cut on the last -- and every other row
        // already held, because a plan whose rep ends on its second stroke has
        // no beat after the one `Done` follows.
        val corpus = listOf(
            // case 1, a closing pause carries the call: the affected family.
            Triple("2011", benchPress, 5),
            // case 1 at one rep: lastRep is set on the first pass, so the
            // fall-through must carry the closing pause with no rep ever
            // announced.
            Triple("2011", benchPress, 1),
            Triple("1110", seatedOhp, 6),
            Triple("1120", latPulldown, 6),
            // case 2, merged into the NEXT rep's opening stroke.
            Triple("3010", benchPress, 10),
            Triple("3010", benchPress, 1),
            // case 3, merged into the rep's OWN last stroke.
            Triple("2010", seatedOhp, 8),
            Triple("1030", legCurl, 12),
            Triple("20X0", seatedOhp, 6),
            Triple("2011", facePull, 12),
            // case 4, no home for a call at all.
            Triple("1010", seatedOhp, 6),
            Triple("1110", benchPress, 2),
        )
        // The corpus cannot be weakened into one that would pass either way:
        // four of its rows must have a beat after the one the rep completes
        // on, which is the only shape #265 fires on.
        assertEquals(
            4,
            corpus.count { (tempo, direction, _) ->
                plan(tempo, direction).let { it.beats.lastIndex > it.repCompleteAfterBeat }
            },
            "rows whose rep ends in a closing pause",
        )
        corpus.forEach { (tempo, direction, n) ->
            val p = plan(tempo, direction)
            val last = CadenceVoice.script(p, n).last()
            assertEquals(
                CadenceVoice.DONE,
                last.utterance,
                "$tempo on ${direction.plane}/${direction.startsWith}: the last call of a bounded set",
            )
            assertEquals(
                n * p.deliveredCycleS,
                last.atSecond,
                "$tempo on ${direction.plane}/${direction.startsWith}, $n reps: " +
                    "Done at $n x ${p.deliveredCycleS}, beats ${p.beats.map { it.label to it.seconds }}",
            )
        }
    }

    @Test
    fun `the last rep's restored closing pause is silent, so the set says one word more than before`() {
        // What the lifter hears, and what the archive gains: nothing but a
        // second of silence before `Done`. The closing pause has no word of its
        // own (`CadenceBeat.spokenLabel` is null) and there is no next rep to
        // announce, so the beat the last rep gets back speaks nothing and
        // writes nothing. That is why the row count above is unchanged.
        listOf(
            Triple(set05, plan("1110", seatedOhp), listOf(15 to "Up", 16 to "Down", 18 to CadenceVoice.DONE)),
            Triple(
                set07,
                plan("1120", latPulldown),
                listOf(20 to "Up", 21 to "1", 22 to "Down", 24 to CadenceVoice.DONE),
            ),
        ).forEach { (fixture, p, lastRep) ->
            val opensAt = (reps - 1) * p.deliveredCycleS
            assertEquals(
                lastRep,
                scriptRows(p, reps).filter { it.first >= opensAt },
                "$fixture: the last rep, from second $opensAt",
            )
            val closing = p.beats.last()
            assertEquals(null, closing.spokenLabel, "$fixture: the beat the last rep gets back has no word")
            assertEquals(false, closing.isStroke, "$fixture: and is not counted out loud")
            assertEquals(1, closing.seconds, "$fixture: one second, which is what the prescription asked for")
        }
    }

    @Test
    fun `a tempo with no closing pause is scripted these rows, and this is the control`() {
        // Set 3, the same session and the same lift, whose rep ends on its
        // second stroke. Nothing about the last rep can move here: there is no
        // beat after the one the rep completes on. Pinned as the whole row
        // list so a change that lengthens EVERY set's last rep -- the shape of
        // issue 106, a beat the prescription did not ask for -- reds here
        // rather than passing as a fix.
        assertEquals(
            listOf(
                0 to "Up", 1 to "Down", 2 to "1", 3 to "2",
                4 to "Up", 5 to "Down", 5 to "Rep 2", 7 to "2",
                8 to "Up", 9 to "Down", 9 to "Rep 3", 11 to "2",
                12 to "Up", 13 to "Down", 13 to "Rep 4", 15 to "2",
                16 to "Up", 17 to "Down", 17 to "Rep 5", 19 to "2",
                20 to "Up", 21 to "Down", 21 to "Last rep", 23 to "2",
                24 to CadenceVoice.DONE,
            ),
            scriptRows(plan("3010", seatedOhp), reps),
            "set 3, 3010: Done at 6 x 4 and every row of the cycle where the archive has it",
        )
        assertEquals(
            reps * plan("3010", seatedOhp).deliveredCycleS,
            scriptRows(plan("3010", seatedOhp), reps).last().first,
            "set 3 already ends where the prescription says",
        )
        // The one row that is NOT the archive's, and it is #243's rather than
        // this file's subject: 0.1.50 withheld `Last rep` on a merged schedule
        // and kept the final `1` at second 22, where the guide now speaks the
        // warning at 21 and that count is given up.
        assertEquals(
            listOf(20 to "Up", 21 to "Down", 22 to "1", 23 to "2", 24 to "Done"),
            cadenceRows(set03).filter { it.first >= 20 },
            "set 3's last rep as 0.1.50 recorded it",
        )
    }
}
