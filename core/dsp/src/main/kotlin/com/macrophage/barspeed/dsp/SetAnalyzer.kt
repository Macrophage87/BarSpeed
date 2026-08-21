package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** Metrics for one segmented rep. Durations in seconds, velocities in m/s. */
@Serializable
data class RepAnalysis(
    val index: Int,
    /**
     * Eccentric seconds, or null when no eccentric was measurable (the rep was
     * counted on the drive alone). Never report an unmeasured phase as 0.
     */
    val eccS: Double?,
    val bottomPauseS: Double,
    val conS: Double,
    val topPauseS: Double,
    /** Mean drive velocity, positive in the direction the drive moves. */
    val meanConVelMps: Double,
    val peakConVelMps: Double,
    /** Mean eccentric velocity, negative (opposite the drive); null when unmeasured. */
    val meanEccVelMps: Double?,
    val peakEccVelMps: Double?,
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
    /**
     * False for pauses -- measured and reported, but deliberately not scored --
     * and false for a prescribed movement phase that no rep resolved, because a
     * phase with nothing behind it cannot be graded. This is the flag the export
     * turns into `scoredPhases`, the coach's only statement of what the set's
     * ratio actually covers.
     */
    val scored: Boolean = true,
)

@Serializable
data class TempoComplianceResult(
    val prescribed: Tempo,
    val toleranceS: Double,
    val phases: List<PhaseComplianceResult>,
    /**
     * Reps within tolerance on every scored (movement) phase THAT REP
     * RESOLVED. A phase the sensor never measured is not held against the
     * lifter, so this is not a uniform bar across reps: a drive-only rep
     * clears an easier one than a rep that resolved both. [phases] carries
     * the per-phase counts, and [PhaseComplianceResult.scored] says which
     * phases the set could be graded on at all.
     */
    val repsFullyCompliant: Int,
    /** Reps that resolved at least one scored phase. Reps that resolved none are excluded, not failed. */
    val repsEvaluated: Int,
    /** Prescribed eccentric:concentric contrast — the ratio the block trains. */
    val prescribedEccConRatio: Double? = null,
    /**
     * Measured eccentric:concentric contrast, in the same units as the
     * prescribed one.
     *
     * Taken over the reps that resolved BOTH phases. A rep counted on the
     * drive alone contributes to neither mean, so this need not cover every
     * rep of the set, and nothing published beside it says how many reps it
     * did cover -- that gap is issue #88. Null when no rep resolved an
     * eccentric at all, which is a different fact from a low ratio and must
     * stay distinguishable from one.
     */
    val actualEccConRatio: Double? = null,
) {
    /**
     * Seconds the ECCENTRIC of this set was graded against.
     *
     * [phases] is built from [TempoSchedule], so this is that resolution frozen
     * at analysis time rather than a second reading of the digits. A screen
     * showing a set that has already been recorded should read it here rather
     * than resolve the prescription again: the history screen has no geometry
     * of its own to resolve it with, and a screen that re-derived the answer
     * could print a target line the compliance ratio beside it was not graded
     * against.
     *
     * Null when the eccentric stroke is explosive. That is a real state and not
     * a zero -- a stroke with no prescribed duration has no target line to draw.
     */
    val eccentricPrescribedS: Double?
        get() = phases.firstOrNull { it.phase == PHASE_ECCENTRIC }?.prescribedS

    companion object {
        /** The phase name [SetAnalyzer.complianceFor] writes, and the export publishes, for the eccentric. */
        const val PHASE_ECCENTRIC = "eccentric"
    }
}

/** Targets carried over from the active plan for this set, if any. */
@Serializable
data class SetTargets(
    val plannedReps: Int? = null,
    /**
     * Reps the lifter or the voice guide actually counted. The segmenter is a
     * separate opinion and a worse one — telling someone they did 2 of 10 leg
     * curls because the sensor only resolved 2 is the app calling its own
     * measurement error the athlete's shortfall.
     */
    val countedReps: Int? = null,
    val tempo: Tempo? = null,
    val toleranceS: Double = 0.5,
    val targetMeanConcentricVelocityMps: Double? = null,
    val velocityLossStopPct: Double? = null,
)

