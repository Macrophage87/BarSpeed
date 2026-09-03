package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.StartPhase
import kotlin.math.abs

internal enum class RunType { UP, DOWN, STILL }

/** The plane a movement travels in; see `PlanExerciseDef.plane`. */
enum class MovementPlane { VERTICAL, HORIZONTAL }

internal data class Run(val type: RunType, val startIdx: Int, val endIdx: Int)

/**
 * How a lift moves, as two independent facts the plan declares (see
 * `PlanExerciseDef.start` / `.concentric`):
 *
 * - [startsWith] — which PHASE comes first (eccentric for a squat, concentric
 *   for a press off the rack).
 * - [concentricUp] — which DIRECTION the drive moves (up for almost everything,
 *   down for leg curls and pulldowns).
 *
 * Both are needed: they decide which run type opens a rep, which velocity sign
 * counts as the drive, and whether the pause between phases is at the top or
 * the bottom.
 */
data class LiftDirection(
    val startsWith: StartPhase = StartPhase.ECCENTRIC,
    val concentricUp: Boolean = true,
    /** Sensor moves opposite to the lifter's load (cable stack) — see [ExerciseDef.sensorInverted]. */
    val sensorInverted: Boolean = false,
    /** Lifter-side travel per unit of sensor travel. */
    val travelRatio: Double = 1.0,
    /**
     * The plane the LIFTER moves in — vertical by default, horizontal for
     * seated rows and chest-press machines. Note this is not necessarily the
     * plane the SENSOR moves in: see [sensorOnStack].
     */
    val plane: MovementPlane = MovementPlane.VERTICAL,
    /**
     * True when the sensor rides a cable weight stack. The stack travels
     * VERTICALLY however the lifter moves, so a seated row measured off the
     * stack is a vertical measurement of a horizontal exercise.
     */
    val sensorOnStack: Boolean = false,
) {
    /**
     * The plane to actually measure in. A stack-mounted sensor moves up and
     * down no matter what the lifter is doing, so it is measured vertically
     * and mapped back through [sensorToLifter]; only a sensor travelling WITH
     * a horizontal load is measured horizontally.
     */
    val measuredPlane: MovementPlane
        get() = if (sensorOnStack) MovementPlane.VERTICAL else plane

    /**
     * Horizontal work has no "up": the axis is oriented so the drive is
     * positive, so the drive-direction sign is always +1 there.
     */
    val driveIsPositive: Boolean get() = plane == MovementPlane.HORIZONTAL || concentricUp

    /**
     * Multiplier that maps measured sensor-space vertical motion into the
     * lifter's frame: flipped when the sensor rides the other end of a cable,
     * and scaled when the pulley does not move 1:1.
     */
    val sensorToLifter: Double get() = (if (sensorInverted) -1.0 else 1.0) * travelRatio

    /** True when every rep opens with a downward movement. */
    val startsAtTop: Boolean get() = (startsWith == StartPhase.ECCENTRIC) == concentricUp

    /** +1 when the drive moves up, -1 when it moves down; multiplies world-vertical velocity. */
    val concentricSign: Double get() = if (driveIsPositive) 1.0 else -1.0

    internal val concentricRun: RunType get() = if (driveIsPositive) RunType.UP else RunType.DOWN

    internal val eccentricRun: RunType get() = if (driveIsPositive) RunType.DOWN else RunType.UP
}

/** The two declared direction properties of an exercise, as the DSP consumes them. */
fun ExerciseDef.liftDirection(): LiftDirection = LiftDirection(
    startsWith = startsWith,
    concentricUp = concentricUp,
    sensorInverted = sensorInverted,
    travelRatio = travelRatio,
    plane = if (horizontal) MovementPlane.HORIZONTAL else MovementPlane.VERTICAL,
    sensorOnStack = sensorOnStack,
)

