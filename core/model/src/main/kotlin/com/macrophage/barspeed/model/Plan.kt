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
                    errors += "sessions[$si].exercises[$ei].start must be one of ${VALID_STARTS.joinToString()}"
                }
                if (exercise.concentric != null && exercise.concentric !in VALID_CONCENTRIC) {
                    errors += "sessions[$si].exercises[$ei].concentric must be \"up\" or \"down\""
                }
                exercise.travelRatio?.let {
                    if (it <= 0.0) errors += "sessions[$si].exercises[$ei].travelRatio must be positive"
                }
                if (exercise.plane != null && exercise.plane !in VALID_PLANES) {
                    errors += "sessions[$si].exercises[$ei].plane must be \"vertical\" or \"horizontal\""
                }
                if (exercise.kind != null && exercise.kind !in VALID_KINDS) {
                    errors += "sessions[$si].exercises[$ei].kind must be one of ${VALID_KINDS.joinToString()}"
                }
                if (exercise.sets.isEmpty()) errors += "sessions[$si].exercises[$ei] must contain at least one set"
                exercise.sets.forEachIndexed { xi, set ->
                    // On bodyweight work the load is what was ADDED, and a band or
                    // assist machine subtracts — so negatives are meaningful there.
                    errors += set.validate(
                        "sessions[$si].exercises[$ei].sets[$xi]",
                        allowNegativeLoad = exercise.bodyweight,
                    )
                }
            }
        }
        return errors
    }

    /**
     * Non-blocking notes shown at the import gate. A declaration always wins —
     * machines of the same movement pattern really do start at opposite ends —
     * but it is worth saying out loud when a plan pins a direction that
     * contradicts how the built-in lift is normally performed, because a
     * mis-declared direction inverts the voice guide's whole cadence.
     */
    fun warnings(): List<String> {
        val out = mutableListOf<String>()
        sessions.forEachIndexed { si, session ->
            session.exercises.forEachIndexed { ei, exercise ->
                val seed = ExerciseDef.seedById(exercise.exercise) ?: return@forEachIndexed
                val declared = exercise.startPhaseOverride ?: return@forEachIndexed
                if (declared == seed.startsWith) return@forEachIndexed
                val natural = if (seed.startsAtTop) "the top" else "the bottom"
                val asked = if (exercise.startsAtTop) "the top" else "the bottom"
                out += "sessions[$si].exercises[$ei]: ${exercise.exercise} normally starts at $natural, " +
                    "but this plan starts it at $asked — the voice guide will follow the plan."
            }
        }
        return out
    }

    companion object {
        const val SCHEMA_VERSION = "1.4"
        val SUPPORTED_SCHEMA_VERSIONS = setOf("1.0", "1.1", "1.2", "1.3", "1.4")
        val VALID_SIDES = setOf("left", "right")

        /** "top"/"bottom" name the start position; "down"/"up" the first movement. */
        val VALID_STARTS = setOf("top", "bottom", "up", "down")
        val VALID_CONCENTRIC = setOf("up", "down")
        val VALID_PLANES = setOf("vertical", "horizontal")

        /**
         * The declarable exercise kinds, lowercased [ExerciseKind] names. This
         * is the first plan vocabulary that is 1:1 with a Kotlin enum, so it is
         * the first that can be pinned in both directions rather than only
         * against the published schema.
         */
        val VALID_KINDS = setOf("dynamic", "hold", "carry", "explosive")
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
     * Where the lift begins in space, so the app knows which way the first
     * movement of every rep goes: `"top"` (squat, bench, leg curl — first
     * movement is down) or `"bottom"` (press from the rack, deadlift, leg
     * press — first movement is up). Legacy `"down"`/`"up"` name the first
     * movement directly and mean the same thing. Omitted → inferred from the id.
     *
     * Machines of the same movement pattern genuinely differ here, which is why
     * it is declared rather than inferred.
     */
    val start: String? = null,
    /**
     * Which way the concentric (driving) phase moves: `"up"` (default) or
     * `"down"` for leg curls, pulldowns and pushdowns. **Independent of
     * [start]** — that says which phase comes first, this says which direction
     * the drive goes — and it is what decides which tempo digit is the
     * eccentric.
     */
    val concentric: String? = null,
    /**
     * True when the sensor moves OPPOSITE to the load the lifter drives — the
     * usual case on a cable machine, where the sensor rides the weight stack, so
     * pulling the handle down sends the stack (and the sensor) up. Without this
     * every phase label and every velocity on that exercise is backwards.
     */
    val sensorInverted: Boolean = false,
    /**
     * True when the sensor rides the cable weight stack rather than the handle.
     * The stack travels VERTICALLY however the lifter moves, so this — not
     * [plane] — is what decides which axis is measured.
     */
    val sensorOnStack: Boolean = false,
    /**
     * Lifter-side travel per unit of sensor travel, for pulleys that do not move
     * 1:1 — a 2:1 cable moves the handle twice as far as the stack, so `2`.
     * Defaults to 1.
     */
    val travelRatio: Double? = null,
    /**
     * Which plane the movement travels in: `"vertical"` (default — squats,
     * presses, curls) or `"horizontal"` (seated rows, chest-press machines,
     * horizontal cable work). Vertical analysis measures the wrong axis
     * entirely on a horizontal machine, so declare it.
     */
    val plane: String? = null,
    /**
     * True when the lifter's own body is the load — pull-ups, dips, push-ups.
     * The set's load is then what was ADDED, and may be negative for assistance
     * (band or machine). Total load is body weight plus that.
     */
    val bodyweight: Boolean = false,
    /** Accessory work that may be dropped when a session runs long. */
    val optional: Boolean = false,
    /**
     * How the movement is performed: `"dynamic"`, `"hold"`, `"carry"` or
     * `"explosive"`. Omitted → the built-in definition if there is one, then a
     * guess from the id, which matches word fragments and gets ordinary names
     * wrong.
     *
     * This says what the movement IS, not how a set of it is measured. Whether
     * a set runs on reps or on the clock comes from the set's own shape — see
     * [PlanSetDef.isTimed]. Kind decides the peak-velocity readout, whether the
     * voice guide runs a tempo, and whether plate math applies.
     */
    val kind: String? = null,
    val sets: List<PlanSetDef>,
) {
    /** True when the drive goes up; plans may pin it, otherwise inferred from the id. */
    val concentricUp: Boolean
        get() = when (concentric) {
            "down" -> false
            "up" -> true
            // Never guessed from the id: see ExerciseDef.concentricUp.
            else -> true
        }

    /** Declared first-movement direction, when the plan pins one. */
    private val startsAtTopOverride: Boolean?
        get() = when (start) {
            "top", "down" -> true
            "bottom", "up" -> false
            else -> null
        }

    /** Where this exercise begins, declared or inferred from the id. */
    val startsAtTop: Boolean
        get() = startsAtTopOverride
            ?: ((ExerciseDef.inferStartPhase(exercise) == StartPhase.ECCENTRIC) == concentricUp)

    /** Declared kind, when the plan pins one and names a kind that exists. */
    val kindOverride: ExerciseKind?
        get() = kind?.let { declared ->
            ExerciseKind.entries.firstOrNull { it.name.equals(declared, ignoreCase = true) }
        }

    /**
     * The kind to track this exercise as. A declaration always wins — over the
     * built-in definition as well as over the guess — because the plan's author
     * knows which movement they meant and the app is guessing from a string.
     * Every disagreement is warned about at the import gate rather than
     * silently resolved.
     */
    val effectiveKind: ExerciseKind
        get() = kindOverride
            ?: ExerciseDef.seedById(exercise)?.kind
            ?: ExerciseDef.inferKind(exercise)

    /**
     * Start-phase override, when the plan pins one. Combines the two declared
     * properties: the first phase is the concentric when the direction the lift
     * starts moving is the direction the drive goes.
     */
    val startPhaseOverride: StartPhase?
        get() {
            val top = startsAtTopOverride ?: return null
            return if (top == concentricUp) StartPhase.ECCENTRIC else StartPhase.CONCENTRIC
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
    /** Commentary for this set alone (the exercise-level `notes` covers all its sets). */
    val note: String? = null,
    @SerialName("targetMeanConcentricVelocity_mps") val targetMeanConcentricVelocityMps: Double? = null,
    @SerialName("velocityLossStop_pct") val velocityLossStopPct: Double? = null,
    @SerialName("rest_s") val restS: Int? = null,
) {
    /** Canonical load in kilograms regardless of which unit the plan used. */
    val resolvedLoadKg: Double?
        get() = loadKg ?: loadLb?.let { it / WeightUnit.LB_PER_KG }

    val isTimed: Boolean get() = durationS != null

    fun validate(path: String, allowNegativeLoad: Boolean = false): List<String> {
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
        if (!allowNegativeLoad && ((loadKg ?: 0.0) < 0 || (loadLb ?: 0.0) < 0)) {
            errors += "$path load must be >= 0 (negative load means assistance, " +
                "which requires \"bodyweight\": true on the exercise)"
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
