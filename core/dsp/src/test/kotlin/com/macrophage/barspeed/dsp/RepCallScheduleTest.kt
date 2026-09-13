package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which rep the guide names, and when, on the six (tempo, lift) pairs two field
 * sessions actually ran (issue #243).
 *
 * ## Provenance
 *
 * The tracks these strings are derived from are not constructed. They are the
 * cue tracks of seven guided sets, read from `_cues.csv` in two capture
 * archives, six of them from archives not committed to this repository:
 *
 * | row | capture | app | set |
 * |---|---|---|---|
 * | `bench3010x6` | field-37 | 0.1.48 | 5, bench press, 6 planned |
 * | `ohp3010x8` | field-38, field-37 | 0.1.50, 0.1.48 | 4 and 1, seated overhead press, 8 planned |
 * | `incline3010x10` | field-38 | 0.1.50 | 1, dumbbell incline press, 10 planned |
 * | `fly2011x12` | field-38 | 0.1.50 | 6, chest-supported rear delt fly, 12 planned |
 * | `curl2010x12` | field-38 | 0.1.50 | 10, seated biceps curl, 12 planned |
 * | `pushdown1120x12` | field-38 | 0.1.50 | 12, triceps pushdown, 12 planned |
 *
 * One of the seven is committed now: field-38 set 4 is
 * `field-ohp-3010-8rep-s38-set04-cues.csv`, added on this branch for issue
 * #72. Its rows reproduce `ohp3010x8` second for second under the PRE-#243
 * schedule -- every `Rep n` one lower (`Rep 1` at 0:05 where the string below
 * has `Rep 2`, through `Rep 6` at 0:25), no `Last rep` at 0:29, and a tempo
 * count `1` at 0:30 that the restored `Last rep` displaces. `Done` is at 0:32
 * on both. The committed track also carries the two prep rows these strings
 * begin after, `Ready` at -0:02 and `Brace` at -0:01. Read against the
 * `work_started_ms` of 1788516049461 in that set's own `_prep.csv`, which is
 * not committed.
 *
 * Each row of a track was taken as `(timestamp_ms - workStartedAt_ms) / 1000`
 * and rounded to the nearest second; the worst deviation from a whole second
 * across all seven tracks is 0.051 s, on the 48-second pushdown, so the
 * rounding is exact rather than lossy. Each set's `LiftDirection` is its own
 * `meta.json` row -- `startsWith`, `concentric`, `plane`, `sensorOnStack`,
 * `sensorInverted` -- so these are the plans those sets were paced on.
 *
 * The two 8-rep overhead-press tracks are the same shape on both app versions,
 * which is why one row stands for both: the schedule this file changes is not
 * a 0.1.50 regression.
 *
 * ## What the tracks recorded, and what these strings now say
 *
 * The commit before this one pinned the tracks as recorded, and they showed
 * three facts on every set with a plan:
 *
 * 1. the last NUMBERED call was `planned - 2` -- `Rep 6` on an eight-rep set;
 * 2. `Last rep` replaced the call for `planned - 1` where it was spoken at all,
 *    and was withheld on four of these six plans (#173);
 * 3. the final rep was never named, and on those four plans nothing at all was
 *    said about it before `Done`.
 *
 * The owner's report is the same three facts heard from the bench: *"I think
 * the counter might be wrong. It seems to end one early and not state last
 * rep."*
 *
 * The strings below are those tracks with the schedule #243 and #293 ask for.
 * #243 moved WHICH rep a call names -- every `Rep n` became `Rep n+1`, and
 * `Last rep` is spoken on the final rep of every plan with a beat for it. #293
 * moves WHERE it lands: onto the first second of the rep it names, in place of
 * that rep's first stroke word.
 *
 * An earlier version of this paragraph said "NOTHING moves in time: the seconds
 * of every stroke, hold, count and `Done` are the seconds the archives
 * recorded." The first clause is deleted: the CALLS move in time on five of
 * these six plans, by one or two seconds, onto the start of their own rep. What
 * still holds, and is the obligation `CadencePlanTest` states over every tempo
 * any plan can express, is that no BEAT moves: every stroke, hold and `Done`
 * lands on the second the archives recorded, and the delivered cycle is the
 * prescription's.
 *
 * Two rows per rep change instead of one. The stroke word the number replaces is
 * not spoken -- so a `Down` or an `Up` leaves each of these tracks on every rep
 * after the first -- and the tempo count that stroke used to give up comes back,
 * renumbered so the count continues from the number: the owner's own example is
 * "a 3010 press goes Rep 3, 2, 3, Up instead of Down, rep three, 2, Up".
 *
 * ## Rep 1 is announced on no plan, and #293 does not change that
 *
 * The reason did change, and the old one is deleted rather than kept beside the
 * new one. It used to be that a call rode either the previous rep's closing
 * pause -- which rep 1 does not have -- or a stroke with a tempo count to give
 * up, which would have cost rep 1 the only count some plans have (#147).
 * Neither is true now: the call takes the opening stroke's WORD, every rep has
 * one, and nothing is given up. Three reasons stand in their place.
 *
 * 1. Rep 1's start is not in doubt. It follows the prep countdown, whose last
 *    words are `Ready` and `Brace`, and the stroke word after them IS rep 1
 *    beginning.
 * 2. `StartCuePolicy` shows that word on the screen through the whole prep and
 *    `StartCueVoiceContractTest` pins that the guide then SAYS it (#241).
 *    Announcing rep 1 would replace the one utterance that contract is about.
 * 3. It keeps one first-stroke word per set in the record. `Down` and `Up` are
 *    the only discriminator a reader has between a guided track and the unguided
 *    counter's (`CueTrackOriginTest`), and a `Rep 1` row would additionally
 *    collide with the pre-1.19 archives, where `Rep 1` is what the guide said as
 *    rep 1 FINISHED.
 */
class RepCallScheduleTest {
    /** field-38 set 1: dumbbell_incline_press, ecc-first, drive up, vertical. */
    private val inclinePress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-37 set 5: bench_press, ecc-first, drive up, vertical. */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-38 set 4 and field-37 set 1: seated_overhead_press, CONC-first, drive up, vertical. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** field-38 sets 6 and 10: fly and biceps curl, conc-first, drive up, vertical. */
    private val concFirstUp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** field-38 set 12: triceps_pushdown, conc-first, drive DOWN, vertical, on-stack, inverted. */
    private val pushdown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** One argument per rep of the set, joined into the whole track. */
    private fun spoken(vararg reps: String) = reps.joinToString(" ")

    private val bench3010x6 = spoken(
        "0:Down 1:1 2:2 3:Up",
        "4:Rep 2 5:2 6:3 7:Up",
        "8:Rep 3 9:2 10:3 11:Up",
        "12:Rep 4 13:2 14:3 15:Up",
        "16:Rep 5 17:2 18:3 19:Up",
        "20:Last rep 21:2 22:3 23:Up",
        "24:Done",
    )

    private val ohp3010x8 = spoken(
        "0:Up 1:Down 2:1 3:2",
        "4:Rep 2 5:Down 6:1 7:2",
        "8:Rep 3 9:Down 10:1 11:2",
        "12:Rep 4 13:Down 14:1 15:2",
        "16:Rep 5 17:Down 18:1 19:2",
        "20:Rep 6 21:Down 22:1 23:2",
        "24:Rep 7 25:Down 26:1 27:2",
        "28:Last rep 29:Down 30:1 31:2",
        "32:Done",
    )

    private val incline3010x10 = spoken(
        "0:Down 1:1 2:2 3:Up",
        "4:Rep 2 5:2 6:3 7:Up",
        "8:Rep 3 9:2 10:3 11:Up",
        "12:Rep 4 13:2 14:3 15:Up",
        "16:Rep 5 17:2 18:3 19:Up",
        "20:Rep 6 21:2 22:3 23:Up",
        "24:Rep 7 25:2 26:3 27:Up",
        "28:Rep 8 29:2 30:3 31:Up",
        "32:Rep 9 33:2 34:3 35:Up",
        "36:Last rep 37:2 38:3 39:Up",
        "40:Done",
    )

    private val fly2011x12 = spoken(
        "0:Up 1:Hold 2:Down 3:1",
        "4:Rep 2 5:Hold 6:Down 7:1",
        "8:Rep 3 9:Hold 10:Down 11:1",
        "12:Rep 4 13:Hold 14:Down 15:1",
        "16:Rep 5 17:Hold 18:Down 19:1",
        "20:Rep 6 21:Hold 22:Down 23:1",
        "24:Rep 7 25:Hold 26:Down 27:1",
        "28:Rep 8 29:Hold 30:Down 31:1",
        "32:Rep 9 33:Hold 34:Down 35:1",
        "36:Rep 10 37:Hold 38:Down 39:1",
        "40:Rep 11 41:Hold 42:Down 43:1",
        "44:Last rep 45:Hold 46:Down 47:1",
        "48:Done",
    )

    private val curl2010x12 = spoken(
        "0:Up 1:Down 2:1",
        "3:Rep 2 4:Down 5:1",
        "6:Rep 3 7:Down 8:1",
        "9:Rep 4 10:Down 11:1",
        "12:Rep 5 13:Down 14:1",
        "15:Rep 6 16:Down 17:1",
        "18:Rep 7 19:Down 20:1",
        "21:Rep 8 22:Down 23:1",
        "24:Rep 9 25:Down 26:1",
        "27:Rep 10 28:Down 29:1",
        "30:Rep 11 31:Down 32:1",
        "33:Last rep 34:Down 35:1",
        "36:Done",
    )

    private val pushdown1120x12 = spoken(
        "0:Down 1:Hold 2:Up 3:1",
        "4:Rep 2 5:Hold 6:Up 7:1",
        "8:Rep 3 9:Hold 10:Up 11:1",
        "12:Rep 4 13:Hold 14:Up 15:1",
        "16:Rep 5 17:Hold 18:Up 19:1",
        "20:Rep 6 21:Hold 22:Up 23:1",
        "24:Rep 7 25:Hold 26:Up 27:1",
        "28:Rep 8 29:Hold 30:Up 31:1",
        "32:Rep 9 33:Hold 34:Up 35:1",
        "36:Rep 10 37:Hold 38:Up 39:1",
        "40:Rep 11 41:Hold 42:Up 43:1",
        "44:Last rep 45:Hold 46:Up 47:1",
        "48:Done",
    )

    private data class Row(
        val tempo: String,
        val direction: LiftDirection,
        val reps: Int,
        val capture: String,
        val track: String,
    )

    private val corpus = listOf(
        Row("3010", benchPress, 6, "field-37 set 5", bench3010x6),
        Row("3010", seatedOhp, 8, "field-38 set 4, field-37 set 1", ohp3010x8),
        Row("3010", inclinePress, 10, "field-38 set 1", incline3010x10),
        Row("2011", concFirstUp, 12, "field-38 set 6", fly2011x12),
        Row("2010", concFirstUp, 12, "field-38 set 10", curl2010x12),
        Row("1120", pushdown, 12, "field-38 set 12", pushdown1120x12),
    )

    private fun plan(row: Row) = CadencePlan.of(TempoSchedule.of(Tempo.parse(row.tempo), row.direction))

    /** Every cue row the guide writes, as `second:row`, in order. */
    private fun rendered(row: Row) = CadenceVoice.script(plan(row), row.reps)
        .flatMap { call -> call.recorded.map { "${call.atSecond}:$it" } }
        .joinToString(" ")

    private fun calls(row: Row) = CadenceVoice.script(plan(row), row.reps)
        .flatMap { it.recorded }
        .filter { it == CadencePlan.LAST_REP || it.startsWith(CadencePlan.REP_CALL_PREFIX) }

    @Test
    fun `the guide names the rep it is calling for, on all six plans these sessions ran`() {
        corpus.forEach { row ->
            assertEquals(row.track, rendered(row), "${row.capture}: ${row.tempo}, ${row.reps} planned")
        }
    }

    @Test
    fun `the numbered calls run to the rep before the last, with none left over`() {
        // The report's first fact, answered. On a set of twelve the last number
        // is "Rep 11" and it opens the eleventh rep, rather than arriving after
        // it or partway through it. Rep 1 is silent: see the class KDoc for why
        // it is not announced on any plan rather than on some.
        corpus.forEach { row ->
            assertEquals(
                (2..row.reps - 1).map { "${CadencePlan.REP_CALL_PREFIX}$it" },
                calls(row).filter { it != CadencePlan.LAST_REP },
                "${row.capture}: the numbered calls of a ${row.reps}-rep set",
            )
        }
    }

    @Test
    fun `the last rep is named on every one of these plans, exactly once`() {
        // The report's second and third facts, answered together. #173 withheld
        // the warning on four of these six; it is spoken on all six now,
        // because under this schedule it names the rep the lifter is in rather
        // than one still to come.
        corpus.forEach { row ->
            assertEquals(
                1,
                calls(row).count { it == CadencePlan.LAST_REP },
                "${row.capture}: the final rep is named once and only once",
            )
        }
    }

    @Test
    fun `every call of every one of these six plans opens the rep it names`() {
        // #293 over the plans two sessions actually ran, as a rule rather than
        // six strings: the nth call is the first row of the nth rep. Five of the
        // six moved for it -- the incline press already opened its rep -- and
        // the seconds the calls used to land on are in the strings above, which
        // the previous commit pinned against the tracks themselves.
        corpus.forEach { row ->
            val p = plan(row)
            val rows = CadenceVoice.script(p, row.reps)
                .flatMap { call -> call.recorded.map { call.atSecond to it } }
            val calls = rows.filter { it.second == CadencePlan.LAST_REP || it.second.startsWith("Rep ") }
            assertEquals(row.reps - 1, calls.size, "${row.capture}: one call per rep after the first")
            calls.forEachIndexed { index, (second, label) ->
                assertEquals(
                    (index + 1) * p.deliveredCycleS,
                    second,
                    "${row.capture}: $label opens the rep it names",
                )
                assertEquals(
                    second to label,
                    rows.first { it.first >= second },
                    "${row.capture}: and is the first thing said in that rep",
                )
            }
        }
    }

    @Test
    fun `no call names a rep that is over, and none names one past the plan`() {
        // The two ways this schedule could still be wrong by one, asserted
        // rather than argued: nothing says "Rep 1" (which would be the finished
        // count returning) and nothing says "Rep planned" (which "Last rep"
        // stands in for).
        corpus.forEach { row ->
            val spoken = calls(row)
            assertEquals(
                emptyList(),
                spoken.filter { it == "${CadencePlan.REP_CALL_PREFIX}1" },
                "${row.capture}: a call naming rep 1 is the finished count come back",
            )
            assertEquals(
                emptyList(),
                spoken.filter { it == "${CadencePlan.REP_CALL_PREFIX}${row.reps}" },
                "${row.capture}: the final rep is named by word, never by number",
            )
        }
    }
}
