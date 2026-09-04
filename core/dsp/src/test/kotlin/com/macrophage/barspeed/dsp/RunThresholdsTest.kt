package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame conversion [RunThresholds] performs, on its own, with no series
 * anywhere near it. Issue #70.
 *
 * The point of a separate type is that the arithmetic can be asserted without
 * a capture: everything below is a pure function of `DspConfig` and one
 * number, so a mutation to the conversion reds here first and in the
 * segmenter second.
 */
class RunThresholdsTest {
    private val config = DspConfig()

    @Test
    fun `the sensor frame is DspConfig unchanged`() {
        val t = RunThresholds.sensorFrame(config)
        assertEquals(config.pauseBandMps, t.pauseBandMps, "pause band")
        assertEquals(config.startThresholdMps, t.startThresholdMps, "start threshold")
        assertEquals(config.minPhaseS, t.minPhaseS, "minimum phase duration")
        assertEquals(config.minRomM, t.minRomM, "minimum rep displacement")
        assertEquals(config.maxRunDisplacementM, t.maxRunDisplacementM, "run displacement cap")
    }

    @Test
    fun `a unit scale is the sensor frame`() {
        assertEquals(RunThresholds.sensorFrame(config), RunThresholds.forSeriesScaledBy(config, 1.0), "1.0")
    }

    @Test
    fun `the four magnitudes scale and the duration does not`() {
        val t = RunThresholds.forSeriesScaledBy(config, 2.0)
        assertEquals(0.06, t.pauseBandMps, 1e-12, "pause band doubles")
        assertEquals(0.20, t.startThresholdMps, 1e-12, "start threshold doubles")
        assertEquals(0.20, t.minRomM, 1e-12, "minimum rep displacement doubles")
        assertEquals(4.0, t.maxRunDisplacementM, 1e-12, "run displacement cap doubles")
        assertEquals(config.minPhaseS, t.minPhaseS, 1e-12, "minPhaseS is a duration and is untouched")
    }

    @Test
    fun `a ratio below one shrinks the four magnitudes`() {
        val t = RunThresholds.forSeriesScaledBy(config, 0.5)
        assertEquals(0.015, t.pauseBandMps, 1e-12, "pause band halves")
        assertEquals(0.05, t.startThresholdMps, 1e-12, "start threshold halves")
        assertEquals(0.05, t.minRomM, 1e-12, "minimum rep displacement halves")
        assertEquals(1.0, t.maxRunDisplacementM, 1e-12, "run displacement cap halves")
        assertEquals(config.minPhaseS, t.minPhaseS, 1e-12, "minPhaseS is a duration and is untouched")
    }

    @Test
    fun `only the magnitude of the scale matters`() {
        // sensorToLifter is negative whenever the sensor rides the far end of a
        // cable. That sign belongs to the series, not to the limits: run typing
        // compares against +band and -band, and a displacement is a sum of
        // absolute values.
        listOf(0.5, 1.0, 2.0, 3.0).forEach {
            assertEquals(
                RunThresholds.forSeriesScaledBy(config, it),
                RunThresholds.forSeriesScaledBy(config, -it),
                "scale $it and -$it must give the same limits",
            )
        }
    }

    @Test
    fun `the lifter-frame form reads sensorToLifter, sign and ratio together`() {
        val legCurl = LiftDirection(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            sensorInverted = true,
            travelRatio = 0.5,
            sensorOnStack = true,
        )
        assertEquals(-0.5, legCurl.sensorToLifter, 1e-12, "the factor SetAnalyzer applies")
        assertEquals(
            RunThresholds.forSeriesScaledBy(config, 0.5),
            RunThresholds.forSeriesMappedToLifter(config, legCurl),
            "an inverted 1:2 stack converts by 0.5, not by -0.5",
        )
    }

    @Test
    fun `a non-default config is scaled, not replaced`() {
        // The limits are declared per-exercise configurable. Nothing here may
        // reach past its argument to a default.
        val custom = DspConfig(pauseBandMps = 0.05, startThresholdMps = 0.2, minRomM = 0.3, maxRunDisplacementM = 5.0)
        val t = RunThresholds.forSeriesScaledBy(custom, 2.0)
        assertEquals(0.10, t.pauseBandMps, 1e-12, "pause band")
        assertEquals(0.40, t.startThresholdMps, 1e-12, "start threshold")
        assertEquals(0.60, t.minRomM, 1e-12, "minimum rep displacement")
        assertEquals(10.0, t.maxRunDisplacementM, 1e-12, "run displacement cap")
    }
}
