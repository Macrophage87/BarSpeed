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
    /**
     * The acceleration a drive must exceed for [DriveImpulseCounter] to be
     * watching a rep, m/s^2, in the DRIVE frame.
     *
     * ## Provenance: one session, and the harness that measured it
     *
     * This and the three values below are the constants issue #301's design
     * round fitted on **field-43**, the first deadlift session with the sensor
     * counting straight reps (`field-deadlift-straight-5rep-s43-set04`, `-05`,
     * `-06`). They were test-local `const val`s in `LiveCountCandidates.kt`
     * while the candidate was being scored and moved here when the candidate
     * became production code; `DriveImpulseCandidateTest` is where every figure
     * behind them re-derives, over those three captures and over the 33
     * committed captures that carry a truth.
     *
     * **They are fitted, not surveyed.** One session, one lifter, one lift, two
     * mounts, both on the bar's collars. Nothing here is calibrated across
     * loads, bar types or mount positions, and a value in [DspConfig] reads as
     * surveyed unless its KDoc says otherwise -- so this one says otherwise.
     *
     * The same number is the BRAKE threshold negated: a brake is the drive
     * frame below `-this`. One constant rather than two because nothing in
     * field-43 distinguishes them and a second fitted number would be a second
     * thing to get wrong.
     */
    val driveAccelThresholdMps2: Double = 1.0,
    /**
     * How long a drive and a brake must each hold past
     * [driveAccelThresholdMps2] before they count, s.
     *
     * Applied to BOTH phases, and it is the brake's copy that decides WHEN the
     * rep is called: [DriveImpulseCounter] speaks at the instant the brake
     * reaches this duration, which is the earliest instant both terms of the
     * rule hold. It lands the call at the brake rather than after a paired
     * velocity run, so on field-43 it speaks earlier in the rep; by how much is
     * not derived anywhere on this branch.
     *
     * Deliberately NOT [minPhaseS], which is 0.20 s and is a bound on a
     * VELOCITY run. A drive impulse is shorter than the stroke it starts.
     */
    val driveMinPhaseS: Double = 0.12,
    /**
     * The peak a drive must reach to be a rep at all, m/s^2, drive frame.
     *
     * **This term does exactly one job on the evidence there is, and it is an
     * accident of one session that it does it.** On field-43 set 6 the set-up
     * pull -- the lifter taking the slack out of the bar before the rep -- sits
     * below this value and every real pull above it, so it is the only thing
     * excluding a call the voice actually made and the lifter did not earn.
     * Relaxing it to 1.6 puts that call back and recovers neither of the two
     * reps set 6 misses (`DriveImpulseCandidateTest`). Nothing guarantees the
     * gap stays open on a different load, bar or lifter, which is the same
     * thing [minRomM]'s KDoc records about its own accidental job.
     */
    val drivePeakAccelMps2: Double = 2.0,
    /**
     * How long after a qualified drive its brake may begin and still belong to
     * the same rep, s.
     *
     * Measured from the drive's LAST sample above [driveAccelThresholdMps2]. A
     * lockout longer than this leaves the rep uncalled rather than calling it
     * late: an unpaired impulse is not a rep, because a lifter setting a bar
     * down produces an impulse too.
     */
    val driveMaxGapS: Double = 1.0,
    /**
     * The run threshold on the smoothed drive-frame acceleration for
     * [CycleRepCounter], m/s^2: a drive or a brake is a stretch beyond +-this.
     *
     * ## Provenance of every `cycle*` value: eight sets, three kinds
     *
     * The `cycle*` constants below are issue #305's design round, carried over
     * unchanged from the harness that scored them (`ClosingRuleCandidates.kt`,
     * `ClosingRuleVariants.kt`). The eight sets are field-43 sets 4-6 and
     * field-44 sets 1-5 -- the only deadlifts the corpus holds, one lifter,
     * dead stop, two mounts -- and every figure behind these values re-derives
     * in `ClosingRuleCandidateTest`. Each KDoc says which of three kinds its
     * value is, because a value in [DspConfig] reads as surveyed unless it says
     * otherwise:
     *
     * - FITTED AND SWEPT: chosen against the eight sets and varied one at a
     *   time in that test's sweep -- this, [cycleDriveGainMps],
     *   [cycleMaxGapS], [cycleClipMps2], [cycleFallG], [cycleMinCycleS],
     *   [cycleDescentMps].
     * - CHOSEN, NEVER VARIED: set during the design round against the same
     *   eight sets and never varied in the committed harness --
     *   [cycleBrakeLossMps], [cycleMinRunS], [cycleSmoothFrames],
     *   [cycleContactWindowS], [cycleContactDebounceS], [cycleFallFrames],
     *   [cycleFallRefireS]. Their sensitivity is unmeasured.
     * - BORROWED, NOT FITTED: [cycleContactG] and [cycleStillS], each taken from
     *   an existing definition and named there.
     *
     * FITTED AND SWEPT. Low enough that field-44 set 5's 120 kg pulls form a run
     * at all.
     */
    val cycleRunThresholdMps2: Double = 0.15,
    /**
     * The velocity a run above [cycleRunThresholdMps2] must GAIN -- its
     * integral -- to be a drive, m/s.
     *
     * FITTED AND SWEPT. Under the 0.16 m/s field-44 set 5's first completed rep
     * gains. The failed pull on that set gains 0.23 m/s, so no value of this
     * separates the two; [cycleMinCycleS] does.
     */
    val cycleDriveGainMps: Double = 0.12,
    /** The velocity a negative run must LOSE to be a brake, m/s. CHOSEN, NEVER VARIED. */
    val cycleBrakeLossMps: Double = 0.12,
    /** The shortest run that can be a drive or a brake, s. CHOSEN, NEVER VARIED. */
    val cycleMinRunS: Double = 0.15,
    /**
     * How long after a drive's end its brake may begin and still arm it, s.
     * FITTED AND SWEPT.
     */
    val cycleMaxGapS: Double = 1.5,
    /**
     * Frames in the moving mean over the acceleration: 0.1 s at the corpus's
     * ~100 Hz. A frame count, not a duration, so it spans a different time at
     * another rate. CHOSEN, NEVER VARIED.
     */
    val cycleSmoothFrames: Int = 10,
    /**
     * The bound the acceleration is clipped to for [cycleContactWindowS] after
     * a floor contact, m/s^2, so a strike's ringing cannot become a drive.
     * FITTED AND SWEPT.
     */
    val cycleClipMps2: Double = 1.0,
    /** How long after a contact the clip holds, s. CHOSEN, NEVER VARIED. */
    val cycleContactWindowS: Double = 0.3,
    /** The shortest interval between two CONTACT events, s. CHOSEN, NEVER VARIED. */
    val cycleContactDebounceS: Double = 0.5,
    /**
     * A floor CONTACT: a sample whose accelerometer MAGNITUDE exceeds this, g.
     *
     * BORROWED, NOT FITTED: issue #305 lens A's contact definition, the same
     * 4 g by magnitude `DeadliftLiveCountFieldTest` counts field-43's floor
     * transients with. Not swept.
     */
    val cycleContactG: Double = 4.0,
    /**
     * A FALL: the [cycleFallFrames]-frame mean magnitude under this, g -- the
     * bar in or near free fall.
     *
     * FITTED AND SWEPT. Every completed rep's lowering stays above it and the
     * failed pull does not (field-44 set 5's grip failure reaches 0.14 g).
     * Completed reps start dropping at 0.55 in the sweep.
     */
    val cycleFallG: Double = 0.4,
    /** Frames in the magnitude mean a FALL is read on. CHOSEN, NEVER VARIED. */
    val cycleFallFrames: Int = 5,
    /**
     * The shortest interval between two FALL events, s.
     *
     * CHOSEN, NEVER VARIED. It was a literal `0.3` inside the design harness's
     * event loop and appeared in no constant list; it is named here so it is
     * one.
     */
    val cycleFallRefireS: Double = 0.3,
    /**
     * A STILL: a run of quiet samples (`VelocityEstimator.isQuietSample`)
     * reaching this, s.
     *
     * BORROWED, NOT FITTED: [minStationaryS]'s value, 0.30 s. A field of its own
     * rather than a read of that one, so re-tuning the ZUPT's window cannot move
     * the rep count without a diff here. Not swept.
     */
    val cycleStillS: Double = 0.30,
    /**
     * The shortest a completed rep is taken to need from its drive's END to the
     * bar being back on the floor, s. A CONTACT or FALL sooner than this rejects
     * the attempt; the next drive STARTING sooner than this replaces it.
     *
     * FITTED AND SWEPT. The next contact comes 0.68 s after field-44 set 5's
     * failed pull's drive ends, and 1.25 s or later after the drive of every
     * completed rep that arms (35 of 36). Completed reps start dropping at 1.4
     * in the sweep. A contact is a proxy for the floor, not a measurement of
     * it.
     */
    val cycleMinCycleS: Double = 1.2,
    /**
     * The magnitude the negative part of the smoothed drive-frame acceleration
     * must reach, integrated from the brake run's start, before a pending rep
     * can be CALLED, m/s. An acceleration integral, not a measured bar
     * velocity.
     *
     * FITTED AND SWEPT. Completed reps reach 1.38-3.1; the two set-downs it
     * removes reach 0.35 and 0.51.
     */
    val cycleDescentMps: Double = 0.9,
)
