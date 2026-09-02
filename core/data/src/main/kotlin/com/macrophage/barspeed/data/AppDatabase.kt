package com.macrophage.barspeed.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * The schema version this build knows, in one place.
 *
 * A top-level constant rather than a literal repeated in the annotation and in
 * the downgrade check: those two must never disagree, and the way they disagree
 * is somebody bumping one of them.
 *
 * MOVING THIS NUMBER IS ALSO A UI EVENT -- issue #118, which is not closed by
 * this comment. [DatabaseRescue] fires only when the file on disk is NEWER than
 * this constant, and the rescue shipped in v0.1.40 with the constant at 9. So
 * until 10 no stock install could produce a `rescued/` directory, and the
 * rescued-database card -- three tiers, their titles, the discard dialog and
 * the share path -- had never been reachable outside a test. Ten was the first
 * value that could make that card appear; eleven, twelve and thirteen are
 * simply the next such values, and the first-time claim that used to stand here
 * is history rather than something these bumps repeat.
 *
 * REACHABLE IS NOT SHOWN. It takes a rollback: a build carrying 13 writes the
 * file, then any build carrying 12 or less opens it. A forward install runs
 * the migration chain and never enters the rescue at all, so an ordinary
 * upgrade sees none of it.
 *
 * The version has moved before -- eleven times, shipped in v0.1.5, v0.1.10,
 * v0.1.13, v0.1.15, v0.1.16, v0.1.20, twice in v0.1.38, once in v0.1.42 and
 * twice in v0.1.44 -- the tagged ones read off the tags rather than
 * remembered. What was new at 11 was that a committed baseline existed for the
 * version below it, so for the first time in this repository a migration had a
 * document to be read against; 12 was the second such bump and 13 is the
 * third, with `12.json` as its baseline.
 *
 * A CORRECTION TO WHAT STOOD HERE, named rather than reworded around. This
 * paragraph read "BOTH bumps of this cluster reach the emulator in the SAME
 * exercise. v0.1.44 has not been cut, so no stock install carries 11 either".
 * Both halves are false now, and the second is what made the first worth
 * saying: v0.1.44 WAS cut, at tag
 * `7cf6e8c3cc546ab8d64c9fb2be86de2129250b43`, whose `AppDatabase.kt` reads
 * `DATABASE_VERSION = 12`. A stock install therefore carries 12, the
 * 10 -> 11 -> 12 pair has shipped, and the only hop a phone upgrading to this
 * build runs is 12 -> 13. That also settles which release the two-way bench
 * exercise installs first: v0.1.44, the last tag carrying the old version --
 * not v0.1.43, which carries 10.
 */
const val DATABASE_VERSION = 13

/** The database file name, shared with the downgrade check for the same reason. */
const val DATABASE_NAME = "accelerometer_lifting.db"

