package com.macrophage.barspeed.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Where one resolved geometry value came from, so a reader can tell a
 * declaration from a guess.
 *
 * The four are ordered by how much they are worth trusting. [DECLARED] is
 * somebody stating a fact about the machine in front of them; [SEEDED] is the
 * app's built-in definition of a lift it ships; [INFERRED] is a guess made by
 * matching words in an exercise id, which by its own documentation "gets
 * ordinary names wrong"; [DEFAULT] is nothing at all -- the type default stood
 * because no inference for that value exists.
 */
@Serializable
enum class GeometrySource {
    /** A plan declared this value explicitly. */
    DECLARED,

    /** No declaration; the value came from an [ExerciseDef.SEED] entry. */
    SEEDED,

    /** No declaration and no seed; the value was guessed from the exercise id. */
    INFERRED,

    /**
     * No declaration, no seed, and no inference exists for this value -- the
     * type default stood. [ExerciseDef.concentricUp] is the reason this is a
     * distinct state rather than a synonym for [INFERRED]: guessing it from the
     * id is deliberately refused, so calling it inferred would claim reasoning
     * the app does not do.
     */
    DEFAULT,
}

/**
 * Provenance for the seven geometry values whose resolution has more than one
 * possible source. [GeometrySourceExport]'s KDoc, in `SessionExport.kt`, is
 * the one statement of which values carry a source and why -- read it there,
 * not here.
 *
 * `sensorOnStack` defaults to [GeometrySource.DEFAULT] rather than being
 * required, and that default is load-bearing rather than decorative: this
 * object is stored as JSON in `SetRecordEntity.geometryJson`, and every row
 * written by a build up to and including v0.1.48 serialized a five-key
 * `sources` object -- this field did not exist at all. Without a default,
 * decoding one of those rows throws `MissingFieldException` rather than
 * reading -- a stored set becomes unreadable the moment this field ships.
 * The default cannot recover what that build never captured: such a row
 * re-exports `geometry.source.sensorOnStack` as `"default"` regardless of
 * what the plan actually said, permanently, because no earlier build
 * tracked the answer to recover.
 *
 * `@EncodeDefault(ALWAYS)` on that field, not silence: `SessionExporter`'s
 * `Json` sets `encodeDefaults = false`, which is also plain `Json`'s own
 * default -- `kotlinx.serialization.json.Json`'s `encodeDefaults` is `false`
 * unless a config says otherwise. Without the annotation, a NEWLY recorded
 * set whose provenance genuinely resolves to [GeometrySource.DEFAULT] would
 * have the key silently DROPPED rather than published as `"default"` --
 * `geometry.source` is a closed six-key required object, so a dropped key is
 * not a smaller export, it is an invalid one. Caught by
 * `StackSeedDifferentialTest`'s `an id nothing seeds still publishes a
 * default stack mount`, which reds without this annotation even though the
 * default value above is correct.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeometrySources(
    val startsWith: GeometrySource,
    val concentric: GeometrySource,
    val plane: GeometrySource,
    val kind: GeometrySource,
    val travelRatio: GeometrySource,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val sensorOnStack: GeometrySource = GeometrySource.DEFAULT,
    /**
     * Where `bodyweight` came from (1.18, #220), on the same terms
     * `sensorOnStack` is stored and defaulted under.
     *
     * The default is load-bearing for the same reason: every row written by a
     * build up to and including v0.1.49 holds a `sources` object without this
     * key, and without a default, decoding one throws `MissingFieldException`
     * -- a stored set becomes unreadable the moment this ships. The default
     * cannot recover what those builds never captured; such a row re-exports
     * `default` however the plan was written, permanently.
     *
     * `@EncodeDefault(ALWAYS)` for the reason stated above this field:
     * `geometry.source` is a closed required object, so a key dropped by
     * `encodeDefaults = false` makes the document invalid rather than smaller.
     *
     * This field carried LESS than the five above it when it was written:
     * `PlanExerciseDef.bodyweight` was a non-nullable `Boolean`, so a declared
     * `false` and an omitted key were one value and both published
     * [GeometrySource.DEFAULT]. `#227` made the plan key `Boolean?`, the same
     * change #223 made for `sensorOnStack`, and the round-1 fix to issue
     * #178's review brought [SetGeometryPolicy.bodyweightSource] into line
     * with it: a declared `false` now reads [GeometrySource.DECLARED], the
     * same as `sensorOnStack`'s pair, and only a genuinely omitted key reads
     * [GeometrySource.DEFAULT] (or [GeometrySource.SEEDED]). See
     * [SetGeometryPolicy.bodyweightSource].
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val bodyweight: GeometrySource = GeometrySource.DEFAULT,
)

/**
 * The direction and geometry one set was actually recorded and analysed
 * against, with where each value came from.
 *
 * Field names mirror [ExerciseDef] rather than the plan's wire vocabulary,
 * because this records what the app USED, not what a plan said. The mapping to
 * the plan's words (`top`/`bottom`, `up`/`down`, `vertical`/`horizontal`)
 * happens once, at the export boundary.
 *
 * Every field is required. This object is stored and published present-or-
 * absent as a unit: present means the app knows all of it, absent means the set
 * was recorded before any of it was captured. A partially-filled geometry would
 * be indistinguishable from a fully-known one whose values happen to be the
 * defaults, which is the failure this whole change exists to remove.
 */
