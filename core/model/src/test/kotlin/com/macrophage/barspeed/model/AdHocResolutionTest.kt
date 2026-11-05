package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AD-HOC path's definition of an id the app ships no entry for, against
 * the plan path's (#69 item 4).
 *
 * [ExerciseDef.resolvedById] fell back to the bare `ExerciseDef(id, id)`,
 * which skips every inference the app makes from an id -- the start phase,
 * the kind and the stack default -- while `SessionRepository.exerciseById`
 * builds the same fallback through [ExerciseDef.inferred] with all three. The
 * same id therefore meant two different exercises depending on which path
 * asked: a `pallof_hold` was a hold to a plan and a rep-counted dynamic lift
 * to an ad-hoc set, and a `lat_pulldown` rode the stack to one and not the
 * other.
 *
 * Unreachable today, and not written up as a live bug: the ad-hoc picker
 * offers [ExerciseDef.SEED] alone, and no seed id is in
 * [ExerciseDef.STACK_MOUNTED_IDS]. It is a landmine that goes off the day the
 * picker offers anything else, and this pins it shut.
 */
class AdHocResolutionTest {
    private val unseen = listOf("pallof_hold", "sled_push", "cable_row", "lat_pulldown", "tricep_pushdown", "pull_up")

    @Test
    fun `an unseen id resolves ad hoc to the definition the plan path infers`() {
        unseen.forEach { id ->
            val inferred = ExerciseDef.inferred(id)
            assertEquals(
                inferred.copy(bodyweight = SetGeometryPolicy.bodyweightMount(id, inferred.bodyweight, declared = null)),
                ExerciseDef.resolvedById(id),
                id,
            )
        }
    }

    @Test
    fun `the inferences an ad-hoc set used to lose`() {
        assertEquals(ExerciseKind.HOLD, ExerciseDef.resolvedById("pallof_hold").kind)
        assertEquals(ExerciseKind.CARRY, ExerciseDef.resolvedById("sled_push").kind)
        assertEquals(StartPhase.CONCENTRIC, ExerciseDef.resolvedById("cable_row").startsWith)
        assertEquals(StartPhase.CONCENTRIC, ExerciseDef.resolvedById("tricep_pushdown").startsWith)
        assertTrue(ExerciseDef.resolvedById("lat_pulldown").sensorOnStack, "a stack id rides the stack ad hoc too")
        assertTrue(ExerciseDef.resolvedById("lat_pulldown").isCustom)
    }

    /** The near neighbour the change must not move: a seed id resolves to its seed entry. */
    @Test
    fun `a seed id still resolves to its own entry`() {
        ExerciseDef.SEED.forEach { seed ->
            assertEquals(
                seed.copy(bodyweight = SetGeometryPolicy.bodyweightMount(seed.id, seed.bodyweight, declared = null)),
                ExerciseDef.resolvedById(seed.id),
                seed.id,
            )
        }
    }
}
