package com.macrophage.barspeed.data

import com.macrophage.barspeed.model.SkippedSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [SessionRepository.endSession] writes into the session's skip list
 * (#300).
 *
 * DIFFERENTIALS. The two tests that read the list back off the written row fail
 * at the commit that introduces them: `endSession` takes the argument and does
 * not write it. The commit after this one is what makes them pass. The two that
 * do NOT fail here are named at the tests themselves.
 *
 * WHERE THE WRITE IS, and what that costs. The list rides to the close beside
 * `sessionRpe` and `hrvRmssdMs` and carries their limit: a session the process
 * does not survive records no skips. It is not a row that is missing at the tap
 * -- `SkipSetControl.target` refuses the opening set of a block, so a set has
 * been recorded and a `sessions` row exists before any skip is offered. Nothing
 * in this file executes that path either way; the exposure is stated in the
 * commit body and at [SessionEntity.skippedSetsJson].
 *
 * A separate file from [SessionRepositoryEndSessionTest], whose subject is the
 * four columns that function has always written and whose fake is shaped for
 * heart-rate arithmetic. This one keeps its own, as every test file in this
 * module does.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * this fake stands in for it: what is verified is the entity the repository hands
 * back, never what the database did with it.
 */
class SessionRepositorySkippedSetTest {
    private class FakeSessionDao(seed: SessionEntity) : SessionDao {
        val sessions = mutableMapOf(seed.id to seed)
        val updates = mutableListOf<SessionEntity>()

        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) {
            updates += session
            sessions[session.id] = session
        }

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 0L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(sessions.values.toList())

        override suspend fun sessionById(id: Long): SessionEntity? = sessions[id]

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(sessions[id])

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

        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int, endedBy: String?) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun open(endedAtMs: Long? = null, skippedSetsJson: String? = null) = SessionEntity(
        id = 1L,
        startedAtMs = 1_000L,
        endedAtMs = endedAtMs,
        planName = "Block A",
        planSessionName = "Lower 1",
        skippedSetsJson = skippedSetsJson,
    )

    private fun decoded(row: SessionEntity): List<SkippedSet>? =
        row.skippedSetsJson?.let { json.decodeFromString(ListSerializer(SkippedSet.serializer()), it) }

    /**
     * The close writes the skips it was handed. DIFFERENTIAL: fails at this
     * commit, where the argument is taken and dropped.
     *
     * Order is asserted with the contents. The export publishes the list as it
     * is stored and a reader takes the entries in order, which is the only thing
     * that separates two blocks of one movement in a document that groups sets by
     * exercise ID.
     */
    @Test
    fun `the close writes the skips the session made`() = runTest {
        val dao = FakeSessionDao(open())
        val repository = SessionRepository(dao, FakeExerciseDao())

        repository.endSession(
            sessionId = 1L,
            endedAtMs = 60_000L,
            skippedSets = listOf(SkippedSet("back_squat", 3), SkippedSet("seated_row", 2)),
        )

        val written = assertNotNull(dao.updates.singleOrNull(), "the close did not write the row once: ${dao.updates}")
        assertEquals(
            listOf(SkippedSet("back_squat", 3), SkippedSet("seated_row", 2)),
            decoded(written),
            "the stored skip list is not the one the close was handed",
        )
    }

    /**
     * What is stored is the `:core:model` type's own wire shape. DIFFERENTIAL.
     *
     * ONE TYPE, TWO WRITERS: the column and `session.json` both carry
     * [SkippedSet], so the exporter can hand the decoded list straight to the
     * document and no second shape exists to drift. A hand-rolled encoding here
     * -- a joined string, an object keyed by exercise -- would compile, pass the
     * test above if it round-tripped, and force the exporter to translate.
     */
    @Test
    fun `the stored list is the model type's own encoding`() = runTest {
        val dao = FakeSessionDao(open())
        val repository = SessionRepository(dao, FakeExerciseDao())

        repository.endSession(sessionId = 1L, endedAtMs = 60_000L, skippedSets = listOf(SkippedSet("back_squat", 4)))

        assertEquals(
            json.encodeToString(ListSerializer(SkippedSet.serializer()), listOf(SkippedSet("back_squat", 4))),
            assertNotNull(dao.updates.singleOrNull()).skippedSetsJson,
            "the column does not carry the type the export publishes",
        )
    }

    /**
     * A session that skipped nothing stores null, not an empty array.
     *
     * NOT a differential: it passes at this commit because nothing writes the
     * column at all, and it starts meaning something one commit later. It is here
     * because null and `[]` are the two states the column must keep apart --
     * [SessionEntity.skippedSetsJson] says null covers both "skipped nothing" and
     * "recorded before v19", while an empty array published on every session
     * would claim each one was checked.
     */
    @Test
    fun `a session that skipped nothing stores null rather than an empty list`() = runTest {
        val dao = FakeSessionDao(open())
        val repository = SessionRepository(dao, FakeExerciseDao())

        repository.endSession(sessionId = 1L, endedAtMs = 60_000L)

        assertNull(
            assertNotNull(dao.updates.singleOrNull()).skippedSetsJson,
            "an empty skip list was stored as a value",
        )
    }

    /**
     * A second close cannot erase a stored skip list.
     *
     * NOT a differential: the guard it relies on is `endSession`'s existing
     * early return on a row that already carries an end time, and that guard is
     * why the list may ride with the close at all. The rest screen's Finish
     * control is an undebounced `TextButton`, and the list lives only in `:app`'s
     * heap, so a second call arriving with an empty default would otherwise wipe
     * every skip the lifter made -- the failure `hrvRmssdMs` and `sessionRpe`
     * already have this guard for.
     */
    @Test
    fun `a second close leaves a stored skip list alone`() = runTest {
        val stored = json.encodeToString(ListSerializer(SkippedSet.serializer()), listOf(SkippedSet("back_squat", 4)))
        val dao = FakeSessionDao(open(endedAtMs = 60_000L, skippedSetsJson = stored))
        val repository = SessionRepository(dao, FakeExerciseDao())

        repository.endSession(sessionId = 1L, endedAtMs = 90_000L, skippedSets = emptyList())

        assertEquals(emptyList(), dao.updates, "a closed session was written again")
        assertEquals(
            listOf(SkippedSet("back_squat", 4)),
            decoded(assertNotNull(dao.sessions[1L])),
            "the stored skip list changed",
        )
    }
}