@Serializable
data class ResolvedGeometry(
    val startsWith: StartPhase,
    val concentricUp: Boolean,
    val horizontal: Boolean,
    val sensorOnStack: Boolean,
    val sensorInverted: Boolean,
    val travelRatio: Double,
    val kind: ExerciseKind,
    val bodyweight: Boolean,
    val sources: GeometrySources,
)

/**
 * One resolved `sensorOnStack` value together with where it came from.
 *
 * A pair rather than a bare boolean because the two answers are decided by the
 * same three-way question -- did the plan say, does the app ship a default for
 * this id, or is nothing known -- and computing them apart is how a published
 * provenance drifts from the value it describes.
 */
data class StackMount(val onStack: Boolean, val source: GeometrySource)

/**
 * How a plan's declarations combine with the app's built-in definition to give
 * the one [ExerciseDef] a set is recorded against, and how to describe the
 * result afterwards.
 *
 * This lived in `app/.../PlanQueue.kt`, inside a suspend extension on
 * `SessionRepository` that no test on the CI path calls, so nothing could run
 * against it. [resolve] is that code, moved here and since changed at its
 * `sensorOnStack` line; [describe] is new and reads its values off the
 * definition that was actually used, never recomputing them -- so what gets
 * published cannot drift from what the DSP was handed, whatever else changes
 * upstream.
 */
object SetGeometryPolicy {
    /**
     * Whether the sensor rode a weight stack for this set, and on whose word.
     *
     * Precedence, highest first:
     *
     * 1. [declared] non-null -- the plan said so, either way. A declared
     *    `false` wins over the seed default: the lifter may have clipped the
     *    sensor to the handle, and only whoever wrote the plan can know.
     * 2. [base] already true, or [id] is one of [ExerciseDef.STACK_MOUNTED_IDS]
     *    -- the app's own definition of that machine.
     * 3. Nothing at all, so the type default stands. Not [GeometrySource.INFERRED]:
     *    no words in the id are being read, only an exact match against a table
     *    the app ships, so an id spelled any other way lands here rather than
     *    being guessed at.
     */
    fun stackMount(id: String, base: Boolean, declared: Boolean?): StackMount = when {
        declared != null -> StackMount(declared, GeometrySource.DECLARED)
        base || ExerciseDef.ridesStack(id) -> StackMount(true, GeometrySource.SEEDED)
        else -> StackMount(false, GeometrySource.DEFAULT)
    }

    /**
     * Whether the lifter's own body was the load for this set.
     *
     * The same three-way precedence [stackMount] uses, WITHOUT a paired
     * [GeometrySource] returned from here: this function answers what the set
     * used, and [bodyweightSource] answers what the export publishes beside
     * it, kept apart because the source is read off [describe]'s [used]
     * rather than re-decided from [id] here -- [bodyweightSource]'s own KDoc
     * says why. A source IS published for `bodyweight` in the export, since
     * 1.18 (#220); it does not come from this function.
     *
     * 1. [declared] non-null -- the plan said so, either way. A declared
     *    `false` wins over the seed: a plan may genuinely mean a pull-up
     *    machine whose sensor and load are both external to the lifter.
     * 2. [base] already true, or [id] is one of [ExerciseDef.BODYWEIGHT_IDS]
     *    -- the app's own definition of that movement.
     * 3. Nothing at all, so the type default (false) stands.
     */
    fun bodyweightMount(id: String, base: Boolean, declared: Boolean?): Boolean = when {
        declared != null -> declared
        else -> base || ExerciseDef.isBodyweightByConstruction(id)
    }

    /**
     * Overlay a plan exercise's declarations on the built-in definition.
     *
     * A declaration always wins: machines of the same movement pattern really
     * do start at opposite ends, and no signal processing can tell which. An
     * omitted key falls back to what [base] already says, not to the Kotlin
     * default -- overwriting with the default would discard the built-in
     * definition.
     *
     * `sensorInverted` is still assigned unconditionally because it cannot
     * express omission (see [GeometrySources]); that is the rest of #64 and is
     * not fixed here. `sensorOnStack` and `bodyweight` are not: `sensorOnStack`
     * goes through [stackMount] and `bodyweight` through [bodyweightMount], so
     * a plan that says nothing about either gets the app's default for that
     * machine and a plan that says `false` still wins (#61, #223, #227).
     */
    fun resolve(base: ExerciseDef, declared: PlanExerciseDef?): ExerciseDef {
        if (declared == null) return base
        return base.copy(
            startsWith = declared.startPhaseOverride ?: base.startsWith,
            concentricUp = declared.concentric?.let { it == "up" } ?: base.concentricUp,
            kind = declared.effectiveKind,
            sensorInverted = declared.sensorInverted,
            travelRatio = declared.travelRatio ?: base.travelRatio,
            horizontal = declared.plane?.let { it == "horizontal" } ?: base.horizontal,
            sensorOnStack = stackMount(base.id, base.sensorOnStack, declared.sensorOnStack).onStack,
            bodyweight = bodyweightMount(base.id, base.bodyweight, declared.bodyweight),
        )
    }

