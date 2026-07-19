package com.macrophage.barspeed.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An imported plan. Plans arrive as JSON, are staged for approval, then activated. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Raw plan JSON as imported (already schema-validated). */
    val json: String,
    val importedAtMs: Long,
    /** One of: staged, active, archived. */
    val status: String,
) {
    companion object {
        const val STATUS_STAGED = "staged"
        const val STATUS_ACTIVE = "active"
        const val STATUS_ARCHIVED = "archived"
    }
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val planName: String? = null,
    val planSessionName: String? = null,
    val notes: String? = null,
    val hrAvgBpm: Int? = null,
    val hrMaxBpm: Int? = null,
    /** Session-wide HRV (RMSSD, ms) from R-R intervals, sets and rests included. */
    val hrvRmssdMs: Double? = null,
)

@Entity(
    tableName = "set_records",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SetRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val orderIdx: Int,
    val exerciseId: String,
    val exerciseName: String,
    val loadKg: Double,
    val plannedLoadKg: Double? = null,
    val actualReps: Int,
    /** True when actualReps was entered or corrected by the lifter, not the sensor. */
    val repsManual: Boolean = false,
    val plannedReps: Int? = null,
    /** Timed sets (planks, carries): actual and planned hold/carry seconds. */
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /** Lifter-reported RPE (6–10), entered on the rest screen. */
    val rpe: Int? = null,
    /** True when the lifter marked the set as failed. */
    val failed: Boolean = false,
    /** True for warm-up sets — kept out of the RPE scale so effort data stays clean. */
    val warmup: Boolean = false,
    val tempo: String? = null,
    val targetMeanConVelMps: Double? = null,
    val velocityLossStopPct: Double? = null,
    val plannedRestS: Int? = null,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** kotlinx-serialized [com.macrophage.barspeed.dsp.SetAnalysis]. */
    val analysisJson: String,
    val hrEndOfSetBpm: Int? = null,
    val hrAvgBpm: Int? = null,
    val hrMaxBpm: Int? = null,
)

@Entity(
    tableName = "raw_streams",
    foreignKeys = [
        ForeignKey(
            entity = SetRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("setId")],
)
data class RawStreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    /** One of: imu, hrm. */
    val kind: String,
    /** Gzipped CSV in the canonical format (see ImuCsv / HrCsv). */
    val csvGzip: ByteArray,
    val sampleRateHz: Double? = null,
) {
    override fun equals(other: Any?): Boolean = other is RawStreamEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val KIND_IMU = "imu"
        const val KIND_HRM = "hrm"
    }
}

@Entity(tableName = "custom_exercises")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** ECCENTRIC or CONCENTRIC. */
    val startsWith: String,
)