@Serializable
data class SetAnalysis(
    val reps: List<RepAnalysis>,
    val sampleRateHz: Double,
    val velocityLossPct: Double?,
    val tempoCompliance: TempoComplianceResult?,
    /** Rule-based coaching notes; empty means "on target". */
    val verdicts: List<String>,
)

/** Full batch analysis of one recorded set. */
object SetAnalyzer {
    fun analyze(
        samples: List<ImuSample>,
        direction: LiftDirection = LiftDirection(),
        loadKg: Double? = null,
        targets: SetTargets = SetTargets(),
        config: DspConfig = DspConfig(),
    ): SetAnalysis {
        val raw = VelocityEstimator.estimate(samples, config, direction.measuredPlane)
        val series = orient(raw, direction, config).mappedToLifter(direction.sensorToLifter)
        val spans = RepSegmenter.segment(series, direction, config)
        val reps = spans.mapIndexed { idx, span -> repMetrics(idx, span, series, direction, loadKg, config) }
        val velocityLoss = velocityLossPct(reps)
        val tempoCompliance =
            targets.tempo?.let { complianceFor(it, targets.toleranceS, reps, direction) }
        val verdicts = CoachingRules.verdicts(reps, velocityLoss, tempoCompliance, targets)
        return SetAnalysis(reps, series.sampleRateHz, velocityLoss, tempoCompliance, verdicts)
    }

    fun analyze(
        samples: List<ImuSample>,
        startsWith: StartPhase,
        loadKg: Double? = null,
        targets: SetTargets = SetTargets(),
        config: DspConfig = DspConfig(),
    ): SetAnalysis = analyze(samples, LiftDirection(startsWith), loadKg, targets, config)

    /**
     * The horizontal principal axis comes out of the covariance with an
     * arbitrary sign — nothing in a yaw-free frame says which end of a seated
     * row is "forward". Orient it from what the lift declares instead: the
     * first real movement of the set is the first phase, so if it points the
     * wrong way, flip the axis. Vertical work needs none of this: up is up.
     *
     * **THIS IS THE ONLY DIRECTION DECISION IN THE DSP TAKEN FROM MEASURED
     * DATA.** Every other direction fact — `driveIsPositive`, `startsAtTop`,
     * `concentricSign`, `concentricRun`, `eccentricRun` — is a pure function of
     * declared inputs, and `concentricUp` is never inferred (issue 96). So the
     * fragile-signal class has exactly one member and it is this function.
     * Searching for a second one has been done; there is not one.
     *
     * It is fragile because ANY pre-rep movement decides it: a 3 cm settle
     * before the first rep inverts the whole set. When that happens the rep
     * count is unchanged and the published velocities stay positive and
     * plausible — they simply describe the wrong stroke, and the eccentric and
     * concentric swap outright. Issue 28, reproduced in
     * [HorizontalAxisOrientationTest], not fixed there: no committed capture
     * can reach this code, so any threshold chosen to harden it would be
     * calibrated against fabricated samples.
     */
    private fun orient(series: VelocitySeries, direction: LiftDirection, config: DspConfig): VelocitySeries {
        if (direction.measuredPlane != MovementPlane.HORIZONTAL) return series
        val firstMove =
            RepSegmenter.classifyRuns(series, config).firstOrNull { it.type != RunType.STILL } ?: return series
        val expected =
            if (direction.startsWith == StartPhase.CONCENTRIC) direction.concentricRun else direction.eccentricRun
        return if (firstMove.type == expected) series else series.mappedToLifter(-1.0)
    }

