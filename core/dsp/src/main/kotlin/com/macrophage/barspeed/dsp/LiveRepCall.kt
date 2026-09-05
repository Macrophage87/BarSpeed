package com.macrophage.barspeed.dsp

/**
 * What the in-set voice should do with the sample just fed.
 *
 * Two states and no third. There is no "unsure" case, because an unsure case
 * that speaks is a wrong number and an unsure case that does not is [Hold] --
 * issue #145's first design default, and the repo's absence-over-wrong-value
 * rule applied to a thing a lifter hears and cannot un-hear.
 */
sealed interface RepCall {
    /** Say nothing. */
    data object Hold : RepCall

    /**
     * Say [count] now.
     *
     * The RUNNING TOTAL, not "one more rep": if the detector resolved two reps
     * between one sample and the next, the number spoken is the later one and
     * the earlier is never said. A lifter counting along wants the number they
     * are on, and saying two numbers a sample apart is worse than saying one.
     *
     * [atTimestampMs] is the arrival stamp of the sample that closed the
     * decision, on the same epoch-ms clock as the IMU stream, the cue track
     * and the rep marks -- so a caller writing this to the cue track writes an
     * instant every other stream can be lined up against.
     */
    data class Speak(val count: Int, val atTimestampMs: Long) : RepCall
}

/**
 * The batch rep detector, applied to the set as it arrives. Issue #145.
 *
 * ## One detector, one rule
 *
 * `StreamingSetTracker` counts reps with its own pairing logic --
 * `onQualifiedRun`, `eccentricPending`, `countRep` -- which is a second,
 * hand-written statement of what [RepSegmenter] does for the batch path. Two
 * statements of one rule is how the app comes to show one number and export
 * another. This class deletes the second statement from the decision that
 * gets SPOKEN: it hands [RepSegmenter.segment] the velocity the tracker has
 * published so far and speaks the count that comes back.
 *
 * WHAT IS AND IS NOT SHARED, stated because "the same detector" is exactly the
 * claim that hardens into more than it is. The PAIRING AND QUALIFICATION rule
 * is shared and is literally the same code, including the eccentric-first
 * `loweredSince` fallback and the `minRomM` floor. The VELOCITY is not and
 * cannot be: `VelocityEstimator.estimate` measures the sample rate from the
 * whole set's arrival span and corrects drift retroactively, neither of which
 * a set still in progress can do. So this runs the batch rule over a causal
 * estimate, and the two disagree wherever the estimates do.
 *
 * ## Where the announcement lands, as this commit leaves it
 *
 * Nothing here has a rest detector, and issue #145 asks for the count at the
 * inter-rep rest. What this commit does instead is speak the moment the
 * segmenter's count rises for any reason, INCLUDING a span whose drive run is
 * still the open run at the end of the prefix -- a rep the detector has not
 * finished watching. That is not the rest and the difference is not cosmetic:
 * an open run can still fail the displacement cap and stop being a rep, after
 * the number has been said. [contradicted] counts how often it does.
 *
 * ## Cost
 *
 * One segmentation per sample over the whole set so far, so the work grows
 * linearly within a set. Measured over the thirteen mark-carrying captures on
 * the machine that ran the suite -- see `LiveRepCallTest` -- and reported
 * there rather than claimed here, because it is a measurement of a JVM on a
 * desktop and says nothing certain about a phone.
 *
 * ## What this class does not do
 *
 * It does not decide whether the voice is on, whether this set is counted by
 * the sensor at all, or what words are said. `SetVoicePolicy` owns the first
 * two and `VoiceMilestonePolicy` the third. **Nothing in `:app` calls this
 * yet**: on today's sets `SetVoicePolicy.sensorCounts` is false for a
 * rep-based straight set, and un-gating it is blocked on evidence this corpus
 * cannot supply -- see `LiveRepCallTest`'s measured scoring and issue #145.
 */
