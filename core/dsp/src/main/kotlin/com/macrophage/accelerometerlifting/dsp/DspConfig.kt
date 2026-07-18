package com.macrophage.accelerometerlifting.dsp

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
     * A quiet window whose raw velocity differs from the previous anchor by more
     * than this is really slow motion (e.g. a 4 s eccentric) and is rejected as
     * a ZUPT anchor (m/s).
     */
    val anchorRejectThresholdMps: Double = 0.15,
    /**
     * Raw velocity must be this flat across an anchor window to count as a true
     * pause; a slow eccentric ramps faster than this while a real pause is
     * noise-flat (m/s).
     */
    val anchorStabilityBandMps: Double = 0.02,
    /** Minimum displacement for a rep to count, filters bumps/re-racks (m). */
    val minRomM: Double = 0.15,
)
