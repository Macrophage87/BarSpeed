package com.macrophage.barspeed.dsp

/**
 * Tunable parameters for the velocity/segmentation pipeline.
 *
 * Defaults are chosen for barbell lifts sampled at 50-200 Hz. All thresholds are
 * intentionally configurable per exercise (see spec section 3.3).
 */
data class DspConfig(
    val gravityMps2: Double = 9.80665,
    /** Low-pass cutoff for linear acceleration before integration. */
    val lowPassCutoffHz: Double = 8.0,
    /** |v| below this counts as "still" for phase boundaries (m/s). */
    val pauseBandMps: Double = 0.03,
    /** A movement run must peak above this to count as a phase (m/s). */
    val startThresholdMps: Double = 0.10,
    /** A movement run must last at least this long to count as a phase (s). */
    val minPhaseS: Double = 0.20,
    /** Quiet IMU windows shorter than this are not ZUPT anchor candidates (s). */
    val minStationaryS: Double = 0.30,
    /** Acc-magnitude band around 1 g for quiet detection (g). */
    val stationaryAccBandG: Double = 0.05,
    /**
     * Gyro magnitude limit for quiet detection (deg/s).
     *
     * ONE CONSTANT, FOUR JOBS. Since issue #87 this value is read at three
     * places that answer different questions, and one of those reads drives
     * two consumers rather than one, so a change to it moves four things at
     * once:
     *
     *  1. **The live per-sample quiet clause.** [VelocityEstimator.isQuietSample]
     *     -- which is [VelocityEstimator.isAnchorCandidate] with the gate
     *     always on -- rejects a sample whose gyro magnitude reaches this,
     *     and [StreamingSetTracker] calls it once per arriving sample with no
     *     distribution to consult. Raising this admits more live ZUPT anchors
     *     on every set; lowering it admits fewer.
     *
     *     That one call has TWO consumers, which is why this heading says
     *     four and the list has three entries. The single `isQuietSample`
     *     result `StreamingSetTracker.feed` computes gates the ZUPT update
     *     AND the exponential accel-bias update immediately above it, so this
     *     constant moves the learned bias as well as the anchors -- and the
     *     bias is then subtracted from every sample that follows, quiet or
     *     not. How much of the live rep count the learner alone accounts for
     *     is NOT measured anywhere in this repository and no figure is quoted
     *     here for it.
     *  2. **The batch clause, on the sets where the gate is kept.**
     *     [VelocityEstimator.quietMask] passes the same threshold through
     *     `isAnchorCandidate`, but only where
     *     [VelocityEstimator.gyroGateApplies] returned true. On the sets it
     *     returns false for, this value plays no part in candidacy at all.
     *  3. **BOTH probes of the straddle test** in
     *     [VelocityEstimator.gyroGateApplies], which decides job 2. The
     *     median of the set's gyro magnitude is compared against this value
     *     and so is its tenth percentile; there is no second threshold.
     *     A second NUMBER does exist: [VelocityEstimator.GYRO_STILLNESS_QUANTILE]
     *     = 0.10, the low probe's fraction. It is fitted to one capture, it is
     *     not a member of this class, and it is therefore not configurable per
     *     exercise the way everything here is.
     *
     * So this is not only a per-sample filter -- it is also the reference the
     * per-set POLICY is chosen against. Moving it re-partitions the corpus
     * into gate-kept and gate-dropped sets before it changes a single sample
     * verdict, and jobs 1 and 2 then move in opposite directions on the sets
     * that cross over. `GyroGateTest` pins the partition on every capture --
     * its corpus list is checked against the resource directory -- and each
     * probe on the subset it can decide: the median on twelve captures and
     * the tenth percentile on the eight whose median clears the gate. Nothing
     * pins the consequence to a lifter-facing figure.
     */
    val stationaryGyroBandDps: Double = 10.0,
    /**
     * The slowest sustained phase the drift correction promises not to erase
     * (m/s). See [VelocityEstimator.anchorAcceptable], which derives both of its
     * caps from this and [minRomM]; no steady phase at or above this speed can
     * be taken for a pause at any anchor gap.
     *
     * Set equal to [startThresholdMps] rather than chosen: a phase slower than
     * the movement threshold is not a phase this pipeline would count, so there
     * is nothing below it left to protect.
     */
    val anchorSlowPhaseFloorMps: Double = 0.10,
    /**
     * Raw velocity must be this flat across an anchor window to count as a true
     * pause; a slow eccentric ramps faster than this while a real pause is
     * noise-flat (m/s).
     */
    val anchorStabilityBandMps: Double = 0.02,
    /**
     * Minimum displacement for a rep to count (m).
     *
     * The stated purpose is filtering bumps and re-racks, and the stated
     * pressure is downward: measured ROM is attenuated at low sample rates, and
     * real ~0.5 m squats measured only ~0.15-0.2 m in 10 Hz field data.
     *
     * **DO NOT LOWER IT ON THAT REASONING.** This floor has acquired a second
     * job that nothing designed and nothing else performs: it is the only thing
     * keeping reps whose displacement reconstruction has FAILED off the screen.
     *
     * Measured on the three cue-tracked leg-curl captures. The live integrator
     * does not degrade gracefully -- it fails on a subset of reps and is roughly
     * right on the rest. Eight drive runs there clear every other gate and are
     * stopped only by this one, and they carry 0.031 to 0.067 m against a batch
     * median rep ROM of 0.489 m: **7% to 14% of the truth.** The smallest rep
     * that does count carries 0.101 m. There is no continuum between them.
     *
     * Lowering this to 0.03 would admit all eight. Each would then publish a
     * mean and peak velocity taken from the same broken reconstruction, into
     * `LiveSetState.repMeanVelocities` and `repPeakVelocities` -- the numbers
     * the lifter reads mid-set. Today those published figures agree with the
     * batch path to within 6-25% on the mean, because the reps that survive are
     * the reps whose reconstruction worked. That agreement is downstream of
     * this floor.
     *
     * So the floor is load-bearing BY ACCIDENT. It filters on reconstruction
     * quality while claiming to filter on rep size, and the two happen to
     * separate at 0.10 m on this corpus. That is what makes it fragile: nothing
     * guarantees the gap stays open, and no other gate would catch a short
     * reconstruction if it closed.
     *
     * AND IT IS NOT ONLY A GATE. [VelocityEstimator.anchorAcceptable] derives
     * both of its caps from this value and [startThresholdMps], so this is an
     * input to the DRIFT CORRECTION as well. Lowering it changes which quiet
     * windows may be taken as zero-velocity anchors, which changes the velocity
     * series, which changes every run formed from it. Measured: at 0.03 m the
     * admitted drive-run count on those three captures is 30, not the 31 that
     * relaxing a gate alone would give. Anyone lowering this to recover missed
     * reps is altering the measurement they were trying to fix.
     *
     * The eight runs and their ratio to batch ROM are pinned by
     * `MinRomFloorTest`. What is NOT pinned anywhere is the consequence -- that
     * admitting them would put bad velocities on screen -- because nothing in
     * this repository renders a screen. See issue 94.
     */
    val minRomM: Double = 0.10,
    /**
     * No real barbell phase displaces more than this (m); a movement run
     * beyond it is read as unanchored integration drift -- typically
     * end-of-set re-rack and bar handling with no quiet window to re-anchor
     * on.
     *
     * THREE CONSUMERS, and what each does with a run beyond it differs.
     * Moving this value moves all three.
     *
     *  1. [StreamingSetTracker] rejects the run on the LIVE path and latches
     *     `countTrusted` false with it.
     *  2. [RepSegmenter] demotes the run to STILL on the batch path, which
     *     both pairing rules skip.
     *  3. Since issue #94, [RunawayDrift] SELECTS on it: `runaways` returns
     *     every same-sign run beyond this value and `corrected` removes each
     *     one's MEAN and re-classifies, so on the batch path such a run is no
     *     longer discarded -- it is de-trended into the strokes inside it.
     *
     * So this is not only a gate. It is also the trigger for the batch drift
     * correction, and because `corrected` iterates until no run exceeds it,
     * the largest surviving batch run is bounded BY this value and falls with
     * it. `LiveCapCalibrationTest` records what that does to the calibration
     * argument this constant used to rest on.
     */
    val maxRunDisplacementM: Double = 2.0,
)
