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
