package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization sweep over the two id-based inferences in [ExerciseDef].
 *
 * Twenty rows moved when whole-token matching replaced substring matching.
 * They were written here first, against the substring matcher, so every one
 * of them failed before it passed — which is the only way to know a row is
 * pinned to the behaviour and not to the implementation. Each of the twenty
 * is named in the section comments below as a fix, an accepted loss, or a
 * question nobody has answered.
 *
 * Both inferences are swept together because they share one mechanism — a
 * hint list matched against the id.
 *
 * The two differ in one way that matters more than the arithmetic. `kind` is
 * re-derived from the id on every read -- `SessionRepository.exerciseById`
 * reaches `inferKind` on both of its non-seed branches, through
 * `CustomExerciseRepair.definition` and `ExerciseDef.inferred` -- so correcting it
 * corrects history too. `startsWith` is stored:
 * `SessionRepository.ensureExerciseExists` writes `inferStartPhase(id).name`
 * into a `CustomExerciseEntity` the first time an id is seen. Since #59
 * `exerciseById` reads that row through `CustomExerciseRepair.definition`,
 * and `ensureExerciseExists` rewrites it when it no longer matches today's
 * inference, so a corrected start reaches the next plan queue built.
 */
class ExerciseIdInferenceCharacterizationTest {
    private data class Row(
        val id: String,
        val kind: ExerciseKind,
        val start: StartPhase,
    )

