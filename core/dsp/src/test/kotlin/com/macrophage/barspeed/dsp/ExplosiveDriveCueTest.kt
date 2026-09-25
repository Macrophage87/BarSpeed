package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the guide says on a stroke a tempo prescribes as `X` (#264).
 *
 * The complaint, measured on field-39 (app 0.1.50): sets 2 (`2010`), 6 and 10
 * (`20X0`) delivered the same cue words at the same seconds, row for row. An X
 * stroke has no prescribed seconds, `CadencePlan.strokeSeconds` plays it as a
 * one-second beat, and the beat carried the stroke's direction word -- so the
 * one tempo whose job is to NOT pace the drive sounded exactly like a paced
 * one-second drive.
 *
 * ## CHARACTERIZATION: what this file pins before the change
 *
 * Three things the change must leave alone, each green before it and after it.
 *
 * 1. **The slot.** The X stroke keeps its one-second beat on every lift, so a
 *    `20X0` rep is the same seconds as a `2010` rep. No beat is added, dropped
 *    or moved; `CadencePlanTest` holds that obligation over 1,380 pairs and
 *    this file only states it for the pair the issue is about.
 * 2. **An X that is the RETURN.** On a drive-down vertical lift -- a pulldown,
 *    a leg curl -- digit 3 is the eccentric, so a `30X0` there prescribes a
 *    fast return, not an explosive drive, and `VelocityLossRegime` already
 *    reads such a set as controlled. It keeps its direction word, `Up`.
 * 3. **Horizontal work.** A seated row's drive is already called `Drive` on
 *    every tempo, so on horizontal work `20X0` and `2010` are STILL the same
 *    audio. That is pinned as it is, not as anyone wants it: the word the
 *    round chose cannot distinguish them on that plane, and this pin is what
 *    reds if a later change does.
 *
 * ## The change: an X DRIVE is called `Drive`
 *
 * The word the prep countdown and the horizontal guide already use for the
 * working stroke, so the lifter hears one vocabulary. It carries no count, as
 * the one-second beat never did. Where the lifter HEARS it depends on which
 * stroke opens the rep and on where the plan names the rep, and neither rule
 * is touched:
 *
 * - eccentric-first (a bench press): the X drive is the SECOND stroke, so
 *   `Drive` is said on every rep;
 * - concentric-first (field-39's seated press and pulldown), on a plan that
 *   names the rep at the rep's start (#293) -- a `20X0` does, because its
 *   two-second lowering has room for the call: the X drive IS the first
 *   stroke, so `Drive` is said once, opening rep 1, and from rep 2 the number
 *   takes its second. On that geometry 20X0 and 2010 now differ by that one
 *   row per set and no other;
 * - concentric-first on a plan that names the rep at the END of the drive
 *   (#266) -- a `10X0` does, because two one-second strokes and no closing
 *   pause leave no second at the rep's start: the number takes the lowering's
 *   `Down`, so `Drive` is said on every rep.
 */
class ExplosiveDriveCueTest {
    /** field-39 sets 2 and 6: seated overhead press, concentric-first, drive up. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    /** An eccentric-first press: the X stroke is the rep's SECOND stroke. */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** Drive DOWN, concentric-first: digit 3 is the return up. */
    private val pulldown = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = false)

    /** Horizontal, concentric-first: called by phase, Drive and Return. */
    private val seatedRow = LiftDirection(startsWith = StartPhase.CONCENTRIC, plane = MovementPlane.HORIZONTAL)

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    private fun script(tempo: String, direction: LiftDirection, reps: Int) =
        CadenceVoice.script(plan(tempo, direction), reps).map { it.atSecond to it.utterance }

    @Test
    fun `the X stroke keeps its one-second slot, so 20X0 plays the seconds 2010 plays`() {
        for (lift in listOf(seatedOhp, benchPress, pulldown, seatedRow)) {
            val explosive = plan("20X0", lift)
            val paced = plan("2010", lift)
            assertEquals(
                paced.beats.map { it.seconds },
                explosive.beats.map { it.seconds },
                "$lift: every beat of 20X0 is as long as the same beat of 2010",
            )
            assertEquals(3, explosive.deliveredCycleS, "$lift: a 20X0 rep is three seconds of cadence")
            assertEquals(paced.announceOnBeat, explosive.announceOnBeat, "$lift: the rep call rides the same beat")
            assertEquals(
                paced.repCompleteAfterBeat,
                explosive.repCompleteAfterBeat,
                "$lift: the rep completes on the same beat",
            )
        }
    }

    /**
     * What a lifter hears on a 20X0 bench press, second by second. Before #264
     * the six `Drive` rows below read `Up` -- seconds 2, 5, 8, 11, 14 and 17 --
     * and the list was otherwise the same, which is the `2010` bench press's.
     * `Last rep` still opens the final rep, in place of its `Down`.
     */
    @Test
    fun `a 20X0 bench press says Drive on the X stroke of every rep`() {
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "Drive",
                3 to "Rep 2", 4 to "2", 5 to "Drive",
                6 to "Rep 3", 7 to "2", 8 to "Drive",
                9 to "Rep 4", 10 to "2", 11 to "Drive",
                12 to "Rep 5", 13 to "2", 14 to "Drive",
                15 to "Last rep", 16 to "2", 17 to "Drive",
                18 to "Done",
            ),
            script("20X0", benchPress, 6),
        )
    }

    /**
     * Field-39 set 6's geometry and tempo, `20X0`. `Drive` is heard once, on
     * rep 1's opening second, and never again: from rep 2 the rep number takes
     * that second (#293), and `Last rep` takes it on the sixth. Before #264 the
     * one row read `Up`. Not every tempo on this geometry does this: see the
     * `10X0` pin below.
     */
    @Test
    fun `where a 20X0 drive opens the rep, Drive opens rep 1 and the rep number takes it after`() {
        val rows = script("20X0", seatedOhp, 6)
        assertEquals(listOf(0 to "Drive"), rows.filter { it.second == "Drive" }, "Drive is said once per set here")
        assertEquals(
            listOf(3 to "Rep 2", 6 to "Rep 3", 9 to "Rep 4", 12 to "Rep 5", 15 to "Last rep"),
            rows.filter { it.first in 3..15 && it.first % 3 == 0 },
            "each later rep's opening second carries its number, as on every plan with a two-second stroke",
        )
        assertEquals(listOf<Pair<Int, String>>(), rows.filter { it.second == "Up" }, "and Up is said nowhere")
    }

    /**
     * The same geometry at `10X0`. Two one-second strokes and no closing pause
     * leave no second at the rep's start for the call, so `CadencePlan.of` puts
     * it at the end of the drive (#266), on the lowering's beat. The drive's
     * own beat is never replaced, so `Drive` is said on every rep -- a sentence
     * saying "once per set" for every drive-first lift was false on exactly
     * this plan (#264 round 1).
     */
    @Test
    fun `a 10X0 seated press names the rep at the drive's end, so Drive is said every rep`() {
        assertEquals(
            listOf(
                0 to "Drive", 1 to "Rep 1",
                2 to "Drive", 3 to "Rep 2",
                4 to "Drive", 5 to "Last rep",
                6 to "Done",
            ),
            script("10X0", seatedOhp, 3),
        )
        assertEquals(1, plan("10X0", seatedOhp).announceOnBeat, "the call rides the lowering's beat")
    }

    @Test
    fun `an X on a pulldown's return keeps its Up`() {
        assertEquals(
            listOf(
                0 to "Down", 1 to "1", 2 to "2", 3 to "Up",
                4 to "Last rep", 5 to "2", 6 to "3", 7 to "Up",
                8 to "Done",
            ),
            script("30X0", pulldown, 2),
            "30X0 on a drive-down lift: the X is the eccentric, and a return is not a drive",
        )
    }

    @Test
    fun `on horizontal work 20X0 and 2010 are still the same audio`() {
        val expected = listOf(
            0 to "Drive",
            1 to "Return",
            2 to "1",
            3 to "Last rep",
            4 to "Return",
            5 to "1",
            6 to "Done",
        )
        assertEquals(expected, script("20X0", seatedRow, 2), "a seated row's X drive")
        assertEquals(expected, script("2010", seatedRow, 2), "a seated row's one-second drive")
    }
}
