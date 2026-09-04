package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which rep the guide names, and when, on the six (tempo, lift) pairs two field
 * sessions actually ran -- pinned as the schedule behaves TODAY (issue #243).
 *
 * ## Provenance
 *
 * The expected strings below are not constructed. They are the cue tracks of
 * seven guided sets, read from `_cues.csv` in two capture archives that are not
 * committed to this repository:
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
 * Each row of a track was taken as `(timestamp_ms - workStartedAt_ms) / 1000`
 * and rounded to the nearest second; the worst deviation from a whole second
 * across all seven tracks is 0.051 s, on the 48-second pushdown, so the
 * rounding is exact rather than lossy. Each set's `LiftDirection` is its own
 * `meta.json` row -- `startsWith`, `concentric`, `plane`, `sensorOnStack`,
 * `sensorInverted` -- so these are the plans those sets were paced on.
 *
 * The two 8-rep overhead-press tracks are the same shape on both app versions,
 * which is why one row stands for both: this schedule is not a 0.1.50
 * regression.
 *
 * ## What the tracks show, and what this class asserts
 *
 * One line per rep, so the schedule is readable down the page. Three facts hold
 * on every set:
 *
 * 1. the last NUMBERED call is `planned - 2`;
 * 2. `Last rep` replaces the call for `planned - 1` where it is spoken at all,
 *    and is withheld on four of these six plans (#173);
 * 3. the final rep is never named by number, and on the four withheld plans
 *    nothing at all is said about it before `Done`.
 *
 * The owner's report is the same three facts heard from the bench: *"I think
 * the counter might be wrong. It seems to end one early and not state last
 * rep."*
 *
 * These are CHARACTERIZATION pins. They record the schedule as shipped so that
 * changing it is a visible diff rather than a claim, and they are expected to
 * be rewritten by the change that answers #243.
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
        "4:Down 4:Rep 1 6:2 7:Up",
        "8:Down 8:Rep 2 10:2 11:Up",
        "12:Down 12:Rep 3 14:2 15:Up",
        "16:Down 16:Rep 4 18:2 19:Up",
        "20:Down 20:Last rep 22:2 23:Up",
        "24:Done",
    )

    private val ohp3010x8 = spoken(
        "0:Up 1:Down 2:1 3:2",
        "4:Up 5:Down 5:Rep 1 7:2",
        "8:Up 9:Down 9:Rep 2 11:2",
        "12:Up 13:Down 13:Rep 3 15:2",
        "16:Up 17:Down 17:Rep 4 19:2",
        "20:Up 21:Down 21:Rep 5 23:2",
        "24:Up 25:Down 25:Rep 6 27:2",
        "28:Up 29:Down 30:1 31:2",
        "32:Done",
    )

    private val incline3010x10 = spoken(
        "0:Down 1:1 2:2 3:Up",
        "4:Down 4:Rep 1 6:2 7:Up",
        "8:Down 8:Rep 2 10:2 11:Up",
        "12:Down 12:Rep 3 14:2 15:Up",
        "16:Down 16:Rep 4 18:2 19:Up",
        "20:Down 20:Rep 5 22:2 23:Up",
        "24:Down 24:Rep 6 26:2 27:Up",
        "28:Down 28:Rep 7 30:2 31:Up",
        "32:Down 32:Rep 8 34:2 35:Up",
        "36:Down 36:Last rep 38:2 39:Up",
        "40:Done",
    )

    private val fly2011x12 = spoken(
        "0:Up 1:Hold 2:Down 3:1",
        "4:Up 5:Hold 6:Down 6:Rep 1",
        "8:Up 9:Hold 10:Down 10:Rep 2",
        "12:Up 13:Hold 14:Down 14:Rep 3",
        "16:Up 17:Hold 18:Down 18:Rep 4",
        "20:Up 21:Hold 22:Down 22:Rep 5",
        "24:Up 25:Hold 26:Down 26:Rep 6",
        "28:Up 29:Hold 30:Down 30:Rep 7",
        "32:Up 33:Hold 34:Down 34:Rep 8",
        "36:Up 37:Hold 38:Down 38:Rep 9",
        "40:Up 41:Hold 42:Down 42:Rep 10",
        "44:Up 45:Hold 46:Down 47:1",
        "48:Done",
    )

    private val curl2010x12 = spoken(
        "0:Up 1:Down 2:1",
        "3:Up 4:Down 4:Rep 1",
        "6:Up 7:Down 7:Rep 2",
        "9:Up 10:Down 10:Rep 3",
        "12:Up 13:Down 13:Rep 4",
        "15:Up 16:Down 16:Rep 5",
        "18:Up 19:Down 19:Rep 6",
        "21:Up 22:Down 22:Rep 7",
        "24:Up 25:Down 25:Rep 8",
        "27:Up 28:Down 28:Rep 9",
        "30:Up 31:Down 31:Rep 10",
        "33:Up 34:Down 35:1",
        "36:Done",
    )

    private val pushdown1120x12 = spoken(
        "0:Down 1:Hold 2:Up 3:1",
        "4:Down 5:Hold 6:Up 6:Rep 1",
        "8:Down 9:Hold 10:Up 10:Rep 2",
        "12:Down 13:Hold 14:Up 14:Rep 3",
        "16:Down 17:Hold 18:Up 18:Rep 4",
        "20:Down 21:Hold 22:Up 22:Rep 5",
        "24:Down 25:Hold 26:Up 26:Rep 6",
        "28:Down 29:Hold 30:Up 30:Rep 7",
        "32:Down 33:Hold 34:Up 34:Rep 8",
        "36:Down 37:Hold 38:Up 38:Rep 9",
        "40:Down 41:Hold 42:Up 42:Rep 10",
        "44:Down 45:Hold 46:Up 47:1",
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
    fun `the script reproduces every cue track these two sessions recorded`() {
        corpus.forEach { row ->
            assertEquals(row.track, rendered(row), "${row.capture}: ${row.tempo}, ${row.reps} planned")
        }
    }

    @Test
    fun `the last numbered call is two short of the plan`() {
        // Fact 1 of the report. The lifter's last number on a set of twelve is
        // "Rep 10", and they have two reps still to perform when they hear it.
        corpus.forEach { row ->
            assertEquals(
                (1..row.reps - 2).map { "${CadencePlan.REP_CALL_PREFIX}$it" },
                calls(row).filter { it != CadencePlan.LAST_REP },
                "${row.capture}: the numbered calls of a ${row.reps}-rep set",
            )
        }
    }

    @Test
    fun `the last-rep warning is spoken on two of these six plans and withheld on four`() {
        // Fact 2, and #173's rule as the field hears it: the same four digits
        // warn on an eccentric-first press and say nothing on a concentric-first
        // one. Written out per row rather than derived, so this table and the
        // production rule cannot agree by sharing an expression.
        val warned = mapOf(
            "field-37 set 5" to true,
            "field-38 set 4, field-37 set 1" to false,
            "field-38 set 1" to true,
            "field-38 set 6" to false,
            "field-38 set 10" to false,
            "field-38 set 12" to false,
        )
        corpus.forEach { row ->
            assertEquals(
                warned.getValue(row.capture),
                calls(row).contains(CadencePlan.LAST_REP),
                "${row.capture}: whether the guide says Last rep at all",
            )
        }
    }

    @Test
    fun `no call of any kind names the final rep`() {
        // Fact 3, and the one the owner reports as the count ending early. The
        // warning that IS spoken replaces the call for rep planned-1, so on
        // every one of these sets the number `planned` and the number
        // `planned - 1` are both unspoken.
        corpus.forEach { row ->
            val spoken = calls(row)
            assertEquals(
                emptyList(),
                spoken.filter { it == "${CadencePlan.REP_CALL_PREFIX}${row.reps}" },
                "${row.capture}: the final rep is never named by number",
            )
            assertEquals(
                emptyList(),
                spoken.filter { it == "${CadencePlan.REP_CALL_PREFIX}${row.reps - 1}" },
                "${row.capture}: nor is the one before it, which Last rep replaces",
            )
        }
    }
}
