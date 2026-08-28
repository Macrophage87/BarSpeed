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
    /**
     * The device's time-zone id when this session was created, and the UTC
     * offset in effect at [startedAtMs] — see
     * [com.macrophage.barspeed.model.RecordedTimeZone], which is the pair these
     * two columns hold.
     *
     * Two columns rather than one JSON blob, unlike [SetRecordEntity.geometryJson]:
     * these are two scalars, so columns cost nothing extra and there is no
     * decode-failure path to reason about.
     *
     * Both null means the row predates the capture, which is permanent — the
     * offset a past session was recorded on is not stored in any artifact, not
     * in the raw CSVs either, so unlike anything the DSP derives it cannot be
     * recomputed later. Nothing writes one without the other; a row holding
     * only one is read as not captured rather than half believed.
     */
    val zoneId: String? = null,
    val utcOffsetMinutes: Int? = null,
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
    /**
     * Timed sets (planks, carries): recorded and planned hold/carry seconds.
     *
     * `actualDurationS` is the prescription on a set that ran to its planned
     * end (#168), the measurement on one the lifter stopped, and the stated
     * figure on one corrected afterwards from the rest screen. No column
     * says which: reps have `repsManual` and seconds have no counterpart,
     * and adding one is a migration #168 deliberately did not make.
     */
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /** Lifter-reported RPE (6–10), logged when the set ends; correctable afterward on the rest screen. */
    val rpe: Int? = null,
    /**
     * True when the set is marked failed: the lifter tapped it as failed, the
     * set fell short of its planned reps or duration and the app derived a
     * failure, or both. The derived case needs no lifter input at all.
     */
    val failed: Boolean = false,
    /** True for warm-up sets — kept out of the RPE scale so effort data stays clean. */
    val warmup: Boolean = false,
    val tempo: String? = null,
    val targetMeanConVelMps: Double? = null,
    val velocityLossStopPct: Double? = null,
    val plannedRestS: Int? = null,
    /**
     * The prep prescribed before this set, and the prep handed to the voice
     * guide, in whole seconds.
     *
     * [plannedPrepS] carries its prefix for the reason issue #76 names: a
     * prescription published under a bare name reads as an observation. Whenever
     * the two differ, the lifter adjusted the prep; they are equal both when no
     * adjustment exists and when the adjustment happens to equal what the plan
     * prescribed.
     *
     * Both null on a set that ran no voice guide. Such a set has no prep, and 0
     * there would be absence rendered as a value -- 0 is a real prep, the one
     * where nothing is spoken before the first stroke call.
     *
     * Both are also null on every row written before v10. The prep such a set
     * ran was 5 s wherever its cue stream shows a lead-in ran -- the fixed prep
     * on every build that ever persisted a cue track; the four that ran 3 s,
     * v0.1.21 to v0.1.24, stored none.
     */
    val plannedPrepS: Int? = null,
    val prepS: Int? = null,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** kotlinx-serialized [com.macrophage.barspeed.dsp.SetAnalysis]. */
    val analysisJson: String,
    /**
     * kotlinx-serialized [com.macrophage.barspeed.model.ResolvedGeometry] — the
     * direction and sensor mounting this set was analysed against.
     *
     * Stored as one JSON column rather than eight typed ones, following
     * [analysisJson]: nothing queries these in SQL, and a ninth value added
     * later then costs no further migration. Null means the row predates the
     * capture, which is a real and permanent state — the geometry of a set
     * already recorded cannot be recovered, and guessing it from the exercise
     * id would publish a declaration the app never made.
     */
    val geometryJson: String? = null,
    /**
     * kotlinx-serialized [com.macrophage.barspeed.model.RecordedSensors] — how
     * many accelerometers this set was armed with, which roles they carried and
     * which one its figures came from (#156).
     *
     * One JSON column rather than four typed ones, following [geometryJson]:
     * nothing queries these in SQL, and a fifth value added later then costs no
     * further migration.
     *
     * Null on the ordinary one-sensor set and on every row written before v11,
     * and those two are deliberately not distinguished -- both mean the same
     * thing to a reader, which is that this set has one unroled stream. What is
     * NOT folded into that null is a set that asked for two sensors and armed
     * one: that row carries a declaration saying so, because what arrived is
     * observable from the streams and what was EXPECTED is observable from
     * nothing at all.
     */
    val sensorsJson: String? = null,
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
    /** One of: imu, hrm, rest_before_hrm, cues, reps. */
    val kind: String,
    /** Gzipped CSV in the canonical format (see ImuCsv / HrCsv / CueCsv / RepMarkCsv). */
    val csvGzip: ByteArray,
    val sampleRateHz: Double? = null,
    /**
     * Which physical accelerometer this stream came from, lowercased
     * [com.macrophage.barspeed.model.SensorRole], issue #156.
     *
     * A COLUMN rather than a role-tagged [kind], and that is a ruling. Carrying
     * the role on both streams through the kind would mean `imu-a`/`imu-b`,
     * which every equality selector in this package would then miss -- and the
     * fix for that is prefix matching, which is the one idiom
     * [KIND_REST_BEFORE_HRM] exists to outlaw: it CONTAINS the string `hrm` and
     * is a different population from [KIND_HRM]. A nullable column keeps every
     * `kind` comparison an equality and makes selecting the analysed stream
     * explicit instead of string-derived.
     *
     * Null on every non-IMU stream, where a role means nothing, and on every
     * IMU stream of a one-sensor set, where there is nothing to tell apart.
     * Null is never backfilled: the migration that adds this column states no
     * role for any row already on disk, because a role that nobody assigned is
     * a claim about which unit a capture came from.
     */
    val role: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is RawStreamEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val KIND_IMU = "imu"
        const val KIND_HRM = "hrm"

        /**
         * Heart rate recorded during the rest window BEFORE this set, issue
         * #90.
         *
         * The direction is in the name because a filename is read before a
         * timestamp is. `set02_bench_press_rest_before_hrm.csv` sitting beside
         * `set02_bench_press_hrm.csv` has to say which side of the set it
         * covers without being decoded first; "rest_hrm" would have read as
         * "the rest of set 2" and been ambiguous about the very thing the
         * design turns on.
         *
         * It attaches FORWARD -- to the set that follows the window rather
         * than the one that precedes it -- because a rest window is only
         * complete when the next set begins. Attaching backwards would mean
         * writing onto a row that already exists: a second, non-atomic write
         * path. Attaching forwards puts these samples in the same
         * [SessionDao.insertSetWithStreams] transaction as the set's own
         * streams. The direction is chosen by atomicity, not by how it reads.
         *
         * A DIFFERENT POPULATION FROM [KIND_HRM], and nothing may blur them.
         * `Exporters.minBpm` selects on equality with [KIND_HRM] and is pinned
         * against exactly this kind reaching it.
         */
        const val KIND_REST_BEFORE_HRM = "rest_before_hrm"

        const val KIND_CUES = "cues"

        /**
         * The instants a rep was COUNTED during this set, issue #158.
         *
         * A different population from [KIND_CUES] and nothing may blur them:
         * a cue is what the app said, on a schedule, whether or not anybody
         * moved, and a mark is what was counted. The stream exists only for
         * sets that produced marks -- a tap-counted set or a guided one --
         * and its absence is not a statement that no rep was performed.
         */
        const val KIND_REPS = "reps"
    }
}

@Entity(tableName = "custom_exercises")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** ECCENTRIC or CONCENTRIC. */
    val startsWith: String,
)
