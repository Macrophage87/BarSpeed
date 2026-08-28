package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a hold or a carry says while its clock runs down.
 *
 * These assertions are new, not moved: the rule they cover spent its whole life
 * as four lines of `when` inside the coroutine `RecordViewModel` launches, in a
 * module with no test source set, where nothing could state any of it. It is
 * unchanged by the commit that brought it here; what changed is that it can now
 * be asserted.
 *
 * Every expectation is a literal. Writing `assertEquals(TimedSetVoice.TIME_UP,
 * ...)` would pass for any value of `TIME_UP` including a wrong one, which is a
 * check that cannot fail.
 */
class TimedSetVoiceTest {
    /**
     * The far half of a long hold is marked, not counted: one utterance every
     * fifteen seconds of what remains, and silence between them.
     *
     * Reds if `MILESTONE_EVERY_S` moves off 15 -- at 20 the 45 s and 15 s rows
     * both fall silent.
     */
    @Test
    fun `a long hold is marked every fifteen seconds of remaining time`() {
        assertEquals("45 seconds", TimedSetVoice.cueFor(45))
        assertEquals("30 seconds", TimedSetVoice.cueFor(30))
        assertEquals("15 seconds", TimedSetVoice.cueFor(15))
        assertNull(TimedSetVoice.cueFor(44))
        assertNull(TimedSetVoice.cueFor(31))
        assertNull(TimedSetVoice.cueFor(16))
        assertNull(TimedSetVoice.cueFor(14))
        assertNull(TimedSetVoice.cueFor(11))
    }

    /**
     * The last ten seconds are every digit, bare.
     *
     * The boundary is the point: 11 is silent and 10 speaks, so a countdown
     * that started a second early or late reds here. Reds also if
     * `FINAL_COUNTDOWN_FROM_S` moves off 10.
     */
    @Test
    fun `the final ten seconds are counted digit by digit`() {
        assertNull(TimedSetVoice.cueFor(11))
        (1..10).forEach { assertEquals("$it", TimedSetVoice.cueFor(it), "$it seconds left") }
    }

    /**
     * Reds if the zero case is dropped: 0 is not inside 1..10 and is not
     * greater than 0, so removing its branch leaves the target reached in
     * silence rather than falling through to a milestone.
     */
    @Test
    fun `the target being reached is spoken`() {
        assertEquals("Time", TimedSetVoice.cueFor(0))
    }

    /**
     * Past the target nothing is said.
     *
     * This used to read "the screen calls it bonus time and the set is still
     * measured", which went false with #168 -- the set now ends at the target,
     * so seconds past it are neither measured nor scored and the screen's
     * bonus-time branch is gone. Corrected rather than reworded. The case
     * itself still matters: a tick the loop was late for presents a remainder
     * already past zero on the tick before the set ends, and the answer has to
     * be silence rather than a spoken negative.
     *
     * Reds if the `remainingS > 0` guard is dropped from the milestone branch:
     * -15 and -30 are both multiples of fifteen and would speak a negative
     * number of seconds.
     */
    @Test
    fun `a set held past its target is not spoken over`() {
        listOf(-1, -14, -15, -30, -45).forEach {
            assertNull(TimedSetVoice.cueFor(it), "$it seconds past the target")
        }
    }

    /**
     * The terminal word is spoken at exactly one value of the argument, and
     * that value is zero.
     *
     * Pinned before #168 wires the auto-end to the same instant. The whole
     * risk in that change is two components each working out when the target
     * is reached and disagreeing by a second -- the word arriving after the
     * set has already been written, or the set running a beat past the word.
     * This states, over a range wide enough to catch an off-by-one in either
     * direction, that the voice's own boundary is here and nowhere else, so
     * the commit that ties the end to it is tying it to a measured thing.
     *
     * Reds if the terminal branch is widened to `<= 0` or moved to 1.
     */
    @Test
    fun `the terminal word lands on exactly one second and it is the target`() {
        val terminal = (-30..30).filter { TimedSetVoice.cueFor(it) == "Time" }
        assertEquals(listOf(0), terminal)
    }
}
