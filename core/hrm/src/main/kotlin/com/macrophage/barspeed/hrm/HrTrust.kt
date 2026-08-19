package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample

/**
 * What one set's heart-rate stream is willing to say about that set.
 *
 * Every figure is null when the stream does not support it. None of them is
 * ever zero: a heart rate of zero is not a low heart rate, it is the absence of
 * one, and publishing it as a number is how a strap on a table came to export a
 * plausible resting heart rate in the first place.
 *
 * [trustedSamples] and [totalSamples] are counts of what the decision was made
 * over. They are not published anywhere; they exist so a test can assert how
 * much of a stream was believed rather than only what came out of it.
 */
data class HrStreamSummary(
    val endOfSetBpm: Int?,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val minBpm: Int?,
    val trustedSamples: Int,
    val totalSamples: Int,
) {
    companion object {
        val NOTHING = HrStreamSummary(null, null, null, null, trustedSamples = 0, totalSamples = 0)
    }
}

/**
 * Whether a heart-rate sample is a measurement, and what a set's stream
 * summarises to once the ones that are not have been set aside.
 *
 * THE SAMPLE-LEVEL TESTS ARE IMPOSSIBILITIES; THE STREAM-LEVEL ONE IS NOT,
 * and this paragraph said otherwise until issue #83 added the third. A bpm at
 * or below zero and an R-R at or below zero are impossibilities with no
 * population dependence: a resting athlete at 50 bpm passes them exactly as a
 * working athlete at 150 does.
 *
 * The stream-level test added for #83 is a MEASUREMENT compared against a cut,
 * and it does have a population it can be wrong about -- see accountedFraction
 * for which one, why the axis is variability rather than heart rate, and the
 * named class of heart it is known to be wrong for. Two earlier candidate
 * rules were withdrawn because they measured the strap's notification cadence
 * divided by heart rate; the reason this one is not a third is written there,
 * not here.
 *
 * The claim that a resting athlete passes every clause trivially is true of the
 * two sample-level tests and is NOT true of the third.
 *
 * Nothing here drops a sample from the recorded stream. The raw capture keeps
 * every sample the strap sent, zeros included, because it is the only
 * irreplaceable artifact and a later reader may know more than this code does.
 * This is about what the analysis and the export are allowed to say.
 */
