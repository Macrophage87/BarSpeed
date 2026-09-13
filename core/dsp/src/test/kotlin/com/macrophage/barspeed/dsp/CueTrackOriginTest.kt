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
 * `RepCallScheduleTest` holds six (tempo, lift) pairs and not one of them has a
 * closing pause, so nothing in that file contradicted the published sentence
 * "each during the rep it names" -- which was false on the one schedule that
 * spoke its call in the PREVIOUS rep's closing pause. The six are the plans two
 * field sessions ran; they are not the plans that exist.
 *
 * The witness for that schedule is committed:
 * `field-backsquat-4011-6rep-s36-set01-cues.csv`, field-36 set 1, back squat at
 * tempo 4011, eccentric-first -- provenance in `AnchorSupplyByMountTest`. Its
 * track puts every call a whole second BEFORE the rep it would name starts:
 * `Up` at 6.005 s, the call at 7.006 s, the next rep's `Down` at 8.007 s.
 * (That track was recorded under the pre-#243 schedule, so its rows read
 * `Rep 1` where this plan now says `Rep 2`; what is read from it here is
 * WHERE a call sat in the cadence.)
 *
 * ## #293 makes the placement uniform, and that is what is pinned now
 *
 * The call opens the rep it names, on every schedule that speaks at all, in
 * place of that rep's first stroke word. So the asymmetry this file was written
 * to hold -- inside the rep on two families, before it on the third -- is gone,
 * and the back squat above is the family that moved furthest: its call lands a
 * second LATER than the archive has it, on the rep it names rather than in the
 * tail of the one before.
 *
 * ## What a consumer can and cannot tell from a row
 *
 * Nothing in a cue row names its writer. The two writers have disjoint
 * vocabularies and that is the only discriminator there is:
 * [CadenceVoice.beatCall] writes a stroke word on every rep of a guided set, and
 * `VoiceMilestonePolicy` -- the UNGUIDED counter, which speaks as a rep
 * completes rather than on a schedule -- writes no word at all beyond digits,
 * `Rep N`, `Last rep` and `Done`.
 *
 * The GUIDED half is weaker from #293 than it was, and the weakening is pinned
 * below rather than described here: a guided rep used to carry BOTH stroke words
 * and now carries one of them on every rep after the first, because the rep
 * number takes the other's second. A guided track still carries a stroke word in
 * every rep, and both of them in rep 1, so the discriminator holds -- but a
 * reader counting `Down` rows to count reps now counts one per set on the
 * geometries whose first stroke is the `Down`.
 *
 * `Hold` is deliberately NOT part of that test, and the trap is worth naming:
 * it is a PAUSE the prescription asked for, absent from every tempo without a
 * mid-rep pause, and it is also the word `LeadInPolicy.timedStartWord` speaks
 * to open a timed HOLD set, which has no reps and no guide. A discriminator
 * built on it would misread both.
 *
 * The guided half is observed as well as derived -- the committed `*-cues.csv`
 * fixtures from a rep-based set in `core/dsp/src/test/resources` carry `Down`
 * and `Up`, and so does the one outside it,
 * `core/data/.../field-backsquat-4011-6rep-s36-set02-cues.csv`. Every one of
 * those was recorded BEFORE #293, so they observe the two-words-per-rep shape
 * rather than the one this file now pins; what a track recorded after it looks
 * like is scripted here and is a `[Field]` question until a session answers it.
 * The UNGUIDED half is derived from source only: no committed fixture is an
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
     * Every call opens the rep it names, on all three schedules (#293).
     *
     * This test asserted the opposite shape until #293 -- a merged call inside
     * the rep it names, a paused one in the previous rep's tail -- and the
     * published document said so with it. Both are corrected: the call is the
     * first thing said in the rep it names, on every plan that speaks.
     */
    @Test
    fun `every call opens the rep it names, on all three schedules`() {
        val cases = listOf(
            Triple(plan("4011", backSquat), 6, "closing pause, which used to speak it a rep early"),
            Triple(plan("3010", inclinePress), 10, "three-second opener, which already opened the rep"),
            Triple(plan("1120", pushdown), 12, "one-second opener, which used to send it to the last stroke"),
        )
        cases.forEach { (p, reps, family) ->
            val cycle = p.deliveredCycleS
            val all = rows(p, reps)
            val calls = all.filter { isCall(it.second) }
            assertEquals(reps - 1, calls.size, "a set of $reps hears one call per rep after the first")
            calls.forEachIndexed { index, (second, row) ->
                // Calls arrive in rep order and the first names rep 2.
                val repStarts = (index + 1) * cycle
                assertEquals(repStarts, second, "$family: $row must open rep ${index + 2}")
                assertEquals(
                    second to row,
                    all.first { it.first >= repStarts },
                    "$family: $row must be the FIRST row of that rep, not merely inside it",
                )
            }
        }
    }

    /**
     * And the warning is the same shape as the numbers, which is the half of
     * the schema sentence a reader is most likely to lean on.
     *
     * `Last rep` is a call like any other, so it opens the final rep. On the
     * back squat it lands at second 30, which is that rep's first stroke; the
     * 0.1.50-era archive has it at 29, in the previous rep's closing pause, and
     * `the guide no longer accounts for every row` in `ClosingPauseLastRepTest`
     * measures that family against its own recordings.
     */
    @Test
    fun `the last-rep warning opens the final rep on a closing-pause schedule`() {
        val p = plan("4011", backSquat)
        val reps = 6
        val warning = rows(p, reps).single { it.second == CadencePlan.LAST_REP }
        assertEquals(30, warning.first, "the warning's second of the cadence")
        assertEquals(30, (reps - 1) * p.deliveredCycleS, "the final rep's first stroke")
        assertEquals("Down", p.beats[0].spokenLabel, "whose word the warning replaces")
    }

    /**
     * The guided half of the discriminator: every rep writes a stroke word, and
     * rep 1 writes both.
     *
     * This said BOTH words on EVERY rep until #293, and that is what the
     * published `voiceCues` description said too. The number takes one of the
     * two on every rep after the first, so the claim is narrowed to what
     * survives -- at least one stroke word in every rep, both in rep 1 -- which
     * is still enough to tell a guided track from the unguided counter's, whose
     * whole vocabulary is digits and rep calls.
     *
     * The two families that speak no call at all keep both words on every rep,
     * which is what makes the absence of a call readable rather than ambiguous.
     */
    @Test
    fun `every rep of a guided track carries a stroke word, and rep 1 carries both`() {
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
            val speaks = p.announceOnBeat != null
            (1..reps).forEach { rep ->
                val window = all.filter { it.first in (rep - 1) * cycle until rep * cycle }
                val spoken = window.map { it.second }.filter { it in words }.toSet()
                val expected = if (rep == 1 || !speaks) words else words - p.beats[0].spokenLabel!!
                assertEquals(
                    expected,
                    spoken,
                    "rep $rep of ${p.beats.map { it.label to it.seconds }}: stroke words written",
                )
                assertTrue(spoken.isNotEmpty(), "rep $rep writes no stroke word, so no reader can attribute it")
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
