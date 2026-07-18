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

    @Query("SELECT * FROM raw_streams WHERE setId = :setId")
    suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity>

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