    private val sweep =
        listOf(
            // --- issue #24's reported cases: fixes. ---
            Row("hanging_leg_raise", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("hanging_knee_raise", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("walking_lunge", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_walking_lunge", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("snatch_grip_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("clean_grip_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            // An RDL starts standing and lowers first, as the seed's
            // romanian_deadlift says; "snatch" named the grip and used to win
            // the start phase too. Moved to ECCENTRIC by the RDL veto (core-
            // model review 2026-11-06, F1).
            Row("snatch_grip_rdl", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),

            // --- Olympic variants. These are the reason the slow-lift veto
            // does NOT contain "squat", though issue #24's fix section says it
            // should: vetoing on "squat" sends squat_clean and squat_snatch to
            // DYNAMIC and hang_squat_clean to HOLD via "hang". They are correct
            // today and must stay correct.
            //
            // The last two rows are why the veto is a plain token test rather
            // than a positional one. Scoping it to "slow token AFTER explosive
            // token" would keep the squat variants and lose these two, which
            // are ordinary slow deadlift work. Both are FIXES and both move,
            // EXPLOSIVE to DYNAMIC, alongside the seven from issue #24 -- they
            // are counted there, not treated as a separate argument. ---
            Row("squat_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("hang_squat_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("squat_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("hang_power_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("hang_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("hang_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("clean_pull", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("snatch_pull", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("clean_and_jerk", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("power_clean_from_blocks", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("romanian_deadlift_snatch_grip", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("deadlift_snatch_grip", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- every seed id, which resolves by lookup before inference ever runs ---
            Row("back_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("front_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("overhead_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            // The inference now agrees with the seed (ECCENTRIC); it used to
            // say CONCENTRIC through "deadlift", which PlanExerciseDef.startsAtTop
            // reads for a seeded id with a declared drive (F1).
            Row("romanian_deadlift", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("barbell_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("hip_thrust", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("plank", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("side_plank", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("dead_hang", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("farmers_walk", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("suitcase_carry", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("power_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("power_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("push_press", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("kettlebell_swing", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("kettlebell_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("kettlebell_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),

            // --- ids already asserted elsewhere, swept here so the whole table moves together ---
            Row("kettlebell_swing_heavy", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("dumbbell_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("med_ball_slam", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("pallof_hold", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("wall_sit", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("overhead_carry", ExerciseKind.CARRY, StartPhase.CONCENTRIC),
            Row("sled_push", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("goblet_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("incline_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("paused_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("cable_fly", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("plank_reach", ExerciseKind.HOLD, StartPhase.ECCENTRIC),

            // --- plurals: `contains` tolerates them by accident, and a whole-
            // token rule has to do it on purpose. Two hints pluralise with -es
            // rather than -s, and each is load-bearing in a different column:
            // push_presses is EXPLOSIVE only through EXPLOSIVE_HINTS' own
            // "push_press" plus the -es arm, and snatches is CONCENTRIC only
            // through "snatch" plus the -es arm. None of these rows moves. ---
            Row("farmers_walks", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("wall_sits", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("planks", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("push_presses", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("snatches", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("barbell_curls", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- unsegmented ids: accepted losses. `contains` reached inside
            // them for free; whole-token matching cannot, by construction.
            // They are losses rather than aliases to add because the ids the
            // app itself publishes are snake_case -- dead_hang, farmers_walk
            // and kettlebell_swing are all seed ids written with underscores --
            // and because any loss here is now declarable away with "kind".
            // pulldown, pullup and chinup are NOT in this group: those are
            // single words in the vocabulary the published schema uses
            // ("lat pulldowns and pushdowns", plan.schema.json), so they are
            // added to the hint list instead and their rows do not move. ---
            Row("deadhang", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("kbswing", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("farmerswalk", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("pullup", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("chinup", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("dbsnatch", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),

            // --- ids carrying the "db" and "kb" abbreviations. None of these
            // rows moves. ---
            Row("db_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("kb_swing", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),
            Row("db_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("kb_carry", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("dumbbell_push_press", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),

            // --- hint lists colliding with themselves and with each other:
            // "narrow" contains "row"; "pullover" contains "pull"; "machine"
            // contains "chin"; "prowler" contains "row"; and EXPLOSIVE_HINTS'
            // own "throw" contains CONCENTRIC_START_HINTS' "row". All seven
            // rows move to ECCENTRIC.
            //
            // Four of the seven are unambiguous: a close-grip bench, a
            // pullover, a prowler push and a med-ball throw all begin by
            // lowering or loading, not by driving. The other three are the
            // machine_* rows below.
            //
            // The three machine_* rows are NOT claimed as fixes. Nothing here
            // knows which end a machine chest press starts at, and Plan.kt's
            // own comment on `start` says machines of the same movement
            // pattern genuinely differ. If it starts with the drive, these
            // three rows are a regression and the answer is a hint entry or a
            // declared "start" -- and because startsWith is written once per
            // id and never updated, an id already recorded keeps whichever
            // value it was given. This is [Field] F3, open. ---
            Row("narrow_grip_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("pullover", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("machine_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("machine_chest_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("machine_fly", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("prowler_push", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("ball_throw", ExerciseKind.EXPLOSIVE, StartPhase.ECCENTRIC),

            // --- "pull" reaches inside "pulldown", which is the movement's
            // own name. A lat pulldown begins with the drive, so CONCENTRIC is
            // right today and whole-token matching would lose it. These three
            // rows do not move, because "pulldown" joins the hint list. ---
            Row("lat_pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("cable_pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- issue #59: "pushdown" was never on the list, so a tricep
            // pushdown -- which also begins with the drive -- fell to the
            // ECCENTRIC default. This row MOVES: "pushdown" joins the hint
            // list beside "pulldown". Only the start PHASE moves here --
            // concentricUp still defaults to true (drive-up) because it is
            // never inferred from the id (see ExerciseDef.concentricUp), so a
            // plan using this id still needs "concentric": "down" declared to
            // get the tempo digits and drive direction right; this row only
            // fixes which phase is counted and announced first. ---
            Row("tricep_pushdown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- issue #254's neighbours: ids sharing a token with
            // "muscle_up", pinned BEFORE that hint lands so the change can be
            // shown to move none of them. The hint is matched as two adjacent
            // whole tokens, so "up" alone and "muscle" alone move nothing:
            // the push-up, sit-up and step-up still lower or load first, the
            // muscle snatch and muscle clean are concentric already through
            // their Olympic word, and an upright row through "row". ---
            Row("push_up", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            // sit_up and step_up MOVE to CONCENTRIC through the exact-id
            // table, not through "up": a sit-up starts lying down and a step-up
            // starts standing on the floor, and both begin with the drive (F1).
            Row("sit_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("step_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("turkish_get_up", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("muscle_snatch", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("muscle_clean", ExerciseKind.EXPLOSIVE, StartPhase.CONCENTRIC),
            Row("upright_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("triceps_pushdown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("rope_pushdown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- issue #254: a muscle-up begins with the drive out of a dead
            // hang -- the pull, then the transition, then the press -- and
            // there is no lowering before it. These rows MOVE: no hint matched
            // either whole token, so all three fell to the ECCENTRIC default,
            // and the countdown said "down" to a lifter hanging from a bar,
            // with the first counted phase the wrong one. The "muscle_up"
            // hint is two adjacent whole tokens with the plural tolerance, so
            // the neighbours above stay where they are. ---
            Row("muscle_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("muscle_ups", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("ring_muscle_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("bar_muscle_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- ordinary ids that must not move ---
            Row("seated_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("pull_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("chin_up", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("dip", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("sled_drag", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("yoke_walk", ExerciseKind.CARRY, StartPhase.ECCENTRIC),
            Row("l_sit", ExerciseKind.HOLD, StartPhase.ECCENTRIC),
            Row("cable_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("landmine_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("landmine_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("t_bar_row", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("leg_curl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("seated_leg_curl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("calf_raise", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            // MOVED (F1): the stack rests at the bottom with the knees bent, and
            // the first movement is the extension. In robocoach's real plan.
            Row("leg_extension", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("cossack_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("rear_foot_elevated_split_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("shoulder_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("military_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("smith_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("band_pull_apart", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("bodyweight_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),

            // --- core-model review 2026-11-06, F1: the exact-id start table.
            // Each of these lifts was checked against where it begins, not
            // against a word in its id. All of them MOVE.
            //
            // Drive first: the lifter starts where the load is at rest (seated,
            // lying, standing on the floor, stack down, dumbbells at the
            // shoulders) and the first movement is the concentric one. ---
            Row("glute_bridge", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("hip_abduction", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("pec_deck", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("tricep_kickback", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("triceps_kickback", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("seated_dumbbell_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("arnold_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- lowering first, which the substring hints got backwards: an
            // overhead squat and an overhead extension start with the load
            // overhead and lower it ("overhead" is a hint for the press); a
            // Nordic curl starts kneeling upright and lowers, a Jefferson curl
            // starts standing and rolls down ("curl" is a hint for the arm
            // curl). ---
            Row("overhead_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("overhead_triceps_extension", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("overhead_tricep_extension", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("nordic_curl", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("jefferson_curl", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),

            // --- every RDL spelling agrees with the seed's romanian_deadlift:
            // the bar is unracked or picked up once, and each rep lowers
            // first. A veto on the whole "romanian"/"rdl"/"stiff_leg" token,
            // ahead of "deadlift" and the Olympic words. rdl and dumbbell_rdl
            // were already right (no hint matched); they are pinned so the veto
            // is seen not to move them. ---
            Row("rdl", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_rdl", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_romanian_deadlift", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("single_leg_romanian_deadlift", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("stiff_leg_deadlift", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("stiff_legged_deadlift", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),

            // --- neighbours the table and the veto must NOT move: a
            // straight-arm pulldown is a "stiff" arm, not a stiff leg; the
            // seated overhead press and dumbbell shoulder press keep their
            // drive-first answer through their hints; a conventional deadlift
            // and a deficit deadlift still start on the floor. ---
            Row("stiff_arm_pulldown", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("seated_overhead_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("dumbbell_shoulder_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("deficit_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("sumo_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("hamstring_curl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
        )

    @Test
    fun `inferKind over the id sweep`() {
        sweep.forEach { assertEquals(it.kind, ExerciseDef.inferKind(it.id), it.id) }
    }

    @Test
    fun `inferStartPhase over the id sweep`() {
        sweep.forEach { assertEquals(it.start, ExerciseDef.inferStartPhase(it.id), it.id) }
    }

    /**
     * The inference agrees with every seed's own start phase, so an id that
     * reaches [ExerciseDef.inferStartPhase] despite being seeded --
     * `PlanExerciseDef.startsAtTop` reads it for any id -- gets the seed's
     * answer. `romanian_deadlift` was the one that disagreed (F1).
     */
    @Test
    fun `the start inference agrees with every seed's start phase`() {
        ExerciseDef.SEED.forEach { seed ->
            assertEquals(seed.startsWith, ExerciseDef.inferStartPhase(seed.id), seed.id)
        }
    }

    @Test
    fun `every seed id resolves by lookup, so inference never decides one`() {
        // SessionRepository.exerciseById checks ExerciseDef.seedById first.
        // These rows are in the sweep to prove a matcher change cannot reach
        // them.
        ExerciseDef.SEED.forEach { seed ->
            assertEquals(seed, ExerciseDef.seedById(seed.id), seed.id)
        }
    }
}
