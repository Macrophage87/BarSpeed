package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differentials for the provenance half of #220. When these differentials were
 * written, geometry.source published a word for six of the eight values and
 * nothing for bodyweight. Since #220 it publishes seven, bodyweight included.
 * That flag decides whether the lifter's own mass is a term in `load_kg`.
 *
 * Measured on field-37, app 0.1.48, export 1.16: six of its thirteen sets are
 * body-weight work, every one of them publishes `"bodyweight": true` under
 * `geometry`, and every one publishes a five-key `source` object that does not
 * mention it. So "the plan declared it" and "the app decided it" are the same
 * document.
 *
 * EVERY TEST HERE IS RED AT THE COMMIT THAT INTRODUCES IT.
 * [SetGeometryPolicy.bodyweightSource] is declared in that commit with a
 * deliberately neutral body -- it answers [GeometrySource.DEFAULT] for
 * everything -- so these compile and fail on the answer rather than failing to
 * build, which is [Migration14To15Test]'s method one module over. Nothing is
 * wired into [SetGeometryPolicy.describe] or into the export until the fix.
 *
 * ## What a word here can and cannot say, on this branch's parent
 *
 * `PlanExerciseDef.bodyweight` is a non-nullable `Boolean` defaulting to false
 * at `52cd0b7c7557411dc7db244ea6c1b83436369474`, so a plan that wrote
 * `"bodyweight": false` and a plan that omitted the key decode to the same
 * value and no reading of them can be told apart. That is why
 * [GeometrySources] published nothing for it, and it is still true here: what
 * changes is that DEFAULT is published for that pair instead of silence.
 * DEFAULT is exactly the claim "the value is false and nothing observable
 * supplied it", which is true of both, so this publishes no distinction the
 * app cannot make. A declared TRUE is unambiguous -- false is the type default,
 * so only a declaration produces true -- and that is the case the six field-37
 * sets are in.
 *
 * [SetGeometryPolicy.bodyweightSource]'s SEEDED branch is UNREACHABLE from any
 * built-in definition at that SHA: `grep "bodyweight = true"` over
 * `core/model/src/main` and `app/src/main` finds nothing, so no shipped
 * [ExerciseDef.SEED] entry is body-weight work. It is written and pinned
 * because the rule -- read the value the set was recorded against, never
 * re-decide it -- is [SetGeometryPolicy.stackSource]'s, and because the branch
 * becomes reachable the moment a seed says otherwise. The test below reaches
 * it through a constructed [ExerciseDef] rather than by naming a seed that
 * does not exist.
 */
class BodyweightProvenanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(id: String, declarations: String = ""): PlanExerciseDef = json.decodeFromString(
        PlanFile.serializer(),
        """
        {"schemaVersion":"1.10","planName":"P","sessions":[{"name":"S","exercises":[
          {"exercise":"$id"$declarations,"sets":[{"reps":5}]}
        ]}]}
        """.trimIndent(),
    ).sessions[0].exercises[0]

    /**
     * RED. field-37's shape: the plan said `"bodyweight": true` and the archive
     * cannot show that it did.
     */
    @Test
    fun `a plan that declares body weight publishes a declared source`() {
        val plan = exercise("assisted_pull_up", ""","bodyweight":true""")
        assertEquals(
            GeometrySource.DECLARED,
            SetGeometryPolicy.bodyweightSource(used = true, declared = plan.bodyweight),
        )
    }

    /**
     * RED. The branch no shipped seed reaches yet: a definition that is
     * body-weight work in its own right, recorded ad-hoc with no plan to have
     * declared anything.
     */
    @Test
    fun `a body-weight definition with no plan publishes a seeded source`() {
        assertEquals(
            GeometrySource.SEEDED,
            SetGeometryPolicy.bodyweightSource(used = true, declared = null),
        )
    }

    /**
     * GREEN at the introducing commit, and marked as such. A loaded set with
     * NO plan declaration has nothing observable behind its false, and
     * DEFAULT is what that state is called.
     */
    @Test
    fun `a loaded set with no declaration publishes a default source`() {
        assertEquals(
            GeometrySource.DEFAULT,
            SetGeometryPolicy.bodyweightSource(used = false, declared = null),
        )
    }

    /**
     * RED as of the round-1 fix for issue #178's review. `PlanExerciseDef.bodyweight`
     * became `Boolean?` on `origin/main` (#227, "Make bodyweight nullable so an
     * omitted key is not a silent false") after this file was first written, the
     * same change #223 made for `sensorOnStack` earlier. [SetGeometryPolicy.stackSource]
     * already reads a non-null [declared] as DECLARED whatever its value; this
     * pin asks [SetGeometryPolicy.bodyweightSource] for the same rule. Before the
     * fix, a declared `false` and an omitted key were bucketed together as
     * DEFAULT even though the type no longer forces that -- a plan that wrote
     * `"bodyweight": false` published a provenance word indistinguishable from
     * one that said nothing.
     */
    @Test
    fun `a declared false is DECLARED, not DEFAULT, now the plan key is nullable`() {
        val plan = exercise("assisted_pull_up", ""","bodyweight":false""")
        assertEquals(
            GeometrySource.DECLARED,
            SetGeometryPolicy.bodyweightSource(used = false, declared = plan.bodyweight),
        )
    }

    /**
     * RED. The published provenance is read off the value the set carries, so
     * the two halves of one fact cannot disagree -- [SetGeometryPolicy.describe]
     * is where that coupling has to hold, not the helper above.
     */
    @Test
    fun `describe publishes the source beside the value it describes`() {
        val plan = exercise("assisted_pull_up", ""","bodyweight":true""")
        val used = SetGeometryPolicy.resolve(ExerciseDef("assisted_pull_up", "Assisted pull-up"), plan)
        val described = SetGeometryPolicy.describe(used, plan)
        assertEquals(true, described.bodyweight, "the set was not resolved as body-weight work")
        assertEquals(GeometrySource.DECLARED, described.sources.bodyweight)
    }

    /**
     * RED. A stored row written before this field existed decodes rather than
     * throwing, and reads as DEFAULT.
     *
     * [GeometrySources] is stored as JSON in `SetRecordEntity.geometryJson`,
     * and every row up to and including v0.1.50 carries a `sources` object
     * without this key. Without a Kotlin default, decoding one throws
     * `MissingFieldException` and a stored set becomes unreadable the moment
     * this ships -- the trap `sensorOnStack` documented one version earlier.
     * The default cannot recover what those builds never captured: such a row
     * reports `default` however the plan was written, permanently.
     */
    @Test
    fun `a sources object written before this key decodes with the key defaulted`() {
        val stored =
            """
            {"startsWith":"DECLARED","concentric":"DECLARED","plane":"DECLARED",
             "kind":"DECLARED","travelRatio":"DEFAULT","sensorOnStack":"DEFAULT"}
            """.trimIndent()
        val decoded = Json.decodeFromString(GeometrySources.serializer(), stored)
        assertEquals(GeometrySource.DEFAULT, decoded.bodyweight)
    }

    /**
     * RED. A newly recorded set whose provenance genuinely resolves to DEFAULT
     * still publishes the word.
     *
     * `SessionExporter`'s `Json` sets `encodeDefaults = false`, and plain
     * `Json` defaults to false as well, so a field carrying a Kotlin default
     * is DROPPED from the wire unless it is annotated. `geometry.source` is a
     * closed required object in the published schema, so a dropped key is not
     * a smaller document, it is an invalid one. `sensorOnStack` learned this
     * one version earlier and the annotation is what fixed it there.
     */
    @Test
    fun `a default source is written out rather than dropped`() {
        val encoded =
            Json { encodeDefaults = false }.encodeToString(
                GeometrySources.serializer(),
                GeometrySources(
                    startsWith = GeometrySource.DEFAULT,
                    concentric = GeometrySource.DEFAULT,
                    plane = GeometrySource.DEFAULT,
                    kind = GeometrySource.DEFAULT,
                    travelRatio = GeometrySource.DEFAULT,
                ),
            )
        assertEquals(true, "bodyweight" in encoded, "the defaulted bodyweight source was dropped: $encoded")
    }
}
