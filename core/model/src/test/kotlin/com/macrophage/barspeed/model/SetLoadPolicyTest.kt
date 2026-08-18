package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The load rules the record flow applies.
 *
 * Three `(pre-fix)` characterization pins have been through this file, all
 * now replaced by their inversions, named in the commit bodies that made
 * each replacement true: `resolve reads the typed field for a loadless plan
 * set (pre-fix)`, `seedAddedKg carries the last load forward for a loadless
 * next slot (pre-fix)`, and `recordedPlannedLoadKg passes the plan's added
 * declaration through unconverted (pre-fix)` (#25).
 */
class SetLoadPolicyTest {
    @Test
    fun `resolve uses the plan's declared load`() {
        assertEquals(
            100.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 100.0,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
    }

    @Test
    fun `resolve honours a plan set that declares zero`() {
        // "load_kg": 0 is explicit bodyweight, distinct from declaring nothing.
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 0.0,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
    }

    @Test
    fun `resolve keeps a negative declared load for assisted work`() {
        assertEquals(
            -20.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = -20.0,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
    }

    @Test
    fun `resolve uses the typed load for an ad-hoc set`() {
        assertEquals(
            60.0,
            SetLoadPolicy.resolve(
                adHoc = true,
                plannedAddedKg = null,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
    }

    @Test
    fun `resolve treats an unparseable typed load as zero`() {
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = true,
                plannedAddedKg = null,
                typedAddedKg = null,
                statedAddedKg = null,
            ),
        )
    }

    /**
     * The #44 scenario as it behaves today. The plan says 100 kg, the bar has
     * 90, and there is nowhere to say so. A typed 90 is not a stale default --
     * it is the shape a deliberate correction would take -- and it is still not
     * consulted, because on a plan set the text field is not evidence.
     */
    @Test
    fun `resolve ignores a typed correction on a plan set`() {
        assertEquals(
            100.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 100.0,
                typedAddedKg = 90.0,
                statedAddedKg = null,
            ),
        )
    }

    /**
     * adHoc short-circuits before the plan is consulted at all. Nothing
     * populates plannedAddedKg on an ad-hoc set today, so this pins the branch
     * rather than a reachable state -- and it is the branch a fourth input must
     * not disturb.
     */
    @Test
    fun `resolve prefers the typed load on an ad-hoc set even when a load is planned`() {
        assertEquals(
            60.0,
            SetLoadPolicy.resolve(
                adHoc = true,
                plannedAddedKg = 100.0,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
    }

    /**
     * A plan set that names no load added nothing, and the load text field is
     * not evidence about it.
     */
    @Test
    fun `resolve records no added load when the plan set declares none`() {
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = null,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
        // Nor from a field left holding the previous exercise's load.
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = null,
                typedAddedKg = 48.0,
                statedAddedKg = null,
            ),
        )
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = null,
                typedAddedKg = null,
                statedAddedKg = null,
            ),
        )
    }

    /**
     * #44. The plan says 100 kg, the bar has 90, and the lifter says so. What
     * is recorded is 90 -- the load that actually travelled -- while
     * plannedLoadKg stays at the plan's 100, so the deviation is visible
     * afterwards instead of being erased.
     */
    @Test
    fun `a plan set records the load the lifter stated for it`() {
        assertEquals(
            90.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 100.0,
                typedAddedKg = 60.0,
                statedAddedKg = 90.0,
            ),
        )
    }

    /**
     * Zero is a statement. A lifter who strips the bar has said the added load
     * was nothing, and that is different from having said nothing at all --
     * which is null and leaves the plan standing. Guards the precedence against
     * being written as a truthiness test on the stated value.
     */
    @Test
    fun `a stated load of zero is a statement, not an absence`() {
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 100.0,
                typedAddedKg = 60.0,
                statedAddedKg = 0.0,
            ),
        )
    }

    /**
     * Assisted work states negative added load. A lifter who needed more band
     * than the plan asked for has to be able to say -30 where it said -20, so
     * the sign must survive the same path a positive load takes.
     */
    @Test
    fun `a negative stated load is kept for assisted work`() {
        assertEquals(
            -30.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = -20.0,
                typedAddedKg = 60.0,
                statedAddedKg = -30.0,
            ),
        )
    }

    /**
     * The near neighbour. On an ad-hoc set the typed field IS the declaration,
     * so a stated load must not displace it -- green before this change and
     * green after, and it is what makes selectExercise safe to leave without a
     * clear.
     */
    @Test
    fun `an ad-hoc set is unaffected by a stated load`() {
        assertEquals(
            60.0,
            SetLoadPolicy.resolve(
                adHoc = true,
                plannedAddedKg = null,
                typedAddedKg = 60.0,
                statedAddedKg = 90.0,
            ),
        )
    }

    /**
     * #22 stands. A plan set the lifter said nothing about still ignores the
     * text field, whether the plan declared a load or declared none. Green
     * before this change and green after: what #44 adds is a separate fact, not
     * a new reading of loadInput.
     */
    @Test
    fun `a plan set that states nothing still ignores the typed field`() {
        assertEquals(
            100.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = 100.0,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = null,
                typedAddedKg = 60.0,
                statedAddedKg = null,
            ),
        )
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
     * #45. The lifter never touched the load field, so what the next set
     * records must be the plan's own number, bit for bit -- not what survived
     * being rendered into a text box and read back out.
     *
     * What this holds is the bit-for-bit return itself: rounding the result
     * to 0.1 reds this test and nothing else. The two declared values are
     * ones a load field would have damaged -- 175 lb stored as kilograms,
     * and a round 100 kg -- but no seed and no parse appear here any more,
     * because neither is in the code path. The round trip they used to
     * exercise is pinned in WeightUnitTest instead.
     */
    @Test
    fun `an untouched field carries the plan's declared load unchanged`() {
        val declaredInLb = 175 / WeightUnit.LB_PER_KG
        assertEquals(
            declaredInLb,
            SetLoadPolicy.carriedIntoNextSet(
                declaredAddedKg = declaredInLb,
                statedAddedKg = null,
            ),
        )
        assertEquals(
            100.0,
            SetLoadPolicy.carriedIntoNextSet(
                declaredAddedKg = 100.0,
                statedAddedKg = null,
            ),
        )
    }

    /**
     * A load the lifter did state is carried, and it is theirs rather than the
     * plan's -- so the deviation the session detail screen then shows is a real
     * one.
     */
    @Test
    fun `a stated load is what the next set carries`() {
        val declaredInLb = 175 / WeightUnit.LB_PER_KG
        assertEquals(
            90.0,
            SetLoadPolicy.carriedIntoNextSet(
                declaredAddedKg = declaredInLb,
                statedAddedKg = 90.0,
            ),
        )
    }

    /**
     * A slot the plan gave no load for carries none. Green before this change
     * and green after: the elvis chain must not turn a declared absence into a
     * number.
     */
    @Test
    fun `a loadless next slot carries no load`() {
        assertNull(
            SetLoadPolicy.carriedIntoNextSet(
                declaredAddedKg = null,
                statedAddedKg = null,
            ),
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
        assertEquals(
            0.0,
            SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = afterSwitch,
                typedAddedKg = 100.0,
                statedAddedKg = null,
            ),
        )
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
        var added = SetLoadPolicy.resolve(
            adHoc = false,
            plannedAddedKg = null,
            typedAddedKg = 60.0,
            statedAddedKg = null,
        )
        assertEquals(0.0, added, "set 1")
        repeat(2) { i ->
            val seed =
                SetLoadPolicy.seedAddedKg(hasPlannedNext = true, nextDeclaredAddedKg = null, lastAddedKg = added)
            assertEquals(0.0, seed, "seed after set ${i + 1}")
            // startNextSet bakes the seeded field back into the slot, so the
            // following set sees it as a NON-null declaration.
            added = SetLoadPolicy.resolve(
                adHoc = false,
                plannedAddedKg = seed,
                typedAddedKg = seed,
                statedAddedKg = null,
            )
            assertEquals(0.0, added, "set ${i + 2}")
        }
    }

    @Test
    fun `totalKg adds body weight for a body-weight movement`() {
        assertEquals(100.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = 80.0, addedKg = 20.0))
    }

    @Test
    fun `totalKg leaves a loaded movement's added kg unchanged`() {
        assertEquals(100.0, SetLoadPolicy.totalKg(bodyweight = false, bodyWeightKg = 80.0, addedKg = 100.0))
    }

    /**
     * Assisted body-weight work states negative added load, same as
     * [resolve] already keeps for it. The sign has to survive the sum with
     * body weight the same way, or an assisted set would record the wrong
     * total on the one path that most needs it right.
     */
    @Test
    fun `totalKg keeps a negative added load for assisted body-weight work`() {
        assertEquals(60.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = 80.0, addedKg = -20.0))
    }

    /**
     * #61, not fixed here. With no recorded body weight the added-only value
     * is what gets stored, exactly as `RecordViewModel`'s inline expression
     * already did before this function existed -- this pin holds that this
     * function does not change that default, only where it lives.
     */
    @Test
    fun `totalKg treats a missing body weight as zero, same as loadKg always has`() {
        assertEquals(20.0, SetLoadPolicy.totalKg(bodyweight = true, bodyWeightKg = null, addedKg = 20.0))
    }

    /**
     * #25. 80 kg lifter, band-assisted pull-up, plan declares -20 kg added,
     * run exactly as prescribed. Before the fix this pairs loadKg 60.0
     * (SetLoadPolicy.totalKg, body weight plus added) against an unconverted
     * plannedLoadKg of -20.0, and SessionDetailScreen's exact `!=` reads an
     * 80 kg deviation the lifter never made.
     */
    @Test
    fun `recordedPlannedLoadKg pairs the plan's declaration on the same scale as the actual load`() {
        assertEquals(
            60.0,
            SetLoadPolicy.recordedPlannedLoadKg(bodyweight = true, bodyWeightKg = 80.0, plannedAddedKg = -20.0),
        )
    }

    /**
     * A plan slot that declared no load has no planned load to pair, on
     * either scale -- distinct from a plan slot that declared zero, which
     * [totalKg] would still add body weight to.
     */
    @Test
    fun `recordedPlannedLoadKg carries a loadless declaration as no planned load`() {
        assertNull(
            SetLoadPolicy.recordedPlannedLoadKg(bodyweight = true, bodyWeightKg = 80.0, plannedAddedKg = null),
        )
    }
}
