package com.macrophage.barspeed.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a hold or a carry says while its clock runs down.
 *
 * These assertions are new, not moved: the rule they cover spent its whole life
 * as four lines of `when` inside the coroutine `RecordViewModel` launches,
 * where no test on the CI path could state any of it. It is
 * unchanged by the commit that brought it here; what changed is that it can now
 * be asserted.
 *
 * Every expectation is a literal. Writing `assertEquals(TimedSetVoice.TIME_UP,
 * ...)` would pass for any value of `TIME_UP` including a wrong one, which is a
 * check that cannot fail.
 */
class TimedSetVoiceTest {
    /**
     * The owner's rule (#312): "for holds, count in 5 second increments until
     * 10 seconds to go (then 1)". A 45 s hold, second by second from the one
     * after `Hold` to the target, names 40, 35, 30, 25, 20 and 15 seconds, then
     * every digit from 10, then the terminal word; every other second is
     * silent. A long hold's first mark keeps today's words: 60 is "60 seconds".
     *
     * This replaces a pin that said the marks came every fifteen seconds of
     * what remains; #312 made it false and it is deleted, not reworded. Reds
     * if `MILESTONE_EVERY_S` moves off 5 -- at 15 the 40 s row is the first to
     * fall silent.
     */
    @Test
    fun `a 45 s hold names the time left every five seconds, then every second from 10`() {
        val spoken = (44 downTo 0).mapNotNull { TimedSetVoice.cueFor(it) }
        assertEquals(
            listOf("40 seconds", "35 seconds", "30 seconds", "25 seconds", "20 seconds", "15 seconds") +
                listOf("10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "Time"),
            spoken,
        )
        assertEquals("60 seconds", TimedSetVoice.cueFor(60))
        listOf(44, 41, 39, 36, 16, 14, 11).forEach { assertNull(TimedSetVoice.cueFor(it), "$it seconds left") }
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
