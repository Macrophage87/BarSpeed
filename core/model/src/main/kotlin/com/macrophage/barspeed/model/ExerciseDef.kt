package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/** How an exercise is performed and therefore how a set of it is measured. */
@Serializable
enum class ExerciseKind {
    /** Rep-based barbell/dumbbell movement — velocity and tempo tracking apply. */
    DYNAMIC,

    /** Isometric hold (plank, dead hang) — measured by duration. */
    HOLD,

    /** Loaded carry (farmer's walk) — measured by duration; load still matters. */
    CARRY,

    /** Single explosive concentric (snatch, clean) — peak velocity is the metric; no tempo. */
    EXPLOSIVE,
    ;

    /** How this kind reads in a sentence written for the lifter. */
    val description: String
        get() = when (this) {
            DYNAMIC -> "a dynamic lift"
            HOLD -> "a hold"
            CARRY -> "a carry"
            EXPLOSIVE -> "an explosive lift"
        }
}

/** Exercise definition with per-exercise segmentation configuration. */
@Serializable
data class ExerciseDef(
    val id: String,
    val displayName: String,
    val startsWith: StartPhase = StartPhase.ECCENTRIC,
    val kind: ExerciseKind = ExerciseKind.DYNAMIC,
    val isCustom: Boolean = false,
    /** True for straight-bar lifts — enables the plate-loading readout. */
    val usesBarbell: Boolean = true,
    /**
     * Which way the concentric (the driving, muscle-shortening phase) moves.
     * True for almost everything; false for lifts whose drive goes DOWN — leg
     * curl, lat pulldown, triceps pushdown. Independent of [startsWith]: one
     * says which phase comes first, this says which direction that phase moves.
     *
     * **Never inferred from the exercise id.** The tempting heuristic (ids
     * containing "leg_curl" or "pulldown" drive downward) reasons about the
     * LIFTER, while the sensor usually rides the machine's weight stack and
     * moves the other way: applied to a seated leg curl in field data it
     * collapsed rep detection from 12 to 2. Direction and mount have to be
     * declared together, so this stays true until a plan says otherwise.
     */
    val concentricUp: Boolean = true,
    /**
     * True when the sensor moves opposite to the load the lifter drives — a
     * cable machine with the sensor on the weight stack, where pulling the
     * handle down drives the stack up. Every measured direction is flipped back
     * into lifter space before analysis.
     */
    val sensorInverted: Boolean = false,
    /** Lifter-side travel per unit of sensor travel (2 for a 2:1 pulley). */
    val travelRatio: Double = 1.0,
    /** True for movements that travel horizontally — seated rows, chest-press machines. */
    val horizontal: Boolean = false,
    /**
     * True when the sensor rides a cable weight stack rather than the load the
     * lifter holds. The stack moves vertically whatever plane the lifter works
     * in, so this decides which axis is measured.
     */
    val sensorOnStack: Boolean = false,
    /**
     * True when the lifter's own body is the load (pull-ups, dips). The set's
     * load is then what was ADDED, negative for band or machine assistance.
     */
    val bodyweight: Boolean = false,
) {
    val isTimed: Boolean get() = kind == ExerciseKind.HOLD || kind == ExerciseKind.CARRY

    /**
     * True when the lift begins at the top of its range, so the first movement
     * of every rep is downward. This is what the voice guide announces and what
     * the tempo digits are positioned against.
     */
    val startsAtTop: Boolean get() = (startsWith == StartPhase.ECCENTRIC) == concentricUp

    /** Maps measured sensor motion into the lifter's frame; see [sensorInverted]. */
    val sensorToLifter: Double get() = (if (sensorInverted) -1.0 else 1.0) * travelRatio

    companion object {
        val SEED: List<ExerciseDef> =
            listOf(
                ExerciseDef("back_squat", "Back Squat"),
                ExerciseDef("front_squat", "Front Squat"),
                ExerciseDef("bench_press", "Bench Press"),
                ExerciseDef("overhead_press", "Overhead Press", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("deadlift", "Deadlift", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("romanian_deadlift", "Romanian Deadlift"),
                ExerciseDef("barbell_row", "Barbell Row", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("hip_thrust", "Hip Thrust", startsWith = StartPhase.CONCENTRIC),
                ExerciseDef("plank", "Plank", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("side_plank", "Side Plank", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("dead_hang", "Dead Hang", kind = ExerciseKind.HOLD, usesBarbell = false),
                ExerciseDef("farmers_walk", "Farmer's Walk", kind = ExerciseKind.CARRY, usesBarbell = false),
                ExerciseDef("suitcase_carry", "Suitcase Carry", kind = ExerciseKind.CARRY, usesBarbell = false),
                ExerciseDef("snatch", "Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_snatch", "Power Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("clean", "Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_clean", "Power Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("push_press", "Push Press", StartPhase.ECCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef(
                    "kettlebell_swing",
                    "Kettlebell Swing",
                    StartPhase.ECCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
                ExerciseDef(
                    "kettlebell_snatch",
                    "KB Snatch",
                    StartPhase.CONCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
                ExerciseDef(
                    "kettlebell_clean",
                    "KB Clean",
                    StartPhase.CONCENTRIC,
                    ExerciseKind.EXPLOSIVE,
                    usesBarbell = false,
                ),
            )

        fun seedById(id: String): ExerciseDef? = SEED.firstOrNull { it.id == id }

        /**
         * The definition an AD-HOC set runs against -- one with no plan slot,
         * resolved from the exercise picker's id alone.
         *
         * Lifted here from `RecordState.currentExercise`, a property on a
         * data class in `:app`. It is lifted so the decision runs in
         * `:core:model`, which cannot see `RecordState` at all and so cannot
         * be pinned against it. It was lifted as the bare
         * `seedById(id) ?: ExerciseDef(id, id)` that stood there, defect
         * included, so a differential had something to red against.
         *
         * It now runs the result through [SetGeometryPolicy.bodyweightMount],
         * which is what a PLAN slot has always got via
         * [SetGeometryPolicy.resolve]: an ad-hoc set against any id in
         * [BODYWEIGHT_IDS] is body-weight work whether or not a plan declared
         * it. There is no plan here to declare anything, so `declared` is null
         * and the seed default decides. #229 item 3, and part of #61's
         * population. Only `dead_hang` of the eight is in [SEED], which is
         * `RecordState.exerciseOptions`, so it is the only one the picker can
         * reach today -- #239's three are not seed entries, so widening the
         * table changed nothing an ad-hoc set can currently exercise; it is
         * this call that makes the seed follow if the picker ever reaches
         * one.
         *
         * `copy` rather than a fresh constructor call, deliberately: the seed
         * entry for `dead_hang` carries a display name, a HOLD kind and
         * `usesBarbell = false`, and rebuilding it from `ExerciseDef(id, id)`
         * would set one flag and discard those three.
         *
         * Only `bodyweight` is seeded here. `sensorOnStack` has the same gap
         * on the same path and is not tracked anywhere; for the owner to
         * decide whether it is.
         */
        fun resolvedById(id: String): ExerciseDef {
            val base = seedById(id) ?: ExerciseDef(id, id)
            return base.copy(
                bodyweight = SetGeometryPolicy.bodyweightMount(base.id, base.bodyweight, declared = null),
            )
        }

        /**
         * Exercise ids whose machine carries the sensor on a pin-selected
         * weight stack by construction, so an omitted `sensorOnStack` on one
         * of them is an omission rather than a declaration of "on the bar".
         *
         * Deliberately NOT [SEED] entries. [SetGeometryPolicy.describe] asks
         * [seedById] once for the whole geometry object, so seeding these ids
         * would publish SEEDED for `concentric`, `plane`, `kind` and
         * `travelRatio` too -- values no entry here has decided, and on a
         * pulldown the concentric default is the wrong one. This table states
         * one fact per id and claims nothing else.
         *
         * The families, and why each rides a stack:
         *
         * - `assisted_pull_up`, `assisted_chin_up`, `assisted_dip`: the
         *   assistance is a pin-selected counterweight, and the sensor clips
         *   to the carriage it drives. This is field-37's own shape (#223).
         * - `lat_pulldown`, `seated_row`, `seated_cable_row`, `cable_row`,
         *   `triceps_pushdown`: cable stations, where the load IS the stack
         *   and the handle is only a handle. The app's own plan prompt uses a
         *   seated cable row as its worked example of the key.
         *   `seated_row` is the weakest of these. The id does not distinguish
         *   a cable station from a plate-loaded machine, and on a
         *   plate-loaded one the seed forces the measured axis to vertical
         *   for a horizontal exercise. Whether a lifter's `seated_row` plan
         *   entry names a cable station or a plate-loaded row machine is a
         *   `[Field]` question: next `seated_row` session, check the machine
         *   before recording -- on a plate-loaded row, declare
         *   `sensorOnStack: false` in the plan, because a plate stack does
         *   not ride the cable's line the way this default assumes.
         * - `leg_curl`, `seated_leg_curl`, `lying_leg_curl`, `leg_extension`:
         *   selectorised machines whose stack is the only place a sensor can
         *   ride the load.
         *
         * Leg press and hack squat sleds are deliberately ABSENT here. An
         * earlier round of `PLAN_PROMPT` in `GuideScreen.kt` disagreed,
         * naming "leg press and hack squat sleds" beside the families that
         * must declare `sensorOnStack: true`; that sentence was corrected to
         * match this table (removed the two ids, added the counter-example)
         * once the disagreement was found -- see the commit that stopped
         * telling the model to declare a stack mount on a plate-loaded sled.
         * A declared `true` still wins in [SetGeometryPolicy.resolve]
         * whether or not an id is in this table -- this table only supplies
         * the default for an OMITTED key -- and either way, `sensorOnStack`
         * forces the measured axis to vertical (`LiftDirection.measuredPlane`),
         * which on a 45-degree sled would be a claim about an axis nothing
         * here has measured.
         *
         * `rope_dead_hang` is deliberately NOT here, decided rather than
         * merely deferred. Field-37 sets 11-13 (v0.1.48) were,
         * owner-confirmed, a hang on an assist machine's rope, and that rope
         * did ride the assist stack's cable that session. But the id names
         * the grip implement, not the equipment class, on the same reasoning
         * that narrows `seated_row` above: a rope dead hang may equally be a
         * fixed rig or bar with a rope tied off for grip -- no cable, no
         * stack, no pin selection at all -- and the id cannot distinguish
         * that case from field-37's. Adding it here would
         * default every future `rope_dead_hang`, including the fixed-rig
         * one, to stack-mounted on the strength of one session. Omitting the
         * key on this id still records bar-mounted; a plan that knows better
         * must declare `sensorOnStack: true` itself.
         */
        val STACK_MOUNTED_IDS: Set<String> =
            setOf(
                "assisted_pull_up",
                "assisted_chin_up",
                "assisted_dip",
                "lat_pulldown",
                "seated_row",
                "seated_cable_row",
                "cable_row",
                "triceps_pushdown",
                "leg_curl",
                "seated_leg_curl",
                "lying_leg_curl",
                "leg_extension",
            )

        /** Whether the app ships a stack-mount default for this exact id. */
        fun ridesStack(id: String): Boolean = id.lowercase() in STACK_MOUNTED_IDS

        /**
         * Exercise ids whose load IS the lifter's own body by construction, so
         * an omitted `bodyweight` on one of them is an omission rather than a
         * declaration of "loaded work" (#61, #227).
         *
         * Deliberately NOT [SEED] entries, for [STACK_MOUNTED_IDS]'s reason:
         * [ExerciseDef.bodyweight] is the only field this table decides, and a
         * SEED entry would have to decide [kind], [startsWith], [usesBarbell]
         * and the rest too. `dead_hang` already has a SEED entry (kind HOLD)
         * that does not set `bodyweight = true` -- this table is what makes an
         * omitted key on it resolve correctly without touching that entry.
         *
         * `assisted_pull_up`, `assisted_chin_up` and `assisted_dip` are
         * deliberately ABSENT: an assist machine's counterweight takes load
         * OFF the lifter rather than the lifter's own mass being the load, and
         * those three ids are already seeded stack-mounted by
         * [STACK_MOUNTED_IDS] -- a different fact about a different id.
         *
         * `muscle_up`, `inverted_row` and `rope_dead_hang` were added by
         * #239. On all three the lifter's own body IS the load, and until
         * they were named here a plan that left the key out recorded the
         * set's `loadKg` as the ADDED load alone -- so a hang with nothing
         * added was written as `0.0`, which is the load being unknown dressed
         * as the load being nothing. `rope_dead_hang` was not hypothetical:
         * field-37 recorded three sets of it (sets 11, 12 and 13). Those rows
         * stay exactly as recorded, because nothing here rewrites a stored
         * set; what tells a reader they carry no body-weight term is the
         * ABSENCE of `bodyWeight_kg` beside their `load_kg` in the export
         * (1.19, #220), which is the signal that split exists to give.
         *
         * `rope_dead_hang` is in THIS table and still deliberately absent from
         * [STACK_MOUNTED_IDS], and the two are not in tension. The id names
         * the grip implement, so it cannot tell a rope tied off on a fixed rig
         * from one running over an assist machine's cable, and the MOUNT
         * therefore stays undeclared -- but a hang is the lifter's own body
         * either way, so the LOAD does not.
         *
         * The commit "Seed pull-ups, dips, push-ups, chin-ups and dead hangs
         * as body weight" left a follow-up list naming PlanDetailScreen and
         * GuideScreen's PLAN_PROMPT as the two sites this table did not yet
         * reach, and there was a THIRD it did not name: the AD-HOC path.
         * [resolvedById] closes that one -- it runs the seed lookup through
         * [SetGeometryPolicy.bodyweightMount], so a set started from the
         * exercise picker gets the same answer a plan slot gets. #229 item 3.
         *
         * Of the two sites that list named, GuideScreen's `PLAN_PROMPT` now
         * enumerates the ids this table holds and moves with it; the approval
         * screen still reads the raw plan declaration and is still #229 item
         * 1, so a plan that omits the key on one of these ids displays there
         * as loaded work while recording as body-weight work. Seven of the
         * eight ids here carry no SEED entry, and `dead_hang`'s SEED entry
         * does not set `bodyweight = true`, so both the seed lookup and the
         * bare constructor still need the mount policy on top -- which is
         * exactly what [resolvedById] does rather than editing SEED.
         */
        val BODYWEIGHT_IDS: Set<String> =
            setOf(
                "pull_up",
                "chin_up",
                "dip",
                "push_up",
                "dead_hang",
                "muscle_up",
                "inverted_row",
                "rope_dead_hang",
            )

        /** Whether the app ships a body-weight default for this exact id. */
        fun isBodyweightByConstruction(id: String): Boolean = id.lowercase() in BODYWEIGHT_IDS

        private val EXPLOSIVE_HINTS = listOf("swing", "snatch", "clean", "jerk", "push_press", "throw", "slam")
        private val HOLD_HINTS = listOf("plank", "hold", "hang", "wall_sit", "l_sit")
        private val CARRY_HINTS = listOf("carry", "walk", "farmer", "yoke", "sled")

        /**
         * Slow strength work built on an Olympic grip: a snatch-grip deadlift is
         * a deadlift, and a clean-grip deadlift is a deadlift. The Olympic word
         * names the grip, not the movement, so it must not win.
         *
         * "squat" is deliberately NOT here, though the obvious reading of this
         * rule puts it there. A squat clean and a squat snatch are the full
         * Olympic lifts — vetoing on "squat" sends squat_clean and squat_snatch
         * to DYNAMIC and hang_squat_clean to HOLD, via "hang". All three are
         * classified correctly today, and there is no id where a "squat" token
         * ought to veto an explosive one.
         */
        private val SLOW_LIFT_VETO = listOf("deadlift", "rdl", "romanian", "row")

        private fun tokens(id: String): List<String> = id.lowercase().split('_')

        /**
         * Whether one snake_case token names one word of a hint. Plurals count,
         * because plans write "farmers_walks" and "push_presses" as readily as
         * the singular; nothing else does, because the alternative — matching a
         * hint anywhere inside a token — is what put "row" inside "narrow" and
         * "chin" inside "machine".
         */
        private fun namesWord(token: String, word: String): Boolean =
            token == word || token == word + "s" || token == word + "es"

        /** Whether these tokens contain the hint's words, in order and adjacent. */
        private fun List<String>.name(hint: String): Boolean {
            val words = hint.split('_')
            if (words.size > size) return false
            return (0..size - words.size).any { start ->
                words.indices.all { namesWord(this[start + it], words[it]) }
            }
        }

        /**
         * Best-effort kind for exercise ids not in the seed list (LLM plans invent
         * ids freely), so e.g. "kettlebell_swing_heavy" still gets the explosive
         * UI and "pallof_hold" the timed one.
         *
         * A guess only: a plan that declares `"kind"` overrides this, and should,
         * because whole-token matching still cannot read an id like "deadhang"
         * that runs two words together.
         */
        fun inferKind(id: String): ExerciseKind {
            val tokens = tokens(id)
            val explosive = EXPLOSIVE_HINTS.any { tokens.name(it) } && SLOW_LIFT_VETO.none { tokens.name(it) }
            return when {
                explosive -> ExerciseKind.EXPLOSIVE
                HOLD_HINTS.any { tokens.name(it) } -> ExerciseKind.HOLD
                CARRY_HINTS.any { tokens.name(it) } -> ExerciseKind.CARRY
                else -> ExerciseKind.DYNAMIC
            }
        }

        private val CONCENTRIC_START_HINTS =
            listOf(
                "deadlift", "row", "curl", "pull", "chin", "shrug", "thrust",
                "overhead", "shoulder_press", "military", "raise", "snatch", "clean",
                // Movements whose own name is one word containing another hint.
                // Substring matching reached inside them for free and whole-token
                // matching cannot, so they are listed rather than lost.
                "pulldown", "pullup", "chinup", "pushdown",
            )

        /**
         * Lifts that begin with the drive (deadlifts, rows, presses from the
         * rack position) must count concentric-first — pairing ecc→con would
         * miss almost every rep. Bench-style lifts start at lockout and lower
         * first, so bare "press" stays eccentric-first.
         *
         * This is the one inference whose result is written down. The others are
         * recomputed from the id on every read, but SessionRepository.kt:308
         * stores this in a CustomExerciseEntity the first time an id is used and
         * ExerciseDao has no statement that would update it, so an id already
         * seen keeps whatever this returned then. A plan-declared `"start"`
         * overrides it per exercise.
         */
        fun inferStartPhase(id: String): StartPhase {
            val tokens = tokens(id)
            return if (CONCENTRIC_START_HINTS.any { tokens.name(it) }) {
                StartPhase.CONCENTRIC
            } else {
                StartPhase.ECCENTRIC
            }
        }

        // "db" and "kb" replace the old "db_" and "kb_". Those were written as
        // prefixes because the match was a substring search; as whole tokens
        // they are unambiguous under either reading, which is the only reason
        // to prefer one spelling of a hint over another.
        private val NON_BARBELL_HINTS =
            listOf("dumbbell", "db", "kettlebell", "kb", "cable", "machine", "band", "bodyweight", "smith")

        /** Plate math only applies to straight-bar lifts. */
        fun inferBarbell(id: String): Boolean {
            if (NON_BARBELL_HINTS.any { tokens(id).name(it) }) return false
            return inferKind(id) == ExerciseKind.DYNAMIC || inferKind(id) == ExerciseKind.EXPLOSIVE
        }
    }
}
