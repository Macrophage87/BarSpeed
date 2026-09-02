package com.macrophage.barspeed.data

import com.macrophage.barspeed.dsp.PhaseComplianceResult
import com.macrophage.barspeed.dsp.RepAnalysis
import com.macrophage.barspeed.dsp.SetAnalysis
import com.macrophage.barspeed.dsp.TempoComplianceResult
import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.GeometrySources
import com.macrophage.barspeed.model.HrSample
import com.macrophage.barspeed.model.ResolvedGeometry
import com.macrophage.barspeed.model.SessionExport
import com.macrophage.barspeed.model.SetExport
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import com.macrophage.barspeed.model.VoiceCue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [SessionExporter] publishes for one set, and how the summary-only export
 * differs from the detailed one.
 *
 * The two exports are two different artifacts the lifter shares from the
 * session-detail screen -- `summary.json` and `detailed.json` -- and both are
 * read by a coach or a model that was not in the gym. Everything asserted here
 * is about which facts and which caveats reach that reader.
 *
 * The analyses are built by hand rather than by running [SetAnalyzer] over
 * samples. The exporter's job is mapping, not measuring: a fixture that went
 * through the DSP would couple these assertions to segmenter behaviour, and a
 * segmenter change would then red a test about JSON field placement.
 *
 * Nothing here executes Room, SQLite or Android. The DAOs are interfaces, so
 * the fake below stands in for them; what is verified is the exporter's own
 * mapping, and nothing about what the database did with it. Each test file in
 * this module carries its own fakes, as [SessionRepositoryRecordSetTest] and
 * [SessionRepositoryEndSessionTest] both do.
 */
/**
 * Two provenance fixtures, top-level rather than members for the reason
 * `serialKeysOf` is top-level in `SchemaContractTest`: [SessionExporterTest]
 * sits on detekt's LargeClass limit, and CI runs detekt before any test, so a
 * class that grows by three lines never gets as far as running one.
 *
 * `seededSources` reports DEFAULT for `sensorOnStack` because that fixture's
 * mount is false, and a seed default for it is only ever a true.
 */
private val declaredSources = GeometrySources(
    startsWith = GeometrySource.DECLARED,
    concentric = GeometrySource.DECLARED,
    plane = GeometrySource.DEFAULT,
    kind = GeometrySource.INFERRED,
    travelRatio = GeometrySource.DECLARED,
    sensorOnStack = GeometrySource.DECLARED,
)

private val seededSources = GeometrySources(
    startsWith = GeometrySource.SEEDED,
    concentric = GeometrySource.SEEDED,
    plane = GeometrySource.SEEDED,
    kind = GeometrySource.SEEDED,
    travelRatio = GeometrySource.SEEDED,
    sensorOnStack = GeometrySource.DEFAULT,
)

class SessionExporterTest {
    // ---- fakes -------------------------------------------------------------

    private class FakeSessionDao(
        private val session: SessionEntity,
        private val rows: List<SetRecordEntity>,
        private val streams: Map<Long, List<RawStreamEntity>> = emptyMap(),
    ) : SessionDao {
        override suspend fun insertSession(session: SessionEntity): Long = 1L

        override suspend fun updateSession(session: SessionEntity) = Unit

        override suspend fun insertSet(set: SetRecordEntity): Long = 1L

        override suspend fun insertRawStream(stream: RawStreamEntity): Long = 1L

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(listOf(session))

        override suspend fun sessionById(id: Long): SessionEntity? = session.takeIf { it.id == id }

        override fun observeSession(id: Long): Flow<SessionEntity?> = flowOf(session)

        override suspend fun setsForSession(sessionId: Long): List<SetRecordEntity> =
            rows.filter { it.sessionId == sessionId }

        override fun observeSetsForSession(sessionId: Long): Flow<List<SetRecordEntity>> = flowOf(rows)

        override suspend fun rawStreamsForSet(setId: Long): List<RawStreamEntity> = streams[setId].orEmpty()

        override suspend fun updateRpe(setId: Long, rpe: Int?, failed: Boolean, warmup: Boolean) = Unit

        override suspend fun updateLimiter(setId: Long, limiter: String?, limiterNote: String?) = Unit

        override suspend fun updateWarmupMark(setId: Long, warmupMark: Boolean?) = Unit

        override suspend fun overrideReps(setId: Long, reps: Int) = Unit

        // Conformance only: SessionDao grew this member for #205 and Kotlin
        // requires it. Nothing in this file calls it.
        override suspend fun overrideLoad(setId: Long, loadKg: Double) = Unit

        override suspend fun overrideDuration(setId: Long, seconds: Int) = Unit

        override suspend fun sessionsInRange(fromMs: Long, toMs: Long): List<SessionEntity> = emptyList()

        override suspend fun deleteSession(id: Long) = Unit
    }