object HrTrust {
    /**
     * True when [sample] carries nothing that a working sensor cannot produce.
     *
     * A bpm at or below zero is the strap's own no-reading sentinel. A reported
     * R-R interval at or below zero is a claim that no time passed between two
     * beats, which no beat detector can mean literally; the bpm arriving in the
     * same notification is not treated as independent of it.
     *
     * KNOWN GAP, and it is why this returns true more often than it looks like
     * it should: a sample carrying NO R-R interval at all passes on its bpm
     * alone, because `all` over an empty list is vacuously true. The R-R
     * evidence is the only evidence this function has, and a strap that streams
     * a plausible bpm with the R-R Present flag clear defeats it entirely. That
     * combination has never been observed in any capture held here, and its
     * likelihood is unmeasured rather than low. Tracked as issue #82; the
     * sensor-contact bits are the only remaining signal for it and they are
     * discarded before anything is persisted.
     */
    /**
     * THE DISCRIMINANT IS VARIABILITY, AND THIS IS THE FIRST THING TO KNOW
     * ABOUT IT. A heart's successive intervals differ from each other; a strap
     * that has lost contact holds one interval and re-sends it, so its series
     * has almost none. A SINUS rhythm's sigma does not approach 1 ms -- not
     * when slow, not when maximal, not when the corpus's hardest set drives it
     * to 6.58 ms. That is what this rule rests on.
     *
     * IT IS NOT TRUE OF ALL HEARTS, and an earlier version of this sentence
     * said "no heart is a metronome", which is a universal asserted over a
     * corpus of one subject. A fixed-rate PACED ventricular rhythm has
     * crystal-controlled interval jitter well under a millisecond, and a chest
     * strap detects the R wave whatever drives it. Simulation puts the point at
     * which half of such streams are silenced near sigma 0.5 ms; a paced rhythm
     * sits below it. So this rule would withhold the published block for a
     * paced user, on every set, silently and permanently.
     *
     * The raw capture keeps every sample either way, so nothing is destroyed --
     * but that is the recovery, not a defence. It is a known limitation of the
     * rule and not a property of hearts.
     *
     * It is measured through a TIME BUDGET rather than by computing a variance
     * directly, and that is deliberate. A budget needs no window, no minimum
     * sample count and no distributional assumption; it degrades gracefully on
     * a short stream instead of becoming undefined; and it is the same
     * arithmetic a reader can check by hand against the raw CSV. A variance
     * would need all three and would say less.
     *
     * The mechanism: RrIngest.newBeats removes a notification identical to the
     * one before it. On a real heart that removes re-sent duplicates and a few
     * genuine ties. On a held value it removes nearly everything, so what
     * survives covers a fraction of the elapsed time while a worn strap's
     * intervals TILE it.
     *
     * WHAT THE SHORTFALL IS MADE OF, and the corpus cannot decompose it:
     * shortfall = (genuine ties + dropped beats) / beats. Both terms are
     * present in every set. Ties are removed by the de-duplication even though
     * they were real; drops are beats the strap never reported. Neither term
     * alone accounts for the seventeen worn sets and this does not claim either
     * does.
     *
     * A MECHANISM THAT LOOKS RIGHT AND IS NOT, recorded because the next reader
     * will reach for it. Tie-removal alone predicts the mean shortfall almost
     * exactly -- 0.0811 against 0.0880 observed -- and predicts the ORDERING not
     * at all, r = 0.145. Worn set 08 is among the least variable sets, so
     * tie-removal predicts a large shortfall for it, 0.118; its observed
     * shortfall is the smallest of all seventeen, 0.037. A mechanism whose
     * average is right and whose ordering is backwards is a fit wearing a
     * derivation's clothes.
     *
     * AN EARLIER VERSION OF THIS CLAIMED THE SHORTFALL REQUIRED DROPPED BEATS,
     * hence two beats inside one notification window, hence a heart above
     * 120 bpm. That is FALSE and was falsified against the corpus it was
     * asserted over: ten of the seventeen worn sets contain no interval shorter
     * than 500 ms at all, so no collision is possible in them, and every one
     * still falls short -- worn set 03 by 12.8% with no interval under 500 ms
     * anywhere in it.
     *
     * WHY REST IS THE SAFE END, now derived on the axis that actually governs
     * it. Both terms shrink as the heart slows: drops need two beats inside one
     * ~500 ms window, so they need a fast heart and vanish at rest; ties go as
     * q/(sigma*sqrt(2)), the density law confirmed independently in issue #81
     * at a level of 0.686 against a parameter-free 0.691, so they shrink as
     * variability rises, and resting variability is several times working
     * variability. This is no longer an extrapolation along heart rate from a
     * predictor range that never contained rest.
     */
    const val MIN_ACCOUNTED_FRACTION = 0.35

    /**
     * The smallest number of distinct beats this will judge on.
     *
     * Below three the budget is dominated by one interval's straddle and the
     * margin collapses: the worst two-beat window anywhere in the worn control
     * scores 0.3551 against a cut of 0.35, a margin of 1.015x. At three it is
     * 0.4547.
     *
     * Three costs nothing, which is the point -- the unworn set this rule was
     * built for has EXACTLY three distinct beats, so the guard does not exempt
     * the case it exists to catch. An earlier version of this file declined a
     * guard on the grounds that any useful one would exempt it. That was wrong,
     * and it was asserted rather than measured.
     */
    const val MIN_DISTINCT_BEATS = 3

    /**
     * How much of a stream's elapsed time its own reported beats account for,
     * or null when the stream cannot say.
     *
     * WHY THIS IS NOT THE RULE #83 SAYS NOT TO RE-DERIVE. That issue records an
     * interval-budget check that fired on 16 of 17 worn sets and calls it
     * "unfittable, not mis-tuned" -- correctly, for the version it describes.
     * That version summed the RAW intervals, and this strap re-sends its last
     * completed R-R at a fixed cadence, so the sum measured notification
     * cadence divided by heart rate: worn 0.948 to 1.204 against unworn 1.446,
     * overlapping and ordered the WRONG way. De-duplicating first removes that
     * term. Note what this is NOT: those two figure sets are measured over
     * different populations, so this is not "the same arithmetic on a different
     * series" -- they are different measurements that happen to share a formula.
     *
     * The de-duplication is RrIngest.newBeats rather than a copy of it: one
     * rule about what counts as a beat, in one place, already pinned.
     *
     * Null below MIN_DISTINCT_BEATS surviving beats, which includes a stream
     * carrying no R-R intervals at all. Null means "cannot say", and the caller
     * treats that as publishable rather than silenced.
     */
    fun accountedFraction(samples: List<HrSample>): Double? {
        val trusted = samples.filter(::isTrusted)
        val beats = RrIngest.newBeats(trusted)
        if (beats.size < MIN_DISTINCT_BEATS) return null
        // The first beat began before the first sample arrived, so the time it
        // describes straddles the start; the span it is measured against has to
        // include it or every stream under-accounts by one interval.
        val span = (trusted.last().timestampMs - trusted.first().timestampMs) + beats.first()
        if (span <= 0.0) return null
        return beats.sum() / span
    }

