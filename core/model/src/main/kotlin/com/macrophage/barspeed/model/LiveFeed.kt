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
     * THE SAME PURE FUNCTION THE ANALYSIS USES, and deliberately not a second
     * rule beside it. Which stream is worth reading is
     * [SensorCapturePolicy.analysedStream]'s question, and asking it twice in
     * two spellings is how the readout and the summary come to disagree about
     * which unit the set was measured from. What this adds is the LATCH and
     * nothing else.
     *
     * THE LATCH, which is the only rule here that [SensorCapturePolicy] does
     * not already state: once the feed has left the armed stream it stays gone
     * for the rest of the set. Without it the answer flips back the moment a
     * recovered armed unit passes [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES],
     * and a readout that changes stream twice mid-set is a rep counter
     * restarting twice.
     *
     * WHAT THE SWITCH COSTS, stated rather than implied. The tracker is NOT
     * rebuilt: the caller goes on handing the same tracker frames, from the
     * other flow. So no partial count is discarded, no rep already spoken is
     * retracted, and the count cannot go backwards. What stays folded into the
     * tracker at the moment of the switch is whatever the armed unit did
     * deliver, which the switch's own condition bounds at
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] minus one frame. Both units'
     * samples carry HOST arrival timestamps, so the tracker's reconstructed
     * clock is not broken by the change of source. What no test here can show
     * is what those few frames do to a live velocity on real hardware; that is
     * a field question and is filed as one.
     *
     * IT DECIDES NOTHING ABOUT WHAT IS RECORDED. The buffers, the journals and
     * the archived raw streams are untouched by this answer, and the set's
     * published figures remain [SensorCapturePolicy.analysedStream]'s answer
     * taken over the WHOLE set. The two can differ: an armed unit that is
     * quiet long enough to lose the readout and then delivers a full capture
     * ends the set analysable, so the summary comes from the armed stream
     * while the readout followed the partner. That is the price of deciding
     * live with part of the set, and it is a difference in what was SHOWN, not
     * in what was kept.
     *
     * QUIET LONG ENOUGH IS EIGHT FRAMES --
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]. `RecordViewModel.beginSet`
     * clears both buffers, so on a set where both units are armed and both are
     * streaming normally the readout can still move: it moves whenever the
     * partner's eighth frame arrives before the armed unit's own eighth frame,
     * roughly 80 ms at the 100 Hz WT901 output rate this app configures
     * (`GattClients`). SHOWN and KEPT can therefore differ on a dual set where
     * nothing is wrong with either sensor -- a field question about whether
     * that is tolerable to read, not a defect this file can fix.
     */
    fun liveFeed(armed: SensorRole?, fedBy: SensorRole?, analysable: List<SensorRole>): LiveFeed {
        val latched = fedBy?.takeIf { it != armed }
        if (latched != null) return LiveFeed(role = latched, fellBack = true, switched = false)
        val decision = SensorCapturePolicy.analysedStream(armed, analysable)
        return LiveFeed(role = decision.role, fellBack = decision.fellBack, switched = decision.role != fedBy)
    }

    /**
     * Whether the collector for [streamRole] is the one that feeds the tracker.
     *
     * A null [LiveFeed.role] means no role is in play, and the stream that
     * carries the set is then the unroled one -- so a null feed matches a null
     * stream role and nothing else, which is what an equality on two nullable
     * roles already says.
     *
     * CORRECTION, forward. The first version of this KDoc, in the commit that
     * introduced this function, said the branching form was written that way
     * round rather than as a plain equality "because the failure direction
     * matters". That was false: the two forms agree on every input, as
     * mutating this line to the equality and finding no test reddened showed.
     * The branch is gone and the claim with it.
     */
    fun feedsTracker(feed: LiveFeed, streamRole: SensorRole?): Boolean = feed.role == streamRole
}
