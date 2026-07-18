package com.macrophage.accelerometerlifting.model

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
        if (schemaVersion != SCHEMA_VERSION) {
            errors += "Unsupported schemaVersion '$schemaVersion' (expected $SCHEMA_VERSION)"
        }
        if (planName.isBlank()) errors += "planName must not be blank"
        if (sessions.isEmpty()) errors += "Plan must contain at least one session"
        sessions.forEachIndexed { si, session ->
            if (session.name.isBlank()) errors += "sessions[$si].name must not be blank"
            if (session.exercises.isEmpty()) errors += "sessions[$si] must contain at least one exercise"
            session.exercises.forEachIndexed { ei, exercise ->
                if (exercise.exercise.isBlank()) errors += "sessions[$si].exercises[$ei].exercise must not be blank"
                if (exercise.sets.isEmpty()) errors += "sessions[$si].exercises[$ei] must contain at least one set"
                exercise.sets.forEachIndexed { xi, set ->
                    val path = "sessions[$si].exercises[$ei].sets[$xi]"
                    if (set.reps <= 0) errors += "$path.reps must be positive"
                    if (set.loadKg < 0) errors += "$path.load_kg must be >= 0"
                    set.tempo?.let {
                        if (Tempo.parseOrNull(it) == null) errors += "$path.tempo '$it' is not valid tempo notation"
                    }
                    set.velocityLossStopPct?.let {
                        if (it <= 0 || it > 100) errors += "$path.velocityLossStop_pct must be in (0, 100]"
                    }
                }
            }
        }
        return errors
    }

    companion object {
        const val SCHEMA_VERSION = "1.0"
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
    val sets: List<PlanSetDef>,
)

@Serializable
data class PlanSetDef(
    val reps: Int,
    @SerialName("load_kg") val loadKg: Double,
    val tempo: String? = null,
    @SerialName("targetMeanConcentricVelocity_mps") val targetMeanConcentricVelocityMps: Double? = null,
    @SerialName("velocityLossStop_pct") val velocityLossStopPct: Double? = null,
    @SerialName("rest_s") val restS: Int? = null,
)
