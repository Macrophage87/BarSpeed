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
     * start of a set -- and [framesByRole] is how many frames each of
     * [expected] has delivered SO FAR, which is why the answer can change
     * while the set runs.
     *
     * IT TAKES COUNTS RATHER THAN [SensorCapturePolicy.analysable]'S ANSWER,
     * and takes [expected] with them so it can ask that question itself; the
     * caller used to ask it and hand the list down. The counts are here
     * because the MARGIN below is a difference between two of them, which a
     * list of roles cannot state.
     *
     * IT CALLS THE PURE FUNCTIONS THE ANALYSIS USES AND ADDS TWO RULES OF ITS
     * OWN. [SensorCapturePolicy.analysable] and
     * [SensorCapturePolicy.analysedStream] decide which streams are worth
     * reading and which one to read, exactly as they do at set end, and they
     * are not re-spelled here. What is added is the LATCH and the MARGIN, and
     * both exist because this question is asked mid-set on a partial capture
     * while the analysis asks it once on a whole one. A PREVIOUS VERSION OF
     * THIS PARAGRAPH SAID THIS WAS "THE SAME PURE FUNCTION THE ANALYSIS USES"
     * AND THAT WHAT IT ADDED WAS "THE LATCH AND NOTHING ELSE": true when it
     * was written, made false by the margin, and deleted rather than reworded.
     * The set-end rule is deliberately NOT given a margin -- the whole set is
     * in by then and there is no race to lose.
     *
     * THE LATCH, the first of the two rules here that [SensorCapturePolicy]
     * does not state: once the feed has left the armed stream it stays gone
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
     * THE MARGIN, the second rule [SensorCapturePolicy] does not state, and
     * the answer to a defect this file DID have. Two conditions now, not one:
     * the armed unit must be under [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]
     * -- unchanged -- AND the unit taking the readout must have delivered at
     * least [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] MORE frames than it.
     * Eight frames is roughly 80 ms at the 100 Hz WT901 output rate this app
     * configures (`GattClients`), so what the margin asks is that the armed
     * link be most of a tenth of a second behind rather than one frame behind.
     *
     * WITHOUT IT THE READOUT MOVED ON A HEALTHY DUAL SET. `RecordViewModel`
     * clears both buffers at `beginSet`, so the two units race from zero, and
     * the rule as first written moved the readout whenever the partner's
     * EIGHTH frame arrived before the armed unit's own eighth -- a lead of one
     * frame -- and then latched it for the rest of the set. THIS PARAGRAPH
     * REPLACES ONE THAT DESCRIBED THAT RACE AND CALLED IT "a field question
     * about whether that is tolerable to read, not a defect this file can
     * fix". It was a defect this file could fix, it is fixed here, and the
     * sentence is deleted rather than reworded (#210, round 3).
     *
     * WHAT THE MARGIN DOES NOT FIX, and the remainder is not small. The race
     * is narrowed, not removed: an armed link whose FIRST frame arrives eight
     * frames after its partner's -- one that connects late, or drops its
     * opening burst -- still loses the readout and still latches. What the
     * margin buys is that a link merely interleaving unluckily with its
     * partner keeps the readout, and that once the armed unit is analysable at
     * all the readout cannot move for the rest of the set. WHICH OF THOSE TWO
     * POPULATIONS A REAL DUAL SESSION PRODUCES HAS NOT BEEN MEASURED: no
     * capture in this repository holds a dual set with both units streaming,
     * so the frame this rule is tuned against is reasoned from the configured
     * output rate and not observed. It is a field question and is raised as
     * one.
     */
    fun liveFeed(
        armed: SensorRole?,
        fedBy: SensorRole?,
        expected: List<SensorRole>,
        framesByRole: Map<SensorRole, Int>,
    ): LiveFeed {
        val latched = fedBy?.takeIf { it != armed }
        if (latched != null) return LiveFeed(role = latched, fellBack = true, switched = false)
        val analysable = SensorCapturePolicy.analysable(expected, framesByRole)
        val armedFrames = framesByRole[armed] ?: 0
        val ahead =
            analysable.filter {
                (framesByRole[it] ?: 0) - armedFrames >= SensorCapturePolicy.MIN_ANALYSABLE_FRAMES
            }
        val candidates = if (armed != null && armed in analysable) analysable else ahead
        val decision = SensorCapturePolicy.analysedStream(armed, candidates)
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