    private fun repMetrics(
        index: Int,
        span: RepSpan,
        series: VelocitySeries,
        direction: LiftDirection,
        loadKg: Double?,
        config: DspConfig,
    ): RepAnalysis {
        // Sign so that the drive always reads positive, whichever way it moves:
        // a leg curl's concentric goes down, and reporting it as -0.4 m/s would
        // make every downstream comparison and every power figure backwards.
        val sign = direction.concentricSign
        val v = series.velocityMps
        val conDur = series.timeS[span.conEndIdx] - series.timeS[span.conStartIdx]
        val conRange = span.conStartIdx..span.conEndIdx
        val meanCon = conRange.map { v[it] * sign }.average()
        val peakCon = conRange.maxOf { v[it] * sign }
        val eccRange = span.eccStartIdx..span.eccEndIdx
        val eccDur = if (span.hasEccentric) series.timeS[span.eccEndIdx] - series.timeS[span.eccStartIdx] else null
        val meanEcc = if (span.hasEccentric) eccRange.map { v[it] * sign }.average() else null
        val peakEcc = if (span.hasEccentric) eccRange.minOf { v[it] * sign } else null
        val rom = RepSegmenter.displacement(series, span.conStartIdx, span.conEndIdx)
        // Bar power P = m(g + a)v over the drive. Only defensible when the drive
        // lifts the load against gravity — a cammed machine whose drive goes
        // down carries no such relationship, so leave power unreported there.
        //
        // The second clause excludes SIGN-INVERTED setups, not cable ones. An
        // earlier comment here said it kept power off cable machines; it does
        // not, and a low pulley with sensorInverted = false has always passed
        // it. Whether a stack-mounted setup should publish power at all is
        // issue 116; on the conservation argument below it now can.
        val effectiveLoad = loadKg?.takeIf { it > 0 && direction.concentricUp && !direction.sensorInverted }
        // The series was mapped into the LIFTER's frame at analyze(), scaling
        // both velocity and acceleration by travelRatio. loadKg was not: it is
        // the mass on the stack. Multiplying them would combine the handle's
        // motion with the stack's mass — two different bodies — and would give
        // r*(m*g*v) + r^2*(m*a*v), inflating a 2:1 pulley's peak from 482 W to
        // 1056 W. Issue 26.
        //
        // So divide the motion back to the frame the mass is in. Power is
        // conserved across an ideal pulley — a 2:1 halves the force and doubles
        // the travel — so this is the lifter's output measured at the stack,
        // not a stack-only quantity. Exact, and a no-op at ratio 1.0.
        //
        // It ignores pulley friction, which means it slightly UNDERSTATES what
        // the lifter produced. That is left as a known bias rather than
        // corrected by an unmeasured constant, which would replace it with an
        // invented one.
        val conPower = effectiveLoad?.let { load ->
            val ratio = direction.travelRatio
            conRange.map { i ->
                load * (config.gravityMps2 + series.accelMps2[i] / ratio) * (v[i] / ratio)
            }
        }
        val peakPower = conPower?.max()
        val meanConPower = conPower?.average()
        // The pause after the DOWN stroke is the bottom pause, whichever phase
        // that stroke happens to be.
        val bottomPause = if (direction.startsAtTop) span.midPauseS else span.endPauseS
        val topPause = if (direction.startsAtTop) span.endPauseS else span.midPauseS
        return RepAnalysis(
            index = index,
            eccS = eccDur?.let { round2(it) },
            bottomPauseS = round2(bottomPause),
            conS = round2(conDur),
            topPauseS = round2(topPause),
            meanConVelMps = round3(meanCon),
            peakConVelMps = round3(peakCon),
            meanEccVelMps = meanEcc?.let { round3(it) },
            peakEccVelMps = peakEcc?.let { round3(it) },
            romM = round3(rom),
            peakPowerW = peakPower?.let { round1(it) },
            meanConPowerW = meanConPower?.let { round1(it) },
        )
    }

    /**
     * Velocity loss at the point of set termination: best rep → LAST rep, the
     * definition velocity-based training uses. A best-to-worst drawdown would
     * report any single fumbled rep mid-set as the set's fatigue, which inverts
     * the metric against perceived effort.
     */
    fun velocityLossPct(reps: List<RepAnalysis>): Double? = VelocityLoss.of(reps).pctOrNull

