package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When a set can state where its prep was, and when it must say nothing (#185).
 *
 * The decision lifted out of `:app`, which is where it would otherwise live and
 * where nothing could assert it: `RecordViewModel` holds both instants and has
 * no test source set to speak of. What runs on every push is the rule; that the
 * recorder hands the rule the right two instants is compile- and lint-gated
 * only, and is stated as such wherever it is claimed.
 */
class PrepWindowPolicyTest {
    @Test
    fun `a timed set states the interval between the tap and the clock starting`() {
        assertEquals(
            PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 11_000L),
            PrepWindowPolicy.of(PrepCase.TIMED, tappedAtMs = 1_000L, workStartedAtMs = 11_000L),
        )
    }

    @Test
    fun `a cued set states the interval between the tap and the first stroke call`() {
        assertEquals(
            PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 6_000L),
            PrepWindowPolicy.of(PrepCase.CUED, tappedAtMs = 1_000L, workStartedAtMs = 6_000L),
        )
    }

    /**
     * A set that ran no prep has no interval, even when an instant is offered.
     *
     * The argument is deliberately non-null here: the caller is `:app`, whose
     * work-start field is reset per set, and a stale one surviving into a set
     * that plays no prep must not become a window. What such a set would
     * publish is a span the lifter spent lifting, labelled as their prep.
     */
    @Test
    fun `a set with no prep states no window even when an instant is offered`() {
        assertNull(PrepWindowPolicy.of(PrepCase.NONE, tappedAtMs = 1_000L, workStartedAtMs = 6_000L))
    }

    /**
     * A set ended during its prep never closed the interval, and says nothing.
     *
     * Not the set's end instant, and not the tap twice. Either would publish a
     * window a reader would then look for a stationary period inside.
     */
    @Test
    fun `a set ended during its prep states no window`() {
        assertNull(PrepWindowPolicy.of(PrepCase.TIMED, tappedAtMs = 1_000L, workStartedAtMs = null))
        assertNull(PrepWindowPolicy.of(PrepCase.CUED, tappedAtMs = 1_000L, workStartedAtMs = null))
    }

    /**
     * An inverted pair is refused rather than ordered.
     *
     * The two instants are two reads of `System.currentTimeMillis` in `:app`,
     * which is not monotonic; a clock correction between them is enough. Sorting
     * them would turn a corrupted pair into a plausible window, and subtracting
     * them anywhere downstream would give a negative prep.
     */
    @Test
    fun `a work start before the tap states no window`() {
        assertNull(PrepWindowPolicy.of(PrepCase.TIMED, tappedAtMs = 5_000L, workStartedAtMs = 4_999L))
    }

    /**
     * A prep of no length is a window and is published.
     *
     * `LeadInPolicy.MIN_S` is 0 and its own KDoc says why that is legal: a
     * lifter who wants a machine set to start the instant they tap should be
     * able to have one. Publishing the empty window tells the reader there is no
     * stationary period to look for -- a different answer from the app not
     * saying, which is what every set recorded before this change reports.
     */
    @Test
    fun `a zero-length prep is a window rather than an absence`() {
        assertEquals(
            PrepWindow(startedAtMs = 1_000L, workStartedAtMs = 1_000L),
            PrepWindowPolicy.of(PrepCase.CUED, tappedAtMs = 1_000L, workStartedAtMs = 1_000L),
        )
    }
}
