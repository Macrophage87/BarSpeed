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
     * True for a squat: a rep-counted lift that lowers first and whose id
     * names the word "squat" -- back, front, box, pause, goblet, overhead,
     * split, hack. A squat clean or squat snatch is not one: both start with
     * the pull, so [startsWith] is concentric, and they are explosive.
     *
     * It decides one thing, which live rep counter runs (`LiveCounterPolicy`).
     * The counter it selects was fitted on the owner's back squat alone
     * (2026-11-06), so every other squat here is reached by name, not by
     * measurement; on bench press and Romanian deadlift captures that counter
     * did worse than the full-cycle one, which is why it stops at the word.
     */
    val isSquat: Boolean
        get() = kind == ExerciseKind.DYNAMIC && startsWith == StartPhase.ECCENTRIC && tokens(id).name("squat")

    /**
     * True when the lift begins at the top of its range, so the first movement
     * of every rep is downward. This is what the voice guide announces and what
     * the tempo digits are positioned against.
     */
    val startsAtTop: Boolean get() = Companion.startsAtTop(startsWith, concentricUp)

    /** Maps measured sensor motion into the lifter's frame; see [sensorInverted]. */
    val sensorToLifter: Double get() = (if (sensorInverted) -1.0 else 1.0) * travelRatio

    companion object {
        /**
         * [ExerciseDef.startsAtTop] for a caller holding the two fields that
         * decide it rather than a whole definition.
         *
         * The property above delegates here, so the rule is stated once. It is
         * a function because the prep countdown asks it while holding a start
         * phase and a drive direction read off a plan slot
         * ([StartCuePolicy], #241), and a second copy of the
         * expression written at that call site would be a second fact able to
         * disagree with this one.
         *
         * `LiftDirection.startsAtTop` in `:core:dsp` is a THIRD copy of the
         * same expression, left standing rather than folded in here: it is
         * numerically sensitive DSP code and this change has no behavioural
         * business in it. The two are pinned equal by
         * `StartCueVoiceContractTest`, which is the module that can see both.
         */
        fun startsAtTop(startsWith: StartPhase, concentricUp: Boolean): Boolean =
            (startsWith == StartPhase.ECCENTRIC) == concentricUp

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
                ExerciseDef("plank", "Plank", kind = ExerciseKind.HOLD),
                ExerciseDef("side_plank", "Side Plank", kind = ExerciseKind.HOLD),
                ExerciseDef("dead_hang", "Dead Hang", kind = ExerciseKind.HOLD),
                // The load travels with the walker (#63). A carry's plane is
                // read by its published geometry, the import gate's inversion
                // line, the tempo digit roles and the live tracker's axis.
                // Neither shape of carry reaches the rep segmenter
                // (RepAnalysisPolicy), so the bar-power figure, whose gate has
                // no plane clause, is never computed on this axis.
                ExerciseDef("farmers_walk", "Farmer's Walk", kind = ExerciseKind.CARRY, horizontal = true),
                ExerciseDef("suitcase_carry", "Suitcase Carry", kind = ExerciseKind.CARRY, horizontal = true),
                ExerciseDef("snatch", "Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_snatch", "Power Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("clean", "Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("power_clean", "Power Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("push_press", "Push Press", StartPhase.ECCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("kettlebell_swing", "Kettlebell Swing", StartPhase.ECCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("kettlebell_snatch", "KB Snatch", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
                ExerciseDef("kettlebell_clean", "KB Clean", StartPhase.CONCENTRIC, ExerciseKind.EXPLOSIVE),
            )

        fun seedById(id: String): ExerciseDef? = SEED.firstOrNull { it.id == id }

        /**
         * The display name the app derives for an id it ships no entry for:
         * underscores to spaces, first letter capitalised -- `cable_fly` is
         * "Cable fly". Stated once here because two writers used to spell it
         * out separately, `SessionRepository.exerciseById`'s fallback and the
         * `CustomExerciseEntity` row `ensureExerciseExists` writes, and
         * [CustomExerciseRepair] compares a stored row against it.
         *
         * A whole word that is one of [ABBREVIATIONS], or its plural, is
         * written in capitals -- `dumbbell_rdl` is "Dumbbell RDL", `kb_swing`
         * "KB swing" (core-model review 2026-11-06, F7). Every other word keeps
         * the sentence case above, so rows already stored for ids without an
         * abbreviation still match and are not rewritten.
         */
        fun inferredDisplayName(id: String): String = id.split('_')
            .joinToString(" ", transform = ::abbreviationInCapitals)
            .replaceFirstChar { it.uppercase() }

        private fun abbreviationInCapitals(word: String): String {
            val singular = word.removeSuffix("s")
            return when {
                word.lowercase() in ABBREVIATIONS -> word.uppercase()
                singular != word && singular.lowercase() in ABBREVIATIONS -> singular.uppercase() + "s"
                else -> word
            }
        }

        /** Abbreviations plan ids use for a whole word, shown in capitals. */
        private val ABBREVIATIONS = setOf("rdl", "sldl", "db", "kb", "ohp", "rfe", "rfess", "ez", "trx", "ghd")

        /**
         * The definition the app builds for an id it ships no [SEED] entry for
         * and has stored no row for: every inference the app makes from an id,
         * and nothing else.
         *
         * Lifted from `SessionRepository.exerciseById`'s fallback branch,
         * which built exactly this inline, so the plan path's base for an
         * unseen id is now a function `:core:model` can pin. [resolvedById]
         * falls back to this too since #69 item 4, so the AD-HOC path and the
         * plan path build one definition for an unseen id.
         *
         * [sensorOnStack] is the [STACK_MOUNTED_IDS] default for the omitted
         * key, as it is on the plan path; [bodyweight] is NOT seeded here,
         * because the plan path seeds it in [SetGeometryPolicy.resolve]
         * through [SetGeometryPolicy.bodyweightMount], and seeding it twice
         * would be two statements of one rule.
         */
        fun inferred(id: String): ExerciseDef = ExerciseDef(
            id,
            inferredDisplayName(id),
            inferStartPhase(id),
            kind = inferKind(id),
            isCustom = true,
            sensorOnStack = ridesStack(id),
        )

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
         * population. Only `dead_hang` of the eight is in [SEED]; the others
         * reach the picker since the UX review of 2026-11-06, which builds
         * `RecordState.exerciseOptions` from the lifter's recent lifts and the
         * active plan before the seeds (`AdHocPicker.options`, each id through
         * this function). So a pull-up he has recorded or a plan lists is
         * offered, and this call is what makes it body-weight work there.
         *
         * `copy` rather than a fresh constructor call, deliberately: the seed
         * entry for `dead_hang` carries a display name and a HOLD kind, and
         * rebuilding it from scratch would set one flag and discard both.
         *
         * An id with no seed entry falls back to [inferred], the definition
         * `SessionRepository.exerciseById` builds for the same id on the plan
         * path (#69 item 4). It used to fall back to the bare
         * `ExerciseDef(id, id)`, which skipped the start phase, kind and stack
         * inferences, so one id meant two exercises depending on which path
         * asked. The stack default comes with it, which is #229 item 3's
         * `sensorOnStack` half: an ad-hoc set against a [STACK_MOUNTED_IDS]
         * id rides the stack as a plan slot does. Both are LIVE since the
         * picker offers recent and planned ids as well as [SEED] (UX review
         * 2026-11-06): an ad-hoc lat pulldown resolves here. What this path
         * still lacks is a plan's declarations -- an ad-hoc set has none, so a
         * lift whose drive goes down keeps `concentricUp = true` here, where an
         * off-plan set of it inside a plan session takes the plan's
         * (`AdHocPicker.offPlanExercise`).
         */
        fun resolvedById(id: String): ExerciseDef {
            val base = seedById(id) ?: inferred(id)
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
         * Exercise ids whose DRIVE goes downward, so the default
         * [concentricUp] `true` is the wrong one and an omitted `concentric`
         * on one of them is an omission rather than a declaration of
         * "drives up".
         *
         * DERIVED FROM TWO STATEMENTS THE APP ALREADY MAKES, and from
         * nothing else. A third statement agrees and is the canonical
         * user-facing copy: GuideScreen.PLAN_PROMPT already tells the model
         * "ALWAYS DECLARE "down" ON A LEG CURL, LAT PULLDOWN OR TRICEPS
         * PUSHDOWN". This table warns about the plans that ignored it.
         * The families are [concentricUp]'s own KDoc -- lifts
         * whose drive goes down, "leg curl, lat pulldown, triceps pushdown"
         * -- which the published plan schema states in the same words at
         * `$defs.exercise.concentric`: "Use 'down' for leg curls, lat
         * pulldowns and pushdowns". The IDS are those three families' entries
         * in [STACK_MOUNTED_IDS], so this table mints no id of its own, and
         * `DriveDownFamilyTest` pins it as a subset of that one.
         *
         * Five of [STACK_MOUNTED_IDS]' twelve are here; the other SEVEN are
         * deliberately ABSENT, and they are why this is a second table rather
         * than a reading of the first. `seated_row`, `seated_cable_row` and
         * `cable_row` are horizontal pulls, with no up or down for a drive
         * direction to be wrong about; `leg_extension` drives UP, so the
         * default is right for it. The three assist-machine ids are absent
         * for the same reason as the leg extension -- an assisted pull-up
         * drives up, and the counterweight travelling the other way is what
         * `sensorInverted` describes, not this. `DriveDownFamilyTest` pins
         * all seven by name.
         *
         * NOT [SEED] entries, for [STACK_MOUNTED_IDS]' reason, and that
         * premise is pinned rather than defended in code: no id here has a
         * seed entry, so there is no built-in drive direction for an omitted
         * key to fall back to and the type default really does stand. If one
         * is ever seeded, `DriveDownFamilyTest` reds and the warning this
         * table feeds has to be re-decided.
         *
         * This table decides NOTHING about how a set resolves. A plan that
         * omits `concentric` on a lat pulldown still records drive-up and
         * still publishes that as [GeometrySource.DEFAULT]; guessing the
         * drive from the id stays refused, for the reason [concentricUp]'s
         * KDoc gives -- applied to a seated leg curl in field data it
         * collapsed rep detection from 12 to 2. All this table does is let
         * the import gate say out loud that the plan did not say (#263).
         */
        val DRIVE_DOWN_IDS: Set<String> =
            setOf(
                "lat_pulldown",
                "triceps_pushdown",
                "leg_curl",
                "seated_leg_curl",
                "lying_leg_curl",
            )

        /**
         * Whether the app knows this exact id names a lift whose drive goes
         * DOWN. [ridesStack]'s shape: an exact match against a shipped table,
         * lowercased, never a reading of the words in an id.
         */
        fun drivesDown(id: String): Boolean = id.lowercase() in DRIVE_DOWN_IDS

        /**
         * Exercise ids whose load IS the lifter's own body by construction, so
         * an omitted `bodyweight` on one of them is an omission rather than a
         * declaration of "loaded work" (#61, #227).
         *
         * Deliberately NOT [SEED] entries, for [STACK_MOUNTED_IDS]'s reason:
         * [ExerciseDef.bodyweight] is the only field this table decides, and a
         * SEED entry would have to decide [kind], [startsWith] and the rest
         * too. `dead_hang` already has a SEED entry (kind HOLD)
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

        // "plank" and "sled" are read in inferKind, each with its own veto.
        private val HOLD_HINTS = listOf("hold", "hang", "wall_sit", "l_sit")
        private val CARRY_HINTS = listOf("carry", "walk", "farmer", "yoke")

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
            val plankHeld = tokens.name("plank") && PLANK_REP_WORK.none { tokens.name(it) }
            val sledMoved = tokens.name("sled") && SLED_MACHINE.none { tokens.name(it) }
            return when {
                explosive -> ExerciseKind.EXPLOSIVE
                plankHeld || HOLD_HINTS.any { tokens.name(it) } -> ExerciseKind.HOLD
                sledMoved || CARRY_HINTS.any { tokens.name(it) } -> ExerciseKind.CARRY
                else -> ExerciseKind.DYNAMIC
            }
        }

        /**
         * Rep work done FROM a plank -- a plank row, a side-plank hip dip --
         * is counted in reps, not held for time, so "plank" alone must not
         * make it a HOLD. Scoped to the plank: `inverted_row_hold` is still a
         * hold through "hold" (core-model review 2026-11-06, F4).
         */
        private val PLANK_REP_WORK = listOf("row", "dip")

        /**
         * A leg-press or hack-squat sled is a machine the lifter presses or
         * squats on, not a sled pushed across the floor, so "sled" beside one
         * of these words is not a CARRY (F4).
         */
        private val SLED_MACHINE = listOf("press", "squat")

        private val CONCENTRIC_START_HINTS =
            listOf(
                "deadlift", "row", "curl", "pull", "chin", "shrug", "thrust",
                "overhead", "shoulder_press", "military", "raise", "snatch", "clean",
                // Movements whose own name is one word containing another hint.
                // Substring matching reached inside them for free and whole-token
                // matching cannot, so they are listed rather than lost.
                "pulldown", "pullup", "chinup", "pushdown",
                // A muscle-up begins with the pull out of a dead hang and has no
                // lowering before it (#254). Two adjacent whole tokens, so "up"
                // alone (push_up, sit_up) and "muscle" alone (muscle_snatch)
                // move nothing.
                "muscle_up",
            )

        /**
         * Lifts that begin with the drive (deadlifts, rows, presses from the
         * rack position) must count concentric-first — pairing ecc→con would
         * miss almost every rep. Bench-style lifts start at lockout and lower
         * first, so bare "press" stays eccentric-first.
         *
         * This is the one inference whose result is written down. The others are
         * recomputed from the id on every read, but
         * SessionRepository.ensureExerciseExists stores this in a
         * CustomExerciseEntity and rewrites the row when [CustomExerciseRepair]
         * finds it no longer matches today's answer (#59). A plan-declared
         * `"start"` overrides it per exercise.
         */
        fun inferStartPhase(id: String): StartPhase {
            val tokens = tokens(id)
            tableStart(tokens)?.let { return it }
            return when {
                LOWERING_FIRST_VETO.any { tokens.name(it) } -> StartPhase.ECCENTRIC
                CONCENTRIC_START_HINTS.any { tokens.name(it) } -> StartPhase.CONCENTRIC
                else -> StartPhase.ECCENTRIC
            }
        }

        /**
         * Exact ids whose start the hints get backwards, checked against where
         * each lift begins rather than against a word in its id. Wins over
         * every hint, the way [STACK_MOUNTED_IDS] states one fact per id
         * (core-model review 2026-11-06, F1).
         *
         * CONCENTRIC: the lifter starts with the load at rest and the first
         * movement is the drive -- the leg extension's stack is down with the
         * knees bent, a step-up starts on the floor, a glute bridge and a
         * sit-up start lying down, the pec deck starts with the arms open and
         * hip abduction with the legs closed, a kickback starts bent (the
         * elbow, or the hip on a glute kickback), and a seated dumbbell or
         * Arnold press starts with the bells at the shoulders, as
         * `shoulder_press` already does through its hint.
         *
         * ECCENTRIC: the load starts overhead or the body starts upright, and
         * the first movement lowers it -- "overhead" is a hint for the press
         * and "curl" for the arm curl, and neither describes these.
         *
         * An id that is a table id behind only [START_MODIFIERS] words takes
         * the table's answer too -- `dumbbell_step_up`, `seated_leg_extension`,
         * `machine_hip_abduction`, `cable_kickback` -- see [tableStart].
         *
         * Every value here reaches stored rows through [CustomExerciseRepair],
         * which compares a row against this function.
         */
        private val START_PHASE_BY_ID: Map<String, StartPhase> =
            listOf(
                "leg_extension", "step_up", "glute_bridge", "sit_up", "hip_abduction", "pec_deck",
                "tricep_kickback", "triceps_kickback", "kickback", "glute_kickback",
                "seated_dumbbell_press", "arnold_press",
            ).associateWith { StartPhase.CONCENTRIC } +
                listOf(
                    "overhead_squat", "overhead_tricep_extension", "overhead_triceps_extension",
                    "nordic_curl", "jefferson_curl",
                ).associateWith { StartPhase.ECCENTRIC }

        /**
         * Equipment and position words that do not change where a lift
         * starts. Deliberately short, and every word on it is a modifier IN
         * FRONT of the movement: "lateral", "jump" or "deficit" are not here,
         * because each can change the movement, so `lateral_step_up` keeps
         * the hint answer and should declare its start.
         */
        private val START_MODIFIERS =
            setOf(
                "dumbbell", "db", "barbell", "kettlebell", "kb", "cable", "machine", "band", "banded",
                "seated", "weighted", "single", "leg", "arm", "smith",
            )

        /**
         * The free-weight subset of [START_MODIFIERS], the only words that may
         * prefix an ECCENTRIC table id. A free-weight overhead extension starts
         * locked out and lowers first; on a cable, band or machine the elbows
         * start bent behind the head and the first move is the extension, so
         * `cable_overhead_triceps_extension` keeps the "overhead" hint's
         * CONCENTRIC.
         */
        private val FREE_WEIGHT_MODIFIERS =
            setOf("dumbbell", "db", "barbell", "kettlebell", "kb", "seated", "weighted", "single", "arm", "smith")

        /**
         * [START_PHASE_BY_ID]'s answer for these tokens: the whole id, or a
         * table id preceded only by modifier words -- [START_MODIFIERS] for a
         * CONCENTRIC entry, [FREE_WEIGHT_MODIFIERS] for an ECCENTRIC one.
         * Matching is on whole tokens at the END of the id, so a table id with
         * words after it (`step_up_jump`) is not matched.
         */
        private fun tableStart(tokens: List<String>): StartPhase? {
            for (k in tokens.indices) {
                val prefix = tokens.take(k)
                if (prefix.any { it !in START_MODIFIERS }) return null
                val start = START_PHASE_BY_ID[tokens.drop(k).joinToString("_")] ?: continue
                val allowed = start == StartPhase.CONCENTRIC || prefix.all { it in FREE_WEIGHT_MODIFIERS }
                return if (allowed) start else null
            }
            return null
        }

        /**
         * Words that make any id an RDL-style lift, which starts standing and
         * lowers first whatever else the id says -- the seed's
         * `romanian_deadlift` is ECCENTRIC, and without this "deadlift" and
         * "snatch" made `dumbbell_romanian_deadlift` and `snatch_grip_rdl`
         * CONCENTRIC. `stiff_leg` rather than `stiff`, so a straight-arm
         * `stiff_arm_pulldown` keeps its drive-first answer.
         */
        private val LOWERING_FIRST_VETO = listOf("romanian", "rdl", "sldl", "stiff_leg", "stiff_legged")
    }
}
