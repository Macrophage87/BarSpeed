package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.GuidedRepCaption
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The number the ring shows is the number the voice is saying (#252).
 *
 * `CadencePlan.announcementFor` decides what the guide calls the rep in hand;
 * `GuidedRepCaption.forRing` decides what the screen calls it. They are in two
 * modules -- the caption is in `:core:model`, which cannot see `CadencePlan` --
 * so nothing but a test on this side can hold them together, and before #243
 * nothing had to: both counted finished reps. #243 moved one of them.
 *
 * The comparison is the leading phrase of each, which is where the rep is
 * named: `"Rep 7"` against `"rep 7 of 12"`, `"Last rep"` against
 * `"last rep of 12"`. The trailing `" of N"` is the ring's alone -- the voice
 * never says the total -- so it is cut before comparing rather than asserted
 * away.
 */
class RingVoiceAgreementTest {
    private val bench = ExerciseDef("bench", "Bench", startsWith = StartPhase.ECCENTRIC)

    private fun planFor(tempo: String) = CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), bench.liftDirection()))

    private fun ring(repInHand: Int, plannedReps: Int?) =
        GuidedRepCaption.forRing(repInHand - 1, plannedReps, leadIn = false, finished = false)!!

    @Test
    fun `every rep a plan announces is the rep the ring is showing`() {
        for (tempo in listOf("3010", "2011", "1120", "3110", "20X0")) {
            val plan = planFor(tempo)
            if (plan.announceOnBeat == null) continue
            for (planned in listOf(3, 8, 12)) {
                for (rep in 2..planned) {
                    val spoken = plan.announcementFor(rep, planned)!!
                    val shown = ring(rep, planned).substringBefore(" of ")
                    assertEquals(spoken.lowercase(), shown, "$tempo, rep $rep of $planned")
                }
            }
        }
    }

    @Test
    fun `the final rep is warned about in both channels at once`() {
        val plan = planFor("3010")
        assertEquals(CadencePlan.LAST_REP, plan.announcementFor(12, 12))
        assertEquals("last rep of 12", ring(12, 12))
    }

    @Test
    fun `a plan too dense to speak still leaves the number on the ring`() {
        val plan = planFor("1010")
        assertEquals(null, plan.announceOnBeat)
        assertEquals(null, plan.announcementFor(7, 12))
        assertEquals("rep 7 of 12", ring(7, 12))
    }

    @Test
    fun `the ring no longer shows the number the voice stopped saying`() {
        val plan = planFor("3010")
        val spokenBefore243 = "${CadencePlan.REP_CALL_PREFIX}6"
        assertEquals("${CadencePlan.REP_CALL_PREFIX}7", plan.announcementFor(7, 12))
        assertEquals(false, ring(7, 12).startsWith(spokenBefore243.lowercase()))
    }
}
