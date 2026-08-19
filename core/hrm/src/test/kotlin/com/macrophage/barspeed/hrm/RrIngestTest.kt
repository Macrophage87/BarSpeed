package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RrIngest] as it behaves TODAY: every reported interval is taken as a beat.
 *
 * These pin the rule currently in force, so the commit moving it out of
 * `RecordViewModel` can be shown to have moved it rather than changed it. Two
 * are marked (pre-fix) and assert the wrong answer on purpose: they are green
 * now and issue #81's fix overturns them, which is how the fix is made to say
 * which behaviour it changed.
 */
class RrIngestTest {
    private fun sample(atMs: Long, bpm: Int, vararg rr: Double) =
        HrSample(timestampMs = atMs, bpm = bpm, rrIntervalsMs = rr.toList())

    @Test
    fun `the first notification of a stream contributes everything it carries`() {
        assertEquals(listOf(800.0), RrIngest.newBeats(sample(0, 75, 800.0)))
    }

    @Test
    fun `a notification carrying no intervals contributes nothing`() {
        assertEquals(emptyList(), RrIngest.newBeats(sample(500, 75)))
    }

    @Test
    fun `two intervals in one notification are two beats`() {
        assertEquals(listOf(420.0, 425.0), RrIngest.newBeats(sample(500, 143, 420.0, 425.0)))
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
        assertEquals(listOf(430.0, 430.0), RrIngest.newBeats(sample(500, 140, 430.0, 430.0)))
    }

    /**
     * (pre-fix) A notification repeating its predecessor is counted again today.
     *
     * Written against the batch form because that is the signature the fix does
     * not change, so this same assertion can be inverted by the fix rather than
     * rewritten by it.
     */
    @Test
    fun `a notification repeating its predecessor is counted again today`() {
        assertEquals(
            listOf(800.0, 800.0),
            RrIngest.newBeats(listOf(sample(0, 75, 800.0), sample(500, 75, 800.0))),
        )
    }

    /** (pre-fix) Nothing is dropped today, so the batch total is the reported total. */
    @Test
    fun `the batch form keeps every reported interval today`() {
        val stream =
            listOf(
                sample(0, 75, 800.0),
                sample(500, 75, 800.0),
                sample(1000, 75, 800.0),
                sample(1500, 76, 790.0),
            )
        assertEquals(listOf(800.0, 800.0, 800.0, 790.0), RrIngest.newBeats(stream))
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
