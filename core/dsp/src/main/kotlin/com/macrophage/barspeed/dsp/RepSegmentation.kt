package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs

internal enum class RunType { UP, DOWN, STILL }

internal data class Run(val type: RunType, val startIdx: Int, val endIdx: Int)

/** One segmented rep, expressed as index spans into the [VelocitySeries]. */
data class RepSpan(
    val eccStartIdx: Int,
    val eccEndIdx: Int,
    val conStartIdx: Int,
    val conEndIdx: Int,
    /** Pause between the two movement phases (bottom for ecc-first lifts). */
    val midPauseS: Double,
    /** Pause after the second movement phase, capped at the next rep or series end. */
    val endPauseS: Double,
)

/**
 * Rep segmentation via run classification: each sample is moving-up, moving-down,
 * or still (velocity dead-band); movement runs that are too short or too slow are
 * merged into stillness; qualifying down+up (or up+down) pairs form reps.
 *
 * Phase boundaries land where |v| crosses the dead-band, which slightly
 * undercounts phase time versus the athlete's intent — the dead-band is small,
 * and the same bias applies to every rep, so tempo comparison stays fair.
 */
object RepSegmenter {
    fun segment(series: VelocitySeries, startsWith: StartPhase, config: DspConfig = DspConfig()): List<RepSpan> {
        val runs = classifyRuns(series, config)
        return pairRuns(runs, series, startsWith, config)
    }

    internal fun classifyRuns(series: VelocitySeries, config: DspConfig): List<Run> {
        val v = series.velocityMps
        val n = series.size
        val rawTypes =
            IntArray(n) {
                when {
                    v[it] > config.pauseBandMps -> 1
                    v[it] < -config.pauseBandMps -> -1
                    else -> 0
                }
            }
        // Collect contiguous runs.
        val runs = mutableListOf<Run>()
        var start = 0
        for (i in 1..n) {
            if (i == n || rawTypes[i] != rawTypes[start]) {
                val type =
                    when (rawTypes[start]) {
                        1 -> RunType.UP
                        -1 -> RunType.DOWN
                        else -> RunType.STILL
                    }
                runs += Run(type, start, i - 1)
                start = i
            }
        }
        // Demote movement runs that never exceed the start threshold or are too brief.
        val demoted =
            runs.map { run ->
                if (run.type == RunType.STILL) {
                    run
                } else {
                    val duration = series.timeS[run.endIdx] - series.timeS[run.startIdx]
                    val peak = (run.startIdx..run.endIdx).maxOf { abs(v[it]) }
                    if (peak < config.startThresholdMps || duration < config.minPhaseS) {
                        run.copy(type = RunType.STILL)
                    } else {
                        run
                    }
                }
            }
        // Merge adjacent STILL runs.
        val merged = mutableListOf<Run>()
        for (run in demoted) {
            val last = merged.lastOrNull()
            if (last != null && last.type == RunType.STILL && run.type == RunType.STILL) {
                merged[merged.size - 1] = last.copy(endIdx = run.endIdx)
            } else {
                merged += run
            }
        }
        return merged
    }

    private fun pairRuns(
        runs: List<Run>,
        series: VelocitySeries,
        startsWith: StartPhase,
        config: DspConfig,
    ): List<RepSpan> {
        val firstType = if (startsWith == StartPhase.ECCENTRIC) RunType.DOWN else RunType.UP
        val secondType = if (startsWith == StartPhase.ECCENTRIC) RunType.UP else RunType.DOWN
        val reps = mutableListOf<RepSpan>()
        var i = 0
        while (i < runs.size) {
            val first = runs[i]
            if (first.type != firstType) {
                i++
                continue
            }
            // Find the matching second movement, allowing one STILL run between.
            var j = i + 1
            while (j < runs.size && runs[j].type == RunType.STILL) j++
            if (j >= runs.size || runs[j].type != secondType) {
                i++
                continue
            }
            val second = runs[j]
            val midPauseS = series.timeS[second.startIdx] - series.timeS[first.endIdx]
            // End pause: stillness after the second movement until the next movement run.
            var k = j + 1
            while (k < runs.size && runs[k].type == RunType.STILL) k++
            val endBoundaryIdx = if (k < runs.size) runs[k].startIdx else series.size - 1
            val endPauseS = series.timeS[endBoundaryIdx] - series.timeS[second.endIdx]

            val rom = displacement(series, second.startIdx, second.endIdx)
            if (rom >= config.minRomM) {
                reps +=
                    if (startsWith == StartPhase.ECCENTRIC) {
                        RepSpan(first.startIdx, first.endIdx, second.startIdx, second.endIdx, midPauseS, endPauseS)
                    } else {
                        RepSpan(second.startIdx, second.endIdx, first.startIdx, first.endIdx, midPauseS, endPauseS)
                    }
            }
            i = j + 1
        }
        return reps
    }

    internal fun displacement(series: VelocitySeries, startIdx: Int, endIdx: Int): Double {
        var d = 0.0
        for (i in startIdx + 1..endIdx) {
            d += abs(series.velocityMps[i]) * (series.timeS[i] - series.timeS[i - 1])
        }
        return d
    }
}
