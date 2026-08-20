package com.macrophage.barspeed.hrm

import com.macrophage.barspeed.model.HrSample
import kotlin.math.sqrt

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
     * THE DISCRIMINANT IS COVERAGE. WHAT BOUNDS THE FALSE POSITIVES IS
     * VARIABILITY. Those are two different statements and this paragraph used
     * to make only the second, in the words of the first.
     *
     * What silences a stream is the fraction of its own elapsed time its
     * de-duplicated beats account for, and nothing else. What makes that safe
     * for a real wearer is that a genuine stream's shortfall comes from ties,
     * ties go as q/(sigma*sqrt(2)), and a SINUS rhythm's sigma does not
     * approach 1 ms -- not when slow, not when maximal, not when the corpus's
     * hardest set drives it to 6.58 ms. That is the bound, and
     * [silencingSigmaMs] is where it lives.
     *
     * THE SENTENCE THIS REPLACES SAID A STRAP THAT HAS LOST CONTACT "HOLDS ONE
     * INTERVAL AND RE-SENDS IT, SO ITS SERIES HAS ALMOST NO VARIABILITY", AND
     * THAT IS FALSE OF THE SERIES THIS FILE MEASURES. It is true of the raw
     * notification stream. It is not true after [RrIngest.newBeats], which is
     * the only series anything here takes a sigma over: the unworn set of
     * session 28 held three values for 24 seconds, every repeat was removed,
     * and the three that survive -- 1902.3, 1607.4 and 1003.9 ms -- give it a
     * sigma of 474.96 ms. That is the LARGEST of any stream in the corpus,
     * worn or not.
     *
     * SO VARIABILITY IS NOT A WEAKER DISCRIMINANT HERE. IT IS NOT ONE IN
     * EITHER DIRECTION. A rule "silence when sigma is below X" needs X above
     * 474.96 ms to reach the unworn set, and every worn stream sits below
     * 391.02 ms, so that rule silences the whole corpus. A rule "silence when
     * sigma is above X" would silence the most variable worn streams first.
     * The unworn set is at the wrong END of this axis for a floor to reach it,
     * which is why the rule reads coverage: 0.171 against a 0.35 cut. Pinned
     * by `FieldHrTrustDischargeTest.the unworn set is the most variable stream
     * held here, not the least`, which also records that RMSSD over the RAW
     * notifications is 99.04 ms -- the withdrawn sentence was true of a series
     * this file does not measure.
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
     * AND MOVING THE CUT CANNOT FIX IT, which is worth saying here because the
     * cut is the first thing a maintainer will reach for. This rule cannot
     * distinguish a paced rhythm from a held, re-sent value: on the axis it
     * measures the two genuinely look the same, near-zero variability in both
     * cases, and no threshold separates two populations that overlap at the
     * point of measurement. A remedy would have to come from outside the
     * series -- a per-user opt-out, or a signal the strap gives about what it
     * is tracking -- not from a different number here.
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
     * WHY REST IS THE SAFE END, on the axis that actually governs it. Both
     * terms shrink as the heart slows: drops need two beats inside one ~500 ms
     * window, so they need a fast heart and vanish at rest; ties go as
     * q/(sigma*sqrt(2)), the density law confirmed independently in issue #81
     * at a level of 0.686 against a parameter-free 0.691, so they shrink as
     * variability RISES.
     *
     * THIS PARAGRAPH USED TO END "AND RESTING VARIABILITY IS SEVERAL TIMES
     * WORKING VARIABILITY". That is not what the data shows. Paired against
     * the set each rest period precedes across the two 0.1.40 captures, the
     * median rest-to-set ratio is 1.26 and four of the twenty rest streams are
     * LESS variable than their own set. It was an assumption stated as a
     * finding, and it is withdrawn rather than reworded.
     *
     * What survives it is the part that was load-bearing anyway, and that part
     * is measured. What the bound is compared with is the FLOOR, not the
     * median, and the floor does not fall at rest: the least variable rest
     * stream is 8.23 ms, and the least variable stream of any kind in any
     * capture held here is 6.58 ms, an in-set stream of session 26. Pinned by
     * `FieldHrRestingBandTest.the resting captures do not lower the
     * variability floor`.
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
     * The grain the strap reports R-R intervals on: 1/1024 s.
     *
     * It is why two consecutive beats can land on the same reported value at
     * all, and so it is the numerator of every tie-rate figure below. Named
     * here because it was written out as `1000.0 / 1024.0` in three places and
     * a constant that appears three times is a constant that will disagree with
     * itself once.
     */
    const val RR_QUANTUM_MS = 1000.0 / 1024.0

    /**
     * The fraction TIE REMOVAL ALONE predicts for a stream of variability
     * [sigmaMs], and it is a CENTRE rather than a floor.
     *
     * Two adjacent intervals that quantise to the same 1/1024 s bucket are
     * removed by the de-duplication even though both beats happened, and the
     * density of that coincidence goes as q/(sigma*sqrt(2)) -- the law
     * confirmed independently in issue #81 at a level of 0.686 against a
     * parameter-free 0.691. So a stream of variability [sigmaMs] is expected to
     * account for about this much of its own time.
     *
     * It OMITS dropped beats, which is why it is a centre and not a bound. Real
     * streams scatter on both sides of it; [MAX_SHORTFALL_BELOW_TIE_ONLY] is
     * how far below it any real worn stream has been measured to fall, and
     * [silencingSigmaMs] is what the two of them together imply.
     */
    fun tieOnlyFraction(sigmaMs: Double): Double = 1.0 - RR_QUANTUM_MS / (sigmaMs * sqrt(2.0))

    /**
     * How far BELOW [tieOnlyFraction] a real worn stream has been measured to
     * fall. Measured over a corpus, not derived, and the corpus is named where
     * it is asserted.
     *
     * This is the drop term the closed form omits, carried as a single worst
     * case rather than modelled. It is NOT part of the rule: nothing in
     * [tracksAHeart] reads it. It exists so that [silencingSigmaMs] -- the
     * number the whole resting argument rests on -- is arithmetic a test can
     * red rather than a sentence in a comment that goes quietly false.
     *
     * `FieldHrTrustDischargeTest.the closed form's residual is bounded by the
     * constant that carries it` pins it from both sides: it may not sit below
     * the largest residual any committed capture shows, and it may not be
     * padded far above it either. A one-sided pin here would let the number be
     * made safe by making it meaningless.
     *
     * THE MEASUREMENT IS 0.180714, ACROSS ALL 57 WORN STREAMS held here --
     * session 26's seventeen sets plus the forty streams of sessions 30 and
     * 31. This constant is 0.181, that figure rounded UP, so it stays above
     * the data by construction. It was 0.07 while session 26 was the whole
     * corpus, and 0.0649 is what session 26 actually shows, so seventeen
     * streams were never a bound on this quantity; they were the worst of
     * seventeen. The stream that sets
     * it is the rest before session 30's first set, which is also the shortest
     * in the corpus at 17 distinct beats and contains a 300 ms interval
     * followed immediately by a 1,600 ms one.
     *
     * THE RESIDUAL IS LARGEST WHERE SIGMA IS LARGEST, which is why carrying a
     * single worst case is conservative here rather than optimistic. Across
     * the 57 the correlation between sigma and residual is +0.57, and among
     * the 36 streams under 15 ms -- the decade the bound is actually evaluated
     * in -- the worst residual is 0.0778, not 0.181. Carrying the corpus-wide
     * maximum down to a sigma near 1 ms applies the drop term of the most
     * variable streams to the least variable ones.
     */
    const val MAX_SHORTFALL_BELOW_TIE_ONLY = 0.181

    /**
     * The variability at which a genuine wearer would be silenced.
     *
     * THIS IS THE WHOLE RESTING ARGUMENT, as arithmetic. Set
     * [tieOnlyFraction] minus [MAX_SHORTFALL_BELOW_TIE_ONLY] equal to
     * [MIN_ACCOUNTED_FRACTION] and solve for sigma. A stream more variable than
     * this cannot be silenced by this rule however slow the heart driving it,
     * because the rule reads variability and not rate.
     *
     * Compare it against the lowest sigma any real worn stream reaches, which
     * is what `FieldHrTrustDischargeTest.the bound the resting argument rests
     * on, against the corpus that has to clear it` does. The comparison is the
     * claim; this function is only one side of it.
     */
    fun silencingSigmaMs(): Double =
        RR_QUANTUM_MS / ((1.0 - MIN_ACCOUNTED_FRACTION - MAX_SHORTFALL_BELOW_TIE_ONLY) * sqrt(2.0))

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
     * AND ON THE TWO CAPTURES THE BRANCH WAS HELD FOR: forty more worn streams
     * at 0.1.40, twenty in-set and twenty rest-before-set, 8,044 samples.
     * Nothing is withheld. The narrowest is 0.8175, a margin of 2.34x, and it
     * is a REST stream -- the regime the hold was about. The discharge is
     * `FieldHrRestingBandTest`, which also states what those captures do not
     * reach: their slowest sample is 67 bpm against the 46 the unworn strap
     * published, and only 16 of the 8,044 are below 70. Below 67 bpm this rule
     * is still argued rather than measured.
     *
     * THE BOUND, on the axis that governs the rule, and it is now arithmetic
     * rather than prose: [silencingSigmaMs]. [tieOnlyFraction] alone puts the
     * cut at sigma 1.06 ms, but it is a centre and not a floor -- five of the
     * seventeen worn sets of session 26 score ABOVE it, so the drop term it
     * omits is real. Carrying that term through
     * [MAX_SHORTFALL_BELOW_TIE_ONLY] gives the honest bound, and the lowest
     * sigma any real worn stream reaches is what it has to be compared with.
     *
     * A PRIOR VERSION OF THIS PARAGRAPH PUT THE HONEST BOUND AT 1.38 ms AND
     * THE MARGIN AT 4.8x, AND BOTH WERE WRONG. 1.38 ms is what you get by
     * carrying 0.15 -- the padded band a test happened to assert -- through the
     * arithmetic, not by carrying the 0.07 residual the same sentence named.
     * The two numbers sat one line apart and did not agree. That is the reason
     * this is a function now: a derivation written as a sentence can carry a
     * figure that no longer follows from the figure beside it, and nothing
     * notices.
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
        // A stream whose own beats cannot account for the time it covers
        // publishes nothing, however plausible each sample looks alone. Issue
        // #83: a strap on a table produced avgBpm 46, maxBpm 46 and minBpm 46
        // from 47 samples that every sample-level test passes.
        //
        // The counts are still reported, and deliberately: the sample-level
        // decision has not moved, and a reader comparing trustedSamples with
        // three null figures can see that this was a decision about the STREAM
        // rather than about its samples.
        if (!tracksAHeart(samples)) {
            return HrStreamSummary(null, null, null, null, samples.count(::isTrusted), samples.size)
        }
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
