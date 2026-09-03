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

    // `failedByLifter` moves with `failed` in ONE statement and never in a
    // second one (#216). The two are one decision -- SetRatingTracker ORs the
    // lifter's tap with the derived shortfall and this writes both the answer
    // and its author -- and the rest screen changes them together: re-tapping
    // the effort restates the author, and correcting a miscounted rep total
    // re-derives the shortfall under the same author. Split across two
    // queries, a crash between them leaves a row saying the lifter called a
    // failure that is no longer recorded, or the reverse.
    //
    // This is deliberately NOT [updateWarmupMark]'s shape, and the difference
    // is the point: `warmup` and `warmupMark` are the PLAN'S word and the
    // LIFTER'S word, two facts about different authors that must survive each
    // other, so they are written apart. `failed` and `failedByLifter` are one
    // fact and its provenance.
    @Query(
        "UPDATE set_records SET rpe = :rpe, failed = :failed, " +
            "failedByLifter = :failedByLifter, warmup = :warmup WHERE id = :setId",
    )
    suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, failedByLifter: Boolean?, warmup: Boolean)

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

    // The LIFTER'S own statement about whether the set was preparatory (#194).
    // Its own statement rather than a fourth argument of [updateRpe], which
    // writes the PLAN'S declaration in the `warmup` column beside it: one
    // query writing both would let a rest-screen effort correction carry a
    // mark the lifter never made, and would put the two facts #194 keeps
    // apart back into one write. A new @Query changes no table, so
    // DATABASE_VERSION does not move for it; the column arrived at v13.
    @Query("UPDATE set_records SET warmupMark = :warmupMark WHERE id = :setId")
    suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?)

    // The lifter's statement that they did not perform this set, and their
    // words for why (#60). Its own statement rather than a fifth argument of
    // [updateRpe]: an effort correction on the rest screen must not carry a
    // mark the lifter never made, and the mark is applied from a different
    // screen at a different time -- after the session has ended, from session
    // detail.
    //
    // BOTH COLUMNS MOVE TOGETHER, for the reason [updateLimiter] gives for the
    // pair it writes: the reason belongs to the mark, so un-voiding must clear
    // the words that went with it, or the export publishes "app fabricated
    // this set" on a set the lifter says they DID perform. The caller passes
    // null for the reason when it passes false for the mark; the query does
    // not enforce that, and [SessionRepository.setVoided] is where it is
    // enforced, once, in code a test can reach.
    //
    // The columns arrived at v16; this @Query changes no table, so
    // DATABASE_VERSION does not move for the query itself.
    @Query("UPDATE set_records SET voided = :voided, voidReason = :reason WHERE id = :setId")
    suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?)

    @Query("UPDATE set_records SET actualReps = :reps, repsManual = 1 WHERE id = :setId")
    suspend fun overrideReps(setId: Long, reps: Int)

    // The load the lifter states for the set just finished (#205). :loadKg is
    // the body-weight-INCLUSIVE total, the scale the column is already on, and
    // plannedLoadKg is deliberately not in the SET list: the plan's
    // prescription is what the correction is a deviation FROM, and rewriting
    // it would erase the deviation the row exists to record.
    //
    // No "corrected" flag beside it, and unlike the seconds one above that is
    // a decision rather than a gap. repsManual distinguishes a count the
    // sensor MEASURED from one the lifter STATED; a load is never measured by
    // anything, so the same flag on this column would be true of every row
    // ever written and would carry no information. A corrected load is
    // therefore indistinguishable in the export from a load typed before the
    // set -- correctly, because they are the same fact.
    //
    // A new @Query changes no table, so DATABASE_VERSION does not move for it.
    @Query("UPDATE set_records SET loadKg = :loadKg WHERE id = :setId")
    suspend fun overrideLoad(setId: Long, loadKg: Double)

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
