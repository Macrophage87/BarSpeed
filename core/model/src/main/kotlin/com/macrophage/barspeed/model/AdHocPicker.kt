package com.macrophage.barspeed.model

/**
 * One recorded set as the exercise picker reads it: which lift, what was on
 * it, and when. Built in `:app` from a `set_records` row; nothing here reads
 * Room.
 *
 * [addedKg] is on the ADDED scale the load box takes, never the
 * body-weight-inclusive one the row stores: `loadKg - bodyWeightKg` on
 * body-weight work, `loadKg` otherwise.
 */
data class RecordedLift(
    val exerciseId: String,
    val addedKg: Double,
    val reps: Int?,
    val durationS: Int?,
    val tempo: String?,
    val side: String?,
    val warmup: Boolean,
    val voided: Boolean,
    val endedAtMs: Long,
)

/** What the ad-hoc boxes are filled with when an exercise is picked. */
data class AdHocPrefill(
    val addedKg: Double,
    val reps: Int,
    val durationS: Int,
    /** Null when there is nothing to carry: the tempo box is left empty. */
    val tempo: String?,
)

/**
 * The ad-hoc exercise picker: which exercises it offers, in what order, and
 * what picking one fills the boxes with (UX review 2026-11-06, record-setup
 * F2/F3/F12, core-model F2/F3).
 *
 * Pure, so the ordering and the prefill source run under a test on every push;
 * the screen and the view model only render and store the answers.
 */
object AdHocPicker {
    /**
     * The load box's opening number for a loaded lift nobody has recorded, in
     * the lifter's own unit -- the "60" the box has always opened on.
     */
    const val DEFAULT_LOAD_IN_UNIT = 60.0

    /** The reps box's opening number for a lift with no recorded set. */
    const val DEFAULT_REPS = 5

    /** The hold box's opening number for a timed lift with no recorded set. */
    const val DEFAULT_HOLD_S = 60

    /**
     * Built-in lifts that are done one side at a time. Everything else in
     * [ExerciseDef.SEED] uses both sides together, so a Both/Left/Right choice
     * under it is noise.
     */
    val ONE_SIDED_SEED_IDS: Set<String> = setOf("suitcase_carry", "side_plank")

    /** Words in an id that name a one-sided lift whatever else it is. */
    private val ONE_SIDED_ID_WORDS = listOf("single_arm", "one_arm", "single_leg", "unilateral")

    /**
     * Every exercise the picker offers, the lifter's own first: lifts he has
     * recorded, most recent first; then the active plan's lifts in plan order;
     * then the built-in [seeds]. Each id once, at its first place.
     *
     * Each id is resolved through [ExerciseDef.resolvedById], the definition
     * an ad-hoc set runs against, so the chip and the set agree on the kind.
     * Voided sets do not count as recent: the lifter said they were not real.
     */
    fun options(
        lifts: List<RecordedLift>,
        plan: List<PlanSessionDef>,
        seeds: List<ExerciseDef> = ExerciseDef.SEED,
    ): List<ExerciseDef> = seeds

    /**
     * The last time the lifter did [exerciseId]: his latest working set of it,
     * or his latest warm-up when he has only warmed up on it. Voided sets are
     * never a source.
     */
    fun lastLift(lifts: List<RecordedLift>, exerciseId: String): RecordedLift? = null

    /**
     * What the boxes open on for [exercise]: [last]'s numbers when there is a
     * last time, else the defaults. A body-weight lift or a hold defaults to 0
     * added, never [DEFAULT_LOAD_IN_UNIT]: 60 on a dead hang records body
     * weight plus 60.
     */
    fun prefill(exercise: ExerciseDef, last: RecordedLift?, unit: WeightUnit): AdHocPrefill = AdHocPrefill(
        addedKg = unit.toKg(DEFAULT_LOAD_IN_UNIT),
        reps = DEFAULT_REPS,
        durationS = DEFAULT_HOLD_S,
        tempo = null,
    )

    /** True for a lift whose load box opens on 0 rather than the loaded default. */
    fun startsUnloaded(exercise: ExerciseDef): Boolean = exercise.bodyweight || exercise.kind == ExerciseKind.HOLD

    /**
     * Whether the ad-hoc form offers Both/Left/Right for [exerciseId]: a
     * one-sided built-in, an id that names one side, or a lift the lifter has
     * recorded, or the plan prescribes, one side at a time.
     */
    fun offersSide(exerciseId: String, lifts: List<RecordedLift>, plan: List<PlanSessionDef>): Boolean = true

    /** The active plan's first declaration of [exerciseId], or null when the plan does not list it. */
    fun planDeclaration(exerciseId: String, plan: List<PlanSessionDef>): PlanExerciseDef? =
        plan.asSequence().flatMap { it.exercises.asSequence() }.firstOrNull { it.exercise == exerciseId }

    /**
     * The definition and geometry an OFF-PLAN set inside a plan session runs
     * against (record-setup F2).
     *
     * When the active plan declares the lift anywhere, its declaration is
     * overlaid exactly as a planned set of it gets ([SetGeometryPolicy.plannedExercise]
     * over the same base `SessionRepository.exerciseById` builds for an id
     * with no stored row), so an extra lat pulldown keeps the plan's drive
     * direction and stack mount. Otherwise it is the ad-hoc definition,
     * [ExerciseDef.resolvedById], described with no declaration.
     */
    fun offPlanExercise(exerciseId: String, plan: List<PlanSessionDef>): PlannedExercise {
        val declared = planDeclaration(exerciseId, plan)
        if (declared != null) {
            val base = ExerciseDef.seedById(exerciseId) ?: ExerciseDef.inferred(exerciseId)
            return SetGeometryPolicy.plannedExercise(base, declared)
        }
        val exercise = ExerciseDef.resolvedById(exerciseId)
        return PlannedExercise(exercise, SetGeometryPolicy.describe(exercise, null), stackRuleApplied = false)
    }
}
