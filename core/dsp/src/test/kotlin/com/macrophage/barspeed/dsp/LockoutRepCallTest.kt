package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a plan with no free beat says, on the two `1010` sets of field-39.
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
 * They are the session's only two `1010` sets, and they are the only two sets
 * of the ten whose plan speaks no rep number at all. That is the complaint
 * (#266): the owner heard six reps of each called `Up, Down` and counted for
 * himself.
 *
 * ## What is pinned here
 *
 * The two archives are FACTS and are asserted as recorded. The script side is
 * what the guide says today, and it reproduces both tracks row for row -- which
 * is the licence for moving it, in the sense `RepCallPlacementTest` uses the
 * word: a change is measured against a script already known to model the
 * shipped app on these very sets, not against a script nobody has scored.
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

    private val set04 = "field-ohp-1010-6rep-s39-set04"
    private val set09 = "field-latpulldown-1010-6rep-s39-set09"

    /** Both sets were prescribed six reps and performed six. */
    private val reps = 6

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

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

    /** The cue rows the plan says a set of [reps] reps writes, as (second, row). */
    private fun scriptRows(p: CadencePlan, count: Int): List<Pair<Int, String>> =
        CadenceVoice.script(p, count).flatMap { call -> call.recorded.map { call.atSecond to it } }

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
        // Measured rather than asserted from the plan: the owner's report is
        // that a 1010 set counts nothing aloud, and these are the two sets it
        // is about. Twelve movement rows and a terminal word, no number.
        listOf(set04, set09).forEach { fixture ->
            val rows = cadenceRows(fixture).map { it.second }
            assertEquals(
                emptyList(),
                rows.filter { it == CadencePlan.LAST_REP || it.startsWith(CadencePlan.REP_CALL_PREFIX) },
                "$fixture: a rep call the archive was not expected to carry",
            )
            assertEquals(13, rows.size, "$fixture: twelve stroke words and one Done")
        }
    }

    @Test
    fun `the shipped script reproduces both tracks row for row`() {
        // THE LICENCE. Both sets resolve to the same two-second cycle from
        // different geometry -- the pulldown's digits are swapped by
        // `TempoSchedule.of` and land in the same order -- and the script
        // models the runner's loop exactly on both. A change measured against
        // this is measured against the app the owner heard.
        listOf(set04 to seatedOhp, set09 to latPulldown).forEach { (fixture, direction) ->
            val p = plan("1010", direction)
            assertEquals(listOf("UP" to 1, "DOWN" to 1), p.beats.map { it.label to it.seconds }, fixture)
            assertEquals(2, p.deliveredCycleS, "$fixture: two seconds a rep, as prescribed")
            assertEquals(cadenceRows(fixture), scriptRows(p, reps), "$fixture: script against archive")
        }
    }

    /**
     * CHARACTERIZATION of the flag the prep note will read, before anything sets
     * it.
     *
     * `announcesAtConcentricEnd` is new and false on every (tempo, lift) pair
     * the plan builder can produce: a call rides beat 0 or nothing rides at all,
     * which is the partition #293 left. The sweep is the whole notation space so
     * that "false everywhere" is a measurement rather than three examples, and
     * it is what the next commit's differential inverts on the two sets above.
     */
    @Test
    fun `no plan announces at the end of the drive yet`() {
        val lifts = listOf(
            seatedOhp,
            latPulldown,
            LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true),
            LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = false),
            LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = false),
            LiftDirection(plane = MovementPlane.HORIZONTAL),
        )
        var checked = 0
        (0..4).forEach { d1 ->
            (0..2).forEach { d2 ->
                (0..4).forEach { d3 ->
                    (0..2).forEach { d4 ->
                        lifts.forEach { lift ->
                            val p = plan("$d1$d2$d3$d4", lift)
                            assertEquals(
                                false,
                                p.announcesAtConcentricEnd,
                                "$d1$d2$d3$d4 on ${lift.plane}/${lift.startsWith}: beats=${p.beats.map { it.label }}",
                            )
                            checked++
                        }
                    }
                }
            }
        }
        assertEquals(225 * 6, checked, "(tempo, lift) pairs checked")
    }
}
