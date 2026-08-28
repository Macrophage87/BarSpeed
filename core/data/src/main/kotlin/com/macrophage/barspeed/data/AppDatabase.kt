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
 * value that could make that card appear; eleven is simply the next such value,
 * and the first-time claim that used to stand here is history rather than
 * something this bump repeats.
 *
 * REACHABLE IS NOT SHOWN. It takes a rollback: a build carrying 11 writes the
 * file, then any build carrying 10 or less opens it. A forward install runs
 * [MIGRATION_10_11] and never enters the rescue at all, so an ordinary upgrade
 * sees none of it.
 *
 * The version has moved before -- nine times, shipped in v0.1.5, v0.1.10,
 * v0.1.13, v0.1.15, v0.1.16, v0.1.20, twice in v0.1.38, and once in v0.1.42 --
 * the last read off the tag rather than remembered.
 * What is new at 11 is neither the bump nor the downgrade screen: it is that
 * `core/data/schemas/…/10.json` is committed, so for the first time in this
 * repository a migration has a baseline to be read against and, on a bench,
 * executed against.
 */
const val DATABASE_VERSION = 11

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
         * already does. Nothing in this repository can execute either of them:
         * there are no migration tests and no instrumented tests at all, so
         * the first time this runs is on the lifter's phone against their real
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
         * [MIGRATION_8_9] already do. Nothing in this repository can execute
         * either of them: there are no migration tests and no instrumented
         * tests at all, so the first time this runs is on the lifter's phone
         * against their real history. The SQL was read against the entity diff
         * by hand.
         *
         * That paragraph used to add "no committed schema baseline for any
         * version". It was true when written and is not now:
         * `core/data/schemas/…/10.json` was committed at 7db7046, which is
         * what gives [MIGRATION_10_11] a document to be diffed against. The
         * false half is deleted rather than reworded, and only the half that
         * is still true is left standing -- there is still no test in this
         * repository that executes any migration.
         */
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE set_records ADD COLUMN plannedPrepS INTEGER")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN prepS INTEGER")
                }
            }

        /**
         * v11: which accelerometer a raw stream came from, and how many a set
         * was armed with, issue #156.
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
         * indistinguishable from a set that declared it.
         *
         * Two ALTER TABLE statements in one migration, as [MIGRATION_1_2],
         * [MIGRATION_8_9] and [MIGRATION_9_10] already do, and neither carries
         * NOT NULL or a DEFAULT -- which is what keeps them the plain
         * column-append form SQLite performs without recreating the table, so
         * every existing row and every gzipped blob is left byte-for-byte where
         * it was.
         *
         * WHAT HAS AND HAS NOT BEEN RUN, said exactly. The SQL was read against
         * the 10.json/11.json diff by hand, and 11.json in this same commit is
         * Room's own generated description of the schema this build compiles
         * to -- so the two columns, their affinities and their nullability are
         * checked against the entity by the build rather than by eye. What has
         * NOT happened yet is execution: no test in this repository runs a
         * migration, and the two-way emulator exercise for this bump runs once
         * at the end of the v0.1.44 cluster, after #159 and #161, before the
         * cut. Until then this migration is unexecuted.
         *
         * IT IS ALSO EXPECTED TO CHANGE BEFORE IT SHIPS. #159 adds a session
         * RPE column and will EXTEND this migration rather than mint a v12,
         * because v11 is unreleased -- no build in the world has ever written
         * a v11 file, so there is no version boundary to preserve. An edit to
         * an unreleased migration is a normal act; an edit to a released one
         * is not, and this comment is the record of which of the two v11 is at
         * the time of writing.
         */
        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE raw_streams ADD COLUMN role TEXT")
                    db.execSQL("ALTER TABLE set_records ADD COLUMN sensorsJson TEXT")
                }
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
         * A ROLLBACK IS WHAT REACHES ANY OF THIS. [DATABASE_VERSION] is 11
         * here, so a rollback from this build to any build carrying 10 or less
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
                )
                .build()
        }
    }
}
