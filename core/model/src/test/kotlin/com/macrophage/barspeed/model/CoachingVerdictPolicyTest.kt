package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [CoachingVerdictPolicy] shows and what it withholds (#261).
 *
 * The pins in this class are the ones that hold on BOTH sides of the change:
 * a max-intent set, a set with no decidable regime, an empty list, and every
 * line that is not the fatigue sentence. The differentials that separate the
 * two sides are added on their own commit, red, before the drop exists.
 */
class CoachingVerdictPolicyTest {
    private val fatigue = "High velocity loss (42.8%) — significant fatigue this set."
    private val stop = "Velocity-loss stop (20.0%) was reached at rep 3; later reps exceeded the plan."
    private val shortfall = "Completed 5 of 6 planned reps."
    private val tempo = "Tempo (concentric): 5/10 reps on tempo; worst was 2.96 s too slow (target 1.00 s)."

    @Test
    fun `a max intent set shows every line it was written`() {
        val all = listOf(shortfall, fatigue, tempo)
        assertEquals(all, CoachingVerdictPolicy.forRegime(all, VelocityLossRegime.MAX_INTENT))
    }

    @Test
    fun `an undecidable regime shows every line it was written`() {
        // Null is every set recorded before geometry was stored, plus holds,
        // carries and unparseable tempos. Those cards drew the whole list
        // before #250 and go on drawing it.
        val all = listOf(shortfall, fatigue, tempo)
        assertEquals(all, CoachingVerdictPolicy.forRegime(all, null))
    }

    @Test
    fun `an empty list stays empty in every regime`() {
        for (regime in listOf(VelocityLossRegime.MAX_INTENT, VelocityLossRegime.CONTROLLED, null)) {
            assertEquals(emptyList(), CoachingVerdictPolicy.forRegime(emptyList(), regime), "regime $regime")
        }
    }

    @Test
    fun `a controlled set with nothing to withhold is left alone`() {
        val all = listOf(shortfall, tempo)
        assertEquals(all, CoachingVerdictPolicy.forRegime(all, VelocityLossRegime.CONTROLLED))
    }

    @Test
    fun `a controlled set keeps the stop the plan asked for`() {
        // A prescribed velocity-loss stop is the lifter's own instruction, and
        // it is a different sentence from the fatigue claim. It survives the
        // regime, and CoachingVerdictLinesTest in :core:dsp pins that the two
        // can never be written together.
        val all = listOf(stop, tempo)
        assertEquals(all, CoachingVerdictPolicy.forRegime(all, VelocityLossRegime.CONTROLLED))
    }

    @Test
    fun `a controlled set does not read significant fatigue`() {
        // #261, the whole of it. #250 withheld the velocity pill on this card;
        // this sentence went on being drawn underneath the gap it left.
        val all = listOf(shortfall, fatigue, tempo)
        assertEquals(
            listOf(shortfall, tempo),
            CoachingVerdictPolicy.forRegime(all, VelocityLossRegime.CONTROLLED),
            "the fatigue sentence is withheld and the order of the rest is kept",
        )
    }

    @Test
    fun `a controlled set withholds the sentence whatever figure it carries`() {
        // The figure is interpolated into the line, so a reader that matched
        // the whole sentence would suppress one archive row and miss the next.
        for (text in listOf("35.1", "42.8", "79.1", "100.0")) {
            val line = "High velocity loss ($text%) — significant fatigue this set."
            assertEquals(
                emptyList(),
                CoachingVerdictPolicy.forRegime(listOf(line), VelocityLossRegime.CONTROLLED),
                "withheld at $text%",
            )
        }
    }

    @Test
    fun `the fatigue sentence is the only line a controlled set withholds`() {
        // Paired with the differential above: that one shows the line goes,
        // this one shows nothing else does. A filter that dropped the list
        // would pass the first and fail this.
        val all = listOf(shortfall, stop, fatigue, tempo)
        assertEquals(
            listOf(shortfall, stop, tempo),
            CoachingVerdictPolicy.forRegime(all, VelocityLossRegime.CONTROLLED),
            "four lines in, three out, the plan's own stop among them",
        )
    }
}
