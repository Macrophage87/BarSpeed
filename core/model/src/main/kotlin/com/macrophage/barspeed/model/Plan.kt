package com.macrophage.barspeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Root of an imported training plan; contract is docs/schemas/plan.schema.json. */
@Serializable
data class PlanFile(
    val schemaVersion: String,
    val planName: String,
    val notes: String? = null,
    val sessions: List<PlanSessionDef>,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            errors += "Unsupported schemaVersion '$schemaVersion' " +
                "(expected one of ${SUPPORTED_SCHEMA_VERSIONS.joinToString()})"
        }
        if (planName.isBlank()) errors += "planName must not be blank"
        if (sessions.isEmpty()) errors += "Plan must contain at least one session"
        sessions.forEachIndexed { si, session ->
            if (session.name.isBlank()) errors += "sessions[$si].name must not be blank"
            if (session.exercises.isEmpty()) errors += "sessions[$si] must contain at least one exercise"
            session.exercises.forEachIndexed { ei, exercise ->
                if (exercise.exercise.isBlank()) errors += "sessions[$si].exercises[$ei].exercise must not be blank"
                if (exercise.start != null && exercise.start !in VALID_STARTS) {
                    errors += "sessions[$si].exercises[$ei].start must be \"up\" or \"down\""
                }
                exercise.contradictoryTempoStartError()?.let {
                    errors += "sessions[$si].exercises[$ei]: $it"
                }
                if (exercise.sets.isEmpty()) errors += "sessions[$si].exercises[$ei] must contain at least one set"
                exercise.sets.forEachIndexed { xi, set ->
                    errors += set.validate("sessions[$si].exercises[$ei].sets[$xi]")
                }
            }
        }
        return errors
    }

    companion object {
        const val SCHEMA_VERSION = "1.2"
        val SUPPORTED_SCHEMA_VERSIONS = setOf("1.0", "1.1", "1.2")
        val VALID_SIDES = setOf("left", "right")
        val VALID_STARTS = setOf("up", "down")
    }
}

@Serializable
data class PlanSessionDef(
    val name: String,
    val notes: String? = null,
    val exercises: List<PlanExerciseDef>,
)

@Serializable
data class PlanExerciseDef(
    val exercise: String,
    val notes: String? = null,
    /**
     * Which direction the lift starts: "up" (press, deadlift — count keys on
     * the concentric) or "down" (squat, bench). Omitted → inferred from the id.
     */
    val start: String? = null,
    val sets: List<PlanSetDef>,
) {
    /** Start-phase override, when the plan pins one. */
    val startPhaseOverride: StartPhase?
        get() = when (start) {
            "up" -> StartPhase.CONCENTRIC
            "down" -> StartPhase.ECCENTRIC
            else -> null
        }

    /**
     * A start override that contradicts how a known lift is performed is only
     * meaningful for drive-keyed counting on sets WITHOUT tempo. With tempo,
     * the voice guide would call the first phase from the wrong end of the
     * movement (e.g. "Up" while sitting at bench lockout) — reject the combo.
     * Custom ids are not second-guessed: inference is heuristic there.
     */
    fun contradictoryTempoStartError(): String? {
        val seed = ExerciseDef.seedById(exercise) ?: return null
        val override = startPhaseOverride ?: return null
        if (override == seed.startsWith) return null
        if (sets.none { it.tempo != null }) return null
        val natural =
            if (seed.startsWith == StartPhase.ECCENTRIC) "the top (down-first)" else "the bottom (up-first)"
        return "tempo sets on $exercise start from $natural — omit \"start\" for tempo work; " +
            "use the override only on sets without tempo (drive-keyed counting)"
    }
}

@Serializable
data class PlanSetDef(
    /** Rep count for dynamic sets; exactly one of reps / duration_s must be present. */
    val reps: Int? = null,
    /** Duration for timed sets (planks, carries); exactly one of reps / duration_s. */
    @SerialName("duration_s") val durationS: Int? = null,
    /** Load in kilograms; at most one of load_kg / load_lb. Omit both for bodyweight. */
    @SerialName("load_kg") val loadKg: Double? = null,
    /** Load in pounds; converted to kilograms on import. */
    @SerialName("load_lb") val loadLb: Double? = null,
    val tempo: String? = null,
    /** For unilateral work: "left" or "right". Emit one set per side. */
    val side: String? = null,
    @SerialName("targetMeanConcentricVelocity_mps") val targetMeanConcentricVelocityMps: Double? = null,
    @SerialName("velocityLossStop_pct") val velocityLossStopPct: Double? = null,
    @SerialName("rest_s") val restS: Int? = null,
) {
    /** Canonical load in kilograms regardless of which unit the plan used. */
    val resolvedLoadKg: Double?
        get() = loadKg ?: loadLb?.let { it / WeightUnit.LB_PER_KG }

    val isTimed: Boolean get() = durationS != null

    fun validate(path: String): List<String> {
        val errors = mutableListOf<String>()
        if (reps == null && durationS == null) {
            errors += "$path must have reps (dynamic set) or duration_s (hold/carry)"
        }
        if (reps != null && durationS != null) {
            errors += "$path must not have both reps and duration_s"
        }
        reps?.let { if (it <= 0) errors += "$path.reps must be positive" }
        durationS?.let { if (it <= 0) errors += "$path.duration_s must be positive" }
        if (loadKg != null && loadLb != null) {
            errors += "$path must not have both load_kg and load_lb"
        }
        if ((loadKg ?: 0.0) < 0 || (loadLb ?: 0.0) < 0) {
            errors += "$path load must be >= 0"
        }
        if (tempo != null && durationS != null) {
            errors += "$path.tempo does not apply to timed sets"
        }
        if (side != null && side !in PlanFile.VALID_SIDES) {
            errors += "$path.side must be \"left\" or \"right\""
        }
        tempo?.let {
            if (Tempo.parseOrNull(it) == null) errors += "$path.tempo '$it' is not valid tempo notation"
        }
        velocityLossStopPct?.let {
            if (it <= 0 || it > 100) errors += "$path.velocityLossStop_pct must be in (0, 100]"
        }
        return errors
    }
}