    /**
     * Tempo compliance over the two MOVEMENT phases only.
     *
     * Pauses are reported but never scored: telling a genuine pause apart from a
     * very slow movement needs displacement, and displacement is the least
     * trustworthy thing this pipeline produces. Scoring them turned every set
     * into a failure regardless of how the lifter actually moved.
     */
    fun complianceFor(
        tempo: Tempo,
        toleranceS: Double,
        reps: List<RepAnalysis>,
        direction: LiftDirection = LiftDirection(),
    ): TempoComplianceResult {
        val schedule = TempoSchedule.of(tempo, direction)
        data class PhaseDef(
            val name: String,
            val prescribed: Double?,
            val scored: Boolean,
            val actual: (RepAnalysis) -> Double?,
        )

        val defs =
            listOf(
                PhaseDef(TempoComplianceResult.PHASE_ECCENTRIC, schedule.eccentricS, scored = true) { it.eccS },
                PhaseDef("bottomPause", tempo.bottomPauseS, scored = false) { it.bottomPauseS },
                PhaseDef("concentric", schedule.concentricS, scored = true) { it.conS },
                PhaseDef("topPause", tempo.topPauseS, scored = false) { it.topPauseS },
            )
        val phaseResults =
            defs.map { def ->
                val actuals = reps.mapNotNull(def.actual)
                val scorable = def.scored && def.prescribed != null
                val deviations = actuals.map { it - (def.prescribed ?: 0.0) }
                PhaseComplianceResult(
                    phase = def.name,
                    prescribedS = def.prescribed,
                    actualMeanS = if (actuals.isEmpty()) 0.0 else round2(actuals.average()),
                    worstDeviationS = round2(deviations.maxByOrNull { abs(it) } ?: 0.0),
                    repsWithinTolerance =
                    if (scorable) actuals.count { abs(it - def.prescribed!!) <= toleranceS } else 0,
                    repsEvaluated = if (scorable) actuals.size else 0,
                    scored = scorable && actuals.isNotEmpty(),
                )
            }
        val scoredDefs = defs.filter { it.scored && it.prescribed != null }
        // A rep is gradeable once at least one scored phase resolved for it.
        // A rep that resolved none is an absence, not a failure; counting it
        // as one is what graded a set of on-tempo drives as 0 of N.
        val gradeable = reps.filter { rep -> scoredDefs.any { def -> def.actual(rep) != null } }
        val fullyCompliant =
            gradeable.count { rep ->
                scoredDefs.all { def ->
                    val actual = def.actual(rep)
                    actual == null || abs(actual - def.prescribed!!) <= toleranceS
                }
            }
        return TempoComplianceResult(
            prescribed = tempo,
            toleranceS = toleranceS,
            phases = phaseResults,
            repsFullyCompliant = fullyCompliant,
            repsEvaluated = gradeable.size,
            prescribedEccConRatio = ratioOf(schedule.eccentricS, schedule.concentricS),
            actualEccConRatio = pairedEccConRatio(reps),
        )
    }

    /**
     * The set's measured ecc:con contrast, over the reps that resolved both
     * phases.
     *
     * Both means must be taken over the SAME reps. Dividing the mean of the
     * eccentrics that resolved by the mean of every rep's concentric produces
     * a number describing no rep that happened, and the error is not
     * one-signed: it depends on whether the reps that resolved an eccentric
     * also had slower-than-average drives. On the committed captures it ran
     * from a 36% overstatement to a 33% understatement. Issue #46.
     */
    private fun pairedEccConRatio(reps: List<RepAnalysis>): Double? {
        val paired = reps.filter { it.eccS != null }
        if (paired.isEmpty()) return null
        return ratioOf(paired.mapNotNull { it.eccS }.average(), paired.map { it.conS }.average())
    }

    /**
     * Ecc:con contrast — the thing a tempo block actually trains. Report it,
     * but do not read it as a corrected compliance score: it says what the
     * contrast was, not whether the prescription was met.
     *
     * An earlier version of this comment claimed a measured multiplicative
     * bias — "a 2x spread in prescribed ratios came back as a 1.26x spread in
     * measured ones on field data". Retracted. Nothing here derives it, and it
     * cannot be derived here even in principle: the measured ratio does not
     * depend on the prescription at all, so checking that claim needs field
     * sets recorded to different prescribed tempos, and no committed capture
     * carries the tempo it was performed to.
     */
    private fun ratioOf(ecc: Double?, con: Double?): Double? {
        if (ecc == null || con == null || con <= 0.0) return null
        return round2(ecc / con)
    }

