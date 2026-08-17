package com.macrophage.barspeed.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlanEntity::class,
        SessionEntity::class,
        SetRecordEntity::class,
        RawStreamEntity::class,
        CustomExerciseEntity::class,
    ],
    version = 9,
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
         * there are no migration tests, no committed schema baseline for any
         * version, and no instrumented tests at all, so the first time this
         * runs is on the lifter's phone against their real history.
         */
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN zoneId TEXT")
                    db.execSQL("ALTER TABLE sessions ADD COLUMN utcOffsetMinutes INTEGER")
                }
            }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "accelerometer_lifting.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
