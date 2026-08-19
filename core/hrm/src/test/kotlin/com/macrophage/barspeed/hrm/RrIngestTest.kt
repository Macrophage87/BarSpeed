package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RrIngest]: which beats a notification brought.
 *
 * A notification identical to the last one that carried intervals brought no
 * new beat -- the strap re-sends its last completed R-R on its own cadence when
 * none has arrived (issue #81). Everything else it carries is new.
 *
 * Every assertion goes through the BATCH form, including the ones about a
 * single notification, because that form's signature does not change when the
 * rule does. Writing four of them against the one-notification form was an
 * oversight that made the fix fail to COMPILE rather than fail an assertion.
 *
 * The multi-interval cases are synthetic and have to be: 0 of the 2,184
 * notifications across all 20 committed captures carry more than one interval.
 * They pin the intended contract and they are the only thing that does -- two
 * semantically different rules otherwise pass every test in this repository.
 */
class RrIngestTest {
    private fun sample(atMs: Long, bpm: Int, vararg rr: Double) =
        HrSample(timestampMs = atMs, bpm = bpm, rrIntervalsMs = rr.toList())

    @Test
    fun `the first notification of a stream contributes everything it carries`() {
        assertEquals(listOf(800.0), RrIngest.newBeats(listOf(sample(0, 75, 800.0))))
    }

    @Test
    fun `a notification carrying no intervals contributes nothing`() {
        assertEquals(emptyList(), RrIngest.newBeats(listOf(sample(500, 75))))
    }

    @Test
    fun `two intervals in one notification are two beats`() {
        assertEquals(listOf(420.0, 425.0), RrIngest.newBeats(listOf(sample(500, 143, 420.0, 425.0))))
    }

    /**
     * Two beats reported in ONE notification with the same interval are two
     * beats, not one repeated. Only the notification boundary separates a queued
     * pair from a re-sent value, so this stays true under any fix to #81 and is
     * the reason the rule cannot live in [Hrv].
     *
     * Synthetic, and it has to be: no capture held here contains a notification
     * carrying more than one interval, so nothing about this case is evidenced
     * by real data. It pins the intended contract, not an observation.
     */
    @Test
    fun `two identical intervals inside one notification are two beats`() {
        assertEquals(listOf(430.0, 430.0), RrIngest.newBeats(listOf(sample(500, 140, 430.0, 430.0))))
    }

    /**
     * A notification repeating its predecessor carried no new beat.
     *
     * Inverts the (pre-fix) assertion of the same name. The strap re-sends its
     * last completed R-R on its own cadence when no beat has arrived, and the
     * second report is the same beat, not a second one.
     */
    @Test
    fun `a notification repeating its predecessor contributes nothing`() {
        assertEquals(
            listOf(800.0),
            RrIngest.newBeats(listOf(sample(0, 75, 800.0), sample(500, 75, 800.0))),
        )
    }

    /** A run of repeats is one beat however long the run is. */
    @Test
    fun `a run of three identical notifications is one beat`() {
        val stream =
            listOf(
                sample(0, 75, 800.0),
                sample(500, 75, 800.0),
                sample(1000, 75, 800.0),
                sample(1500, 76, 790.0),
            )
        assertEquals(listOf(800.0, 790.0), RrIngest.newBeats(stream))
    }

    /**
     * A value returning after a different one intervened is a NEW beat.
     *
     * Only the IMMEDIATE predecessor is compared. A heart returning to an
     * interval it held a moment ago is ordinary, and treating equality at any
     * distance as duplication would delete real beats wholesale.
     *
     * Green both before and after the fix, and not dead weight for it: this is
     * the only test that kills a rule freezing its reference at the first
     * notification of the stream instead of advancing it.
     */
    @Test
    fun `a value that returns after another intervenes is a new beat`() {
        val stream =
            listOf(
                sample(0, 75, 800.0),
                sample(500, 76, 790.0),
                sample(1000, 75, 800.0),
            )
        assertEquals(listOf(800.0, 790.0, 800.0), RrIngest.newBeats(stream))
    }

    /**
     * Equality is over the WHOLE list, not the first interval.
     *
     * Kills a rule comparing only firstOrNull(), which otherwise passes every
     * test in this repository and is a different rule: it discards a
     * notification sharing its opening interval with its predecessor while
     * carrying a genuinely new one after it.
     */
    @Test
    fun `two notifications agreeing only on their first interval are both kept`() {
        val stream = listOf(sample(0, 80, 800.0, 700.0), sample(500, 82, 800.0, 650.0))
        assertEquals(listOf(800.0, 700.0, 800.0, 650.0), RrIngest.newBeats(stream))
    }

    /**
     * Equality, not containment.
     *
     * Kills a rule asking whether the predecessor already CONTAINED everything
     * this notification carries, which also otherwise passes every test here. A
     * strap reporting two beats and then one of them again is reporting a beat
     * it has not reported in that position; containment would drop it.
     */
    @Test
    fun `a notification the predecessor merely contained is still a new beat`() {
        val stream = listOf(sample(0, 80, 800.0, 700.0), sample(500, 82, 700.0))
        assertEquals(listOf(800.0, 700.0, 700.0), RrIngest.newBeats(stream))
    }

    /**
     * Order inside a notification is part of its identity.
     *
     * Kills the remaining family of predicates that differ from the shipped
     * rule only where no capture reaches: set equality, sorted equality, and
     * multiset equality all call this pair a duplicate. It is two beats
     * reported in the opposite order, which is a different notification.
     */
    @Test
    fun `a notification carrying the same intervals reordered is not a repeat`() {
        val stream = listOf(sample(0, 80, 800.0, 700.0), sample(500, 80, 700.0, 800.0))
        assertEquals(listOf(800.0, 700.0, 700.0, 800.0), RrIngest.newBeats(stream))
    }

    /**
     * A silent notification does not break a repeat run.
     *
     * The comparison is against the last notification that CARRIED intervals,
     * not against the last notification. A notification with the R-R flag clear
     * says nothing about beats, so letting it reset the comparison would turn
     * one beat into two whenever the strap went quiet mid-run.
     */
    @Test
    fun `a silent notification does not make the next repeat a new beat`() {
        val stream =
            listOf(
                sample(0, 75, 800.0),
                sample(500, 75),
                sample(1000, 75, 800.0),
            )
        assertEquals(listOf(800.0), RrIngest.newBeats(stream))
    }

    /** A notification carrying nothing contributes nothing, before or after the fix. */
    @Test
    fun `a silent notification between two beats contributes nothing`() {
        val stream = listOf(sample(0, 75, 800.0), sample(500, 76), sample(1000, 76, 790.0))
        assertEquals(listOf(800.0, 790.0), RrIngest.newBeats(stream))
    }

    @Test
    fun `an empty stream yields no beats`() {
        assertEquals(emptyList(), RrIngest.newBeats(emptyList()))
    }
}
