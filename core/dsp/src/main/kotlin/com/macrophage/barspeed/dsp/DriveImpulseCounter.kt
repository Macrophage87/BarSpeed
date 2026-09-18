package com.macrophage.barspeed.dsp

import kotlin.math.max

/**
 * A live rep counter with no velocity in it: issue #301's candidate (c).
 *
 * A rep is an upward acceleration impulse followed by braking. Nothing here
 * integrates, so nothing here has a zero to lose:
 *
 *  1. a DRIVE -- the drive-frame acceleration above
 *     [DspConfig.driveAccelThresholdMps2] for at least
 *     [DspConfig.driveMinPhaseS], peaking at or above
 *     [DspConfig.drivePeakAccelMps2];
 *  2. a BRAKE -- the drive frame below the negated threshold, beginning within
 *     [DspConfig.driveMaxGapS] of the drive's last sample and holding for
 *     [DspConfig.driveMinPhaseS].
 *
 * The rep is called at the instant the brake reaches that duration, which is
 * the earliest instant both terms hold and therefore the earliest a voice could
 * speak it.
 *
 * ## Why it exists
 *
 * `LiveRepCaller` counts from the velocity `StreamingSetTracker` publishes, so
 * it inherits the live integrator's zero. On a floor-based lift there isn't
 * one: field-43's three deadlift sets gave the ZUPT no window it would accept
 * (set 5 offered none at all for 15.5 s), the integrator drifted, and
 * [DspConfig.maxRunDisplacementM] then refused runs it could not bound -- 3, 1
 * and 2 calls for five performed reps a set, one of the six on a set-up pull.
 * `DeadliftLiveCountFieldTest` pins all of that. None of those three mechanisms
 * can reach this class.
 *
 * ## What it measures, and what it therefore cannot
 *
 * It measures the lifter's INTENT to move the bar, not the bar's travel. Two
 * consequences, both measured in `DriveImpulseCandidateTest` rather than argued
 * here:
 *
 * - **It under-counts a grind.** On field-43 set 6 at 102 kg it misses reps 4
 *   and 5, the two slowest pulls. A rep the lifter barely completes produces
 *   barely any impulse, and no threshold on acceleration separates that from a
 *   rep not attempted.
 * - **It collapses on stack and machine work.** The thresholds are drive-frame
 *   numbers fitted at `travelRatio` 1.0 and are NOT converted through the
 *   ratio, so on a pulley they are applied in the wrong frame: 2 calls against
 *   10 performed on a leg curl, 0 against 8 on a single-leg press. That is why
 *   `LiveCounterPolicy` chooses this counter per SET rather than making it the
 *   counter everywhere.
 *
 * There is also no ROM term of any kind, so a short sharp jolt with no travel
 * -- a bar bumped on the rack -- is a rep to this class and is not one to
 * `LiveRepCaller`. Nothing in field-43 exercises that and nothing here defends
 * against it.
 *
 * ## The frame, which is the one thing easy to get wrong here
 *
 * [LiveSetState.accelMps2] is published in the SENSOR frame while
 * [LiveSetState.velocityMps] beside it is in the lifter's -- `LiveRepCall`'s own
 * comment says so, and says any future consumer of that field must map it
 * through `sensorToLifter` first. This is that consumer. The series read here is
 * `accelMps2 * sensorToLifter * concentricSign`, the same product the run
 * classifier applies to the velocity, so a drive-down lift and an inverted
 * mount are handled the way every other drive in this module is.
 */
class DriveImpulseCounter(
    direction: LiftDirection = LiftDirection(),
    private val config: DspConfig = DspConfig(),
) : LiveRepCounter {
    /** Sensor frame to drive frame: positive is the direction the lifter drives. */
    private val driveSign: Double = direction.sensorToLifter * direction.concentricSign

    /** What this counter has called; 0 before the first rep. */
    var called: Int = 0
        private set

    private var driveStartS = Double.NaN
    private var drivePeak = 0.0
    private var lastDriveSampleS = Double.NaN
    private var awaitBrakeUntilS = Double.NaN
    private var brakeStartS = Double.NaN

    /**
     * One published live sample.
     *
     * [live] is `StreamingSetTracker.feed`'s own return value, for
     * `LiveRepCaller`'s reason: the caller feeds the tracker it already has
     * rather than this class running a second pass over the same stream.
     *
     * [timestampMs] is the ARRIVAL stamp of that sample, carried through to
     * [RepCall.Speak] unchanged so a cue written from it lines up against the
     * IMU stream and the rep marks. The rule itself runs on
     * [LiveSetState.elapsedS], the reconstructed clock the tracker integrated
     * on, because a burst of frames sharing one arrival stamp would make every
     * phase duration zero.
     */
    override fun feed(live: LiveSetState, timestampMs: Long): RepCall {
        val timeS = live.elapsedS
        val accel = live.accelMps2 * driveSign
        if (accel > config.driveAccelThresholdMps2) {
            if (driveStartS.isNaN()) {
                driveStartS = timeS
                drivePeak = accel
            } else {
                drivePeak = max(drivePeak, accel)
            }
            lastDriveSampleS = timeS
        } else if (!driveStartS.isNaN()) {
            closeDrive()
        }
        if (awaitBrakeUntilS.isNaN()) return RepCall.Hold
        if (accel < -config.driveAccelThresholdMps2) return brake(timeS, timestampMs)
        brakeStartS = Double.NaN
        if (timeS > awaitBrakeUntilS) awaitBrakeUntilS = Double.NaN
        return RepCall.Hold
    }

    /**
     * The drive just ended: arm the brake window if it qualified.
     *
     * A second qualified drive arriving before the brake REPLACES the first
     * rather than adding a call -- two drives with no braking between them are
     * one rep with a stall in it, not two reps. That is a decision, it is
     * reachable on a grinding pull, and nothing in field-43 exercises it.
     */
    private fun closeDrive() {
        val longEnough = lastDriveSampleS - driveStartS >= config.driveMinPhaseS
        if (longEnough && drivePeak >= config.drivePeakAccelMps2) {
            awaitBrakeUntilS = lastDriveSampleS + config.driveMaxGapS
            brakeStartS = Double.NaN
        }
        driveStartS = Double.NaN
        drivePeak = 0.0
    }

    /**
     * One braking sample: call the rep once the brake has held long enough.
     *
     * The RUNNING TOTAL is spoken, [RepCall.Speak]'s rule. A brake that breaks
     * off clears [brakeStartS] rather than accumulating, so a rep is called only
     * on one contiguous brake.
     */
    private fun brake(timeS: Double, timestampMs: Long): RepCall {
        if (brakeStartS.isNaN()) brakeStartS = timeS
        if (timeS - brakeStartS < config.driveMinPhaseS) return RepCall.Hold
        called++
        awaitBrakeUntilS = Double.NaN
        brakeStartS = Double.NaN
        return RepCall.Speak(called, timestampMs)
    }
}
