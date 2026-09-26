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
 * calls `inferKind` on both of its non-seed branches -- so correcting it
 * corrects history too. `startsWith` is not:
 * `SessionRepository.ensureExerciseExists` writes `inferStartPhase(id).name`
 * into a `CustomExerciseEntity` the first time an id is seen, `exerciseById`
 * reads it back forever after, and `ExerciseDao` has no update statement.
 * That column is written once.
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
            Row("snatch_grip_rdl", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

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
            Row("romanian_deadlift_snatch_grip", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("deadlift_snatch_grip", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),

            // --- every seed id, which resolves by lookup before inference ever runs ---
            Row("back_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("front_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("overhead_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("romanian_deadlift", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
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
            Row("leg_extension", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("cossack_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("rear_foot_elevated_split_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("shoulder_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("military_press", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("smith_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("band_pull_apart", ExerciseKind.DYNAMIC, StartPhase.CONCENTRIC),
            Row("bodyweight_squat", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
            Row("dumbbell_bench_press", ExerciseKind.DYNAMIC, StartPhase.ECCENTRIC),
        )

    @Test
    fun `inferKind over the id sweep`() {
        sweep.forEach { assertEquals(it.kind, ExerciseDef.inferKind(it.id), it.id) }
    }

    @Test
    fun `inferStartPhase over the id sweep`() {
        sweep.forEach { assertEquals(it.start, ExerciseDef.inferStartPhase(it.id), it.id) }
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
