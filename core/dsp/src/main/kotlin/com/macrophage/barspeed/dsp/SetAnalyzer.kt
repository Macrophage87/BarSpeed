package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Metrics for one segmented rep. Durations in seconds, velocities in m/s. */
@Serializable
data class RepAnalysis(
    val index: Int,
    val eccS: Double,
    val bottomPauseS: Double,
    val conS: Double,
    val topPauseS: Double,
    val meanConVelMps: Double,
    val peakConVelMps: Double,
    val meanEccVelMps: Double,
    val peakEccVelMps: Double,
    val romM: Double,
    val peakPowerW: Double?,
    /** Average power over the concentric (drive) phase, watts. Null for bodyweight. */
    val meanConPowerW: Double? = null,
)

@Serializable
data class PhaseComplianceResult(
    val phase: String,
    val prescribedS: Double?,
    val actualMeanS: Double,
    val worstDeviationS: Double,
    val repsWithinTolerance: Int,
    val repsEvaluated: Int,
)

@Serializable
data class TempoComplianceResult(
    val prescribed: Tempo,
    val toleranceS: Double,
    val phases: List<PhaseComplianceResult>,
    /** Reps where every prescribed phase was within tolerance. */
    val repsFullyCompliant: Int,
    val repsEvaluated: Int,
)

/** Targets carried over from the active plan for this set, if any. */
@Serializable
data class SetTargets(
    val plannedReps: Int? = null,
    val tempo: Tempo? = null,
    val toleranceS: Double = 0.5,
    val targetMeanConcentricVelocityMps: Double? = null,
    val velocityLossStopPct: Double? = null,
)

@Serializable
data class SetAnalysis(
    val reps: List<RepAnalysis>,
    val sampleRateHz: Double,
    /** Velocity loss of the slowest rep vs the best rep, percent (VBT fatigue metric). */
    val velocityLossPct: Double?,
    val tempoCompliance: TempoComplianceResult?,
    /** Rule-based coaching notes; empty means "on target". */
    val verdicts: List<String>,
)

/** Full batch analysis of one recorded set. */
object SetAnalyzer {
    fun analyze(
        samples: List<ImuSample>,
        startsWith: StartPhase = StartPhase.ECCENTRIC,
        loadKg: Double? = null,
        targets: SetTargets = SetTargets(),
        config: DspConfig = DspConfig(),
    ): SetAnalysis {
        val series = VelocityEstimator.estimate(samples, config)
        val spans = RepSegmenter.segment(series, startsWith, config)
        val reps = spans.mapIndexed { idx, span -> repMetrics(idx, span, series, startsWith, loadKg, config) }
        val velocityLoss = velocityLossPct(reps)
        val tempoCompliance = targets.tempo?.let { complianceFor(it, targets.toleranceS, reps) }
        val verdicts = CoachingRules.verdicts(reps, velocityLoss, tempoCompliance, targets)
        return SetAnalysis(reps, series.sampleRateHz, velocityLoss, tempoCompliance, verdicts)
    }

    private fun repMetrics(
        index: Int,
        span: RepSpan,
        series: VelocitySeries,
        startsWith: StartPhase,
        loadKg: Double?,
        config: DspConfig,
    ): RepAnalysis {
        val v = series.velocityMps
        val eccDur = series.timeS[span.eccEndIdx] - series.timeS[span.eccStartIdx]
        val conDur = series.timeS[span.conEndIdx] - series.timeS[span.conStartIdx]
        val conRange = span.conStartIdx..span.conEndIdx
        val eccRange = span.eccStartIdx..span.eccEndIdx
        val meanCon = conRange.map { v[it] }.average()
        val peakCon = conRange.maxOf { v[it] }
        val meanEcc = eccRange.map { v[it] }.average()
        val peakEcc = eccRange.minOf { v[it] }
        val rom = RepSegmenter.displacement(series, span.conStartIdx, span.conEndIdx)
        // Bar power P = m(g + a)v over the drive; meaningless without load on the bar.
        val effectiveLoad = loadKg?.takeIf { it > 0 }
        val conPower = effectiveLoad?.let { load ->
            conRange.map { i -> load * (config.gravityMps2 + series.accelMps2[i]) * v[i] }
        }
        val peakPower = conPower?.max()
        val meanConPower = conPower?.average()
        val bottomPause = if (startsWith == StartPhase.ECCENTRIC) span.midPauseS else span.endPauseS
        val topPause = if (startsWith == StartPhase.ECCENTRIC) span.endPauseS else span.midPauseS
        return RepAnalysis(
            index = index,
            eccS = round2(eccDur),
            bottomPauseS = round2(bottomPause),
            conS = round2(conDur),
            topPauseS = round2(topPause),
            meanConVelMps = round3(meanCon),
            peakConVelMps = round3(peakCon),
            meanEccVelMps = round3(meanEcc),
            peakEccVelMps = round3(peakEcc),
            romM = round3(rom),
            peakPowerW = peakPower?.let { round1(it) },
            meanConPowerW = meanConPower?.let { round1(it) },
        )
    }