    /**
     * How far the reps of one set disagree with each other about range of
     * motion, as a percentage of the mean, or null when the set cannot say.
     *
     * A machine has ONE travel and a lifter range of motion varies a little,
     * so a large spread is a statement about the measurement rather than about
     * the lifter. Published beside meanRom_m so a consumer can see how much the
     * mean is worth; see issue 74, of which it is the readable half.
     *
     * NULL below two reps, never 0.0 and never 100.0. Dispersion over a single
     * rep is undefined, and rendering an undefined figure as a number is the
     * defect this repo keeps re-learning.
     *
     * Population rather than sample deviation: the reps of the set ARE the
     * population, and there is no wider one being estimated.
     *
     * WHAT IT CANNOT TELL YOU. It cannot separate a segmenter that mismeasured
     * a good set from a lifter whose range genuinely varied, and no capture in
     * this corpus can settle that -- the only machine here with an
     * independently known travel is the leg curl rail. A large value says the
     * derived channels rest on displacements that disagree; it does not say
     * which rep is wrong.
     */
    fun romSpreadPct(reps: List<RepAnalysis>): Double? {
        if (reps.size < 2) return null
        val roms = reps.map { it.romM }
        val mean = roms.average()
        if (mean <= 0.0) return null
        val variance = roms.sumOf { (it - mean) * (it - mean) } / roms.size
        return round1(sqrt(variance) / mean * 100.0)
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
        if (targets.countedReps != null && targets.countedReps != reps.size) {
            out += "Sensor resolved ${reps.size} of ${targets.countedReps} reps — " +
                "per-rep velocity and tempo below cover only those."
        }
        val counted = targets.countedReps ?: reps.size
        targets.plannedReps?.let { planned ->
            if (counted < planned) out += "Completed $counted of $planned planned reps."
            if (counted > planned) out += "Completed $counted reps, ${counted - planned} over plan."
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
            if (phase.scored && phase.repsEvaluated > 0 && phase.repsWithinTolerance < phase.repsEvaluated) {
                val direction = if (phase.worstDeviationS < 0) "fast" else "slow"
                out += "Tempo (${phase.phase}): ${phase.repsWithinTolerance}/${phase.repsEvaluated} reps on " +
                    "tempo; worst was ${fmt(abs(phase.worstDeviationS))} s too $direction " +
                    "(target ${fmt(phase.prescribedS ?: 0.0)} s)."
            }
        }
        return out
    }

    /**
     * The one-line caption under the rest screen's eccentric-time chart, and
     * under the same chart on the session detail screen.
     *
     * Lifted verbatim out of `RecordScreen.EccTempoChart` so that it can be
     * tested at all: `:app` has no test source set, so while this decision
     * lived there nothing could execute it. It sits beside [verdicts] because
     * the same card renders both, two lines apart.
     *
     * The rep is named by its own [RepAnalysis.index], not by its position
     * among the reps that resolved an eccentric, and "Fatigue showing." means
     * the last rep PERFORMED rather than the last one measured. Those differ
     * exactly when the unmeasured reps are the late ones, which is the common
     * case: issue #33.
     */
    fun eccentricTempoInsight(reps: List<RepAnalysis>, targetEccS: Double, toleranceS: Double): String {
        // Carry each rep's own ordinal through the filter. Reps counted on the
        // drive alone are not charted and cannot be named, but they are still
        // reps, and the ones after them keep their place in the set.
        val measured = reps.mapNotNull { rep -> rep.eccS?.let { rep.index to it } }
        val lastRepIndex = reps.lastOrNull()?.index
        val worst = measured.maxByOrNull { abs(it.second - targetEccS) }
        return if (worst == null) {
            // Every rep was counted on the drive alone, so the chart above is
            // empty. Saying "All reps on tempo" here states that an unmeasured
            // phase was on tempo -- the same defect as the ratio, in words.
            // Say only what is known: this branch is also reached when NOTHING
            // was graded (an "X" up stroke leaves the eccentric as the only
            // scored phase), so it must not claim the drive was graded either.
            "Eccentric not measured this set."
        } else if (abs(worst.second - targetEccS) <= toleranceS) {
            // Covers only the reps that resolved an eccentric, which is issue
            // #89 and is not addressed here.
            "All reps on tempo."
        } else {
            val delta = targetEccS - worst.second
            String.format(
                java.util.Locale.US,
                "Rep %d eccentric %.1f s — %.1f s too %s.%s",
                worst.first + 1,
                worst.second,
                abs(delta),
                if (delta > 0) "fast" else "slow",
                // The last rep of the set, not the last one measured. A rep
                // partway through a set says nothing about fatigue at the end
                // of it.
                if (delta > 0 && worst.first == lastRepIndex) " Fatigue showing." else "",
            )
        }
    }

    private fun fmt(x: Double) = String.format(java.util.Locale.US, "%.2f", x)
}
