package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.TimedSetEndPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The word and the end land on the same second.
 *
 * `TimedSetVoice` says the terminal word when the target is reached;
 * [TimedSetEndPolicy] ends the set when the target is reached. Those are two
 * sentences about one instant, written in two modules, and #168 is the change
 * that makes the second of them exist. If they ever disagree by a second the
 * lifter gets one of two failures, both silent:
 *
 *  - the end lands first, so the set is written and the screen has already
 *    moved to rest when "Time" is spoken over it;
 *  - the word lands first and the clock runs on, so the lifter puts the bar
 *    down on the word and the extra second is recorded as hold time -- the
 *    inflation #168 exists to remove, one second of it, on every timed set.
 *
 * The tick loop is what actually protects against this: it computes
 * [TimedSetEndPolicy.remainingS] ONCE per second and hands the same value to
 * both readers, so there is one computation and no second one to drift from.
 * This file asserts the property that arrangement is worth having -- that the
 * two readers agree over the whole range, not merely at the point someone
 * happened to try.
 *
 * Across the range rather than at zero: agreement at the single boundary point
 * is what an off-by-one already satisfies on one side.
 */
class TimedSetVoiceCouplingTest {
    /**
     * Over a range spanning a minute either side of the target, the one second
     * on which the voice says the terminal word is the FIRST second on which
     * the policy ends the set.
     *
     * Not set equality between the two, which is the assertion this test was
     * first written as and which is false on purpose: `endsNow` stays true for
     * every second past the target so a missed tick still ends the set, while
     * the voice falls silent after the word. Set equality reds against a
     * correct implementation, so it was the wrong statement of the property
     * rather than a finding -- recorded here because the corrected form is the
     * weaker of the two and a reader is owed the reason.
     *
     * Reds if either boundary moves: narrowing `endsNow` to `== 0` still
     * passes here and is caught by [TimedSetEndPolicyTest]; moving the voice's
     * terminal branch off zero, or moving the first second the policy ends on,
     * reds here.
     */
    @Test
    fun `the terminal word is spoken on the first second the set ends on`() {
        val range = -60..60
        val spoken = range.filter { TimedSetVoice.cueFor(it) == TimedSetVoice.TIME_UP }
        val ended = range.filter { TimedSetEndPolicy.endsNow(it) }
        assertEquals(listOf(0), spoken)
        assertEquals(spoken, listOf(ended.last()))
        // Nothing ends before the word: every second the voice is still
        // counting down through is a second the set is still running.
        assertTrue(range.none { it > 0 && TimedSetEndPolicy.endsNow(it) })
    }

    /**
     * And they agree when driven the way the tick loop drives them: from an
     * elapsed second and a prescription, through the one function that turns
     * that pair into a remainder.
     *
     * A 20 s hold and a 30 s carry, the two fixtures the bench plan uses, plus
     * a 45 s hang, walked second by second past their targets. Reds if the
     * remainder is ever computed as `elapsed - target` rather than
     * `target - elapsed`, which agrees at zero and nowhere else.
     */
    @Test
    fun `a hold walked second by second speaks and ends on the same tick`() {
        listOf(20, 30, 45).forEach { target ->
            val spoken = (0..target + 10).filter {
                TimedSetVoice.cueFor(TimedSetEndPolicy.remainingS(it, target) ?: 1) == TimedSetVoice.TIME_UP
            }
            val ended = (0..target + 10).filter { TimedSetEndPolicy.endsNow(TimedSetEndPolicy.remainingS(it, target)) }
            assertTrue(spoken.isNotEmpty(), "target ${target}s never reaches the terminal word")
            assertEquals(listOf(target), spoken, "target ${target}s speaks on the wrong second")
            // The policy keeps ending past the target where the voice falls
            // silent -- a missed tick must not sail past -- so the tick the
            // word lands on is the FIRST one that ends the set, not the only.
            assertEquals(target, ended.first(), "target ${target}s ends on the wrong second")
        }
    }
}
