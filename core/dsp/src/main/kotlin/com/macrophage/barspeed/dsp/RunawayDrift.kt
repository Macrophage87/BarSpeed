package com.macrophage.barspeed.dsp

import kotlin.math.abs

/**
 * Second-stage drift removal, for the BATCH path only.
 *
 * ## What it is for
 *
 * [VelocityEstimator.applyZupt] corrects drift between quiet windows it can
 * find. Where it finds none for tens of seconds -- continuous cycling on a
 * bar-mounted sensor is the case measured -- the corrected velocity keeps a
 * residual offset, never returns to the dead band, and the whole stretch
 * becomes ONE same-sign run.
 *
 * [RepSegmenter.classifyRunsDetailed] already declares such a run impossible:
 * no real phase displaces beyond [DspConfig.maxRunDisplacementM]. What it then
 * does with it is demote it to STILL, which is the defect -- the stretch is
 * neither a phase nor a pause, and calling it a pause discards every rep
 * inside it. Both pairing rules skip STILL runs, so a runaway is transparent:
 * the eccentric before it can pair with the concentric after it across the
 * reps it swallowed.
 *
 * ## The rule, and why it needs no new constant
 *
 * A run displacing beyond the cap is, by the cap's own premise, not one stroke
 * of a lift. Over a stretch spanning whole reps the bar returns to where it
 * started, so the stretch's own MEAN velocity is the offset the anchor pass
 * failed to remove. Subtract it and re-classify.
 *
 * The two thresholds read here -- [DspConfig.maxRunDisplacementM] for what
 * counts as a runaway and [DspConfig.pauseBandMps] for what counts as
 * same-sign motion -- are the two the segmenter already reads. Nothing here
 * introduces a tunable.
 *
 * ## What it does NOT claim
 *
 * It does not claim the recovered reps' ROM or velocity are right. Measured on
 * `field-bench-3010-6rep-s37-set05`, the recovered reps still read 0.14-0.90 m
 * on a bench whose ROM this corpus measures at 0.333-0.345 m. What is measured
 * is that the reps become VISIBLE and land on the metronome's marks --
 * `BatchCueCoverageTest` scores them window by window. Magnitude is a separate
 * defect and issue #115 is where it lives.
 *
 * It runs in [SetAnalyzer] and nowhere else. [StreamingSetTracker] integrates
 * per arriving sample with no set to take a mean over, so nothing here reaches
 * the live counter or changes what is recorded.
 */
object RunawayDrift {
    /**
     * Hard bound on the fixed-point iteration, so the loop terminates whatever
     * the input.
     *
     * Removing a runaway's mean can expose a shorter runaway inside it, so one
     * pass is not always enough. Measured over the 30 committed captures: 11
     * need no pass at all, 17 settle after one, `field-rdl-3010-10rep-s36-set05`
     * after two and `field-rdl-3010-10rep-s36-set04` after four. Both of those
     * are session 36 Romanian deadlifts, which is the family issue #94's
     * field-36 comment calls the failure family. The bound is twice the largest
     * number anyone has needed and is a termination guarantee, not a tuning
     * knob; `RunawayDriftTest` pins the distribution.
     */
    const val MAX_PASSES = 8

    /**
     * [series] with the mean removed from every same-sign run that displaces
     * beyond [DspConfig.maxRunDisplacementM], repeated to a fixed point.
     *
     * Returns the input UNTOUCHED, same instance, when no run exceeds the cap.
     * That is the contract that bounds the blast radius: a capture with no
     * runaway is bit-identical before and after, which is what
     * `RunawayDriftTest` and `BatchCueCoverageTest` assert rather than assume.
     */
    fun corrected(series: VelocitySeries, config: DspConfig): VelocitySeries {
        var velocity: DoubleArray? = null
        var pass = 0
        while (pass < MAX_PASSES) {
            val current = velocity ?: series.velocityMps
            val runs = runaways(current, series.timeS, config)
            if (runs.isEmpty()) break
            val next = current.copyOf()
            for (run in runs) {
                val mean = meanVelocity(current, series.timeS, run)
                for (k in run) next[k] = current[k] - mean
            }
            velocity = next
            pass++
        }
        return if (velocity == null) series else series.copy(velocityMps = velocity)
    }

    /**
     * The index ranges of same-sign runs displacing beyond the cap.
     *
     * Same-sign is judged against [DspConfig.pauseBandMps], the dead band
     * [RepSegmenter.classifyRunsDetailed] classifies with, so a run here is the
     * same object the segmenter would form and reject.
     */
    internal fun runaways(velocity: DoubleArray, timeS: DoubleArray, config: DspConfig): List<IntRange> {
        val n = velocity.size
        val sign = IntArray(n) {
            when {
                velocity[it] > config.pauseBandMps -> 1
                velocity[it] < -config.pauseBandMps -> -1
                else -> 0
            }
        }
        val out = mutableListOf<IntRange>()
        var start = 0
        for (i in 1..n) {
            if (i < n && sign[i] == sign[start]) continue
            val end = i - 1
            if (sign[start] != 0 && end > start) {
                var displacement = 0.0
                for (k in start + 1..end) displacement += abs(velocity[k]) * (timeS[k] - timeS[k - 1])
                if (displacement > config.maxRunDisplacementM) out += start..end
            }
            start = i
        }
        return out
    }

    /** Time-weighted mean of [velocity] over [run]; 0.0 for a run of no duration. */
    private fun meanVelocity(velocity: DoubleArray, timeS: DoubleArray, run: IntRange): Double {
        val durationS = timeS[run.last] - timeS[run.first]
        if (durationS <= 0.0) return 0.0
        var net = 0.0
        for (k in run.first + 1..run.last) net += velocity[k] * (timeS[k] - timeS[k - 1])
        return net / durationS
    }
}
