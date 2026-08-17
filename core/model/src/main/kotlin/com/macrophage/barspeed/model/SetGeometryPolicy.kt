package com.macrophage.barspeed.model

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
 * Provenance for the five geometry values whose resolution has more than one
 * possible source.
 *
 * `sensorInverted`, `sensorOnStack` and `bodyweight` are absent on purpose, not
 * by oversight: they are non-nullable `Boolean` on [PlanExerciseDef], so a plan
 * that declared `false` and a plan that said nothing decode to the same value
 * and there is nothing left to observe. Reporting a source for them would mean
 * inventing one.
 */
@Serializable
data class GeometrySources(
    val startsWith: GeometrySource,
    val concentric: GeometrySource,
    val plane: GeometrySource,
    val kind: GeometrySource,
    val travelRatio: GeometrySource,
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
 * How a plan's declarations combine with the app's built-in definition to give
 * the one [ExerciseDef] a set is recorded against, and how to describe the
 * result afterwards.
 *
 * This lived in `app/.../PlanQueue.kt`, which is in a module with no test
 * source set, so nothing could run against it. [resolve] is that code moved
 * without change; [describe] is new and reads its values off the definition
 * that was actually used, never recomputing them -- so what gets published
 * cannot drift from what the DSP was handed, whatever else changes upstream.
 */
object SetGeometryPolicy {
    /**
     * Overlay a plan exercise's declarations on the built-in definition.
     *
     * A declaration always wins: machines of the same movement pattern really
     * do start at opposite ends, and no signal processing can tell which. An
     * omitted key falls back to what [base] already says, not to the Kotlin
     * default -- overwriting with the default would discard the built-in
     * definition.
     *
     * `sensorInverted`, `sensorOnStack` and `bodyweight` are assigned
     * unconditionally because they cannot express omission (see
     * [GeometrySources]). That is latent today, since no seed entry sets any of
     * the three; the first one that does makes it live.
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
            sensorOnStack = declared.sensorOnStack,
            bodyweight = declared.bodyweight,
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
            ),
        )
    }

    private fun source(declared: Boolean, seeded: Boolean, inferable: Boolean): GeometrySource = when {
        declared -> GeometrySource.DECLARED
        seeded -> GeometrySource.SEEDED
        inferable -> GeometrySource.INFERRED
        else -> GeometrySource.DEFAULT
    }
}