@Database(
    entities = [
        PlanEntity::class,
        SessionEntity::class,
        SetRecordEntity::class,
        RawStreamEntity::class,
        CustomExerciseEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao

    abstract fun sessionDao(): SessionDao

    abstract fun exerciseDao(): ExerciseDao

    companion object {
        /** v2: timed-set (hold/carry) duration columns on set_records. */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN actualDurationS INTEGER")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN plannedDurationS INTEGER")
                }
            }

        /** v3: unilateral side column on set_records. */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN side TEXT")
                }
            }

        /** v4: lifter-reported RPE and failed-set flag on set_records. */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN rpe INTEGER")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** v5: warm-up flag on set_records, distinct from the RPE scale. */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN warmup INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** v6: session-wide HRV (RMSSD) on sessions. */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN hrvRmssdMs REAL")
                }
            }

        /** v7: manual rep count flag on set_records. */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN repsManual INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * v8: the resolved direction and sensor geometry a set was analysed
         * against, as one JSON column on set_records.
         *
         * Nullable with no default, so existing rows are left exactly as they
         * are and read back as "not captured". Backfilling was considered and
         * refused: only exerciseId links a stored set to a plan, the plan may
         * have been archived or re-imported since, and ad-hoc sets have no plan
         * at all — so a backfill would publish a declaration nobody made.
         */
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN geometryJson TEXT")
                }
            }

        /**
         * v9: the device's time zone and the UTC offset a session was recorded
         * on, as two columns on sessions.
         *
         * Nullable with no default, so existing rows are untouched and read
         * back as "not captured". Backfilling was considered and refused for
         * the same reason [MIGRATION_7_8] refused it, and more sharply: the
         * only offset available at migration time is the one the device is on
         * NOW, and stamping that onto sessions recorded arbitrarily far in the
         * past would be right for most of them and silently wrong for any
         * recorded in another zone — while looking exactly like a value that
         * had been measured.
         *
         * Two ALTER TABLE statements in one migration, as [MIGRATION_1_2]
         * already does. Nothing in this repository executes either of them:
         * [Migration10To11Test] covers the v10 to v11 step and no other, and
         * there is no androidTest source set at all, so the first time this
         * runs is on the lifter's phone against their real
         * history. This used to add "no committed schema baseline for any
         * version"; that half went false at 7db7046 and is deleted rather than
         * reworded. What a baseline changed and what it did not is stated once,
         * at [MIGRATION_10_11], and not repeated here.
         */
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN zoneId TEXT")
                    db.execSQL("ALTER TABLE sessions ADD COLUMN utcOffsetMinutes INTEGER")
                }
            }

        /**
         * v10: the prep prescribed before a set and the prep handed to the
         * voice guide, as two columns on set_records.
         *
         * Nullable with no default, so existing rows are untouched and read
         * back as "not captured" -- the same shape and the same refusal
         * [MIGRATION_7_8] and [MIGRATION_8_9] wrote down. A backfill of the
         * old constant was considered and refused: it would be right for every
         * guided set already recorded and wrong for every unguided one, and the
         * row cannot say which it was. `tempo` is the prescription, not evidence
         * that a voice guide ran against it -- a tempo set recorded as an
         * explosive lift plays no lead-in at all.
         *
         * Two ALTER TABLE statements in one migration, as [MIGRATION_1_2] and
         * [MIGRATION_8_9] already do. Nothing in this repository executes
         * either of them: [Migration10To11Test] covers the v10 to v11 step and
         * no other, and there is no androidTest source set at all, so the
         * first time this runs is on the lifter's phone against their real
         * history. The SQL was read against the entity diff by hand.
         *
         * That paragraph used to add "no committed schema baseline for any
         * version". It was true when written and is not now:
         * `core/data/schemas/…/10.json` was committed at 7db7046, which is
         * what gives [MIGRATION_10_11] a document to be diffed against. The
         * false half is deleted rather than reworded, and only the half that
         * is still true is left standing.
         */
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN plannedPrepS INTEGER")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN prepS INTEGER")
                }
            }

        /**
         * v11: which accelerometer a raw stream came from and how many a set
         * was armed with (issue #156), and how the whole session felt to the
         * lifter (issue #159).
         *
         * Nullable with no default, so existing rows are untouched and read
         * back as "not captured" -- the same shape and the same refusal
         * [MIGRATION_7_8], [MIGRATION_8_9] and [MIGRATION_9_10] each wrote
         * down. A blanket backfill of `role` was considered and refused twice
         * over: a role is meaningless on an `hrm`, `rest_before_hrm`, `cues` or
         * `reps` row, and on an `imu` row it would state which physical unit a
         * capture came from when nobody assigned one. `sensorsJson` is refused
         * for the same reason from the other end -- a row written before this
         * column had one stream, and saying so explicitly would be
         * indistinguishable from a set that declared it. `sessionRpe` is the
         * sharpest of the three: how a past workout FELT is recorded in no
         * artifact this app has ever written, so there is nothing to backfill
         * from and any value put there would be invented outright.
         *
         * Three ALTER TABLE statements in one migration, where [MIGRATION_1_2],
         * [MIGRATION_8_9] and [MIGRATION_9_10] each carry two, and none of the
         * three carries NOT NULL or a DEFAULT -- which is what keeps them the
         * plain column-append form SQLite performs without recreating the
         * table, so every existing row and every gzipped blob is left
         * byte-for-byte where it was.
         *
         * THE EXTENSION THIS COMMENT PREDICTED HAS HAPPENED. It used to say
         * #159 would add a session RPE column and EXTEND this migration rather
         * than mint a v12; that is this third statement, and the reasoning
         * stands as the record of why an edit to a migration was a normal act
         * here. v11 is unreleased -- no build in the world has ever written a
         * v11 file, so there is no version boundary to preserve, and 10.json is
         * untouched while 11.json moves. An edit to a RELEASED migration would
         * not be this; it would need a v12. Read the absence of any further
         * such note as the absence of a further extension, not as this
         * paragraph having gone stale: v11 is still unreleased at the time of
         * writing, so a fourth column may still join it before the cut.
         *
         * WHAT HAS AND HAS NOT BEEN RUN, said exactly. The three columns, their
         * affinities and their nullability are checked against the entity by
         * the build, because 11.json in this same commit is Room's own
         * generated description of the schema this build compiles to.
         * [Migration10To11Test] then runs this migration body -- not a copy of
         * its SQL -- against a recording SupportSQLiteDatabase and diffs what
         * it executes against the committed 10.json/11.json baselines, so a
         * column added to an entity and forgotten here is caught, and so is
         * the reverse. What has NOT happened is execution against a real
         * engine: there is no SQLite on the JVM test classpath and no
         * androidTest source set, so nothing here says SQLite accepts these
         * statements. The two-way emulator exercise for this bump still runs
         * ONCE at the end of the v0.1.44 cluster, after #161, before the cut --
         * this commit does not discharge it and does not claim to. Extending
         * the migration does not move that exercise earlier; it changes what
         * the exercise will have to cover when it runs.
         */
        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE raw_streams ADD COLUMN role TEXT")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN sensorsJson TEXT")
                    db.execSQL("ALTER TABLE sessions ADD COLUMN sessionRpe INTEGER")
                }
            }

        /**
         * v12: `added` on set_records -- whether the lifter appended this set
         * to the exercise mid-session rather than the plan prescribing it
         * (#177).
         *
         * NOT NULL DEFAULT 0, which is a different statement from the three
         * appends at [MIGRATION_10_11] and matches [MIGRATION_4_5]'s `warmup`
         * instead. The entity's field is a non-null `Boolean`, so Room's
         * generated v12 description says `notNull: true`; a plain nullable
         * append would disagree with that description and Room's own TableInfo
         * check would throw on the lifter's phone. SQLite refuses a NOT NULL
         * append with no default on a populated table, so the default is
         * required for the statement to run at all -- and 0 is the only value
         * it can be, because it is the same claim the column's own KDoc makes:
         * nothing in this app's history records which past sets were
         * improvised, so every existing row reads "prescribed" and a set
         * appended on an older build is indistinguishable from one the plan
         * asked for.
         *
         * That is a default written by SQLite into existing rows, not a
         * backfill computed from anything -- no UPDATE runs, and
         * [Migration11To12Test] pins that.
         */
        internal val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN added INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * v13: three nullable columns on set_records -- `limiter` and
         * `limiterNote`, why a set ended (#189), and `warmupMark`, the
         * lifter's own statement about whether a set was preparatory (#194).
         *
         * ALL THREE NULLABLE WITH NO DEFAULT, which is [MIGRATION_10_11]'s
         * shape and deliberately not [MIGRATION_11_12]'s. Each has a real
         * absent state no value can stand for, so a default would publish an
         * answer nobody gave: a set whose reason was never asked for is not a
         * set that ended for an unknown reason, and a set the lifter never
         * marked is not a set they marked as not-a-warm-up. `warmupMark` is a
         * `Boolean?` for exactly that reason -- the two-state flag beside it,
         * `warmup`, is what the PLAN declared, and null here is the third
         * state that keeps "the lifter has said nothing" apart from "the
         * lifter disagreed".
         *
         * WHY ONE HOP CARRIES TWO ISSUES' COLUMNS. #189 and #194 land in the
         * same unreleased window, so a second bump would mint a version
         * boundary no build ever carried and oblige a second two-way bench
         * exercise for a hop no phone will ever run. It is the rule the export
         * schema already follows for an unreleased number, applied to the
         * database: extend the open hop, never mint a boundary nothing
         * crossed. Said here because the alternative -- 12 -> 13 -> 14 inside
         * one release -- reads as the more careful choice and is not.
         *
         * `limiter` is TEXT rather than an enum column, following `side`. The
         * vocabulary is closed and is enforced where the value is PRODUCED
         * rather than where it is stored, so a row written by a later build
         * carrying a value this one does not know reads as an unrecognised
         * string instead of throwing inside a type converter on the lifter's
         * phone. The producers are `SessionDao.updateLimiter` and
         * `SessionDao.updateWarmupMark`; this migration adds only the columns.
         *
         * Nothing is backfilled and no UPDATE runs. Which past sets ended for
         * which reason, and which past sets the lifter would have called
         * warm-ups, is recorded in no artifact this app has ever written --
         * the refusal every migration since v8 has written down.
         * [Migration12To13Test] pins all of it.
         */
        internal val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN limiter TEXT")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN limiterNote TEXT")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN warmupMark INTEGER")
                }
            }

        /**
         * v14: `plannedSide` on set_records -- the side the PLAN prescribed,
         * frozen beside `side`, which from #215 carries the side the lifter
         * actually worked (#144).
         *
         * NULLABLE WITH NO DEFAULT, which is [MIGRATION_10_11]'s and
         * [MIGRATION_12_13]'s shape rather than [MIGRATION_11_12]'s: the
         * entity's field is a `String?` with a real absent state -- bilateral
         * work, an ad-hoc set, an appended set, and every row written before
         * this column existed -- and any default would publish a prescription
         * nobody wrote.
         *
         * Nothing is backfilled, and here the refusal is worth stating
         * precisely because a backfill looks so nearly right: on every row
         * written before v14, `side` WAS the prescription, so
         * `UPDATE set_records SET plannedSide = side` would be true of the
         * plan and false about the lifter. It would assert of every historical
         * set that the app knew which limb moved, which is exactly the claim
         * #144 was opened to say it could not make.
         *
         * DECLARED AND NOT YET REGISTERED AT THIS COMMIT: the body, the
         * [DATABASE_VERSION] bump and the entry in [addMigrations] are #215's
         * fix commit, and [Migration13To14Test] is red until then.
         */
        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) = Unit
            }

        /**
         * Open the database, having first made sure opening it cannot destroy
         * it. Issue #101.
         *
         * BEFORE ROOM IS ASKED FOR ANYTHING, the file on disk is read directly
         * and, if it is newer than this build, moved aside -- so Room then
         * finds nothing, creates an empty database, and the destructive
         * downgrade path is never entered. That is why
         * fallbackToDestructiveMigrationOnDowngrade is gone from the chain
         * below: with the rescue in front of it, it was unreachable, and
         * removing it means a downgrade the rescue MISSES now throws instead of
         * silently dropping every table. Loud beats quiet when the alternative
         * is a corpus that cannot be rebuilt.
         *
         * If the rescue cannot complete there is nothing further to do and
         * nothing further is done: Room meets the newer main file and throws.
         * What "nothing further is done" means for what is still where it was
         * is [RescueOutcome.Failed]'s own KDoc to state, not repeated here --
         * an earlier version of this exact sentence claimed the original was
         * untouched, a gate found that false for a mid-move failure, and it
         * was deleted there rather than reworded. A crash with the data
         * recoverable beats a clean start with it gone.
         *
         * A ROLLBACK IS WHAT REACHES ANY OF THIS. [DATABASE_VERSION] is 13
         * here, so a rollback from this build to any build carrying 12 or less
         * enters the rescue; the first version at which that was true of a
         * stock install was 10, and what it exposes on screen is stated at the
         * constant, with issue #118. An ordinary forward install runs the
         * migration chain and never comes near it.
         *
         * The EXISTING migrations are untouched, and a missing UPGRADE
         * migration still throws exactly as before.
         */
        fun build(context: Context): AppDatabase {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            DatabaseRescue.rescue(
                databaseFile = databaseFile,
                rescueRoot = File(context.filesDir, DatabaseRescue.RESCUE_DIR),
                compiledVersion = DATABASE_VERSION,
            )
            return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                )
                .build()
        }
    }
}