    /**
     * Records whether anything ever asked it to dispatch, then hands the work
     * to a real dispatcher so the coroutine actually completes. See
     * [RawExporterTest]'s own copy for the reasoning; each test file here
     * carries its own fakes.
     */
    private class RecordingDispatcher(private val real: CoroutineDispatcher = Dispatchers.Default) :
        CoroutineDispatcher() {
        var dispatched = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            real.dispatch(context, block)
        }
    }

    private class FakeExerciseDao : ExerciseDao {
        override suspend fun insert(exercise: CustomExerciseEntity) = Unit

        override fun observeAll(): Flow<List<CustomExerciseEntity>> = flowOf(emptyList())

        override suspend fun all(): List<CustomExerciseEntity> = emptyList()

        override suspend fun byId(id: String): CustomExerciseEntity? = null
    }

    // ---- fixtures ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mean and peak drive velocity per rep, written as literals rather than
     * derived from each other.
     *
     * `peakConVel_mps` is the one summary field the exporter passes through
     * unrounded (`reps.maxOfOrNull { it.peakConVelMps }`), so a peak computed
     * as `mean + 0.3` arrives as 0.8999999999999999 and an assertion on 0.9
     * fails. That is the exporter behaving as written; the fixture was wrong.
     * Pinned as its own fact below.
     */
    private val repVelocities =
        listOf(0.60 to 0.90, 0.55 to 0.85, 0.50 to 0.80, 0.45 to 0.75, 0.40 to 0.70)

    private fun rep(index: Int) = RepAnalysis(
        index = index,
        eccS = 2.0,
        bottomPauseS = 0.3,
        conS = 1.0,
        topPauseS = 0.4,
        meanConVelMps = repVelocities[index].first,
        peakConVelMps = repVelocities[index].second,
        meanEccVelMps = -0.25,
        peakEccVelMps = -0.4,
        romM = 0.5,
        peakPowerW = 800.0,
        meanConPowerW = 500.0,
    )

    private val compliance =
        TempoComplianceResult(
            prescribed = Tempo.parse("2-0-1-0"),
            toleranceS = 0.5,
            phases =
            listOf(
                PhaseComplianceResult("eccentric", 2.0, 2.0, 0.1, 3, 3, scored = true),
                PhaseComplianceResult("bottomPause", 0.0, 0.3, 0.3, 0, 3, scored = false),
            ),
            repsFullyCompliant = 2,
            repsEvaluated = 3,
            prescribedEccConRatio = 2.0,
            actualEccConRatio = 2.1,
        )

    /** A set the sensor segmented into [segmentedReps] reps, with tempo and velocity loss. */
    private fun analysis(segmentedReps: Int) = SetAnalysis(
        reps = (0 until segmentedReps).map { rep(it) },
        sampleRateHz = 100.0,
        velocityLossPct = if (segmentedReps >= 2) 16.7 else null,
        tempoCompliance = if (segmentedReps >= 1) compliance else null,
        verdicts = listOf("Bar speed held up well."),
    )

    private fun row(analysis: SetAnalysis, actualReps: Int, repsManual: Boolean, geometryJson: String? = null) =
        SetRecordEntity(
            id = 5L,
            sessionId = 1L,
            orderIdx = 0,
            exerciseId = "back_squat",
            exerciseName = "Back Squat",
            loadKg = 100.0,
            actualReps = actualReps,
            repsManual = repsManual,
            plannedReps = 10,
            tempo = "2-0-1-0",
            startedAtMs = 1_000L,
            endedAtMs = 61_000L,
            analysisJson = json.encodeToString(SetAnalysis.serializer(), analysis),
            geometryJson = geometryJson,
        )

    /**
     * A plan-declared seated leg curl: the drive goes DOWN, off a cable stack
     * through a 2:1 pulley. Values chosen so no two fields share a value and
     * no field sits on its type default, so a mapping that crossed two of them
     * cannot pass.
     */
    private val legCurl =
        ResolvedGeometry(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            horizontal = false,
            sensorOnStack = true,
            sensorInverted = true,
            travelRatio = 2.0,
            kind = ExerciseKind.DYNAMIC,
            bodyweight = false,
            sources = declaredSources,
        )

    /** An ordinary barbell squat: every boolean false, which is a statement here. */
    private val squat =
        ResolvedGeometry(
            startsWith = StartPhase.ECCENTRIC,
            concentricUp = true,
            horizontal = false,
            sensorOnStack = false,
            sensorInverted = false,
            travelRatio = 1.0,
            kind = ExerciseKind.DYNAMIC,
            bodyweight = false,
            sources = seededSources,
        )

    /** [legCurl] in the form a Room row holds it. */
    private val storedLegCurl = json.encodeToString(ResolvedGeometry.serializer(), legCurl)

    private val cueStream =
        RawStreamEntity(
            id = 9L,
            setId = 5L,
            kind = RawStreamEntity.KIND_CUES,
            csvGzip = Gzip.compress(CueCsv.encode(listOf(VoiceCue(1_100L, "Rep 1")))),
        )

    /** A raw HRM stream whose trusted minimum -- 90 -- appears in no stored column any test here uses. */
    private fun hrStream(samples: List<HrSample>) = RawStreamEntity(
        id = 11L,
        setId = 5L,
        kind = RawStreamEntity.KIND_HRM,
        csvGzip = Gzip.compress(HrCsv.encode(samples)),
    )

    /**
     * A strap that has lost contact, in the shape the real capture takes:
     * three distinct intervals held across a long span, so a budget exists and
     * is far too small. Modelled on unworn set 3 of session 28, which scores
     * 0.171 against a cut of 0.35.
     */
    private fun lostContactStream(): List<HrSample> = List(20) { HrSample(it * 500L, 46, listOf(1_902.3)) } +
        List(20) { HrSample(10_000L + it * 500L, 46, listOf(1_607.4)) } +
        List(20) { HrSample(20_000L + it * 500L, 46, listOf(1_003.9)) }

    private fun exporterOf(
        stored: SetRecordEntity,
        zoneId: String? = null,
        utcOffsetMinutes: Int? = null,
        extraStreams: List<RawStreamEntity> = emptyList(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        sessionAvgBpm: Int? = null,
        sessionMaxBpm: Int? = null,
        sessionHrvRmssdMs: Double? = null,
    ): SessionExporter {
        val dao =
            FakeSessionDao(
                session =
                SessionEntity(
                    id = 1L,
                    startedAtMs = 1_000L,
                    endedAtMs = 61_000L,
                    zoneId = zoneId,
                    utcOffsetMinutes = utcOffsetMinutes,
                    hrAvgBpm = sessionAvgBpm,
                    hrMaxBpm = sessionMaxBpm,
                    hrvRmssdMs = sessionHrvRmssdMs,
                ),
                rows = listOf(stored),
                streams = mapOf(5L to listOf(cueStream) + extraStreams),
            )
        return SessionExporter(SessionRepository(dao, FakeExerciseDao()), dispatcher = dispatcher)
    }

    /** The export root for a session row carrying the given stored zone columns. */
    private suspend fun rootWithZone(zoneId: String?, utcOffsetMinutes: Int?): JsonObject {
        val exporter =
            exporterOf(row(analysis(3), actualReps = 3, repsManual = false), zoneId, utcOffsetMinutes)
        return Json.parseToJsonElement(exporter.exportJson(1L, includeRepDetail = true)!!).jsonObject
    }

    private fun exporter(analysis: SetAnalysis, actualReps: Int, repsManual: Boolean): SessionExporter =
        exporterOf(row(analysis, actualReps, repsManual))

    /** The single set of an export built over a row carrying [geometryJson]. */
    private suspend fun setWithGeometry(geometryJson: String?): SetExport =
        exporterOf(row(analysis(3), actualReps = 3, repsManual = false, geometryJson = geometryJson))
            .buildExport(1L, includeRepDetail = true)!!
            .exercises.single().sets.single()

    private suspend fun setOf(
        analysis: SetAnalysis,
        actualReps: Int,
        repsManual: Boolean,
        includeRepDetail: Boolean,
    ): SetExport = exporter(analysis, actualReps, repsManual)
        .buildExport(1L, includeRepDetail)!!
        .exercises.single().sets.single()

    /** The first exercise object of the export, as it lands on the wire. */
    private suspend fun exerciseObject(includeRepDetail: Boolean = true): JsonObject {
        val text = exporter(analysis(3), actualReps = 3, repsManual = false).exportJson(1L, includeRepDetail)!!
        return Json.parseToJsonElement(text).jsonObject.getValue("exercises").jsonArray.single().jsonObject
    }

    /** The first set object of the export, as it lands on the wire. */
    private suspend fun setObject(includeRepDetail: Boolean = true): JsonObject =
        exerciseObject(includeRepDetail).getValue("sets").jsonArray.single().jsonObject

    /** The whole export object, as it lands on the wire. */
    private suspend fun rootObject(includeRepDetail: Boolean = true): JsonObject {
        val text = exporter(analysis(3), actualReps = 3, repsManual = false).exportJson(1L, includeRepDetail)!!
        return Json.parseToJsonElement(text).jsonObject
    }

    // ---- issue 75: what the export says about WHEN the session happened -----

    /**
     * Both session-level instants are rendered UTC with a `Z` designator.
     *
     * Pinned as exact strings before anything moves, because the rendering is
     * not a choice the exporter makes and could be changed by accident.
     * `Instant.toString()` is `DateTimeFormatter.ISO_INSTANT`, which emits UTC
     * whatever the default zone is -- verified by running it under
     * `-Duser.timezone=America/New_York`, not by reading the javadoc. So the
     * `Z` is honest and these values are correct instants; what they do not
     * carry is the offset the device was on, which is issue 75.
     *
     * The fixture's 1000 ms and 61000 ms are epoch millis, so these are the
     * first minute of 1970 and nothing about that is arbitrary.
     */
    @Test
    fun `the session instants are rendered as UTC with a Z designator`() = runTest {
        val root = rootObject()
        assertEquals("1970-01-01T00:00:01Z", root.getValue("startedAt").jsonPrimitive.content)
        assertEquals("1970-01-01T00:01:01Z", root.getValue("endedAt").jsonPrimitive.content)
    }

    /**
     * The version on the wire is the version the model declares.
     *
     * `schemaVersion` is the only field carrying `@EncodeDefault(ALWAYS)`,
     * because an export without its version cannot be read by anything that has
     * to tell one version's field meanings from another's. This asserts the
     * mechanism still works end to end: the exporter never sets the field, so
     * it arrives entirely by that annotation, and losing the annotation would
     * drop the key from every export the app produces without failing anything
     * else.
     */
    @Test
    fun `the exported document declares the schema version the model does`() = runTest {
        assertEquals(
            SessionExport.SCHEMA_VERSION,
            rootObject().getValue("schemaVersion").jsonPrimitive.content,
        )
    }

    /**
     * The root object's keys, exactly.
     *
     * The same instrument as `the exercise object states only its id and its
     * sets`, one level up: an exact set, because the point is what is ABSENT.
     * A containment assertion would go on passing after a key was added, and
     * the root object is the published contract's top level -- a key appearing
     * there without `docs/schemas/session-export.schema.json` and its example
     * moving in the same commit is a contract break that ajv would catch only
     * because the schema sets `additionalProperties: false`.
     *
     * `notes` is absent from this fixture's session row and `explicitNulls =
     * false` drops it, so the absent-optional keys are part of what is pinned.
     */
    @Test
    fun `the export root states exactly the keys it states today`() = runTest {
        assertEquals(
            setOf("schemaVersion", "startedAt", "endedAt", "exercises"),
            rootObject().keys,
        )
    }

    /**
     * CHARACTERIZATION, before #177 adds anything. A set the lifter did not
     * append carries no `added` key on the wire.
     *
     * Pinned before the key exists because it must go on being true after.
     * `encodeDefaults = false` drops a `false`, so absence is what tells a
     * reader "the plan asked for this set" -- for every session already
     * exported as well as for every ordinary set recorded from now on. A field
     * added with `@EncodeDefault` instead would write `"added": false` onto
     * every set of every export and change what a byte-comparison of two
     * exports of the same session means.
     */
    @Test
    fun `an ordinary set says nothing about being appended`() = runTest {
        assertNull(setObject()["added"], "a set nobody appended publishes the appended flag anyway")
    }

    /**
     * A session recorded in New York in August publishes the zone it was
     * recorded in and the offset that applied.
     *
     * This is the whole of issue 75 in one assertion. Without it a reader has a
     * correct UTC instant and no way to turn it into a time of day, and the
     * downstream tool that consumes this export says so itself: it treats the
     * Z-suffixed clock as local with the designator stripped, and its own
     * data-quality section records that whether that clock is true UTC is not
     * established by anything it can see.
     *
     * Read as a nested object rather than as loose keys, for the reason issue
     * 73 settled one level down: an offset present with no zone is a half
     * answer a reader cannot complete, and nesting makes the absence atomic.
     */
    @Test
    fun `a session publishes the zone and offset it was recorded on`() = runTest {
        val zone = rootWithZone("America/New_York", -240).getValue("timeZone").jsonObject
        assertEquals("America/New_York", zone.getValue("id").jsonPrimitive.content)
        assertEquals(-240, zone.getValue("utcOffsetMinutes").jsonPrimitive.content.toInt())
    }

    /**
     * A zero offset is written, not dropped as a default.
     *
     * London in winter is on UTC. The exporter runs with
     * `encodeDefaults = false`, so this is the case that vanishes the moment
     * the offset field acquires a Kotlin default of 0 -- and it vanishes
     * silently, reading to a consumer as "the app did not say" for every
     * session recorded in the one zone whose offset happens to equal the type
     * default. The direct analogue of `a geometry false reaches the wire
     * instead of being dropped as a default`.
     */
    @Test
    fun `a zero offset reaches the wire instead of being dropped as a default`() = runTest {
        val text =
            exporterOf(row(analysis(3), actualReps = 3, repsManual = false), "Europe/London", 0)
                .exportJson(1L, includeRepDetail = true)!!
        assertTrue("\"utcOffsetMinutes\": 0" in text, "expected a written zero offset, got:\n$text")
    }

    /**
     * A row carrying only one of the two columns publishes no zone at all.
     *
     * Nothing writes such a row -- `startSession` writes the pair or neither --
     * but the columns are separately nullable because Room offers nothing else,
     * so the state exists in the database and the exporter has to have an
     * answer for it. Half an answer is worse than none: a reader given an
     * offset with no zone cannot tell whether the zone was withheld or the
     * offset invented.
     */
    @Test
    fun `a half-stated zone publishes nothing`() = runTest {
        assertNull(rootWithZone("America/New_York", null)["timeZone"])
        assertNull(rootWithZone(null, -240)["timeZone"])
    }

    // ---- issue 73: what the export says about how the set was measured -----

    /**
     * The exercise object states an id and a list of sets, and nothing else.
     *
     * Pinned as an exact key set rather than as a pair of `assertTrue`s
     * because the point is the absence, not the presence: an exercise object
     * that grew a third key would still pass a containment assertion. The
     * downstream tool that reads this export withheld four of its five tempo
     * charts over exactly this key set.
     */
    @Test
    fun `the exercise object states only its id and its sets`() = runTest {
        assertEquals(setOf("exercise", "sets"), exerciseObject().keys)
    }

    /**
     * The geometry is one nested key on the set, and never a scatter of loose
     * ones beside `reps` and `load_kg`.
     *
     * Asserted on parsed keys, not on the JSON text. "concentric" and
     * "eccentric" both occur as `scoredPhases` values, so a substring search
     * would report the geometry as present when only the verdict is.
     */
    @Test
    fun `the geometry is nested, never loose keys on the set`() = runTest {
        val loose =
            setOf(
                "startsWith",
                "concentric",
                "plane",
                "sensorOnStack",
                "sensorInverted",
                "travelRatio",
                "kind",
                "bodyweight",
            )
        assertEquals(emptySet(), setObject().keys intersect loose)
    }

    /**
     * A leg curl declared by the plan, as it reaches the reader.
     *
     * This is the case that withheld four of five tempo charts downstream. The
     * tempo digits are POSITIONAL -- digit 1 is the down stroke -- so on a lift
     * whose drive goes DOWN, digit 1 is the CONCENTRIC and a three-second
     * eccentric is written 1030, not 3010. Nothing in the raw signal can
     * settle that; two direction tests were built downstream and both failed.
     * Every value here is published in the plan's own vocabulary so a reader
     * holding both schemas reads them the same way.
     */
    @Test
    fun `a set states the direction and geometry it was measured with`() = runTest {
        val g = setWithGeometry(storedLegCurl).geometry
        assertNotNull(g, "the set published no geometry at all")
        assertEquals("concentric", g.startsWith)
        assertEquals("down", g.concentric)
        assertEquals("vertical", g.plane)
        assertEquals(true, g.sensorOnStack)
        assertEquals(true, g.sensorInverted)
        assertEquals(2.0, g.travelRatio)
        assertEquals("dynamic", g.kind)
        assertEquals(false, g.bodyweight)
    }

    /**
     * Where each value came from: a consumer treats a guess and a declaration
     * differently. Six carry a source; `sensorInverted` and `bodyweight`
     * cannot, being non-nullable booleans in the plan format.
     */
    @Test
    fun `the geometry says where each resolvable value came from`() = runTest {
        val g = setWithGeometry(storedLegCurl).geometry
        assertNotNull(g)
        assertEquals("declared", g.source.startsWith)
        assertEquals("declared", g.source.concentric)
        assertEquals("default", g.source.plane)
        assertEquals("inferred", g.source.kind)
        assertEquals("declared", g.source.travelRatio)
        assertEquals("declared", g.source.sensorOnStack)
    }

    /**
     * A false is written, not dropped.
     *
     * The exporter encodes with `encodeDefaults = false`, so a geometry field
     * carrying a Kotlin default of `false` would vanish from the wire and its
     * absence would read as "the app did not say" when it meant "the app said
     * no". That is the defect this object exists to remove, reintroduced by
     * one character at a declaration site, and only the wire form can catch it
     * -- a decoded object shows `false` either way.
     */
    @Test
    fun `a geometry false reaches the wire instead of being dropped as a default`() = runTest {
        val text =
            exporterOf(
                row(
                    analysis(3),
                    actualReps = 3,
                    repsManual = false,
                    geometryJson = json.encodeToString(ResolvedGeometry.serializer(), squat),
                ),
            ).exportJson(1L, includeRepDetail = false)!!
        assertTrue("\"sensorOnStack\": false" in text, "a stated false was dropped, got:\n$text")
        assertTrue("\"sensorInverted\": false" in text, "a stated false was dropped, got:\n$text")
        assertTrue("\"bodyweight\": false" in text, "a stated false was dropped, got:\n$text")
    }

    /**
     * How a set was measured is not a fact about how much detail was asked
     * for, so both artifacts say the same thing.
     *
     * Equality alone would be satisfied by both sides being absent, which is
     * exactly the state this is meant to move away from, so the stated case
     * asserts presence in both modes before comparing them and the unstated
     * case asserts absence in both.
     */
    @Test
    fun `the geometry is the same in both exports of one set`() = runTest {
        val stub = row(analysis(3), actualReps = 3, repsManual = false, geometryJson = storedLegCurl)
        val summary = exporterOf(stub).buildExport(1L, false)!!.exercises.single().sets.single()
        val detailed = exporterOf(stub).buildExport(1L, true)!!.exercises.single().sets.single()
        assertNotNull(summary.geometry, "the summary export withheld the geometry")
        assertNotNull(detailed.geometry, "the detailed export withheld the geometry")
        assertEquals(detailed.geometry, summary.geometry)

        val bare = row(analysis(3), actualReps = 3, repsManual = false, geometryJson = null)
        assertNull(exporterOf(bare).buildExport(1L, false)!!.exercises.single().sets.single().geometry)
        assertNull(exporterOf(bare).buildExport(1L, true)!!.exercises.single().sets.single().geometry)
    }

    /**
     * A row that carries no stored geometry publishes none — no key, not an
     * object full of plausible defaults.
     *
     * This is the permanent state of every set recorded before the app began
     * storing it, the whole of the field session that found this included. It
     * cannot be repaired: only exerciseId links a stored set back to a plan,
     * the plan may have been archived or re-imported since, and an ad-hoc set
     * had no plan at all — so a backfill would publish a declaration nobody
     * made.
     */
    @Test
    fun `a set with no stored geometry publishes no geometry`() = runTest {
        assertNull(setWithGeometry(null).geometry)
    }

    /**
     * A stored geometry that will not decode is the same answer: absent.
     *
     * Deliberately not distinguished from "never stored". Both mean the app
     * cannot say how this set was measured, and the alternative — a default
     * object standing in for a corrupt one — is the failure mode this whole
     * change exists to remove. The second case is the subtler one: valid JSON
     * that is missing required fields, which a lenient decoder would happily
     * fill in.
     */
    @Test
    fun `a stored geometry that will not decode publishes no geometry`() = runTest {
        assertNull(setWithGeometry("{ not json").geometry)
        assertNull(setWithGeometry("""{"startsWith":"ECCENTRIC"}""").geometry)
    }

    // ---- what the two modes differ by, and what they must not -------------

    /**
     * The only fields the export mode is allowed to decide.
     *
     * This is the pin the [SetExport.repMetricsComplete] change is measured
     * against: everything NOT listed here has to be identical in both
     * artifacts, because it describes the set rather than the level of detail
     * requested.
     */
    @Test
    fun `only the per-rep array and the voice cues depend on the export mode`() = runTest {
        val summary = setOf(analysis(3), actualReps = 3, repsManual = false, includeRepDetail = false)
        val detailed = setOf(analysis(3), actualReps = 3, repsManual = false, includeRepDetail = true)
        assertNull(summary.repMetrics)
        assertNull(summary.voiceCues)
        assertEquals(3, detailed.repMetrics?.size)
        assertEquals(listOf(VoiceCue(1_100L, "Rep 1")), detailed.voiceCues)
        // Everything the flag qualifies is mode-independent and must stay so.
        assertEquals(detailed.velocityLossPct, summary.velocityLossPct)
        assertEquals(detailed.tempoCompliance, summary.tempoCompliance)
        assertEquals(detailed.summary, summary.summary)
        assertEquals(detailed.reps, summary.reps)
        assertEquals(detailed.repsManual, summary.repsManual)
    }

    /**
     * The three figures the coverage flag warns about are published in BOTH
     * modes and all three come from the same segmenter rep list.
     *
     * Named individually rather than folded into the equality assertion above,
     * because "identical in both modes" would also be satisfied by all three
     * being absent in both. What matters is that they are PRESENT in the
     * summary-only export while being drawn from a rep list the reader cannot
     * see.
     */
    @Test
    fun `velocity loss, tempo compliance and the summary are published without per-rep detail`() = runTest {
        val summary = setOf(analysis(3), actualReps = 10, repsManual = true, includeRepDetail = false)
        assertEquals(16.7, summary.velocityLossPct)
        assertEquals("2010", summary.tempoCompliance?.prescribed)
        assertEquals(3, summary.tempoCompliance?.of)
        assertEquals(2, summary.tempoCompliance?.withinTolerance)
        assertEquals(listOf("eccentric"), summary.tempoCompliance?.scoredPhases)
        assertEquals(0.55, summary.summary.meanConVelMps)
        assertEquals(0.9, summary.summary.peakConVelMps)
        assertEquals(0.5, summary.summary.meanRomM)
        assertEquals(800.0, summary.summary.peakPowerW)
    }

    /**
     * Six of the seven summary figures are rounded on the way out and
     * `peakConVel_mps` is not.
     *
     * Pinned because it is not obvious from the field list and it cost this
     * file a red on its first run: an averaged figure lands on a clean decimal
     * only because `round3` puts it there, while the peak is whichever double
     * the DSP produced. A reader comparing `meanConVel_mps` and
     * `peakConVel_mps` is not looking at two figures of the same precision.
     */
    @Test
    fun `the summary rounds every figure it averages and passes the peak through raw`() = runTest {
        val raw = 0.123_456_789
        val one =
            SetAnalysis(
                reps = listOf(rep(0).copy(meanConVelMps = raw, peakConVelMps = raw, romM = raw, meanConPowerW = raw)),
                sampleRateHz = 100.0,
                velocityLossPct = null,
                tempoCompliance = null,
                verdicts = emptyList(),
            )
        val summary = setOf(one, actualReps = 1, repsManual = false, includeRepDetail = false).summary
        assertEquals(0.123, summary.meanConVelMps)
        assertEquals(0.123, summary.meanRomM)
        assertEquals(0.1, summary.meanConPowerW)
        assertEquals(raw, summary.peakConVelMps)
    }

    /**
     * EVERY field the summary block publishes, for one fixed input.
     *
     * This exists so that "purely additive" can be MEASURED rather than
     * asserted. Every version-log entry in this repo that claims a change is
     * additive claims it in prose; the schema contract checks the version enum
     * and the example, and nothing checks that the existing values survived. A
     * commit that adds a key to this block has to leave all seven of these
     * untouched, and if it does not, this fails and says which one moved.
     *
     * Named field by field rather than compared against a constructed object,
     * because an object comparison would follow a default change silently --
     * the same reason the required-keys pin below is not derived from Kotlin
     * nullability.
     */
    @Test
    fun `every existing summary figure, so an additive change can be shown to be additive`() = runTest {
        val summary = setOf(analysis(3), actualReps = 3, repsManual = false, includeRepDetail = true).summary
        assertEquals(0.55, summary.meanConVelMps, "mean concentric velocity")
        assertEquals(0.9, summary.peakConVelMps, "peak concentric velocity")
        assertEquals(2.0, summary.meanEccS, "mean eccentric seconds")
        assertEquals(1.0, summary.meanConS, "mean concentric seconds")
        assertEquals(0.5, summary.meanRomM, "mean range of motion")
        assertEquals(800.0, summary.peakPowerW, "peak power")
        assertEquals(500.0, summary.meanConPowerW, "mean concentric power")
    }

    // ---- the coverage flag as it stands ------------------------------------

    /**
     * The detailed export already answers the question correctly in both
     * directions. These two pins are what the summary-only fix must reproduce,
     * not replace.
     */
    @Test
    fun `the detailed export reports incomplete coverage when the counts disagree`() = runTest {
        val detailed = setOf(analysis(3), actualReps = 10, repsManual = true, includeRepDetail = true)
        assertEquals(false, detailed.repMetricsComplete)
        assertEquals(10, detailed.reps)
        assertEquals(3, detailed.repMetrics?.size)
    }

    @Test
    fun `the detailed export reports complete coverage when the counts agree`() = runTest {
        val detailed = setOf(analysis(5), actualReps = 5, repsManual = true, includeRepDetail = true)
        assertEquals(true, detailed.repMetricsComplete)
    }

    /**
     * A set the sensor segmented into nothing -- a plank, or a set recorded
     * with no sensor connected -- has no coverage to report, and that is a
     * different fact from incomplete coverage.
     *
     * The flag stays absent in BOTH modes here, and must go on doing so: with
     * no reps there is nothing for it to qualify. `summary` renders as an empty
     * object and both `velocityLoss_pct` and `tempoCompliance` are dropped, so
     * a reader is not being shown numbers whose provenance is being withheld.
     * This pin is what stops the fix turning "nothing was measured" into
     * "measurement disagrees with the lifter".
     */
    @Test
    fun `a set with no segmented reps reports no coverage flag in either mode`() = runTest {
        val none = analysis(0)
        val summary = setOf(none, actualReps = 12, repsManual = true, includeRepDetail = false)
        val detailed = setOf(none, actualReps = 12, repsManual = true, includeRepDetail = true)
        assertNull(summary.repMetricsComplete)
        assertNull(detailed.repMetricsComplete)
        assertNull(summary.repMetrics)
        assertNull(detailed.repMetrics)
        assertNull(summary.velocityLossPct)
        assertNull(summary.tempoCompliance)
        assertNull(summary.summary.meanConVelMps)
        assertEquals(12, summary.reps)
    }

    /**
     * `explicitNulls = false`, so a null flag is not written at all. The wire
     * form is what a coach reads, and "absent" is the only way this exporter
     * can express "not stated" -- there is no third rendering.
     */
    @Test
    fun `an absent coverage flag is omitted from the JSON rather than written as null`() = runTest {
        val text = exporter(analysis(0), actualReps = 12, repsManual = true).exportJson(1L, false)!!
        assertTrue("repMetricsComplete" !in text, "expected the key to be absent entirely, got:\n$text")
        assertTrue("null" !in text, "the exporter must never write a null literal, got:\n$text")
    }

    // ---- issue 32: the caveat must travel with the numbers it qualifies ----

    /**
     * The summary-only export ships the numbers and withholds the warning.
     *
     * A standard lift is manually counted by construction: the bar sensor is
     * record-only and the lifter or the voice guide does the counting, so the
     * stored count and the segmenter's count are two independent opinions and
     * they routinely differ. Ten reps recorded, three segmented, and
     * `summary.json` states velocity loss, tempo compliance and a full summary
     * block computed over those three -- while the one field that says so is
     * suppressed because per-rep detail was not requested.
     *
     * The reader has no way to notice. There is no `repMetrics` array in this
     * artifact to count against, and an absent flag renders identically to a
     * flag that was never applicable.
     */
    @Test
    fun `a summary export states that its numbers do not cover the whole set`() = runTest {
        val summary = setOf(analysis(3), actualReps = 10, repsManual = true, includeRepDetail = false)
        // The numbers, present and drawn from three reps out of ten.
        assertEquals(10, summary.reps)
        assertEquals(16.7, summary.velocityLossPct)
        assertEquals(3, summary.tempoCompliance?.of)
        assertEquals(0.55, summary.summary.meanConVelMps)
        // The caveat that qualifies them.
        assertEquals(false, summary.repMetricsComplete)
    }

    /** The same statement in the other direction: coverage confirmed, not merely unstated. */
    @Test
    fun `a summary export states when its numbers do cover the whole set`() = runTest {
        val summary = setOf(analysis(5), actualReps = 5, repsManual = true, includeRepDetail = false)
        assertEquals(true, summary.repMetricsComplete)
    }

    /**
     * The two artifacts describe one set and must not disagree about it.
     *
     * This is the assertion that survives a future refactor of either branch:
     * whatever the flag says, both exports of the same set say the same thing,
     * because the level of detail requested is not a fact about how the lifter
     * trained. Written over all three states -- disagreeing counts, agreeing
     * counts, and no segmented reps -- so it cannot pass by both sides being
     * null.
     */
    @Test
    fun `the coverage flag is the same in both exports of one set`() = runTest {
        val cases =
            listOf(
                Triple(analysis(3), 10, false),
                Triple(analysis(5), 5, true),
                Triple(analysis(0), 12, null),
            )
        for ((a, recorded, expected) in cases) {
            val summary = setOf(a, actualReps = recorded, repsManual = true, includeRepDetail = false)
            val detailed = setOf(a, actualReps = recorded, repsManual = true, includeRepDetail = true)
            assertEquals(expected, summary.repMetricsComplete, "summary export, $recorded recorded")
            assertEquals(expected, detailed.repMetricsComplete, "detailed export, $recorded recorded")
        }
    }

    /**
     * The wire form, because that is what a coach opens. `explicitNulls = false`
     * drops a null, so the assertion has to be that the key is present with the
     * value false -- not merely that the object decoded to something.
     */
    @Test
    fun `the summary JSON carries the coverage flag beside the figures it qualifies`() = runTest {
        val text =
            exporter(analysis(3), actualReps = 10, repsManual = true)
                .exportJson(1L, includeRepDetail = false)!!
        assertTrue("\"repMetricsComplete\": false" in text, "expected the caveat in the wire form, got:\n$text")
        assertTrue("\"velocityLoss_pct\"" in text, "the figure the caveat qualifies should be here too")
        assertTrue("\"repMetrics\"" !in text, "summary-only export must still omit the per-rep array")
    }

    // ---- heart rate ---------------------------------------------------------

    /**
     * A summary carrying a mean and a maximum but no end-of-set reading still
     * publishes its `hr` block.
     *
     * That row shape is not hypothetical: it is what a set produces when its
     * final sample is not a measurement, and session 28's third set is exactly
     * it -- null, 46, 46. The exporter gates the block on ANY of the three
     * stored columns, or the freshly computed minBpm, being present, and
     * nothing exercised that gate. Narrowing it to ALL FOUR deletes the block
     * entirely for this shape, silently, and the set would export as though
     * no strap had been connected.
     *
     * No raw HRM stream is wired for this set, so minBpm is null here too --
     * this test is about the three stored columns, not the fourth source;
     * that one gets its own tests below.
     *
     * Asserted on the emitted JSON as well as on the object, because the JSON
     * is what a consumer reads and `explicitNulls = false` is what turns the
     * absent reading into an absent key rather than a null.
     */
    @Test
    fun `a set with no end-of-set reading still exports its mean and maximum`() = runTest {
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = null, hrAvgBpm = 46, hrMaxBpm = 46)
        val exported =
            exporterOf(stored).buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr, "the hr block was dropped")
        assertNull(hr.endOfSetBpm)
        assertEquals(46, hr.avgBpm)
        assertEquals(46, hr.maxBpm)
        assertNull(hr.minBpm, "no raw stream was wired for this set")

        val text = exporterOf(stored).exportJson(1L, includeRepDetail = true)!!
        val block =
            Json.parseToJsonElement(text).jsonObject["exercises"]!!.jsonArray.single()
                .jsonObject["sets"]!!.jsonArray.single().jsonObject["hr"]!!.jsonObject
        assertEquals(setOf("avgBpm", "maxBpm"), block.keys, "the absent reading was published as a key")
        assertEquals("46", block["avgBpm"]!!.jsonPrimitive.content)
    }

    /**
     * minBpm comes from this set's own raw HRM stream, decoded fresh at
     * export time -- not from any stored column, and not the same population
     * a reader might assume from [avgBpm]/[maxBpm] alone.
     *
     * The stored row and the raw stream disagree on purpose: the row's three
     * columns are 148/131/152, none of which is anywhere near the stream's
     * trusted minimum of 90. If minBpm were somehow deriving from the stored
     * row -- copied from one of the other three, or computed from a stale
     * cache of the same numbers -- this would either fail to compile the
     * assertion below or produce one of 148, 131 or 152 instead of 90.
     */
    @Test
    fun `a set's minBpm comes from its raw HRM stream, not the stored row`() = runTest {
        val samples =
            listOf(
                HrSample(1_000L, 130, listOf(600.0)),
                HrSample(2_000L, 90, listOf(650.0)),
                HrSample(3_000L, 110, listOf(700.0)),
            )
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = 148, hrAvgBpm = 131, hrMaxBpm = 152)
        val exported =
            exporterOf(stored, extraStreams = listOf(hrStream(samples)))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr)
        assertEquals(148, hr.endOfSetBpm)
        assertEquals(131, hr.avgBpm)
        assertEquals(152, hr.maxBpm)
        assertEquals(90, hr.minBpm, "the stream's trusted minimum, not any of the stored columns")
    }

    /**
     * The gate's fourth clause, discharged directly: a set whose three
     * stored columns are all null -- nothing trusted at record time, or a
     * session that predates minBpm entirely -- still publishes an `hr` block
     * when its raw stream has a trusted minimum today. Reverting the gate to
     * check only the three stored columns would drop this block silently;
     * this is the test that reds if that happens.
     */
    @Test
    fun `a set with nothing stored still publishes minBpm from its raw stream`() = runTest {
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = null, hrAvgBpm = null, hrMaxBpm = null)
        val samples = listOf(HrSample(1_000L, 100, listOf(600.0)))
        val exported =
            exporterOf(stored, extraStreams = listOf(hrStream(samples)))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr, "the hr block was dropped despite a trusted raw stream")
        assertNull(hr.endOfSetBpm)
        assertNull(hr.avgBpm)
        assertNull(hr.maxBpm)
        assertEquals(100, hr.minBpm)

        val text =
            exporterOf(stored, extraStreams = listOf(hrStream(samples))).exportJson(1L, includeRepDetail = true)!!
        val block =
            Json.parseToJsonElement(text).jsonObject["exercises"]!!.jsonArray.single()
                .jsonObject["sets"]!!.jsonArray.single().jsonObject["hr"]!!.jsonObject
        assertEquals(setOf("minBpm"), block.keys, "minBpm should be the only key this set can support")
    }

    /**
     * minBpm reads the HRM stream and ONLY the HRM stream.
     *
     * `raw_streams.kind` is a free-form column, so a set can carry a stream of
     * any kind at all, and `Exporters.minBpm` selects with
     * `firstOrNull { it.kind == KIND_HRM }`. This pins that selection against a
     * kind that does not exist yet: heart-rate samples recorded during the REST
     * window before a set are a different population from the set's own, and
     * folding them into a figure published under the set's name would be the
     * mixed-population defect this repository has already shipped once.
     *
     * Written before the rest stream exists, deliberately -- the kind here is a
     * literal rather than a constant, so this test does not need the feature it
     * guards, and it reds if the selection is ever widened to match on a
     * prefix, on "contains hrm", or on anything but equality.
     *
     * The two streams disagree by design: the set's own trusted minimum is 100,
     * the impostor's is 44. Publishing 44 would be the failure.
     */
    @Test
    fun `a stream of another kind cannot reach minBpm`() = runTest {
        val ownSamples = listOf(HrSample(5_000L, 100, listOf(600.0)))
        val restSamples =
            listOf(HrSample(1_000L, 44, listOf(1_350.0)), HrSample(2_000L, 47, listOf(1_270.0)))
        val impostor =
            RawStreamEntity(
                id = 12L,
                setId = 5L,
                kind = "rest_before_hrm",
                csvGzip = Gzip.compress(HrCsv.encode(restSamples)),
            )
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = null, hrAvgBpm = null, hrMaxBpm = null)
        val exported =
            // Impostor FIRST. With the real stream first this passed under a
            // widened selection too -- firstOrNull took the right stream by
            // position rather than by kind, so the pin was asserting the order
            // of a list whose order the caller chooses arbitrarily.
            exporterOf(stored, extraStreams = listOf(impostor, hrStream(ownSamples)))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr)
        assertEquals(100, hr.minBpm, "a non-HRM stream reached the set's published minimum")
    }

    /**
     * And with no HRM stream at all, a stream of another kind does not stand in
     * for one: the block is absent rather than filled from the wrong stream.
     */
    @Test
    fun `a stream of another kind does not substitute for a missing HRM stream`() = runTest {
        val restSamples = listOf(HrSample(1_000L, 44, listOf(1_350.0)))
        val impostor =
            RawStreamEntity(
                id = 12L,
                setId = 5L,
                kind = "rest_before_hrm",
                csvGzip = Gzip.compress(HrCsv.encode(restSamples)),
            )
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = null, hrAvgBpm = null, hrMaxBpm = null)
        val exported =
            exporterOf(stored, extraStreams = listOf(impostor))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        assertNull(exported.hr, "a non-HRM stream produced an hr block on its own")
    }

    /**
     * THE EXPORT-PATH GATE, which nothing pinned until this test existed.
     *
     * This is the retroactive half of issue #83 and the largest claim its
     * commit makes: three of the four figures come from FROZEN columns written
     * when the set was recorded, so a set already on disk keeps exporting them
     * unless the export asks the stored stream the same question. Reverting the
     * gate red no test at all before this one.
     *
     * The stored row says 46/46/46 -- the unworn shape -- and the stream it
     * came from cannot account for its own elapsed time. The whole block goes,
     * not merely the recomputed minimum.
     */
    @Test
    fun `a stored row whose stream cannot track a heart publishes no hr block`() = runTest {
        // The real unworn shape, not a perfectly held one: a detector that has
        // lost contact emits a HANDFUL of distinct values over a long span. A
        // single repeated value produces no budget at all and is deliberately
        // not silenced, so a fixture built that way would test nothing here.
        val lost = lostContactStream()
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = 46, hrAvgBpm = 46, hrMaxBpm = 46)
        val exported =
            exporterOf(stored, extraStreams = listOf(hrStream(lost)))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        assertNull(exported.hr, "the frozen columns survived a stream that tracks nothing")
    }

    /**
     * And the block is NOT withheld when the stream is fine, which is the other
     * half of the same gate: without this a rule that silenced everything would
     * pass the test above.
     */
    @Test
    fun `a stored row whose stream does track a heart keeps its hr block`() = runTest {
        val beats =
            (0 until 60).map { i ->
                val rr = if (i % 2 == 0) 800.0 else 800.9765625
                HrSample((i * 800L), 75, listOf(rr))
            }
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = 76, hrAvgBpm = 75, hrMaxBpm = 78)
        val exported =
            exporterOf(stored, extraStreams = listOf(hrStream(beats)))
                .buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr, "a healthy stream lost its block")
        assertEquals(75, hr.avgBpm)
    }

    /**
     * THE SESSION BLOCK IS AGGREGATED FROM THE SET ROWS, so it must not outlive
     * them. A session whose every set has been silenced published avgBpm and
     * maxBpm from columns aggregated out of figures this export no longer
     * carries -- the session asserting what all of its sets have withdrawn.
     */
    @Test
    fun `a session whose every set is silenced publishes no session heart rate`() = runTest {
        val lost = lostContactStream()
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = 46, hrAvgBpm = 46, hrMaxBpm = 46)
        val exported =
            exporterOf(stored, extraStreams = listOf(hrStream(lost)), sessionAvgBpm = 46, sessionMaxBpm = 46)
                .buildExport(1L, includeRepDetail = true)!!
        assertNull(exported.heartRate, "the session outlived every set it was aggregated from")

        // AND THE FIGURE THE GATE IS WRONG ABOUT, in the same test because it
        // is the same gate. hrvRmssd_ms rides in this block and is NOT
        // aggregated from the set rows -- its input spans READY, IN_SET and
        // RESTING while the gate reads only the per-set IN_SET streams. This
        // was the only path through the gate with nothing asserting on it,
        // which is how a decision made for one figure comes to be assumed for
        // three. Behaviour deliberate and unchanged; the note is at the gate.
        val hrvOnly =
            exporterOf(stored, extraStreams = listOf(hrStream(lost)), sessionHrvRmssdMs = 42.5)
                .buildExport(1L, includeRepDetail = true)!!
        assertNull(hrvOnly.heartRate, "an HRV figure outlived every set, on streams it was not computed from")
    }

    /**
     * A set whose raw stream is NOT STORED cannot be evaluated, and is left
     * exactly as it was. Issue #83's retroactive reach stops at the sets whose
     * streams survive; this pins that boundary rather than leaving it implied,
     * and it is why the schema entry says an absent minBpm is a statement about
     * the archive rather than about the heart.
     */
    @Test
    fun `a set with no stored stream is left alone by the gate`() = runTest {
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = 46, hrAvgBpm = 46, hrMaxBpm = 46)
        val exported =
            exporterOf(stored).buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        val hr = assertNotNull(exported.hr, "a set with no stream to judge was silenced on no evidence")
        assertEquals(46, hr.avgBpm)
        assertNull(hr.minBpm, "and it still has no minimum, because there is no stream to take one from")
    }

    /**
     * A set that summarised to nothing at all publishes no `hr` block.
     *
     * The other side of the same gate. An empty object here would read as "a
     * heart rate was measured and every figure happened to be missing", which
     * is a different claim from "there is nothing to say".
     */
    @Test
    fun `a set that summarised to nothing publishes no hr block`() = runTest {
        val stored =
            row(analysis(3), actualReps = 3, repsManual = false)
                .copy(hrEndOfSetBpm = null, hrAvgBpm = null, hrMaxBpm = null)
        val exported =
            exporterOf(stored).buildExport(1L, includeRepDetail = true)!!
                .exercises.single().sets.single()
        assertNull(exported.hr)

        val text = exporterOf(stored).exportJson(1L, includeRepDetail = true)!!
        val setObject =
            Json.parseToJsonElement(text).jsonObject["exercises"]!!.jsonArray.single()
                .jsonObject["sets"]!!.jsonArray.single().jsonObject
        assertTrue("hr" !in setObject.keys, "an empty hr block was published")
    }

    // ---- issue #29: exportJson/buildExport must actually dispatch ----------

    /**
     * The seam issue #29 needed: proof that both entry points honour an
     * injected dispatcher rather than running inline on the caller's. Two
     * assertions, not one, because [exportJson] and [buildExport] are two
     * separate `withContext` calls -- one dropping its wrapper would not
     * necessarily red the other.
     */
    @Test
    fun `exportJson and buildExport both dispatch through their injected dispatcher`() = runTest {
        val stored = row(analysis(3), actualReps = 3, repsManual = false)
        val recordingForJson = RecordingDispatcher()
        exporterOf(stored, dispatcher = recordingForJson).exportJson(1L, includeRepDetail = false)
        assertTrue(recordingForJson.dispatched, "exportJson never dispatched through the injected dispatcher")

        val recordingForBuild = RecordingDispatcher()
        exporterOf(stored, dispatcher = recordingForBuild).buildExport(1L, includeRepDetail = false)
        assertTrue(recordingForBuild.dispatched, "buildExport never dispatched through the injected dispatcher")
    }
}
