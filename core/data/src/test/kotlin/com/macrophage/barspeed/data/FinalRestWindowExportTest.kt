package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.model.HrSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the raw archive does with a rest window recorded AFTER a set, issue
 * #109.
 *
 * Characterization only. Nothing here asks for the write path that would
 * produce such a stream; it asks what the exporter already does when one is
 * handed to it, because the answer decides how much of #109 is an export
 * change. It is none of it: the zip loop writes every stream as
 * `set%02d_<exercise>_<kind>.csv` and the kind alone names the file, so a
 * stream of a kind that did not exist when the loop was written is carried
 * without the loop being touched.
 *
 * The fixture is field-36's shape and not an invented one: fourteen sets, an
 * `hrm` stream on each, a `rest_before_hrm` stream on the thirteen sets that
 * follow a rest window, and a session row carrying the summary that session
 * published -- avg 107, max 137, RMSSD 13.1 ms. What field-36 reports as
 * missing is a fifteenth heart-rate file after set 14, and
 * [a set with no rest_after stream produces no rest_after file] pins the
 * absence before this branch's write landed so the differential that closes
 * it has something to move.
 *
 * Nothing here executes Room, SQLite or Android. The DAO is an interface and
 * the fakes below stand in for it; what is verified is the exporter's own
 * naming and selection and nothing about what the database did with it.
 */
class FinalRestWindowExportTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>>,
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> = rows

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

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

    /** field-36's count. Fourteen sets, and the rest window after the last is the one at issue. */
    private val setCount = 14

    private fun row(idx: Int) = SetRecordEntity(
        id = (idx + 1).toLong(),
        sessionId = 1L,
        orderIdx = idx,
        exerciseId = "bench_press",
        exerciseName = "bench_press",
        loadKg = 60.0,
        actualReps = 5,
        // The set's own heart rate, frozen at the write. Distinct values so a
        // figure taken from the wrong set is visible rather than plausible.
        hrEndOfSetBpm = 120 + idx,
        hrAvgBpm = 100 + idx,
        hrMaxBpm = 130 + idx,
        startedAtMs = 1_000L + idx * 200_000L,
        endedAtMs = 46_000L + idx * 200_000L,
        analysisJson =
        json.encodeToString(SetAnalysis.serializer(), SetAnalysis(emptyList(), 0.0, null, null, emptyList())),
    )

    private fun hrStream(setId: Long, bpm: Int) = RawStreamEntity(
        id = setId * 10,
        setId = setId,
        kind = RawStreamEntity.KIND_HRM,
        csvGzip = Gzip.compress(HrCsv.encode(listOf(HrSample(1_000L, bpm, listOf(60_000.0 / bpm))))),
    )

    private fun restStream(setId: Long, kind: String, samples: List<HrSample>) =
        RawStreamEntity(id = setId * 10 + 1, setId = setId, kind = kind, csvGzip = Gzip.compress(HrCsv.encode(samples)))

    /** The resting shape #109 exists to keep: a slow, settling rate. */
    private val restingSamples =
        listOf(
            HrSample(1_000L, 96, listOf(625.0)),
            HrSample(2_000L, 88, listOf(682.0)),
            HrSample(3_000L, 81, listOf(741.0)),
        )

    /**
     * field-36's session: fourteen sets, an `hrm` on every one, a
     * `rest_before_hrm` on every set after the first, and [extra] appended to
     * whichever set it names.
     */
    private suspend fun archive(extra: Map<Long, List<RawStreamEntity>> = emptyMap()): Map<String, String> {
        val rows = (0 until setCount).map(::row)
        val streams =
            rows.associate { r ->
                r.id to
                    buildList {
                        add(hrStream(r.id, 130 + r.orderIdx))
                        if (r.orderIdx > 0) {
                            add(restStream(r.id, RawStreamEntity.KIND_REST_BEFORE_HRM, restingSamples))
                        }
                        // [extra] LAST, and that is load-bearing rather than
                        // arbitrary. The archive's minBpm is not the
                        // `firstOrNull` selector [SessionExporterTest] pins --
                        // RawExporter computes it inside the zip loop with a
                        // `when (stream.kind)` and hands it to the session
                        // export as an override, so the LAST matching stream
                        // wins. A trailing stream placed first would be
                        // overwritten by the set's own hrm stream and the pin
                        // below would pass while asserting nothing. Measured:
                        // with [extra] first, widening that branch to admit
                        // KIND_REST_AFTER_HRM was killed by no pin here.
                        addAll(extra[r.id].orEmpty())
                    }
            }
        val dao =
            FakeSessionDao(
                session =
                SessionEntity(
                    id = 1L,
                    startedAtMs = 1_000L,
                    endedAtMs = 3_000_000L,
                    hrAvgBpm = 107,
                    hrMaxBpm = 137,
                    hrvRmssdMs = 13.1,
                ),
                rows = rows,
                streams = streams,
            )
        val repo = SessionRepository(dao, FakeExerciseDao())
        val bytes = RawExporter(repo, SessionExporter(repo), appVersion = "0.1.48").buildZip(1L)!!
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes().decodeToString()
            }
        }
        return entries
    }

    private fun filesOfLastSet(meta: String): List<String> = Json.parseToJsonElement(meta)
        .jsonObject.getValue("sets").jsonArray.last()
        .jsonObject.getValue("files").jsonArray.map { it.jsonPrimitive.content }

    // ---- pins --------------------------------------------------------------

    /**
     * What the exporter writes for a set carrying no trailing window.
     *
     * The fixture supplies no rest_after_hrm stream, so this pins that the
     * exporter adds none -- not that a real session has none. Thirteen
     * `rest_before_hrm` files, one per set after the first, and nothing
     * covering the window after set 14 in this fixture. Thirteen rather than
     * fourteen here because the fixture stores no stream for the READY window
     * before set 1, which the app does write and which this test does not
     * need.
     */
    @Test
    fun `a set with no rest_after stream produces no rest_after file`() = runTest {
        val entries = archive()
        assertEquals(
            13,
            entries.keys.count { it.endsWith("_rest_before_hrm.csv") },
            "a rest_before file per set after the first is what #90 already writes",
        )
        assertTrue(
            entries.keys.none { it.endsWith("_rest_after_hrm.csv") },
            "the exporter invented a rest_after file for a set that has no such stream",
        )
    }

    /**
     * And the exporter needs no change to carry one.
     *
     * The kind alone names the file, so a stream whose kind nothing in the
     * exporter has heard of is written out under its own name and listed in
     * that set's `files`. This is the whole of what makes #109 a write-path
     * change: if this pin reds, the zip loop has stopped being kind-driven and
     * a new kind would need exporter work as well.
     */
    @Test
    fun `a stream of a kind the exporter does not know is named by its kind`() = runTest {
        val entries = archive(mapOf(14L to listOf(restStream(14L, "rest_after_hrm", restingSamples))))
        val name = "set14_bench_press_rest_after_hrm.csv"
        assertTrue(name in entries.keys, "the trailing window was not written into the archive")
        assertEquals(restingSamples, HrCsv.decode(entries.getValue(name)), "the samples did not survive the round trip")
        assertTrue(name in filesOfLastSet(entries.getValue("meta.json")), "the manifest did not list it")
    }

    /**
     * By kind and not by position.
     *
     * The trailing window sits in the same stream list as the set's own `hrm`,
     * and `Exporters.minBpm` selects with `firstOrNull { it.kind == KIND_HRM }`.
     * `"rest_after_hrm"` ends in `"hrm"`, so a selection written as a suffix or
     * a `contains` match would take the resting minimum of 81 and publish it
     * under set 14's name. Which POSITION makes that detectable depends on the
     * selector, and here it is last rather than first; the fixture above
     * carries the measurement and the reason.
     */
    @Test
    fun `a rest_after stream cannot reach the last set's published minimum`() = runTest {
        val entries = archive(mapOf(14L to listOf(restStream(14L, "rest_after_hrm", restingSamples))))
        val hr =
            Json.parseToJsonElement(entries.getValue("session.json"))
                .jsonObject.getValue("exercises").jsonArray.single()
                .jsonObject.getValue("sets").jsonArray.last()
                .jsonObject.getValue("hr").jsonObject
        assertEquals(143, hr.getValue("minBpm").jsonPrimitive.content.toInt(), "the trailing window reached minBpm")
        assertEquals(113, hr.getValue("avgBpm").jsonPrimitive.content.toInt(), "the set's own frozen average moved")
    }
}
