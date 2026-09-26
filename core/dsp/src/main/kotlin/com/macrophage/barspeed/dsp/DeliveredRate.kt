package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample

/**
 * How many frames one unit's link DELIVERED per second over a set's working
 * window, and how often it handed a notification over (#321).
 *
 * ## Why it exists
 *
 * On field-42 one unit's link delivered about 44 frames a second, every
 * arrival stamp carrying exactly four rows and 90 ms from the next, while its
 * partner delivered about 99 on a 31 ms rhythm. That held on every committed capture of that session, and
 * nothing either document or the screen showed said so. The set was analysed
 * as if the stream were whole. `DeliveredRateFieldTest` reads those captures.
 *
 * ## What is computed
 *
 * [Measured.hz] is the rows stamped inside the window divided by the window's
 * length. The window is [RollExcursion.inWindow]'s, both bounds inclusive: from
 * the work-start instant to the terminal cue. A missing bound is replaced by
 * the windowed rows' own earliest or latest stamp. It is one window, not a
 * second one, so the archive's `rollExcursion_deg` and this figure answer for
 * the same seconds of the same stream.
 *
 * The WINDOW's length is the denominator, not the span of the rows that
 * arrived. So a link that went quiet for part of a known window reads low
 * instead of reading its rate while it was up. With neither bound known the
 * two are the same thing.
 *
 * Shared stamps can move the figure at the start of the window by one stamp's
 * rows over the window's length: the rows stamped just after the work start
 * include frames produced before it, and so do the first rows where that
 * bound is missing. On the committed captures at most twelve consecutive rows
 * share one stamp, against 1,392 to 13,740 rows per window.
 *
 * [Measured.burstSpacingMs] is the upper median of the gaps between
 * consecutive DISTINCT stamps in the window. Every frame decoded from one
 * notification carries that notification's stamp, so it measures how often a
 * notification arrived. A median, so one long stall leaves it where it was
 * while it lowers [Measured.hz]. The two are published side by side for that
 * reason.
 *
 * ## What is not claimed
 *
 * DELIVERED, NOT SAMPLED. The rows carry the instant the app stamped when
 * Android's Bluetooth stack delivered each notification. Nothing in them says
 * how many frames the sensor produced, so a unit producing 100 a second over a
 * link that carried 44 and a unit producing 44 read the same here. Nothing
 * here observes a sensor, a link or a phone. It is arithmetic over stamps.
 *
 * NO THRESHOLD. This computes a figure and judges nothing. The committed
 * captures read two clusters, 99.1 to 99.6 and 43.5 to 44.5, and fix no
 * boundary between them.
 */
object DeliveredRate {
    private const val MS_PER_S = 1000.0

    /**
     * One unit's delivery over the window.
     *
     * [hz] is frames per second. [burstSpacingMs] is the median gap between
     * consecutive distinct arrival stamps, or null where the window holds only
     * one distinct stamp.
     */
    data class Measured(val hz: Double, val burstSpacingMs: Long?)

    /**
     * [samples]' delivery over the window, or null where it cannot be stated.
     *
     * [workStartedAtMs] is `PrepWindow.workStartedAtMs`, or null where the set
     * stored no window. [end] is [SetEnd.of] over the set's cue track and its
     * prescription, the same pair [RollExcursion.of] takes. Both are on the
     * rows' own arrival clock.
     *
     * Null on fewer than two rows in the window, and null where the window has
     * no length, which happens only when every windowed row shares one stamp
     * and no known bound lies apart from it. A rate there would be a division
     * by nothing, and a zero would read as a link that delivered nothing.
     * Absence is not a low number.
     */
    fun of(samples: List<ImuSample>, workStartedAtMs: Long?, end: SetEnd): Measured? {
        val windowed = RollExcursion.inWindow(samples, workStartedAtMs, end)
        if (windowed.size < 2) return null
        val fromMs = workStartedAtMs ?: windowed.minOf { it.timestampMs }
        val toMs = (end as? SetEnd.Cued)?.atMs ?: windowed.maxOf { it.timestampMs }
        if (toMs <= fromMs) return null
        return Measured(windowed.size * MS_PER_S / (toMs - fromMs), burstSpacingMs(windowed))
    }

    /** The upper median of the gaps between consecutive distinct stamps, or null with fewer than two. */
    private fun burstSpacingMs(windowed: List<ImuSample>): Long? {
        val stamps = windowed.map { it.timestampMs }.distinct().sorted()
        if (stamps.size < 2) return null
        val gaps = stamps.zipWithNext { earlier, later -> later - earlier }.sorted()
        return gaps[gaps.size / 2]
    }
}
