package com.macrophage.barspeed.dsp

import kotlin.math.abs

/**
 * The five limits that decide what a movement run IS, carried in the frame of
 * the series they are applied to.
 *
 * [RepSegmenter] and [StreamingSetTracker] each classify a velocity series into
 * runs against `DspConfig.pauseBandMps`, `startThresholdMps`, `minPhaseS`,
 * `minRomM` and `maxRunDisplacementM`. Every capture those five were fitted on
 * had the sensor travelling 1:1 with the load, so they are SENSOR-frame
 * quantities -- but both callers are handed a series already multiplied by
 * [LiftDirection.sensorToLifter], which is not that frame whenever a pulley
 * ratio is declared.
 *
 * This type exists so that conversion has a name a test can reach, and so a
 * caller has to say which frame it is in rather than defaulting into one.
 * Issue #70.
 *
 * Three callers, and which form each takes is the whole content of the fix:
 * [RepSegmenter.segmentDetailed] and [StreamingSetTracker] classify a MAPPED
 * series and convert; `SetAnalyzer.orient` classifies the UNMAPPED one and
 * does not.
 */
data class RunThresholds(
    /** |v| below this counts as "still". */
    val pauseBandMps: Double,
    /** A movement run must peak above this to count as a phase. */
    val startThresholdMps: Double,
    /** A movement run must last at least this long to count as a phase (s). */
    val minPhaseS: Double,
    /** Minimum displacement for a rep to count (m). */
    val minRomM: Double,
    /** Beyond this a run is read as unanchored integration drift (m). */
    val maxRunDisplacementM: Double,
) {
    companion object {
        /**
         * The limits exactly as [DspConfig] states them, for a series still in
         * the sensor's own frame -- nothing has been scaled, so nothing is
         * converted.
         *
         * `SetAnalyzer.orient` classifies the UNMAPPED series to decide which
         * end of a horizontal machine is forward, and this is permanently the
         * right form there.
         */
        fun sensorFrame(config: DspConfig): RunThresholds = RunThresholds(
            pauseBandMps = config.pauseBandMps,
            startThresholdMps = config.startThresholdMps,
            minPhaseS = config.minPhaseS,
            minRomM = config.minRomM,
            maxRunDisplacementM = config.maxRunDisplacementM,
        )

        /**
         * The same limits, restated for a series every sample of which has been
         * multiplied by [scale].
         *
         * Four of the five are speeds or lengths and move with the scale.
         * [minPhaseS] is a duration and does not: mapping a series into another
         * frame does not change when a sample arrived.
         *
         * Only the MAGNITUDE of the scale matters. A run's type is decided by
         * comparing against `+band` and `-band`, and a displacement is a sum of
         * absolute values, so the cable inversion -- the negative half of
         * [LiftDirection.sensorToLifter] -- leaves every limit where it was.
         * Hence [abs].
         *
         * A scale of zero maps the whole series to zero and takes the limits to
         * zero with it. Nothing then clears a strict `>`, so such a set reads as
         * one long stillness and resolves no reps, which is the honest answer to
         * a declaration saying the load does not move. `Plan` rejects a
         * non-positive `travelRatio` before it can reach here.
         */
        fun forSeriesScaledBy(config: DspConfig, scale: Double): RunThresholds {
            val k = abs(scale)
            return RunThresholds(
                pauseBandMps = config.pauseBandMps * k,
                startThresholdMps = config.startThresholdMps * k,
                minPhaseS = config.minPhaseS,
                minRomM = config.minRomM * k,
                maxRunDisplacementM = config.maxRunDisplacementM * k,
            )
        }

        /**
         * [forSeriesScaledBy] for the factor `SetAnalyzer.analyze` actually
         * applies to the series it hands the segmenter:
         * [LiftDirection.sensorToLifter].
         */
        fun forSeriesMappedToLifter(config: DspConfig, direction: LiftDirection): RunThresholds =
            forSeriesScaledBy(config, direction.sensorToLifter)
    }
}
