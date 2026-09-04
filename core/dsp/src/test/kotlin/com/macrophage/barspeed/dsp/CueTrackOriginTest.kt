package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.LeadInPolicy
import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceMilestonePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a rep call LANDS relative to the rep it names, and which of the two
 * counters wrote a cue row at all.
 *
 * Two questions the published `session-export.schema.json` answers in prose and
 * nothing asserted, both raised against #243 in review round 2.
 *
 * ## Why the existing corpus could not catch either
 *
 * `RepCallScheduleTest` holds six (tempo, lift) pairs, and every one of them
 * MERGES its call into a stroke of the rep it names -- cases 2 and 3 of
 * [CadencePlan]. Not one of the six has a closing pause, so nothing in that
 * file contradicts the sentence "each during the rep it names", which is
 * exactly the sentence that is false on the third schedule. The six are the
 * plans two field sessions ran; they are not the plans that exist.
 *
 * The witness for the third schedule is committed:
 * `field-backsquat-4011-6rep-s36-set01-cues.csv`, field-36 set 1, back squat at
 * tempo 4011, eccentric-first -- provenance in `AnchorSupplyByMountTest`. Its
 * track puts every call a whole second BEFORE the rep it would name starts:
 * `Up` at 6.005 s, the call at 7.006 s, the next rep's `Down` at 8.007 s.
 * (That track was recorded under the pre-#243 schedule, so its rows read
 * `Rep 1` where this plan now says `Rep 2`; what is read from it here is
 * WHERE a call sits in the cadence, which #243 did not move.)
 *
 * ## What a consumer can and cannot tell from a row
 *
 * Nothing in a cue row names its writer. The two writers have disjoint
 * vocabularies and that is the only discriminator there is:
 * [CadenceVoice.beatCall] writes the metronome's stroke words on every rep of a
 * guided set, and `VoiceMilestonePolicy` -- the UNGUIDED counter, which speaks
 * as a rep completes rather than on a schedule -- writes no word at all beyond
 * digits, `Rep N`, `Last rep` and `Done`.
 *
 * `Hold` is deliberately NOT part of that test, and the trap is worth naming:
 * it is a PAUSE the prescription asked for, absent from every tempo without a
 * mid-rep pause, and it is also the word `LeadInPolicy.timedStartWord` speaks
 * to open a timed HOLD set, which has no reps and no guide. A discriminator
 * built on it would misread both.
 *
 * The guided half is observed as well as derived -- all 28 committed
 * `*-cues.csv` fixtures from a rep-based set in `core/dsp/src/test/resources`
 * carry `Down` and `Up`, and so does the one outside it,
 * `core/data/.../field-backsquat-4011-6rep-s36-set02-cues.csv`. The
 * UNGUIDED half is derived from source only: no committed fixture is an
 * unguided-counter track, so what is pinned here is the policy's own output
 * vocabulary and not a capture anyone has replayed.
 */
class CueTrackOriginTest {
    /** field-36 set 1: back_squat, ecc-first, drive up, vertical. Closing pause of 1 s. */
    private val backSquat = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-38 set 1: dumbbell_incline_press, ecc-first, drive up, vertical. */
    private val inclinePress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-38 set 12: triceps_pushdown, conc-first, drive DOWN, vertical, on-stack, inverted. */
    private val pushdown = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** A seated row: horizontal, so its strokes are called by phase. */
    private val seatedRow = LiftDirection(plane = MovementPlane.HORIZONTAL)

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    private fun rows(plan: CadencePlan, reps: Int) = CadenceVoice.script(plan, reps)
        .flatMap { call -> call.recorded.map { call.atSecond to it } }

    private fun isCall(row: String) = row == CadencePlan.LAST_REP ||
        row.startsWith(CadencePlan.REP_CALL_PREFIX)

    /**
     * The published claim, split in two because the two halves are opposite.
     *
     * A call names the rep now due on every schedule. WHEN it is spoken is not
     * uniform, and the schema said it was: on the two merging schedules the
     * call lands inside the rep it names, and on the schedule with a closing
     * pause it lands in the PREVIOUS rep's tail, before the named rep has
     * started a stroke.
     */
    @Test
    fun `a merged call lands inside the rep it names and a paused one lands before it`() {
        val cases = listOf(
            // tempo, lift, reps, does the call land inside the rep it names?
            Triple(plan("4011", backSquat), 6, false),
            Triple(plan("3010", inclinePress), 10, true),
            Triple(plan("1120", pushdown), 12, true),
        )
        cases.forEach { (p, reps, inside) ->
            val cycle = p.deliveredCycleS
            val calls = rows(p, reps).filter { isCall(it.second) }
            assertEquals(reps - 1, calls.size, "a set of $reps hears one call per rep after the first")
            calls.forEachIndexed { index, (second, row) ->
                // Calls arrive in rep order and the first names rep 2.
                val namedRep = index + 2
                val repStarts = (namedRep - 1) * cycle
                if (inside) {
                    assertTrue(
                        second in repStarts until repStarts + cycle,
                        "$row at ${second}s should be inside rep $namedRep ($repStarts..${repStarts + cycle - 1})",
                    )
                } else {
                    assertTrue(
                        second < repStarts,
                        "$row at ${second}s should be in rep ${namedRep - 1}'s tail, before ${repStarts}s",
                    )
                }
            }
        }
    }

    /**
     * And the warning is the same shape as the numbers, which is the half of
     * the schema sentence a reader is most likely to lean on.
     *
     * `Last rep` is a call like any other, so on a closing-pause schedule it is
     * spoken before the final rep begins rather than during it. On the back
     * squat it lands at second 29 of the cadence and the sixth rep opens at 30.
     */
    @Test
    fun `the last-rep warning is spoken before the final rep on a closing-pause schedule`() {
        val p = plan("4011", backSquat)
        val reps = 6
        val warning = rows(p, reps).single { it.second == CadencePlan.LAST_REP }
        assertEquals(29, warning.first, "the warning's second of the cadence")
        assertEquals(30, (reps - 1) * p.deliveredCycleS, "the final rep's first stroke")
        assertTrue(warning.first < (reps - 1) * p.deliveredCycleS, "spoken before the rep it names")
    }

    /**
     * The guided half of the discriminator: every rep writes stroke words.
     *
     * Two of them, one per stroke, on every rep of every plan -- including the
     * two families that speak no call at all, which is what makes the absence
     * of a call readable rather than ambiguous.
     */
    @Test
    fun `every rep of a guided track carries the metronome's stroke words`() {
        val vertical = setOf("Down", "Up")
        val horizontal = setOf("Drive", "Return")
        val cases = listOf(
            Triple(plan("4011", backSquat), 6, vertical),
            Triple(plan("3010", inclinePress), 10, vertical),
            Triple(plan("1120", pushdown), 12, vertical),
            Triple(plan("1010", inclinePress), 5, vertical),
            Triple(plan("1110", inclinePress), 5, vertical),
            Triple(plan("3010", seatedRow), 8, horizontal),
        )
        cases.forEach { (p, reps, words) ->
            val cycle = p.deliveredCycleS
            val all = rows(p, reps)
            (1..reps).forEach { rep ->
                val window = all.filter { it.first in (rep - 1) * cycle until rep * cycle }
                assertEquals(
                    words,
                    window.map { it.second }.filter { it in words }.toSet(),
                    "rep $rep of ${p.beats.map { it.label to it.seconds }} carries both stroke words",
                )
            }
        }
    }

    /**
     * The unguided half: the sensor counter speaks no stroke word, ever.
     *
     * Swept rather than asserted case by case, because the claim the schema
     * publishes is about the whole vocabulary and not about three strings.
     * `phaseCount` emits bare digits and `repMilestone` emits `Rep N`,
     * `Last rep` and `Done`; nothing else can come out of either.
     */
    @Test
    fun `the unguided counter never speaks a stroke word`() {
        val strokeWords = setOf("Down", "Up", "Drive", "Return")
        val spoken = mutableSetOf<String>()
        Phase.entries.forEach { phase ->
            (0..8).forEach { elapsed ->
                (0..8).forEach { latch ->
                    VoiceMilestonePolicy.phaseCount(phase, elapsed.toDouble(), phase, latch).speak
                        ?.let { spoken += it }
                }
            }
        }
        (0..15).forEach { count ->
            listOf(null, 1, 5, 12).forEach { planned ->
                VoiceMilestonePolicy.repMilestone(count, announcedRep = 0, plannedReps = planned)
                    ?.let { spoken += it }
            }
        }
        assertTrue(spoken.isNotEmpty(), "the sweep said nothing at all, so it pins nothing")
        assertEquals(
            emptySet(),
            spoken intersect strokeWords,
            "the unguided counter spoke a stroke word, so no track can be attributed by one",
        )
        assertTrue("Rep 5" in spoken && CadencePlan.LAST_REP in spoken, "the calls it does write")
    }

    /**
     * `Hold` is not a stroke word and must not be read as one.
     *
     * Both halves of the trap, so a later author who reaches for it finds the
     * reason written down: a prescription with no mid-rep pause never speaks
     * it, and a timed HOLD set -- no reps, no guide, no cadence -- opens on it.
     */
    @Test
    fun `hold cannot discriminate -- a tempo without a pause never says it and a hold prep does`() {
        val noPause = rows(plan("3010", inclinePress), 4).map { it.second }
        assertFalse("Hold" in noPause, "3010 has no Hold beat")
        assertTrue("Hold" in rows(plan("1120", pushdown), 4).map { it.second }, "1120 does")
        assertEquals("Hold", LeadInPolicy.timedStartWord(ExerciseKind.HOLD), "and a timed hold opens on the word")
        assertNull(LeadInPolicy.timedStartWord(ExerciseKind.DYNAMIC), "which a rep-based lift has no use for")
    }
}
