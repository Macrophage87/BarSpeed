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
 * The strings below are those tracks with the schedule #243 asks for: the call
 * names the rep it is calling FOR. Two things move and nothing else does --
 * every `Rep n` becomes `Rep n+1`, and `Last rep` is spoken on the final rep of
 * every plan that has a beat for it, on the same beat it would have used.
 * NOTHING moves in time: the seconds of every stroke, hold, count and `Done`
 * are the seconds the archives recorded. The only rows that appear or vanish
 * are the four restored `Last rep` calls and the four tempo counts the strokes
 * carrying them give up again.
 *
 * Rep 1 is announced on no plan, and that is deliberate rather than an
 * oversight. On the plans that carry a call in the previous rep's closing pause
 * there is no beat before the first rep to carry one, so announcing rep 1 would
 * be possible on some tempo families and impossible on others; and on the
 * families where it IS possible it would cost rep 1 the only tempo count those
 * plans have (#147). Silence on rep 1 is not a wrong number, so the report's
 * "one early" is answered without it.
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
        "4:Down 4:Rep 2 6:2 7:Up",
        "8:Down 8:Rep 3 10:2 11:Up",
        "12:Down 12:Rep 4 14:2 15:Up",
        "16:Down 16:Rep 5 18:2 19:Up",
        "20:Down 20:Last rep 22:2 23:Up",
        "24:Done",
    )

    private val ohp3010x8 = spoken(
        "0:Up 1:Down 2:1 3:2",
        "4:Up 5:Down 5:Rep 2 7:2",
        "8:Up 9:Down 9:Rep 3 11:2",
        "12:Up 13:Down 13:Rep 4 15:2",
        "16:Up 17:Down 17:Rep 5 19:2",
        "20:Up 21:Down 21:Rep 6 23:2",
        "24:Up 25:Down 25:Rep 7 27:2",
        "28:Up 29:Down 29:Last rep 31:2",
        "32:Done",
    )

    private val incline3010x10 = spoken(
        "0:Down 1:1 2:2 3:Up",
        "4:Down 4:Rep 2 6:2 7:Up",
        "8:Down 8:Rep 3 10:2 11:Up",
        "12:Down 12:Rep 4 14:2 15:Up",
        "16:Down 16:Rep 5 18:2 19:Up",
        "20:Down 20:Rep 6 22:2 23:Up",
        "24:Down 24:Rep 7 26:2 27:Up",
        "28:Down 28:Rep 8 30:2 31:Up",
        "32:Down 32:Rep 9 34:2 35:Up",
        "36:Down 36:Last rep 38:2 39:Up",
        "40:Done",
    )

    private val fly2011x12 = spoken(
        "0:Up 1:Hold 2:Down 3:1",
        "4:Up 5:Hold 6:Down 6:Rep 2",
        "8:Up 9:Hold 10:Down 10:Rep 3",
        "12:Up 13:Hold 14:Down 14:Rep 4",
        "16:Up 17:Hold 18:Down 18:Rep 5",
        "20:Up 21:Hold 22:Down 22:Rep 6",
        "24:Up 25:Hold 26:Down 26:Rep 7",
        "28:Up 29:Hold 30:Down 30:Rep 8",
        "32:Up 33:Hold 34:Down 34:Rep 9",
        "36:Up 37:Hold 38:Down 38:Rep 10",
        "40:Up 41:Hold 42:Down 42:Rep 11",
        "44:Up 45:Hold 46:Down 46:Last rep",
        "48:Done",
    )

    private val curl2010x12 = spoken(
        "0:Up 1:Down 2:1",
        "3:Up 4:Down 4:Rep 2",
        "6:Up 7:Down 7:Rep 3",
        "9:Up 10:Down 10:Rep 4",
        "12:Up 13:Down 13:Rep 5",
        "15:Up 16:Down 16:Rep 6",
        "18:Up 19:Down 19:Rep 7",
        "21:Up 22:Down 22:Rep 8",
        "24:Up 25:Down 25:Rep 9",
        "27:Up 28:Down 28:Rep 10",
        "30:Up 31:Down 31:Rep 11",
        "33:Up 34:Down 34:Last rep",
        "36:Done",
    )

    private val pushdown1120x12 = spoken(
        "0:Down 1:Hold 2:Up 3:1",
        "4:Down 5:Hold 6:Up 6:Rep 2",
        "8:Down 9:Hold 10:Up 10:Rep 3",
        "12:Down 13:Hold 14:Up 14:Rep 4",
        "16:Down 17:Hold 18:Up 18:Rep 5",
        "20:Down 21:Hold 22:Up 22:Rep 6",
        "24:Down 25:Hold 26:Up 26:Rep 7",
        "28:Down 29:Hold 30:Up 30:Rep 8",
        "32:Down 33:Hold 34:Up 34:Rep 9",
        "36:Down 37:Hold 38:Up 38:Rep 10",
        "40:Down 41:Hold 42:Up 42:Rep 11",
        "44:Down 45:Hold 46:Up 46:Last rep",
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
        // is "Rep 11" and it is spoken during the eleventh rep, not after it.
        // Rep 1 is silent: see the class KDoc for why it is not announced on
        // any plan rather than on some.
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
