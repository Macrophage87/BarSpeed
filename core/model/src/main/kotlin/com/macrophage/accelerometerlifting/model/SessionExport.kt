package com.macrophage.accelerometerlifting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Root of a session export; contract is docs/schemas/session-export.schema.json. */
@Serializable
data class SessionExport(
    val schemaVersion: String = SCHEMA_VERSION,
    val startedAt: String,
    val endedAt: String? = null,
    val planRef: String? = null,
    val notes: String? = null,
    val heartRate: HrSessionSummary? = null,
    val exercises: List<ExerciseExport>,
) {
    companion object {
        const val SCHEMA_VERSION = "1.0"
    }
}

@Serializable
data class HrSessionSummary(
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
)

@Serializable
data class ExerciseExport(
    val exercise: String,
    val sets: List<SetExport>,
)

@Serializable
data class SetExport(
    @SerialName("load_kg") val loadKg: Double,
    /** Same load in pounds, for readers who think in lb; kg remains canonical. */
    @SerialName("load_lb") val loadLb: Double? = null,
    @SerialName("plannedLoad_kg") val plannedLoadKg: Double? = null,
    val reps: Int,
    val plannedReps: Int? = null,
    @SerialName("rest_s") val restS: Int? = null,
    val tempoPrescribed: String? = null,
    val tempoCompliance: TempoComplianceExport? = null,
    @SerialName("velocityLoss_pct") val velocityLossPct: Double? = null,
    val hr: HrSetSummary? = null,
    /** Per-rep detail; included only when the user enables detailed export. */
    val repMetrics: List<RepMetricsExport>? = null,
    /** Always-included summary across reps. */
    val summary: SetSummaryExport,
)

@Serializable
data class SetSummaryExport(
    @SerialName("meanConVel_mps") val meanConVelMps: Double? = null,
    @SerialName("peakConVel_mps") val peakConVelMps: Double? = null,
    @SerialName("meanEcc_s") val meanEccS: Double? = null,
    @SerialName("meanCon_s") val meanConS: Double? = null,
    @SerialName("meanRom_m") val meanRomM: Double? = null,
)

@Serializable
data class RepMetricsExport(
    @SerialName("ecc_s") val eccS: Double,
    @SerialName("bottomPause_s") val bottomPauseS: Double,
    @SerialName("con_s") val conS: Double,
    @SerialName("topPause_s") val topPauseS: Double,
    @SerialName("meanConVel_mps") val meanConVelMps: Double,
    @SerialName("peakConVel_mps") val peakConVelMps: Double,
    @SerialName("meanEccVel_mps") val meanEccVelMps: Double,
    @SerialName("rom_m") val romM: Double,
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
)

@Serializable
data class TempoComplianceExport(
    val prescribed: String,
    @SerialName("tolerance_s") val toleranceS: Double,
    val withinTolerance: Int,
    val of: Int,
)

@Serializable
data class HrSetSummary(
    val endOfSetBpm: Int? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
)