/** One segmented rep, expressed as index spans into the [VelocitySeries]. */
data class RepSpan(
    val eccStartIdx: Int,
    val eccEndIdx: Int,
    val conStartIdx: Int,
    val conEndIdx: Int,
    /**
     * Stillness between this rep's two movement phases -- the turnaround, and
     * the only stationary interval the rep's own phase boundaries contain.
     *
     * Null when the rep was counted on the drive alone: there is no second
     * phase, so there is no interval between two of them. Never 0.0, which
     * would read as a turnaround the lifter took no time over.
     */
    val turnaroundPauseS: Double?,
    /**
     * False when no eccentric run was detectable and the rep was counted on the
     * drive alone — the eccentric span is then a placeholder and its duration
     * and velocity must be reported as unknown, never as zero.
     */
    val hasEccentric: Boolean = true,
)

/**
 * Rep segmentation via run classification: each sample is moving-up, moving-down,
 * or still (velocity dead-band); movement runs that are too short or too slow are
 * merged into stillness; qualifying down+up (or up+down) pairs form reps.
 *
 * KNOWN LIMITATION: phase boundaries land where |v| crosses the dead-band, and
 * a slow phase ramps through that band slowly, so the clipping GROWS with the
 * prescribed duration — measured at 0.37 / 0.91 / 1.36 s lost on 2 / 3 / 4 s
 * eccentrics in field data. Graded against an absolute tolerance that alone
 * fails most reps, so reported phase seconds run short on slow tempo work and
 * should not be read as the athlete's timing.
 */
object RepSegmenter {
    /** [segmentDetailed]'s result: the spans, and the census of how they were arrived at. */
    data class Segmentation(val spans: List<RepSpan>, val census: SegmentationCensus)

    fun segment(
        series: VelocitySeries,
        direction: LiftDirection = LiftDirection(),
        config: DspConfig = DspConfig(),
    ): List<RepSpan> = segmentDetailed(series, direction, config).spans

    fun segment(series: VelocitySeries, startsWith: StartPhase, config: DspConfig = DspConfig()): List<RepSpan> =
        segment(series, LiftDirection(startsWith), config)

    /**
     * [segment], plus the counts a caller needs to say why the span list came
     * back empty. Issue #138.
     *
     * This is the only implementation; [segment] delegates to it and discards
     * the census. There is deliberately no second walk over the series, so the
     * counts describe the run that produced the spans rather than a
     * reconstruction of it.
     */
    fun segmentDetailed(
        series: VelocitySeries,
        direction: LiftDirection = LiftDirection(),
        config: DspConfig = DspConfig(),
    ): Segmentation {
        val classified = classifyRunsDetailed(series, config)
        val paired = pairRuns(classified.runs, series, direction, config)
        return Segmentation(
            paired.spans,
            SegmentationCensus(
                movementRuns = classified.movementRuns,
                overDisplacementCap = classified.overDisplacementCap,
                belowStartThreshold = classified.belowStartThreshold,
                shorterThanMinPhase = classified.shorterThanMinPhase,
                qualifyingRuns = classified.runs.count { it.type != RunType.STILL },
                pairsBelowMinRom = paired.pairsBelowMinRom,
                spans = paired.spans.size,
            ),
        )
    }

    /** [classifyRunsDetailed]'s result. */
    internal data class ClassifiedRuns(
        val runs: List<Run>,
        val movementRuns: Int,
        val overDisplacementCap: Int,
        val belowStartThreshold: Int,
        val shorterThanMinPhase: Int,
    )

    internal fun classifyRuns(series: VelocitySeries, config: DspConfig): List<Run> =
        classifyRunsDetailed(series, config).runs