class LiveRepCaller(
    private val direction: LiftDirection = LiftDirection(),
    private val config: DspConfig = DspConfig(),
) {
    private var timeS = DoubleArray(INITIAL_CAPACITY)
    private var accelMps2 = DoubleArray(INITIAL_CAPACITY)
    private var velocityMps = DoubleArray(INITIAL_CAPACITY)
    private var size = 0

    /** The last number [feed] returned a [RepCall.Speak] for; 0 before the first. */
    var spoken: Int = 0
        private set

    /**
     * Samples at which the detector held FEWER reps than the number already
     * spoken -- a count the lifter has heard that the set, one sample later,
     * no longer contains.
     *
     * [spoken] never goes down, so this is the only place a taken-back number
     * is visible at all. An `Int` that only rises reads exactly like a correct
     * count no matter what the detector did behind it, which is the repo's
     * *absence rendered as a value* class; this is the separation
     * `LiveSetState.countTrusted` makes for the same reason.
     *
     * Counted per SAMPLE, not per rep: one contradiction that persists for
     * fifty samples counts fifty. The figure is a measure of how much of the
     * set was spent standing behind a number the detector had withdrawn, and
     * the only value that means anything is zero.
     */
    var contradicted: Int = 0
        private set

    /**
     * Take one published live sample and say what the voice should do.
     *
     * [live] is `StreamingSetTracker.feed`'s own return value, so the caller
     * feeds the tracker it already has rather than this class running a second
     * integrator over the same stream: two integrators over one stream is the
     * duplication this class exists to remove, not one to add.
     *
     * [timestampMs] is the arrival stamp of the sample that produced [live].
     * It is passed rather than read off [live] because [LiveSetState] carries
     * the reconstructed clock and not the arrival one, and the instant a cue
     * is written at has to be comparable with the other recorded streams.
     */
    fun feed(live: LiveSetState, timestampMs: Long): RepCall {
        append(live)
        if (size < MIN_SAMPLES) return RepCall.Hold
        val series = VelocitySeries(
            timeS = timeS.copyOf(size),
            accelMps2 = accelMps2.copyOf(size),
            velocityMps = velocityMps.copyOf(size),
            sampleRateHz = rateHz(),
        )
        val detected = countDetected(series)
        if (detected < spoken) contradicted++
        if (detected <= spoken) return RepCall.Hold
        spoken = detected
        return RepCall.Speak(detected, timestampMs)
    }

    /**
     * How many reps the detector has resolved in the prefix so far.
     *
     * Every span the segmenter returns, including one whose drive run is still
     * the open run at the end of the prefix.
     */
    private fun countDetected(series: VelocitySeries): Int = RepSegmenter.segment(series, direction, config).size

    /**
     * The prefix's mean frame rate, `(n - 1) / elapsed`.
     *
     * [VelocitySeries.sampleRateHz] is not read by [RepSegmenter] and is
     * carried here only because the type has the field. It is the rate of the
     * RECONSTRUCTED clock, which is what the tracker integrated on; it is not
     * a measurement of what the sensor delivered, and the difference is the
     * class `VelocityEstimator.measureSampleRate` is the canonical instance of.
     */
    private fun rateHz(): Double {
        val elapsed = timeS[size - 1]
        return if (elapsed > 0.0) (size - 1) / elapsed else 0.0
    }

    private fun append(live: LiveSetState) {
        if (size == timeS.size) {
            timeS = timeS.copyOf(size * 2)
            accelMps2 = accelMps2.copyOf(size * 2)
            velocityMps = velocityMps.copyOf(size * 2)
        }
        timeS[size] = live.elapsedS
        accelMps2[size] = live.accelMps2
        velocityMps[size] = live.velocityMps
        size++
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024

        /**
         * Below this nothing is segmented. Two samples is what a displacement
         * needs -- `RepSegmenter.displacement` sums over `startIdx + 1..endIdx`
         * -- and a one-sample series would make every run a zero-length one.
         *
         * Deliberately NOT `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`, which
         * is the bound `VelocityEstimator.estimate` refuses below and is about
         * measuring a rate off a span. Nothing here measures a rate off a span.
         */
        const val MIN_SAMPLES = 2
    }
}
