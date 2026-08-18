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
    /** Gyro magnitude limit for quiet detection (deg/s). */
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
     * Minimum displacement for a rep to count, filters bumps/re-racks (m).
     * Kept low because measured ROM is attenuated at low sample rates: real
     * ~0.5 m squats measured only ~0.15-0.2 m in 10 Hz field data.
     */
    val minRomM: Double = 0.10,
    /**
     * No real barbell phase displaces more than this (m); movement runs beyond
     * it are unanchored integration drift (typically end-of-set re-rack and
     * bar handling with no quiet window to re-anchor on) and are discarded.
     */
    val maxRunDisplacementM: Double = 2.0,
)