    /**
     * Whether a stream may say anything about heart rate at all.
     *
     * MEASURED ON THE CONTROL: all 17 worn sets of session 26 score 0.8577 to
     * 0.9632 and the one unworn set that still publishes scores 0.17111. The
     * rule costs the worn control nothing and silences the unworn set. Margins
     * 2.45x and 2.05x.
     *
     * THE BOUND, on the axis that governs the rule, stated as the centre
     * estimate it is rather than as a requirement. The closed form
     * 1 - q/(sigma*sqrt(2)) puts the cut at sigma 1.06 ms, but it is a centre
     * with a measured residual of about 0.07 against the corpus and five of the
     * seventeen worn sets score ABOVE it -- the drop term it omits is real.
     * Carrying that term, the honest bound is sigma at or below about 1.38 ms.
     * The lowest sigma of any worn set is 6.58 ms, on the hardest set in it, so
     * the margin is 4.8x rather than the 6.2x the closed form alone suggests.
     * Still a bound on a quantity that can be measured, in place of an
     * extrapolation along a predictor whose range never included rest.
     *
     * A stream that cannot produce a budget is NOT silenced. Issue #82 -- a
     * strap streaming bpm with no R-R at all -- is deliberately not covered:
     * "no budget" is equally true of a genuinely short set and of a strap that
     * connected a second before the set ended, and silencing on missing
     * evidence rests on zero observed instances against this rule's one.
     */
    fun tracksAHeart(samples: List<HrSample>): Boolean {
        val fraction = accountedFraction(samples) ?: return true
        return fraction >= MIN_ACCOUNTED_FRACTION
    }

    fun isTrusted(sample: HrSample): Boolean = sample.bpm > 0 && sample.rrIntervalsMs.all { it > 0.0 }

    /**
     * Summarise one set's stream over its trusted samples only.
     *
     * The mean, the maximum and the minimum are taken over the trusted samples
     * and nothing else -- the same [trusted] list, filtered once, feeds all
     * three. Both extrema carry the same exposure: a single untrusted sample
     * sets one of them permanently, which is how one set of session 28
     * published avgBpm 31 beside maxBpm 50, both from the same 72 samples,
     * before this filter existed. A stray low reading is no less plausible a
     * strap artifact than a stray high one -- issue #82's gap (a sample with no
     * R-R interval at all is trusted on bpm alone) can hand either extremum a
     * single-sample outlier, so [HrStreamSummary.minBpm] is computed by the
     * identical rule as [HrStreamSummary.maxBpm], not a stricter or looser one.
     * The cost of excluding an untrusted sample is that a true extreme landing
     * in a sample that also carries an impossible R-R is lost, and the reported
     * range understates the set on both ends.
     *
     * [HrStreamSummary.endOfSetBpm] is the last sample's bpm, and only when
     * that sample is itself trusted. It is not backfilled from an earlier
     * trusted sample, because the key names a TIME as much as a value: on
     * session 28's third set the last trusted sample is 14,939 ms before the
     * end of the set, and a reading a quarter of a minute old published under
     * that name is a claim the stream does not support. Omitting it costs
     * nothing measurable on real worn data -- across the 17 sets of the worn
     * control not one set has an untrusted final sample.
     *
     * A stream with no trusted sample in it summarises to nothing at all, which
     * is a different fact from a set recorded with no strap connected and
     * reaches the export the same way: as an absent block rather than a number.
     */
    fun summarize(samples: List<HrSample>): HrStreamSummary {
        if (samples.isEmpty()) return HrStreamSummary.NOTHING
        val trusted = samples.filter(::isTrusted)
        val bpm = trusted.map { it.bpm }
        return HrStreamSummary(
            endOfSetBpm = samples.last().takeIf(::isTrusted)?.bpm,
            avgBpm = if (bpm.isEmpty()) null else bpm.average().toInt(),
            maxBpm = bpm.maxOrNull(),
            minBpm = bpm.minOrNull(),
            trustedSamples = trusted.size,
            totalSamples = samples.size,
        )
    }
}
