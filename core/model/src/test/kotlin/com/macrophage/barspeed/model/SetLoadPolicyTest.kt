package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The load rules the record flow applies.
 *
 * The two `(pre-fix)` characterization pins this file was created with have
 * been replaced by their inversions below, named in the commit body:
 * `resolve reads the typed field for a loadless plan set (pre-fix)` and
 * `seedAddedKg carries the last load forward for a loadless next slot
 * (pre-fix)`.
 */
class SetLoadPolicyTest {
    @Test
    fun `resolve uses the plan's declared load`() {
        assertEquals(100.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = 100.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve honours a plan set that declares zero`() {
        // "load_kg": 0 is explicit bodyweight, distinct from declaring nothing.
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = 0.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve keeps a negative declared load for assisted work`() {
        assertEquals(-20.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = -20.0, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve uses the typed load for an ad-hoc set`() {
        assertEquals(60.0, SetLoadPolicy.resolve(adHoc = true, plannedAddedKg = null, typedAddedKg = 60.0))
    }

    @Test
    fun `resolve treats an unparseable typed load as zero`() {
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = true, plannedAddedKg = null, typedAddedKg = null))
    }

    /**
     * The #44 scenario as it behaves today. The plan says 100 kg, the bar has
     * 90, and there is nowhere to say so. A typed 90 is not a stale default --
     * it is the shape a deliberate correction would take -- and it is still not
     * consulted, because on a plan set the text field is not evidence.
     */
    @Test
    fun `resolve ignores a typed correction on a plan set`() {
        assertEquals(100.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = 100.0, typedAddedKg = 90.0))
    }

    /**
     * adHoc short-circuits before the plan is consulted at all. Nothing
     * populates plannedAddedKg on an ad-hoc set today, so this pins the branch
     * rather than a reachable state -- and it is the branch a fourth input must
     * not disturb.
     */
    @Test
    fun `resolve prefers the typed load on an ad-hoc set even when a load is planned`() {
        assertEquals(60.0, SetLoadPolicy.resolve(adHoc = true, plannedAddedKg = 100.0, typedAddedKg = 60.0))
    }

    /**
     * A plan set that names no load added nothing, and the load text field is
     * not evidence about it. The field defaults to "60", startPlanSession never
     * resets it, and a plan session's READY screen does not draw it at all — so
     * whatever it holds when a loadless set is recorded came from somewhere
     * else entirely.
     */
    @Test
    fun `resolve records no added load when the plan set declares none`() {
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = null, typedAddedKg = 60.0))
        // Nor from a field left holding the previous exercise's load.
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = null, typedAddedKg = 48.0))
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = null, typedAddedKg = null))
    }

    @Test
    fun `seedAddedKg prefers the next slot's declared load`() {
        assertEquals(
            100.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = 100.0, lastAddedKg = 60.0),
        )
    }

    @Test
    fun `seedAddedKg carries the last load forward when the plan has run out`() {
        assertEquals(
            60.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = false, nextDeclaredAddedKg = null, lastAddedKg = 60.0),
        )
    }

    @Test
    fun `seedAddedKg has nothing to seed from at the end of an ad-hoc queue`() {
        assertNull(SetLoadPolicy.seedAddedKg(hasPlannedNext = false, nextDeclaredAddedKg = null, lastAddedKg = null))
    }

    /**
     * "The next planned set declares no load" and "there is no next planned
     * set" are different facts and must not both mean "carry the last load
     * forward". Only the second is a reason to carry anything.
     */
    @Test
    fun `seedAddedKg seeds zero when the next planned set declares no load`() {
        assertEquals(
            0.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = 100.0),
        )
    }

    /**
     * The switch-exercise route. jumpToExercise has no last load to offer, so
     * the seed is driven entirely by what the exercise it landed on declares —
     * and a loadless one must clear the field rather than leave the previous
     * exercise's number sitting in it.
     */
    @Test
    fun `seedAddedKg seeds zero after switching onto a loadless exercise`() {
        assertEquals(
            0.0,
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = null),
        )
    }

    /**
     * Squat 100 kg x3 then plank x2, with the lifter tapping "Equipment busy?
     * Switch exercise" onto the plank after squat set 1. The seed is correct
     * before the switch — the next slot really does declare 100 — so nothing
     * upstream is wrong; it is the switch that has to clear it. Left uncleared
     * it is baked into the plank slot by startNextSet and read straight back as
     * a declared load, which for an 80 kg lifter records a 180 kg plank.
     */
    @Test
    fun `a loadless exercise switched to mid-plan does not inherit the last load`() {
        val beforeSwitch =
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = 100.0, lastAddedKg = 100.0)
        assertEquals(100.0, beforeSwitch)

        val afterSwitch =
            SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = null)
        assertEquals(0.0, afterSwitch)
        assertEquals(0.0, SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = afterSwitch, typedAddedKg = 100.0))
    }

    /**
     * Body-weight work does not compound set over set. Set 1 of a loadless
     * pull-up block records nothing added; that value is what seeds the field,
     * what startNextSet bakes into the next slot, and therefore what set 2
     * reads back as a declaration. Before the fix each step fed the next its
     * own answer, so an 80 kg lifter recorded 140, then 220, then 300 kg.
     */
    @Test
    fun `a loadless plan block does not compound set over set`() {
        var added = SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = null, typedAddedKg = 60.0)
        assertEquals(0.0, added, "set 1")
        repeat(2) { i ->
            val seed =
                SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = added)
            assertEquals(0.0, seed, "seed after set ${i + 1}")
            // startNextSet bakes the seeded field back into the slot, so the
            // following set sees it as a NON-null declaration.
            added = SetLoadPolicy.resolve(adHoc = false, plannedAddedKg = seed, typedAddedKg = seed)
            assertEquals(0.0, added, "set ${i + 2}")
        }
    }
}
