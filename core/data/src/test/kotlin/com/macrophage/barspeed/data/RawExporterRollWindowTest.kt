package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.ImuCsv
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.PrepWindow
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What interval the archive's `rollExcursion_deg` covers, and whether it can
 * still express a sweep past a full turn. Issue #133.
 *
 * The figure decides whether an outside analysis trusts a set's kinematics at
 * all, and it was `max(roll) - min(roll)` over every row in the capture file.
 * `roll_deg` is bounded to (-180, 180], so the range saturates -- field-36's
 * sets 1 and 5 published 358.6 and 360.0 for sweeps of 909.0 and 515.2 -- and
 * the file is not the set: it opens at the lifter's tap and, on a guided set,
 * runs on while the load is racked. field-37 sets 3 and 4 published 92.9 and
 * 86.7 for working windows of 54.0 and 63.7 with NO samples after their
 * terminal cue at all, so on those two the whole excess was the prep.
 *
 * `RollExcursion` in `:core:dsp` holds the arithmetic and is pinned against
 * those four captures in `RollExcursionFieldTest`. What is asked here is only
 * whether the exported document uses it -- the near neighbour being that the
 * set-level key moves and the per-sensor `sensors[]` entries, computed by a
 * different function two hundred lines away, keep the old figure.
 *
 * A separate file from [RawExporterTest], which is already past 1,100 lines,
 * on the same grounds [RawExporterPrepWindowTest] is separate.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * the fakes below stand in for it; what is verified is the exporter's own
 * mapping and nothing about what the database did with it. No sensor produced
 * any sample in this file -- every roll value is written down here.
 */
class RawExporterRollWindowTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())

        override suspend fun sessionById(id: Long): SessionEntity? =
            SessionEntity(id = 1L, startedAtMs = 1_000L, endedAtMs = 61_000L)

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(null)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

        override suspend fun updateRpe(
            setId: Long,
            rpe: Int?,
            failed: Boolean,
            failedByLifter: Boolean?,
            warmup: Boolean,
        ) = Unit

        // Conformance only: SessionDao grew this member on main for #60 and
        // Kotlin requires every implementation to carry it. Nothing here
        // calls it, and a voided set is not what this file is about.
        override suspend fun updateVoided(setId: Long, voided: Boolean, reason: String?) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { encodeDefaults = true }

    private fun row() = SetRecordEntity(
        id = 5L,
        sessionId = 1L,
        orderIdx = 0,
        exerciseId = "back_squat",
        exerciseName = "back_squat",
        loadKg = 100.0,
        actualReps = 5,
        startedAtMs = 1_000L,
        endedAtMs = 61_000L,
        analysisJson =
        json.encodeToString(SetAnalysis.serializer(), SetAnalysis(emptyList(), 0.0, null, null, emptyList())),
    )

    /** One sample per 10 ms from [startMs], carrying [rolls] in order. */
    private fun samples(startMs: Long, rolls: List<Double>): List<ImuSample> = rolls.mapIndexed { index, roll ->
        ImuSample(startMs + index * 10L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, roll, 0.0, 0.0)
    }

    /** A sweep of [count] samples from [from] to [to] inclusive. */
    private fun sweep(from: Double, to: Double, count: Int): List<Double> =
        (0 until count).map { from + (to - from) * it / (count - 1) }

    /** [turns] whole turns of the mount, as the bounded signal reports them. */
    private fun turning(turns: Int, stepDeg: Double = 30.0): List<Double> =
        (0..(turns * 360 / stepDeg.toInt())).map { step -> ((step * stepDeg + 180.0) % 360.0) - 180.0 }

    private fun imuStream(samples: List<ImuSample>, role: String? = null, id: Long = 1L) = RawStreamEntity(
        id = id,
        setId = 5L,
        kind = RawStreamEntity.KIND_IMU,
        csvGzip = Gzip.compress(ImuCsv.encode(samples)),
        sampleRateHz = 100.0,
        role = role,
    )

    private fun cueStream(vararg cues: Pair<Long, String>) = RawStreamEntity(
        id = 7L,
        setId = 5L,
        kind = RawStreamEntity.KIND_CUES,
        csvGzip = Gzip.compress(CueCsv.encode(cues.map { VoiceCue(it.first, it.second) })),
    )

    private fun prepStream(startedAtMs: Long, workStartedAtMs: Long) = RawStreamEntity(
        id = 9L,
        setId = 5L,
        kind = RawStreamEntity.KIND_PREP,
        csvGzip = Gzip.compress(PrepWindowCsv.encode(PrepWindow(startedAtMs, workStartedAtMs))),
    )

    private suspend fun meta(streams: List<RawStreamEntity>): JsonObject {
        val dao = FakeSessionDao(listOf(row()), mapOf(5L to streams))
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.50").buildZip(1L)!!
        var metaText: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val text = zin.readBytes().decodeToString()
                if (entry.name == "meta.json") metaText = text
            }
        }
        return Json.parseToJsonElement(metaText!!).jsonObject
            .getValue("sets").jsonArray[0].jsonObject
    }

    private fun JsonObject.num(key: String): Double? = get(key)?.jsonPrimitive?.content?.toDouble()

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun JsonObject.sensor(role: String): JsonObject =
        getValue("sensors").jsonArray.map { it.jsonObject }.single { it.text("role") == role }

    // ---- the range must stop saturating ------------------------------------

    /**
     * Three whole turns are three whole turns, not "at least one".
     *
     * The bounded signal's max-minus-min over this capture is 330, which is
     * indistinguishable from a mount that rocked back and forth through 330 and
     * never turned at all.
     */
    @Test
    fun `a mount that turns three times reports three turns`() = runTest {
        val set = meta(listOf(imuStream(samples(1_000L, turning(turns = 3)))))
        assertEquals(1080.0, set.num("rollExcursion_deg"))
    }

    @Test
    fun `a single crossing of the boundary is a small sweep, not a near-full rotation`() = runTest {
        val set = meta(listOf(imuStream(samples(1_000L, listOf(178.0, 179.0, -179.0, -178.0)))))
        assertEquals(4.0, set.num("rollExcursion_deg"))
    }

    // ---- the interval must be the set --------------------------------------

    /**
     * field-37 sets 3 and 4 in miniature: everything before `workStartedAt_ms`
     * is the lifter getting into position, and neither of those sets had a tail
     * at all.
     */
    @Test
    fun `the prep is not the set`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 95.0, 20) + sweep(0.0, 12.0, 20))),
                    prepStream(startedAtMs = 1_000L, workStartedAtMs = 1_200L),
                ),
            )
        assertEquals(12.0, set.num("rollExcursion_deg"), "the 95-degree prep swing was counted as the set")
    }

    /**
     * The tail issue #125 is about: after `Done` the sensor is put down,
     * unclipped or carried while the load is racked.
     */
    @Test
    fun `the tail after the terminal cue is not the set`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 20) + sweep(0.0, 140.0, 20))),
                    cueStream(1_190L to "Done"),
                ),
            )
        assertEquals(12.0, set.num("rollExcursion_deg"), "the re-rack was counted as the set")
    }

    /** `Set ended` bounds a set the lifter stopped early, exactly as `Done` does. */
    @Test
    fun `a set that ended early is bounded at its own terminal cue`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 20) + sweep(0.0, 140.0, 20))),
                    cueStream(1_190L to "Set ended"),
                ),
            )
        assertEquals(12.0, set.num("rollExcursion_deg"))
    }

    /**
     * A window holding fewer than two samples states nothing, rather than the
     * 0.0 that a range over one sample produces.
     *
     * A reader deciding whether to trust a set reads 0.0 as "it did not
     * rotate", which is the most reassuring answer available and is not what
     * happened.
     */
    @Test
    fun `a window holding one sample publishes no excursion`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 95.0, 20))),
                    prepStream(startedAtMs = 1_000L, workStartedAtMs = 1_190L),
                ),
            )
        assertNull(set["rollExcursion_deg"], "a one-sample window published a range anyway")
        assertNull(set["rollExcursionBasis"], "a basis was published for a figure that was not")
    }

    // ---- the document says which interval it used --------------------------

    @Test
    fun `a set with both bounds says it measured the working window`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 40))),
                    prepStream(startedAtMs = 1_000L, workStartedAtMs = 1_100L),
                    cueStream(1_300L to "Done"),
                ),
            )
        assertEquals("workingWindow", set.text("rollExcursionBasis"))
    }

    @Test
    fun `a set with no prep window and no cue says it measured the whole capture`() = runTest {
        val set = meta(listOf(imuStream(samples(1_000L, sweep(0.0, 12.0, 40)))))
        assertEquals("wholeCapture", set.text("rollExcursionBasis"))
    }

    @Test
    fun `a set with a prep window and no cue says it measured from the work start`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 40))),
                    prepStream(startedAtMs = 1_000L, workStartedAtMs = 1_100L),
                ),
            )
        assertEquals("fromWorkStart", set.text("rollExcursionBasis"))
    }

    @Test
    fun `a set with a cue and no prep window says it measured to the terminal cue`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 40))),
                    cueStream(1_300L to "Done"),
                ),
            )
        assertEquals("toTerminalCue", set.text("rollExcursionBasis"))
    }

    // ---- the near neighbour: the per-sensor entries ------------------------

    /**
     * The `sensors[]` entries are computed by a different function from the
     * set-level key, and field-36's 358.6 and 360.0 were published THERE.
     * Windowing one and not the other would leave the two figures in one
     * document disagreeing about what a set is.
     */
    @Test
    fun `each sensor entry is windowed and unwrapped too`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 95.0, 20) + turning(turns = 2)), role = "a", id = 1L),
                    imuStream(samples(1_000L, sweep(0.0, 95.0, 20) + sweep(0.0, 12.0, 20)), role = "b", id = 2L),
                    prepStream(startedAtMs = 1_000L, workStartedAtMs = 1_200L),
                ),
            )
        assertEquals(720.0, set.sensor("a").num("rollExcursion_deg"))
        assertEquals(12.0, set.sensor("b").num("rollExcursion_deg"))
        assertEquals("fromWorkStart", set.sensor("a").text("rollExcursionBasis"))
        assertEquals("fromWorkStart", set.sensor("b").text("rollExcursionBasis"))
    }

    // ---- what must not change ----------------------------------------------

    /**
     * A cue track carrying no TERMINAL cue bounds nothing at that end.
     *
     * This is an ad-hoc set with the voice off, or one recorded before the app
     * spoke a word at the ending; `SetEnd` declines to invent a boundary there,
     * and the last sample is when the lifter got round to tapping rather than
     * when the set ended. The figure then covers what remains bounded and the
     * basis says which end that was.
     */
    @Test
    fun `a cue track with no terminal cue bounds nothing at that end`() = runTest {
        val set =
            meta(
                listOf(
                    imuStream(samples(1_000L, sweep(0.0, 12.0, 40))),
                    cueStream(1_100L to "Down", 1_200L to "Up"),
                ),
            )
        assertEquals(12.0, set.num("rollExcursion_deg"))
        assertEquals("wholeCapture", set.text("rollExcursionBasis"))
    }

    @Test
    fun `a set with no imu stream still states no excursion and no basis`() = runTest {
        val set = meta(listOf(cueStream(1_300L to "Done")))
        assertNull(set["rollExcursion_deg"])
        assertTrue("rollExcursionBasis" !in set, "a basis was published with no figure to qualify")
    }
}
