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
 * another. This class is written to REPLACE the second statement in the
 * decision that gets SPOKEN: it hands [RepSegmenter.segment] the velocity the
 * tracker has published so far and speaks the count that comes back. It
 * deletes nothing yet -- nothing constructs it, `StreamingSetTracker.countRep`
 * still runs on every set, and the app still shows what that counted.
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
 * The PLANE is not shared either, and that is a sharper difference than the
 * velocity. `StreamingSetTracker.feed` always integrates
 * `FrameTransform.verticalLinearAccelMps2` whatever `LiftDirection.plane`
 * says, while `SetAnalyzer.analyze` passes `direction.measuredPlane` to
 * `VelocityEstimator.estimate`, which takes a horizontal branch -- a
 * principal-axis projection of the horizontal acceleration -- for a
 * handle-mounted horizontal lift. On such a lift the two measure DIFFERENT
 * AXES, not two estimates of one axis, so "the same rule over a causal
 * estimate" understates the divergence there.
 *
 * No committed capture reaches it. The three captures of horizontal exercises
 * on this module's classpath -- `field-cablerow-static-8rep`,
 * `field-facepull-static-12rep` and `field-pallof-static-12rep` -- are scored
 * everywhere as `LiftDirection(startsWith = CONCENTRIC)`, whose `plane`
 * defaults to VERTICAL, and all thirteen mark-carrying captures measure
 * VERTICAL too, which `LiveRepCallCorpusTest`'s `every capture here is
 * measured vertically, so the batch series needs no orientation` asserts.
 * What the divergence does to a real handle-mounted set is unmeasured and is
 * not claimed here.
 *
 * ## Why the announcement lands one sample after the drive
 *
 * Nothing here has a rest detector, and it does not have one. A [RepSpan]
 * ends at `conEndIdx`, the last sample of the drive, and
 * `RepSegmenter.classifyRunsDetailed` cannot close a run until a sample of a
 * DIFFERENT RUN TYPE arrives -- STILL, or the opposite sign when the
 * turnaround crosses the dead band in one frame. [countDetected] counts only
 * spans whose drive run has closed, so the earliest prefix a rep can be
 * spoken from is the one holding that next sample. NOTHING HERE MEASURES
 * STILLNESS, so the call lands one sample after the drive run closes and no
 * further claim about what the lifter was doing at that instant is available.
 *
 * The heading and three sentences that stood here are deleted rather than
 * reworded: they said the announcement lands "at the inter-rep rest", that
 * the closing sample is "the first still sample of the rest", and that "the
 * announcement instant IS the detected rest instant". The third is refuted by
 * the second bullet above -- an opposite-sign sample closes the run too --
 * and the first two describe a rest this class never detects.
 *
 * WHERE THAT INSTANT IS ON THE LIFT depends on the phase order. On an
 * ECCENTRIC-FIRST lift the drive ends at the start position, which is issue
 * #145's "the top" for a `start: top` lift and the bottom for a
 * `start: bottom` one. On a CONCENTRIC-FIRST lift -- eight of the thirteen
 * mark-carrying captures here -- the drive ends at the FAR END of the stroke,
 * which is neither the start position nor a rest. The sentence that read "on
 * a `start: top` lift that rest is at the top and on a `start: bottom` lift
 * it is at the bottom" is deleted for asserting the eccentric-first case of
 * both.
 *
 * Waiting for the run to close is also what makes a spoken number final. An
 * OPEN run can still travel past `maxRunDisplacementM` and stop being a rep;
 * a closed one cannot, and no later sample changes an earlier pair, because
 * `RepSegmenter`'s pairing walks forward and `loweredSince` looks only
 * backward. [contradicted] measures that the property holds --
 * `LiveRepCallCorpusTest` pins it at zero on all thirteen captures, against
 * 25,967 samples before this rule.
 *
 * ## Cost
 *
 * One segmentation per sample over the whole set so far, so the work PER
 * SAMPLE grows linearly within a set and the TOTAL grows with the square of
 * its length. Measured over the thirteen mark-carrying captures on the
 * machine that ran the suite and reported in the body of the commit
 * "Correct eight prose claims round 1 found wrong, and pin two of them"
 * rather than claimed here, because it is a measurement of a JVM on a desktop
 * and says nothing certain about a phone. Not the body of "Speak only the
 * reps whose drive the detector has finished watching": that commit's bounds
 * were retracted by the correcting commit's finding 2.
 *
 * ## What this class does not do
 *
 * It does not decide whether the voice is on, whether this set is counted by
 * the sensor at all, or what words are said. `SetVoicePolicy` owns the first
 * two and `VoiceMilestonePolicy` the third.
 *
 * `:app` CALLS THIS NOW, on the owner's rule of 2026-09-12 -- "The sensor
 * should count the reps" -- for every rep-based set with no prescribed tempo
 * and an IMU connected (#286). `RecordViewModel`'s `SensorRepCounter` holds one
 * per set and feeds it the samples `StreamingSetTracker` publishes; the count
 * it returns is what the voice says, what the ring draws and what the row
 * records.
 *
 * TWO SENTENCES ARE DELETED HERE RATHER THAN REWORDED, both of them true when
 * written and false now: that **nothing in `:app` calls this yet**, and that
 * "the live count `RecordScreen` DRAWS is still `StreamingSetTracker.repCount`
 * ... that move is owed by the un-gating commit." The move was made in the
 * same commit as the un-gating: the screen and the voice read one field, and
 * the tracker's own `repCount` is drawn nowhere.
 *
 * WHAT IS STILL OWED is the EVIDENCE, which is unchanged. No committed capture
 * is a no-tempo max-intent set, `LiveRepCallCorpusTest` scores this against
 * thirteen guided captures whose marks are the GUIDE's calls, and issue #145's
 * F1 capture -- a straight-rep set with independent per-rep truth -- has not
 * been recorded. The first deadlift session is what measures whether the
 * number this class speaks is right.
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
     *
     * It is zero on the THIRTEEN mark-carrying captures
     * `LiveRepCallCorpusTest` feeds it. The other twenty-nine committed
     * captures are never fed to a [LiveRepCaller], so nothing here measures
     * them. The closed-run rule in [countDetected] is the REASON to expect
     * zero on any stream; the pin MEASURES thirteen of them. "It is zero on
     * every committed capture" stood here and is deleted. Removing the increment reds nothing, which is
     * stated here rather than left for a reader to discover; what guards the
     * property is the pin plus the mutation that reverts the rule, not this
     * counter on its own.
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
        // MIXED FRAMES, deliberately, and stated because nothing in the type
        // says it. The acceleration in this series is in the SENSOR frame and
        // the velocity is in the LIFTER frame: StreamingSetTracker scales only
        // the velocity, by sensorToLifter -- `(rawV - anchorOffset) *
        // velocityScale` -- and publishes `filtered - accelBias` unscaled,
        // while the batch path's `VelocitySeries.mappedToLifter` scales BOTH.
        // On the corpus's pulldown, whose sensorToLifter is -1, that leaves
        // the two fields signed against OPPOSITE axes. Every VelocitySeries
        // SetAnalyzer builds holds the invariant this one breaks.
        //
        // It is safe only because RepSegmenter reads velocityMps and timeS and
        // nothing else. Any future consumer of accelMps2 from here must map it
        // through sensorToLifter first.
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
     * How many reps the detector has FINISHED resolving in the prefix so far.
     *
     * Every span whose drive run has closed, which is every span ending before
     * the last sample of the prefix: the run containing that last sample is
     * still open and may yet be demoted. Excluding it is the whole difference
     * between a number that is final when spoken and one that is not -- see
     * the class KDoc, and `LiveRepCallCorpusTest`.
     *
     * Strictly `<`, not `<=`. A span ending exactly on the last sample is the
     * open run, and admitting it admits every case this excludes.
     *
     * WHAT THE RULE COSTS AT THE END OF A SET. A rep becomes speakable only
     * once a sample of another run type has arrived, so a stream that ENDS
     * INSIDE ITS LAST DRIVE never hears that rep's number -- the
     * velocity-loss-stop case, where the lifter racks the bar the instant the
     * drive finishes. What that costs a real set is unmeasured: no committed
     * capture is a velocity-loss stop and nothing here counts how often a
     * stream ends mid-drive.
     */
    private fun countDetected(series: VelocitySeries): Int =
        RepSegmenter.segment(series, direction, config).count { it.conEndIdx < series.size - 1 }

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
