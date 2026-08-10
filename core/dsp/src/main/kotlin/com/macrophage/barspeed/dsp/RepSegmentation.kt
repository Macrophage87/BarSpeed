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
        // Demote movement runs that never exceed the start threshold, are too
        // brief, or displace implausibly far (unanchored drift, not a lift).
        val demoted =
            runs.map { run ->
                if (run.type == RunType.STILL) {
                    run
                } else {
                    val duration = series.timeS[run.endIdx] - series.timeS[run.startIdx]
                    val peak = (run.startIdx..run.endIdx).maxOf { abs(v[it]) }
                    val disp = displacement(series, run.startIdx, run.endIdx)
                    if (peak < config.startThresholdMps || duration < config.minPhaseS ||
                        disp > config.maxRunDisplacementM
                    ) {
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
    ): List<RepSpan> = if (startsWith == StartPhase.ECCENTRIC) {
        pairEccentricFirst(runs, series, config)
    } else {
        pairConcentricFirst(runs, series, config)
    }

    /** Ecc-first lifts: a rep is a qualifying down+up pair (kills walkout/re-rack bumps). */
    private fun pairEccentricFirst(runs: List<Run>, series: VelocitySeries, config: DspConfig): List<RepSpan> {
        val reps = mutableListOf<RepSpan>()
        var i = 0
        while (i < runs.size) {
            val first = runs[i]
            if (first.type != RunType.DOWN) {
                i++
                continue
            }
            // Find the matching up movement, allowing one STILL run between.
            var j = i + 1
            while (j < runs.size && runs[j].type == RunType.STILL) j++
            if (j >= runs.size || runs[j].type != RunType.UP) {
                i++
                continue
            }
            val second = runs[j]
            val midPauseS = series.timeS[second.startIdx] - series.timeS[first.endIdx]
            val endBoundaryIdx = nextMovementStart(runs, j + 1, series)
            val endPauseS = series.timeS[endBoundaryIdx] - series.timeS[second.endIdx]
            if (displacement(series, second.startIdx, second.endIdx) >= config.minRomM) {
                reps += RepSpan(first.startIdx, first.endIdx, second.startIdx, second.endIdx, midPauseS, endPauseS)
            }
            i = j + 1
        }
        return reps
    }

    /**
     * Con-first lifts (press, deadlift): the DRIVE alone makes the rep — a slow
     * controlled lowering often never exceeds the run threshold, and requiring a
     * down pair would then drop the whole rep. A following down run, when seen,
     * supplies the eccentric metrics; otherwise the eccentric span is empty.
     */
    private fun pairConcentricFirst(runs: List<Run>, series: VelocitySeries, config: DspConfig): List<RepSpan> {
        val reps = mutableListOf<RepSpan>()
        var i = 0
        while (i < runs.size) {
            val con = runs[i]
            if (con.type != RunType.UP || displacement(series, con.startIdx, con.endIdx) < config.minRomM) {
                i++
                continue
            }
            var j = i + 1
            while (j < runs.size && runs[j].type == RunType.STILL) j++
            if (j < runs.size && runs[j].type == RunType.DOWN) {
                val ecc = runs[j]
                val midPauseS = series.timeS[ecc.startIdx] - series.timeS[con.endIdx]
                val endBoundaryIdx = nextMovementStart(runs, j + 1, series)
                val endPauseS = series.timeS[endBoundaryIdx] - series.timeS[ecc.endIdx]
                reps += RepSpan(ecc.startIdx, ecc.endIdx, con.startIdx, con.endIdx, midPauseS, endPauseS)
                i = j + 1
            } else {
                // No detectable lowering — count the drive, leave the eccentric empty.
                val endBoundaryIdx = if (j < runs.size) runs[j].startIdx else series.size - 1
                val endPauseS = series.timeS[endBoundaryIdx] - series.timeS[con.endIdx]
                reps += RepSpan(con.endIdx, con.endIdx, con.startIdx, con.endIdx, 0.0, endPauseS)
                i = j
            }
        }
        return reps
    }

    private fun nextMovementStart(runs: List<Run>, from: Int, series: VelocitySeries): Int {
        var k = from
        while (k < runs.size && runs[k].type == RunType.STILL) k++
        return if (k < runs.size) runs[k].startIdx else series.size - 1
    }

    internal fun displacement(series: VelocitySeries, startIdx: Int, endIdx: Int): Double {
        var d = 0.0
        for (i in startIdx + 1..endIdx) {
            d += abs(series.velocityMps[i]) * (series.timeS[i] - series.timeS[i - 1])
        }
        return d
    }
}
