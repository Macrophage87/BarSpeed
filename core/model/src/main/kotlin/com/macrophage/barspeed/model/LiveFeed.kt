package com.macrophage.barspeed.model

/**
 * Which stream the LIVE readout is fed from while a set is still running, and
 * whether that is the stream the set armed (#210).
 *
 * THE SAME QUESTION [SensorCapturePolicy.analysedStream] ANSWERS AT SET END,
 * asked earlier and with less to go on. #207 moved the ANALYSIS off an armed
 * unit that delivered nothing, and #209 narrowed "delivered" to "delivered
 * enough frames to analyse"; both run once, when the set is stored. The live
 * path never asked the question at all, so on exactly the set those two
 * repair -- armed unit quiet, partner streaming -- the lifter reads a velocity
 * of zero, hears no rep called and no tempo counted, and the repaired summary
 * appears only afterwards on the rest screen.
 *
 * [switched] is the fact the caller cannot re-derive: [role] alone says which
 * stream feeds the tracker now, not that this call is the one that moved it.
 * No production caller reads it today -- RecordViewModel re-decides on every
 * frame and uses [role] alone -- so [switched] is pinned by LiveFeedPolicyTest
 * and by nothing that ships.
 */
data class LiveFeed(
    /**
     * The role whose samples feed the tracker, or null when no role is in play
     * at all -- the ordinary one-sensor set, and the set that met two paired
     * units it could not tell apart. Both capture one unroled stream.
     */
    val role: SensorRole?,
    /** True when [role] is not the role the set armed for analysis. */
    val fellBack: Boolean,
    /** True on the single call that moved the feed off the armed stream. */
    val switched: Boolean,
)

/**
 * Which stream the in-set tracker is fed from (#210).
 *
 * A `:core:model` object for [SensorCapturePolicy]'s reason, and more sharply:
 * `:app`'s live path has no test on the CI path at all, so a rule left in
 * [com.macrophage.barspeed.record.RecordViewModel] is a rule nothing can run
 * against. This is the "extract a pure seam and pin it" move.
 *
 * WHAT IS RECORDED IS NOT DECIDED HERE and is not changed by anything here.
 * Both buffers are filled and both raw streams are archived exactly as before,
 * whichever one the readout followed; the set's published figures stay
 * [SensorCapturePolicy.analysedStream]'s answer, taken over the whole set.
 */
object LiveFeedPolicy {
    /**
     * Which role feeds the tracker, given what has arrived so far.
     *
     * [fedBy] is the role feeding the tracker now -- the armed role at the
     * start of a set -- and [analysable] is
     * [SensorCapturePolicy.analysable]'s answer over the frames counted SO FAR,
     * which is why the answer can change while the set runs.
     *
     * TWO HALVES, AND ONLY ONE OF THEM IS HERE YET. The LATCH is: once the feed
     * has left the armed stream it stays gone, so a set has at most one switch
     * and no rep boundary is announced twice. That half is implemented and
     * pinned. The SWITCH -- moving the feed onto a partner that is delivering
     * while the armed unit is not -- is the behaviour change, and it is the
     * only reader [analysable] has, so the parameter is suppressed here and the
     * suppression goes when the switch lands. Until then this is a
     * characterization of the shipped rule: the live path reads the armed
     * buffer and nothing else.
     */
    @Suppress("UnusedParameter")
    fun liveFeed(armed: SensorRole?, fedBy: SensorRole?, analysable: List<SensorRole>): LiveFeed {
        val latched = fedBy?.takeIf { it != armed }
        return LiveFeed(role = latched ?: armed, fellBack = latched != null, switched = false)
    }

    /**
     * Whether the collector for [streamRole] is the one that feeds the tracker.
     *
     * A null [LiveFeed.role] means no role is in play, and the stream that
     * carries the set is then the unroled one -- so a null feed matches a null
     * stream role and nothing else. Written that way round rather than as
     * `feed.role != streamRole` because the failure direction matters: the
     * loose form would let a second collector feed the tracker on a set with no
     * roles, and the bar would appear to move twice.
     */
    fun feedsTracker(feed: LiveFeed, streamRole: SensorRole?): Boolean =
        if (feed.role == null) streamRole == null else feed.role == streamRole
}
