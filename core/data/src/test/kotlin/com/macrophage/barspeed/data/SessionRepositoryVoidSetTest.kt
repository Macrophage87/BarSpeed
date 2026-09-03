package com.macrophage.barspeed.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [SessionRepository.setVoided] actually writes to the row (#60).
 *
 * TWO RULES LIVED IN PROSE AND IN NOTHING ELSE, which is what this file is for.
 * `Daos.kt` says of `updateVoided` that the query "does not enforce that, and
 * [SessionRepository.setVoided] is where it is enforced, once, in code a test
 * can reach", and `setVoided`'s own KDoc says the reason is normalized and
 * cleared there. Round 1 of the review found that no test in this module or in
 * `:app` called `setVoided` at all, so both sentences were claims about code
 * nothing exercised. They are pinned here.
 *
 * The two rules, and the consequence of each being wrong:
 *
 * - NORMALIZED ON THE WAY IN. The reason reaches the raw archive's `meta.json`,
 *   which is assembled as text by a writer that escapes nothing, so a double
 *   quote, a backslash or a newline in it does not corrupt one reason -- it
 *   makes the whole manifest unparseable for every set in the session. The rule
 *   is [com.macrophage.barspeed.model.VoidSetPolicy.reason], which is
 *   [com.macrophage.barspeed.model.SetLimiter.normalizeNote] and not a second
 *   spelling of it.
 * - CLEARED ON THE WAY OUT. A reason surviving an un-void would publish "the
 *   app fabricated this set" beside a set the lifter has just said they DID
 *   perform. The DAO writes whatever it is handed, so the clearing has to
 *   happen above it.
 *
 * Nothing here executes Room, SQLite or Android. `SessionDao` is an interface
 * and the fake below stands in for it, so what is verified is the repository's
 * own mapping and call shape -- never what the database did with it.
 */
class SessionRepositoryVoidSetTest {
    /** Records every void write in the order it is handed over. */
    private class RecordingDao : SessionDao {
        val voidWrites = mutableListOf<Triple<Long, Boolean, String?>>()

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) {
            voidWrites += Triple(setId, voided, reason)
        }

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? = null

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = emptyList()

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(emptyList())

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = emptyList()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class StubExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private fun repo(dao: SessionDao) = SessionRepository(dao, StubExerciseDao())

    /**
     * The mark and the row it is written against reach the DAO unchanged.
     *
     * The plainest thing that can be wrong about a two-argument write is that
     * the arguments were crossed, and nothing else in this file would catch a
     * set id taken from somewhere other than the caller.
     */
    @Test
    fun `voiding writes the mark against the set the caller named`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 4321L, voided = true, reason = "app re-armed a finished slot")
        assertEquals(1, dao.voidWrites.size, "setVoided did not reach the DAO exactly once")
        val (setId, voided, _) = dao.voidWrites.single()
        assertEquals(4321L, setId, "the mark was written against a different row")
        assertEquals(true, voided, "the mark was written as false on a void")
    }

    /**
     * A reason carrying the characters the manifest cannot survive is stored
     * without them.
     *
     * The backslash and the double quote are dropped; a newline and a tab each
     * become a space; runs of spaces collapse; the ends are trimmed. That is
     * [com.macrophage.barspeed.model.SetLimiter]'s rule, asserted here as an
     * exact string rather than as "contains no backslash", so a normalization
     * that dropped the wrong characters is caught too.
     */
    @Test
    fun `the reason is normalized before it is stored`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 1L, voided = true, reason = RAW_REASON)
        assertEquals("app re-armed a finished slot", dao.voidWrites.single().third)
    }

    /**
     * A reason that is nothing but whitespace is no reason.
     *
     * Blank has to come back as null rather than as an empty string, because an
     * empty string in the column publishes a `voidReason` key saying nothing,
     * and the export's own contract is that the key is absent when there is no
     * reason.
     */
    @Test
    fun `a blank reason is stored as no reason`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 1L, voided = true, reason = BLANK_REASON)
        assertNull(dao.voidWrites.single().third, "a blank reason was stored as a reason")
    }

    /** Voiding without a reason is the ordinary case and stores null. */
    @Test
    fun `voiding with no reason at all stores null`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 1L, voided = true)
        assertEquals(Triple(1L, true, null), dao.voidWrites.single())
    }

    /**
     * Un-voiding clears the reason EVEN WHEN ONE IS PASSED.
     *
     * The screen has no reason to pass one, and that is exactly why this is
     * pinned above the caller: the rule holds for a caller that does. A reason
     * left on the row would be published beside a set the lifter has just said
     * they performed.
     */
    @Test
    fun `un-voiding clears the reason even when one is handed in`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 9L, voided = false, reason = "app re-armed a finished slot")
        assertEquals(Triple(9L, false, null), dao.voidWrites.single())
    }

    /**
     * The cap is the limiter note's cap and is applied at the write.
     *
     * 120 characters, [com.macrophage.barspeed.model.SetLimiter.NOTE_MAX_CHARS],
     * and the delegation is the point: a second spelling of the rule here would
     * drift from the one the manifest writer's other note already obeys.
     */
    @Test
    fun `an over-long reason is capped at the limiter note's length`() = runTest {
        val dao = RecordingDao()
        repo(dao).setVoided(setId = 1L, voided = true, reason = "x".repeat(200))
        assertEquals("x".repeat(120), dao.voidWrites.single().third)
    }

    private companion object {
        /**
         * A reason as a lifter could type it, carrying every character the rule
         * removes: leading and trailing spaces, a backslash, two double quotes,
         * a newline, a tab and a double space.
         */
        const val RAW_REASON = "  app \\ \"re-armed\"\n\ta finished  slot  "

        /** Whitespace only: two spaces, a newline, a tab, two more spaces. */
        const val BLANK_REASON = "  \n\t  "
    }
}