    internal fun classifyRunsDetailed(series: VelocitySeries, config: DspConfig): ClassifiedRuns {
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
        //
        // The three counts below are taken WHILE this runs, for #138's
        // diagnosis, and are independent: a run failing two terms is counted
        // under both, because the demotion is one three-way `||` and nothing
        // here attributes it to a single cause. They change no verdict.
        var movementRuns = 0
        var overDisplacementCap = 0
        var belowStartThreshold = 0
        var shorterThanMinPhase = 0
        val demoted =
            runs.map { run ->
                if (run.type == RunType.STILL) {
                    run
                } else {
                    movementRuns++
                    val duration = series.timeS[run.endIdx] - series.timeS[run.startIdx]
                    val peak = (run.startIdx..run.endIdx).maxOf { abs(v[it]) }
                    val disp = displacement(series, run.startIdx, run.endIdx)
                    if (peak < config.startThresholdMps) belowStartThreshold++
                    if (duration < config.minPhaseS) shorterThanMinPhase++
                    if (disp > config.maxRunDisplacementM) overDisplacementCap++
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
        return ClassifiedRuns(merged, movementRuns, overDisplacementCap, belowStartThreshold, shorterThanMinPhase)
    }

    /** [pairRuns]'s result: the spans, and how many pairs the [DspConfig.minRomM] floor discarded. */
    private data class Paired(val spans: List<RepSpan>, val pairsBelowMinRom: Int)

    private fun pairRuns(
        runs: List<Run>,
        series: VelocitySeries,
        direction: LiftDirection,
        config: DspConfig,
    ): Paired = if (direction.startsWith == StartPhase.ECCENTRIC) {
        pairEccentricFirst(runs, series, direction, config)
    } else {
        pairConcentricFirst(runs, series, direction, config)
    }

    /** Ecc-first lifts: a rep is a qualifying eccentric+concentric pair (kills walkout/re-rack bumps). */
    private fun pairEccentricFirst(
        runs: List<Run>,
        series: VelocitySeries,
        direction: LiftDirection,
        config: DspConfig,
    ): Paired {
        val reps = mutableListOf<RepSpan>()
        var belowMinRom = 0
        var i = 0
        while (i < runs.size) {
            val first = runs[i]
            if (first.type != direction.eccentricRun) {
                i++
                continue
            }
            // Find the matching drive, allowing one STILL run between.
            var j = i + 1
            while (j < runs.size && runs[j].type == RunType.STILL) j++
            if (j >= runs.size || runs[j].type != direction.concentricRun) {
                i++
                continue
            }
            val second = runs[j]
            val turnaroundPauseS = series.timeS[second.startIdx] - series.timeS[first.endIdx]
            if (displacement(series, second.startIdx, second.endIdx) >= config.minRomM) {
                reps += RepSpan(
                    first.startIdx,
                    first.endIdx,
                    second.startIdx,
                    second.endIdx,
                    turnaroundPauseS,
                )
            } else {
                belowMinRom++
            }
            i = j + 1
        }
        return Paired(reps, belowMinRom)
    }

    /**
     * Con-first lifts (press, deadlift, leg curl): the DRIVE alone makes the rep
     * — a slow controlled return often never exceeds the run threshold, and
     * requiring a paired return would then drop the whole rep. A following
     * eccentric run, when seen, supplies the eccentric metrics; otherwise the
     * rep is flagged [RepSpan.hasEccentric] = false so the export can say
     * "unknown" rather than invent a zero.
     */
    private fun pairConcentricFirst(
        runs: List<Run>,
        series: VelocitySeries,
        direction: LiftDirection,
        config: DspConfig,
    ): Paired {
        val reps = mutableListOf<RepSpan>()
        var belowMinRom = 0
        var i = 0
        while (i < runs.size) {
            val con = runs[i]
            if (con.type != direction.concentricRun) {
                i++
                continue
            }
            if (displacement(series, con.startIdx, con.endIdx) < config.minRomM) {
                belowMinRom++
                i++
                continue
            }
            var j = i + 1
            while (j < runs.size && runs[j].type == RunType.STILL) j++
            if (j < runs.size && runs[j].type == direction.eccentricRun) {
                val ecc = runs[j]
                val turnaroundPauseS = series.timeS[ecc.startIdx] - series.timeS[con.endIdx]
                reps += RepSpan(ecc.startIdx, ecc.endIdx, con.startIdx, con.endIdx, turnaroundPauseS)
                i = j + 1
            } else {
                // No detectable return — count the drive; the eccentric is
                // unknown, and so is any turnaround: a turnaround is between
                // two phases and only one of them resolved.
                reps += RepSpan(
                    con.endIdx,
                    con.endIdx,
                    con.startIdx,
                    con.endIdx,
                    turnaroundPauseS = null,
                    hasEccentric = false,
                )
                i = j
            }
        }
        return Paired(reps, belowMinRom)
    }

    internal fun displacement(series: VelocitySeries, startIdx: Int, endIdx: Int): Double {
        var d = 0.0
        for (i in startIdx + 1..endIdx) {
            d += abs(series.velocityMps[i]) * (series.timeS[i] - series.timeS[i - 1])
        }
        return d
    }
}
