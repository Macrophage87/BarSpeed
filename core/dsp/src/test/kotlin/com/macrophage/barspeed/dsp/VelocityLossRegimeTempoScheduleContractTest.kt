package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VelocityLossRegime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two statements of "which digit is the concentric", pinned equal
 * (round 1 finding 2 on #250).
 *
 * `TempoSchedule.of` in this module and `VelocityLossRegime.of` in
 * `:core:model` both have to answer it, and they answered it differently: the
 * schedule reads the PLANE first and falls through to the drive direction only
 * on vertical work, while the regime read the direction alone. Nothing could
 * see the disagreement, because no test until this one could see both -- the
 * regime lives in a module that cannot import `TempoSchedule`, and this module
 * is the one that depends on both.
 *
 * The invariant is exact rather than approximate, which is what makes it
 * worth pinning: on a DYNAMIC set carrying a parseable tempo, the regime is
 * `MAX_INTENT` if and only if the schedule's concentric stroke has no
 * prescribed seconds. Both sentences are the same sentence -- "the drive's
 * speed is not prescribed" -- said in two modules.
 *
 * The same shape as `StartCueVoiceContractTest`, which is in this module for
 * the same reason.
 */
class VelocityLossRegimeTempoScheduleContractTest {
    // X010 and X0X0 since #258 (plan schema 1.15): digit 1 can be explosive,
    // which is the drive on a vertical concentric-down lift and the lowering
    // on every other shape, so the invariant has a new place to break.
    private val tempos = listOf("3010", "1030", "30X0", "2011", "0000", "X010", "X0X0")

    @Test
    fun `the regime is max intent exactly when the schedule's concentric has no prescribed seconds`() {
        var rows = 0
        for (text in tempos) {
            val tempo = Tempo.parse(text)
            for (concentricUp in listOf(true, false)) {
                for (plane in MovementPlane.entries) {
                    for (startsWith in StartPhase.entries) {
                        val direction =
                            LiftDirection(startsWith = startsWith, concentricUp = concentricUp, plane = plane)
                        val schedule = TempoSchedule.of(tempo, direction)
                        val regime =
                            VelocityLossRegime.of(
                                tempoPrescribed = text,
                                concentricUp = concentricUp,
                                horizontal = plane == MovementPlane.HORIZONTAL,
                                kind = ExerciseKind.DYNAMIC,
                            )
                        assertEquals(
                            schedule.concentricS == null,
                            regime == VelocityLossRegime.MAX_INTENT,
                            "$text, drive ${if (concentricUp) "up" else "down"}, $plane, starts $startsWith: " +
                                "the guide gives a concentric of ${schedule.concentricS} s " +
                                "and the regime says $regime",
                        )
                        rows++
                    }
                }
            }
        }
        assertEquals(56, rows, "the cross product lost rows, so this pin covers less than it reads as covering")
    }

    /**
     * The two #258 rows asserted by name. A pulldown at `X010` drives down as
     * fast as it can, so the guide gives its concentric no seconds and the
     * regime reads it as max intent; a bench press at the same `X010` lowers
     * explosively and drives in one second, which is a controlled drive, and
     * reading it as max intent would grade a prescribed one-second press as
     * if the lifter had been told to go flat out.
     */
    @Test
    fun `an explosive digit 1 is max intent on a pulldown and controlled on a bench press`() {
        val pulldown = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = false)
        assertEquals(null, TempoSchedule.of(Tempo.parse("X010"), pulldown).concentricS)
        assertEquals(
            VelocityLossRegime.MAX_INTENT,
            VelocityLossRegime.of("X010", concentricUp = false, horizontal = false, kind = ExerciseKind.DYNAMIC),
        )
        val bench = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)
        assertEquals(1.0, TempoSchedule.of(Tempo.parse("X010"), bench).concentricS)
        assertEquals(
            VelocityLossRegime.CONTROLLED,
            VelocityLossRegime.of("X010", concentricUp = true, horizontal = false, kind = ExerciseKind.DYNAMIC),
        )
    }

    /**
     * The row the disagreement actually lived on, asserted on its own so a
     * failure names it rather than naming a loop iteration.
     *
     * A chest-supported row declared `plane: horizontal` with
     * `concentric: down` -- two independent plan keys, both stored as written
     * -- prescribed `30X0`. The guide calls digit 3 the DRIVE and gives it no
     * count; the regime called the set controlled and the history card
     * withheld its velocity pill.
     */
    @Test
    fun `a horizontal 30X0 set is an explosive drive to the guide and to the regime alike`() {
        val direction = LiftDirection(concentricUp = false, plane = MovementPlane.HORIZONTAL)
        val schedule = TempoSchedule.of(Tempo.parse("30X0"), direction)
        val drive = listOf(schedule.first, schedule.second).single { it.isConcentric }
        assertEquals("DRIVE", drive.label, "the concentric stroke is the one the guide calls DRIVE")
        assertEquals(null, drive.seconds, "the guide prescribes no seconds for this drive")
        assertEquals(
            VelocityLossRegime.MAX_INTENT,
            VelocityLossRegime.of("30X0", concentricUp = false, horizontal = true, kind = ExerciseKind.DYNAMIC),
        )
    }
}
