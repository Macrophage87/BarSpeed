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
    val trustedSamples: Int,
    val totalSamples: Int,
) {
    companion object {
        val NOTHING = HrStreamSummary(null, null, null, trustedSamples = 0, totalSamples = 0)
    }
}

/**
 * Whether a heart-rate sample is a measurement, and what a set's stream
 * summarises to once the ones that are not have been set aside.
 *
 * Both tests here are IMPOSSIBILITIES, not statistics, and that is the whole
 * design. Two earlier candidate rules for this defect were statistics over the
 * series -- an interval-budget check and a median-successive-difference check
 * -- and both were withdrawn because both turned out to measure the strap's
 * fixed notification cadence divided by heart rate. A statistic over the series
 * has a rate term whether or not its author intended one, so it separates fast
 * hearts from slow hearts rather than worn straps from unworn ones. An
 * impossibility has no population dependence: a resting athlete at 50 bpm
 * reports bpm 50 and an R-R near 1,200 ms and passes both clauses trivially,
 * exactly as a working athlete at 150 does.
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
    fun isTrusted(sample: HrSample): Boolean = sample.bpm > 0 && sample.rrIntervalsMs.all { it > 0.0 }

    /**
     * Summarise one set's stream over its trusted samples only.
     *
     * The mean and the maximum are taken over the trusted samples and nothing
     * else. A maximum is an extremum, so a single untrusted sample sets it
     * permanently -- that is how one set of session 28 published avgBpm 31
     * beside maxBpm 50, both from the same 72 samples. The cost of excluding
     * them is that a true peak landing in a sample that also carries an
     * impossible R-R is lost and the reported maximum understates the set.
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
            trustedSamples = trusted.size,
            totalSamples = samples.size,
        )
    }
}