    /**
     * Describe the definition a set was recorded against.
     *
     * [used] is the definition the analysis actually ran on, so its values are
     * copied straight across rather than recomputed from [declared]. The only
     * thing worked out here is provenance.
     *
     * One hedge on [GeometrySource.INFERRED] for `startsWith`: an ad-hoc set
     * against an id the app does not ship is reported inferred, which is what
     * `SessionRepository.exerciseById` produces for both of its non-seed
     * branches. `RecordViewModel` has a bare fallback that would default it
     * instead, and this cannot tell the two apart -- but that fallback is not
     * reachable from the UI today, because the exercise picker offers seeded
     * ids only.
     */
    fun describe(used: ExerciseDef, declared: PlanExerciseDef?): ResolvedGeometry {
        val seeded = ExerciseDef.seedById(used.id) != null
        return ResolvedGeometry(
            startsWith = used.startsWith,
            concentricUp = used.concentricUp,
            horizontal = used.horizontal,
            sensorOnStack = used.sensorOnStack,
            sensorInverted = used.sensorInverted,
            travelRatio = used.travelRatio,
            kind = used.kind,
            bodyweight = used.bodyweight,
            sources =
            GeometrySources(
                startsWith = source(declared?.startPhaseOverride != null, seeded, inferable = true),
                concentric = source(declared?.concentric != null, seeded, inferable = false),
                plane = source(declared?.plane != null, seeded, inferable = false),
                kind = source(declared?.kindOverride != null, seeded, inferable = true),
                travelRatio = source(declared?.travelRatio != null, seeded, inferable = false),
                sensorOnStack = stackSource(used.sensorOnStack, declared?.sensorOnStack),
                bodyweight = bodyweightSource(used.bodyweight, declared?.bodyweight),
            ),
        )
    }

    /**
     * Provenance for the value [used] already carries, never a re-decision.
     *
     * Deliberately NOT [stackMount]: that answers what the value SHOULD be and
     * consults the id table to do it, so calling it here could report SEEDED
     * beside a published `false` on a set that never went through [resolve] --
     * an ad-hoc set has no plan. Reading the resolved value is what keeps the
     * two halves of one published fact from disagreeing.
     */
    /**
     * Provenance for the `bodyweight` value [used] already carries (#220).
     *
     * [stackSource]'s rule, exactly, since the round-1 fix to issue #178's
     * review: the value is read off the definition the set was recorded
     * against, never re-decided from the id, so the published word cannot
     * disagree with the published flag.
     *
     * [declared] is the plan's key for this exercise, null when there was no
     * plan. WRITTEN FIRST when `PlanExerciseDef.bodyweight` was a non-nullable
     * `Boolean`, so a declared `false` and an omitted key decoded to the same
     * wire value and no reading could have told them apart; `#227` made the
     * plan key `Boolean?` afterwards, the same change #223 made for
     * `sensorOnStack`, so [declared] `!= null` is now a real declaration
     * whatever its value, and this function was corrected to read it that way
     * to match [stackSource]:
     *
     * 1. [declared] non-null -- a declaration, either way. A declared `false`
     *    wins over the seed: a plan may genuinely mean a pull-up machine whose
     *    sensor and load are both external to the lifter.
     * 2. otherwise, [used] true -- nothing declared it and the set was
     *    body-weight work anyway, which only the app's own definition of that
     *    lift can have supplied. [GeometrySource.SEEDED], never
     *    [GeometrySource.INFERRED]: no words in the id are read.
     * 3. otherwise [GeometrySource.DEFAULT] -- nothing declared and nothing
     *    seeded.
     */
    fun bodyweightSource(used: Boolean, declared: Boolean?): GeometrySource = when {
        declared != null -> GeometrySource.DECLARED
        used -> GeometrySource.SEEDED
        else -> GeometrySource.DEFAULT
    }

    private fun stackSource(used: Boolean, declared: Boolean?): GeometrySource = when {
        declared != null -> GeometrySource.DECLARED
        used -> GeometrySource.SEEDED
        else -> GeometrySource.DEFAULT
    }

    private fun source(declared: Boolean, seeded: Boolean, inferable: Boolean): GeometrySource = when {
        declared -> GeometrySource.DECLARED
        seeded -> GeometrySource.SEEDED
        inferable -> GeometrySource.INFERRED
        else -> GeometrySource.DEFAULT
    }
}
