package com.macrophage.barspeed.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Insert
    suspend fun insert(plan: PlanEntity): Long

    @Update
    suspend fun update(plan: PlanEntity)

    @Query("SELECT * FROM plans ORDER BY importedAtMs DESC")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE status = 'active' LIMIT 1")
    fun observeActive(): Flow<PlanEntity?>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun byId(id: Long): PlanEntity?

    @Query("UPDATE plans SET status = 'archived' WHERE status = 'active'")
    suspend fun archiveActive()

    @Query("UPDATE plans SET status = 'active' WHERE id = :id")
    suspend fun markActive(id: Long)

    @Transaction
    suspend fun activate(id: Long) {
        archiveActive()
        markActive(id)
    }

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Insert
    suspend fun insertSet(set: SetRecordEntity): Long

    @Insert
    suspend fun insertRawStream(stream: RawStreamEntity): Long

    /**
     * A set row and every raw stream belonging to it, in one transaction.
     *
     * The streams carry a placeholder [RawStreamEntity.setId]; the real id is
     * only known once the row is inserted, so it is stamped here.
     *
     * Written as a loop over the existing single-row inserts rather than as a
     * new list-taking `@Insert`, so that the DAO gains no abstract member: an
     * abstract one would have to be implemented by every hand-written fake,
     * which would force the commit that switches the caller to edit the test
     * that guards it. Same shape as [PlanDao.activate], the only other
     * `@Transaction` in this file.
     */
    @Transaction
    suspend fun insertSetWithStreams(set: SetRecordEntity, streams: List<RawStreamEntity>): Long {
        val setId = insertSet(set)
        for (stream in streams) insertRawStream(stream.copy(setId = setId))
        return setId
    }

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun sessionById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM set_records WHERE sessionId = :sessionId ORDER BY orderIdx")
    suspend fun setsForSession(sessionId: Long): List<SetRecordEntity>

    @Query("SELECT * FROM set_records WHERE sessionId = :sessionId ORDER BY orderIdx")
    fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>>

    // ORDER BY id, added with issue #156. With one stream per kind the order
    // was incidental and insertion order is what every existing pin observes;
    // with two `imu` rows on one set the archive's file order and its `files`
    // array would be unspecified, and a reader diffing two exports of the same
    // session could see the two streams swap places for no reason. Insertion
    // order is exactly what the rowid gives back, so this guarantees the order
    // that was already being relied on rather than choosing a new one. A new
    // ORDER BY changes no table, so DATABASE_VERSION does not move for it.
    @Query("SELECT * FROM raw_streams WHERE setId = :setId ORDER BY id")
    suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity>

    @Query("UPDATE set_records SET rpe = :rpe, failed = :failed, warmup = :warmup WHERE id = :setId")
    suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean)

    // Why the set ended, and the lifter's own words where the answer is
    // `other` (#189). Written as its own statement rather than folded into
    // [updateRpe]: the reason is asked on a page AFTER the set is stored and
    // is corrected on the rest screen independently of the effort, and one
    // query writing both would make every effort correction overwrite a reason
    // the caller did not mean to touch. Both columns move together because the
    // note belongs to the answer -- changing the answer away from `other` must
    // clear the words that went with it, or a coach reads "grip gave out"
    // under a note about a photo shoot. A new @Query changes no table, so
    // DATABASE_VERSION does not move for this one; the columns it writes
    // arrived at v13.
    @Query("UPDATE set_records SET limiter = :limiter, limiterNote = :limiterNote WHERE id = :setId")
    suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?)

    @Query("UPDATE set_records SET actualReps = :reps, repsManual = 1 WHERE id = :setId")
    suspend fun overrideReps(setId: Long, reps: Int)

    // No "corrected" flag beside it: reps have repsManual, seconds have no such
    // column, and adding one is a migration this change does not make. A new
    // @Query changes no table, so DATABASE_VERSION does not move for this.
    @Query("UPDATE set_records SET actualDurationS = :seconds WHERE id = :setId")
    suspend fun overrideDuration(setId: Long, seconds: Int)

    @Query("SELECT * FROM sessions WHERE startedAtMs >= :fromMs AND startedAtMs <= :toMs ORDER BY startedAtMs")
    suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}

@Dao
interface ExerciseDao {
    @Insert
    suspend fun insert(exercise: CustomExerciseEntity)

    @Query("SELECT * FROM custom_exercises")
    fun observeAll(): Flow<List<CustomExerciseEntity>>

    @Query("SELECT * FROM custom_exercises")
    suspend fun all(): List<CustomExerciseEntity>

    @Query("SELECT * FROM custom_exercises WHERE id = :id")
    suspend fun byId(id: String): CustomExerciseEntity?
}
