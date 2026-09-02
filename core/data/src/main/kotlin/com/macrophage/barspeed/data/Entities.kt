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
    /**
     * How the whole session felt to the lifter, 1 to 10, stated once at the
     * finish (#159). See [com.macrophage.barspeed.model.SessionRpe], which owns
     * the scale.
     *
     * NOT [SetRecordEntity.rpe]. That column is how much ONE set had left in
     * it, on 1 to 10 anchored as reps in reserve at the top and as load or
     * time headroom below; this is the whole workout on 1 to 10. Two columns
     * of the same type in the same database, both called RPE, so the difference
     * is written at both of them.
     *
     * Null means the lifter did not rate the session -- the rating is skippable
     * with one tap -- and null on every row recorded before this column existed.
     * There is no default and no midpoint: a 5 would be an answer nobody gave,
     * and a 0 would be an answer off the scale that reads as the easiest session
     * ever recorded. Nothing backfills it, the refusal every migration since
     * v8 has written down, and here it is not even guessable: no artifact
     * anywhere records how a past workout felt.
     */
    val sessionRpe: Int? = null,
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
     * `actualDurationS` is the seconds the set was working to on a set that
     * ran to its planned end (#168) -- `plannedDurationS` unless the lifter
     * changed the hold in the change-set dialog, in which case theirs -- the
     * measurement on one the lifter stopped, and the stated
     * figure on one corrected afterwards from the rest screen. No column
     * says which: reps have `repsManual` and seconds have no counterpart,
     * and adding one is a migration #168 deliberately did not make.
     */
    val actualDurationS: Int? = null,
    val plannedDurationS: Int? = null,
    /** Unilateral sets: "left" or "right". */
    val side: String? = null,
    /**
     * Lifter-reported effort for ONE set, 1 to 10, logged when the set ends
     * and correctable afterward on the rest screen.
     *
     * The grid writes 1, 4, 6, 7, 8, 9 or 10;
     * [com.macrophage.barspeed.model.EffortScale] owns the scale, which is
     * reps in reserve at 7 to 10 and load or time headroom at 6, 4 and 1. A
     * pre-v0.1.45 value is on the old 6-to-10 ladder, where 6 was its FLOOR,
     * "easy, 4+ reps left", and so absorbed everything 1 and 4 now separate.
     */
    val rpe: Int? = null,
    /**
     * True when the set is marked failed: the lifter tapped it as failed, the
     * set fell short of its planned reps or duration and the app derived a
     * failure, or both. The derived case needs no lifter input at all.
     */
    val failed: Boolean = false,
    /**
     * True when the PLAN declared this set preparatory -- a ramp set, a
     * warm-up.
     *
     * Since v0.1.45 it is written from `PlanSetDef.warmup` off the frozen slot
     * and by nothing else. It says what the set was FOR and carries no claim
     * about [rpe], because a warm-up set is now rated on the same scale as any
     * other set. Before that the only producer was an effort TILE that stored
     * this flag and a null [rpe] together, so on an older row the pair means
     * the app could not record both. False on an ad-hoc or an appended set,
     * because nothing declared those.
     */
    val warmup: Boolean = false,
    /**
     * The LIFTER'S own statement that this set was, or was not, preparatory
     * (#194). Null means they have never said.
     *
     * Three states and not two, which is the whole reason it sits beside
     * [warmup] rather than inside it. [warmup] is what the PLAN declared,
     * frozen when the set was recorded, and #187 spent a whole change making
     * it mean only that; overwriting it with a lifter's tap would take the
     * declaration back out of the row. So the two facts are kept apart, the
     * way `tappedFailed` and the derived shortfall are kept apart one flag
     * over, and the effective answer is composed from them by
     * [com.macrophage.barspeed.model.WarmupMarkPolicy] rather than by whoever
     * happens to be reading.
     *
     * WHOSE ANSWER WINS IS DECIDED AND IS THE LIFTER'S. The plan's
     * declaration is a prediction written before the session; the mark is a
     * statement by the person who did the set, made after it. That is the
     * same ordering by which a re-rating on the rest screen overwrites a
     * tapped failure. Null is not a quiet "false": it is the ordinary state
     * of every set on a declared plan, and it is what lets the record say
     * that a lifter unmarked a plan warm-up rather than silently reading as
     * though the plan never declared one.
     *
     * Null on every row written before v13, and nothing backfills it.
     */
    val warmupMark: Boolean? = null,
    /**
     * True when the lifter APPENDED this set to the exercise mid-session
     * rather than the plan prescribing it (#177).
     *
     * A column and not a derivation. The obvious derivation -- `plannedReps`
     * is null, so nothing prescribed it -- cannot tell an appended set from an
     * ad-hoc one or from a plan block written without rep targets, and the
     * question a coach asks of the export is exactly the one those three
     * collapse: how many sets did the plan ask for, and how many did the
     * lifter do. An appended set silently occupying a prescribed slot makes
     * that unanswerable.
     *
     * FALSE IS THE HONEST DEFAULT AND ALSO AN AMBIGUITY, stated rather than
     * hidden: every set recorded before v12 reads false, and a set the lifter
     * appended on an older build is indistinguishable from a prescribed one.
     * The migration writes nothing into existing rows, because which past sets
     * were improvised is recorded in no artifact this app has ever written and
     * a plausible backfill reads exactly like a measured one.
     */
    val added: Boolean = false,
    /**
     * Why the set ended, from a closed vocabulary, or null (#189).
     *
     * A NULLABLE COLUMN ON EVERY SET ROW, deliberately not a field keyed off
     * [failed]. Only a failed set is asked today, so in practice every
     * non-null value sits on one; #191 widens the question to completed sets,
     * and storing it per-failure would have made that a migration instead of
     * a screen change. The column is therefore named for what it holds -- the
     * thing that limited the set -- and not `failure_reason`, so a released
     * field never has to be renamed.
     *
     * NULL IS A STATE AND NOT A DEFAULT ANSWER. The page is skippable in one
     * tap, and a set nobody was asked about, a set the lifter declined to
     * explain and a set recorded before v13 all read null: none of them is a
     * set that ended for an unknown reason, and none of them may be counted
     * as one.
     *
     * TEXT, holding the lowercased name of one
     * [com.macrophage.barspeed.model.SetLimiter], validated where the value
     * is produced rather than by a type converter -- so a value written by a
     * later build reads back as an unrecognised string here instead of
     * throwing while the lifter is mid-session. `side` is stored the same way
     * for the same reason.
     */
    val limiter: String? = null,
    /**
     * The lifter's own words, when [limiter] is `other` and only then (#189).
     *
     * A SEPARATE COLUMN BESIDE THE ENUM, never a value inside it. The whole
     * point of the closed vocabulary is that a coach can group by it, and a
     * free string stored in the same column destroys exactly that.
     *
     * Capped and single-line at capture by
     * [com.macrophage.barspeed.model.SetLimiter.normalizeNote], which is also
     * what makes "exported verbatim" true rather than aspirational: the raw
     * archive's manifest is assembled as text, so a newline or a backslash
     * arriving here would make that whole document unparseable. What is
     * stored is what the lifter typed with the ends trimmed once, here at the
     * write; see [com.macrophage.barspeed.model.SetLimiter.sanitizeForTyping]
     * for why the two transforms are not the same function.
     */
    val limiterNote: String? = null,
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
     * NOT folded into that null is a set that met two PAIRED units it could
     * not tell apart and recorded one: that row carries a declaration naming
     * the gap, because what arrived is observable from the streams and what
     * was IN THE WAY is observable from nothing at all. Paired, not connected
     * -- the declaration is written from the persisted list of units the app
     * remembers, so it says nothing about whether the second one was on.
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
    /** One of: imu, hrm, rest_before_hrm, rest_after_hrm, cues, reps, prep. */
    val kind: String,
    /**
     * Gzipped CSV in the canonical format (see ImuCsv / HrCsv / CueCsv /
     * RepMarkCsv / PrepWindowCsv).
     */
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

        /**
         * Heart rate recorded during the rest window AFTER the last set of a
         * session, issue #109.
         *
         * The one window [KIND_REST_BEFORE_HRM]'s forward attachment cannot
         * reach: there is no next set to carry it. Field-36 measured the cost
         * -- fourteen `rest_before_hrm` files and nothing after set 14 -- and
         * the window it drops is the one the lifter is most likely to be
         * genuinely resting through.
         *
         * It attaches BACKWARD, onto the last set's row, and that is the
         * second, non-atomic write [KIND_REST_BEFORE_HRM] was designed to
         * avoid. The trade is stated rather than hidden: the alternative is a
         * column or a table on the session, which costs a `DATABASE_VERSION`
         * bump and a migration, and `kind` is a free-form string so this costs
         * neither. A failed second write loses this window and nothing else --
         * the set row and all its own streams are already durable -- whereas
         * the forward attachment risks nothing because it rides an insert that
         * has to happen anyway.
         *
         * A SEPARATE KIND FROM [KIND_REST_BEFORE_HRM], not a position. Both
         * are rest windows and both would be a `rest_before` of some set if
         * the session had continued; what distinguishes this one is that the
         * session ended, and a reader must be able to see that from the
         * filename rather than by counting sets. It is a different population
         * from [KIND_HRM] for the same reason that one is, and the name ends
         * in `hrm` in the same trap-laying way: every selector matching it
         * must match by equality.
         */
        const val KIND_REST_AFTER_HRM = "rest_after_hrm"

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

        /**
         * Where this set's prep was: the interval between the lifter starting
         * the set and the set's work beginning (#185).
         *
         * A row rather than a column on [SetRecordEntity], and that is a
         * ruling taken under a constraint rather than a preference. A column
         * costs a `DATABASE_VERSION` bump and a migration, which #185's app
         * half was scoped without; a row costs neither, because `kind` is a
         * string and every selector in this package matches it by equality.
         * The cost is stated rather than hidden: an interval is not a stream,
         * so this is the first kind here that holds exactly one row.
         *
         * A DIFFERENT POPULATION FROM [KIND_CUES] AND [KIND_REPS], the same
         * way those two are different from each other. A cue is what the app
         * SAID, a mark is what was COUNTED, and this is where the set stopped
         * being prep -- a boundary the app knows exactly and an analysis
         * reading the samples alone can only estimate. It is written only for
         * a set that ran a prep and closed it; its absence is not a claim that
         * the lifter was never stationary.
         */
        const val KIND_PREP = "prep"
    }
}

@Entity(tableName = "custom_exercises")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** ECCENTRIC or CONCENTRIC. */
    val startsWith: String,
)