    fun velocityLossPct(reps: List<RepAnalysis>): Double? {
        if (reps.size < 2) return null
        val best = reps.maxOf { it.meanConVelMps }
        val worst = reps.minOf { it.meanConVelMps }
        if (best <= 0) return null
        return round1((best - worst) / best * 100.0)
    }

    fun complianceFor(tempo: Tempo, toleranceS: Double, reps: List<RepAnalysis>): TempoComplianceResult {
        data class PhaseDef(val name: String, val prescribed: Double?, val actual: (RepAnalysis) -> Double)

        val defs =
            listOf(
                PhaseDef("eccentric", tempo.eccentricS) { it.eccS },
                PhaseDef("bottomPause", tempo.bottomPauseS) { it.bottomPauseS },
                PhaseDef("concentric", tempo.concentricS) { it.conS },
                PhaseDef("topPause", tempo.topPauseS) { it.topPauseS },
            )
        val phaseResults =
            defs.map { def ->
                val deviations = reps.map { rep -> def.actual(rep) - (def.prescribed ?: 0.0) }
                val evaluated = if (def.prescribed != null) reps.size else 0
                val within =
                    if (def.prescribed != null) {
                        reps.count { rep -> abs(def.actual(rep) - def.prescribed) <= toleranceS }
                    } else {
                        0
                    }
                PhaseComplianceResult(
                    phase = def.name,
                    prescribedS = def.prescribed,
                    actualMeanS = round2(reps.map(def.actual).average()),
                    worstDeviationS = round2(deviations.maxByOrNull { abs(it) } ?: 0.0),
                    repsWithinTolerance = within,
                    repsEvaluated = evaluated,
                )
            }
        val fullyCompliant =
            reps.count { rep ->
                defs.all { def ->
                    def.prescribed == null || abs(def.actual(rep) - def.prescribed) <= toleranceS
                }
            }
        return TempoComplianceResult(tempo, toleranceS, phaseResults, fullyCompliant, reps.size)
    }

    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
}

/** Deterministic rule-based coaching verdicts shown on the rest screen. */
object CoachingRules {
    fun verdicts(
        reps: List<RepAnalysis>,
        velocityLossPct: Double?,
        tempoCompliance: TempoComplianceResult?,
        targets: SetTargets,
    ): List<String> {
        val out = mutableListOf<String>()
        if (reps.isEmpty()) {
            out += "No reps detected — check sensor placement and that the set was recorded."
            return out
        }
        targets.plannedReps?.let { planned ->
            if (reps.size < planned) out += "Completed ${reps.size} of $planned planned reps."
            if (reps.size > planned) out += "Completed ${reps.size} reps, ${reps.size - planned} over plan."
        }
        targets.targetMeanConcentricVelocityMps?.let { target ->
            val mean = reps.map { it.meanConVelMps }.average()
            if (mean < target - 0.05) {
                out += "Bar speed low: mean concentric ${fmt(mean)} m/s vs target ${fmt(target)} m/s — " +
                    "consider reducing load."
            }
        }
        targets.velocityLossStopPct?.let { stop ->
            val best = reps.maxOf { it.meanConVelMps }
            val firstOver =
                reps.firstOrNull { best > 0 && (best - it.meanConVelMps) / best * 100.0 > stop }
            if (firstOver != null && firstOver.index < reps.size - 1) {
                out += "Velocity-loss stop ($stop%) was reached at rep ${firstOver.index + 1}; " +
                    "later reps exceeded the plan."
            }
        }
        velocityLossPct?.let { loss ->
            if (loss > 35.0 && targets.velocityLossStopPct == null) {
                out += "High velocity loss ($loss%) — significant fatigue this set."
            }
        }
        tempoCompliance?.phases?.forEach { phase ->
            if (phase.repsEvaluated > 0 && phase.repsWithinTolerance < phase.repsEvaluated) {
                val direction = if (phase.worstDeviationS < 0) "fast" else "slow"
                out += "Tempo (${phase.phase}): ${phase.repsWithinTolerance}/${phase.repsEvaluated} reps on " +
                    "tempo; worst was ${fmt(abs(phase.worstDeviationS))} s too $direction " +
                    "(target ${fmt(phase.prescribedS ?: 0.0)} s)."
            }
        }
        return out
    }

    private fun fmt(x: Double) = String.format(java.util.Locale.US, "%.2f", x)
}
