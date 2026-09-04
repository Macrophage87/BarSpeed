package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The differentials for #175: what the load, reps and hold boxes say about the
 * plan, and when they say nothing.
 *
 * RED at the commit that adds this file. All three entry points return null
 * unconditionally there, which is what ships today. Every test asserting a
 * SENTENCE fails; every test asserting null passes at both ends, and is here
 * because "says nothing" is half the contract and the half a caption is most
 * likely to get wrong once it can speak at all. Which is which is named in each
 * KDoc.
 *
 * The two sentence forms are quoted here in full deliberately. They are the two
 * already shipping in RecordScreen.kt -- the prep adjuster's and sensor-count
 * line's "Plan says X - your change is recorded in the export", and the tempo
 * steppers' "Plan says X - the rest of this exercise runs Y unless the plan
 * changes it" -- and nothing mechanically couples this file to those literals,
 * so a differing character here is a fourth phrasing being minted. The
 * separator is an ASCII hyphen surrounded by spaces in both, as it is at all
 * four shipped sites.
 */
class PlanValueCaptionContractTest {
    /** Green before the fix and after it: an unchanged value needs no caption. */
    @Test
    fun `an unchanged load says nothing`() {
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 90.0,
                nextDeclaredAddedKg = 90.0,
                shownAddedKg = 90.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it, and the reason c0 pinned the
     * rendering: a plan declaring 175 lb stores 79.3786647517562 kg, a kg field
     * hands back 79.4, and the lifter touched nothing. Decided on the doubles
     * this caption would appear on every set of that block.
     */
    @Test
    fun `a load the field only rounded says nothing`() {
        val declared = 175 / WeightUnit.LB_PER_KG
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = declared,
                nextDeclaredAddedKg = declared,
                shownAddedKg = WeightUnit.KG.parseToKg(WeightUnit.KG.inputValue(declared)),
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix. The tempo steppers' sentence, verbatim, because with
     * #124's and #174's carries in place this statement outlives the set being
     * set up and the lifter cannot otherwise discover how far it reaches.
     */
    @Test
    fun `a changed load names the plan's figure and how far the change reaches`() {
        assertEquals(
            "Plan says 90 kg - the rest of this exercise runs 100 kg unless the plan changes it",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 90.0,
                nextDeclaredAddedKg = 90.0,
                shownAddedKg = 100.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix, #143 round 2's own scenario. Plan 45 / 55 / 65
     * opened at 50: the correction DOES carry (#143), but the literal $shown
     * figure -- 50 -- does not, because the plan's own next step is 55 and the
     * set after THAT would read 60, not 50. The reach sentence asserts a
     * fixed continuing number, so it is withheld once the plan itself steps,
     * and the export sentence is used instead -- true of this box regardless
     * of whether the value reaches further.
     */
    @Test
    fun `a stepping block does not claim a fixed number carries forward`() {
        assertEquals(
            "Plan says 45 kg - your change is recorded in the export",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 45.0,
                nextDeclaredAddedKg = 55.0,
                shownAddedKg = 50.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it: no next declaration at all -- the
     * ad-hoc tail of the plan, or the last set of the exercise -- is not
     * stepping, so the reach sentence still names $shown. `standingKg` agrees:
     * with nextDeclaredAddedKg null, SetLoadPolicy.standingStatedAddedKg's
     * "nothing prescribed for the coming set to yield to" branch returns the
     * statement unshifted, so the box really would read $shown.
     */
    @Test
    fun `a block with no next declaration still claims the fixed number`() {
        assertEquals(
            "Plan says 45 kg - the rest of this exercise runs 50 kg unless the plan changes it",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 45.0,
                nextDeclaredAddedKg = null,
                shownAddedKg = 50.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix. On the last set of a block, and wherever the plan
     * prescribes a different number next, the statement reaches no further --
     * so the reach sentence would be false and the prep adjuster's sentence is
     * used instead. It is true of this box: load_kg is published beside
     * plannedLoad_kg.
     */
    @Test
    fun `a change that reaches no further says what it does reach`() {
        assertEquals(
            "Plan says 90 kg - your change is recorded in the export",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 90.0,
                nextDeclaredAddedKg = 90.0,
                shownAddedKg = 100.0,
                standsForLaterSets = false,
            ),
        )
    }

    /** RED before the fix. #160's notation, in the display unit the box is in. */
    @Test
    fun `a changed load on body-weight work names the plan's figure in the BW notation`() {
        assertEquals(
            "Plan says BW + 10 lb - the rest of this exercise runs BW + 25 lb unless the plan changes it",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = true,
                unit = WeightUnit.LB,
                plannedAddedKg = 10 / WeightUnit.LB_PER_KG,
                nextDeclaredAddedKg = 10 / WeightUnit.LB_PER_KG,
                shownAddedKg = 25 / WeightUnit.LB_PER_KG,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix. A pull-up prescribed at body weight has prescribed
     * something, and BW is the zero of the notation rather than an absence, so
     * a lifter who hangs 20 kg off a belt is told what the plan asked for.
     */
    @Test
    fun `a body-weight set the plan declared no load for is still named as BW`() {
        assertEquals(
            "Plan says BW - the rest of this exercise runs BW + 20 kg unless the plan changes it",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = true,
                unit = WeightUnit.KG,
                plannedAddedKg = null,
                nextDeclaredAddedKg = null,
                shownAddedKg = 20.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * THE INFORMATION THIS DESIGN LOSES, pinned rather than left to be
     * rediscovered (#143 round 2 finding 10). On body-weight work
     * `plannedLoadText` answers "BW" for an absent declaration, so a set that
     * declares an added load followed by one that declares none renders
     * "BW + 10 kg" against "BW": unequal, and `stepsAfterThis` is therefore
     * true where a loaded block would have read null and kept the reach
     * sentence. The export sentence is used instead. It is still true -- the
     * change is published beside `plannedLoad_kg` on every set the carry
     * reaches -- so what is lost is the reach, not the accuracy.
     */
    @Test
    fun `a body-weight block whose next set declares no load loses the reach sentence`() {
        assertEquals(
            "Plan says BW + 10 kg - your change is recorded in the export",
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = true,
                unit = WeightUnit.KG,
                plannedAddedKg = 10.0,
                nextDeclaredAddedKg = null,
                shownAddedKg = 15.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it. Loaded work with the weight left to
     * the lifter has no prescription to name, and "Plan says 0 kg" would invent
     * one.
     */
    @Test
    fun `a loaded set the plan declared no load for gets no caption`() {
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                added = false,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = null,
                nextDeclaredAddedKg = null,
                shownAddedKg = 60.0,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it. An ad-hoc set has no plan, so it gets
     * no caption rather than "Plan says none" -- including on body-weight work,
     * where the notation would otherwise supply a "BW" nothing prescribed.
     */
    @Test
    fun `an ad-hoc set gets no caption on any of the three boxes`() {
        assertNull(
            PlanValueCaption.load(
                adHoc = true,
                added = false,
                bodyweight = true,
                unit = WeightUnit.KG,
                plannedAddedKg = null,
                nextDeclaredAddedKg = null,
                shownAddedKg = 20.0,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.reps(
                adHoc = true,
                added = false,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = 10,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = true,
                added = false,
                plannedDurationS = 45,
                nextDeclaredDurationS = 45,
                shownDurationS = 30,
                standsForLaterSets = true,
            ),
        )
    }

    /** RED before the fix. */
    @Test
    fun `a changed rep count names the plan's count`() {
        assertEquals(
            "Plan says 8 - the rest of this exercise runs 6 unless the plan changes it",
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = 6,
                standsForLaterSets = true,
            ),
        )
        assertEquals(
            "Plan says 8 - your change is recorded in the export",
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = 6,
                standsForLaterSets = false,
            ),
        )
    }

    /**
     * RED before the fix. The reps box's own stepping case: a descending
     * scheme of 10 / 8 / 6 opened at 9 has corrected by -1, and the reach
     * sentence must not claim "runs 9" when set 3 is actually offered 5.
     */
    @Test
    fun `a stepping rep scheme does not claim a fixed count carries forward`() {
        assertEquals(
            "Plan says 10 - your change is recorded in the export",
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = 10,
                nextDeclaredReps = 8,
                shownReps = 9,
                standsForLaterSets = true,
            ),
        )
    }

    /** RED before the fix. Seconds, because the box is labelled in them. */
    @Test
    fun `a changed hold names the plan's seconds`() {
        assertEquals(
            "Plan says 45s - the rest of this exercise runs 30s unless the plan changes it",
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
                nextDeclaredDurationS = 45,
                shownDurationS = 30,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix. The hold box's own stepping case, seconds rather
     * than kilograms.
     */
    @Test
    fun `a stepping hold scheme does not claim a fixed duration carries forward`() {
        assertEquals(
            "Plan says 45s - your change is recorded in the export",
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
                nextDeclaredDurationS = 30,
                shownDurationS = 40,
                standsForLaterSets = true,
            ),
        )
    }

    /** Green before the fix and after it. */
    @Test
    fun `an unchanged rep count and an unchanged hold say nothing`() {
        assertNull(
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = 8,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
                nextDeclaredDurationS = 45,
                shownDurationS = 45,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it. A box mid-keystroke holds "" or "1x",
     * which parses to nothing. There is no change to describe yet, and shouting
     * "Plan says 8" at a lifter who has cleared the box to retype it is noise.
     */
    @Test
    fun `a box holding nothing readable says nothing`() {
        assertNull(
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = null,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
                nextDeclaredDurationS = 45,
                shownDurationS = null,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * Green before the fix and after it. A set the plan gave no count for has
     * no prescription to name -- the reps equivalent of the loadless barbell
     * set above.
     */
    @Test
    fun `a set the plan gave no count for gets no caption`() {
        assertNull(
            PlanValueCaption.reps(
                adHoc = false,
                added = false,
                plannedReps = null,
                nextDeclaredReps = null,
                shownReps = 10,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = null,
                nextDeclaredDurationS = null,
                shownDurationS = 30,
                standsForLaterSets = true,
            ),
        )
    }

    /**
     * RED before the fix, and the joint requirement of #174 and #175 stated as
     * an assertion rather than as prose.
     *
     * With the carries in, the number in the box can differ from the plan
     * because of something the lifter said several sets ago. Two facts then
     * share one control: the plan's PRESCRIPTION and the lifter's STANDING
     * STATEMENT. Whatever the caption says, the number it attributes to the
     * plan must be the plan's -- so "Plan says" is followed by the plan's
     * figure and by no other, on every one of the three boxes and in both
     * sentence forms.
     */
    @Test
    fun `the plan's number is the only one the caption attributes to the plan`() {
        val captions =
            listOf(true, false).flatMap { reach ->
                listOf(
                    PlanValueCaption.load(
                        adHoc = false,
                        added = false,
                        bodyweight = false,
                        unit = WeightUnit.KG,
                        plannedAddedKg = 90.0,
                        nextDeclaredAddedKg = 90.0,
                        shownAddedKg = 100.0,
                        standsForLaterSets = reach,
                    ),
                    PlanValueCaption.reps(
                        adHoc = false,
                        added = false,
                        plannedReps = 8,
                        nextDeclaredReps = 8,
                        shownReps = 6,
                        standsForLaterSets = reach,
                    ),
                    PlanValueCaption.hold(
                        adHoc = false,
                        added = false,
                        plannedDurationS = 45,
                        nextDeclaredDurationS = 45,
                        shownDurationS = 30,
                        standsForLaterSets = reach,
                    ),
                )
            }
        val planned = listOf("Plan says 90 kg", "Plan says 8", "Plan says 45s")
        val shownAsPlan = listOf("Plan says 100 kg", "Plan says 6", "Plan says 30s")
        captions.forEachIndexed { i, caption ->
            val expected = planned[i % 3]
            assertTrue(caption != null && caption.startsWith(expected), "caption $i: $caption")
            assertFalse(caption!!.contains(shownAsPlan[i % 3]), "caption $i attributes the lifter's number to the plan")
        }
    }

    /**
     * RED before the fix. A set the LIFTER appended gets no caption at all,
     * on any of the three boxes (#177).
     *
     * The body-weight load box is the live defect and the reason this exists.
     * `plannedLoadText` answers "BW" for a body-weight set that declared no
     * load -- deliberately, because BW is the zero of that notation and a
     * pull-up prescribed at body weight HAS prescribed something -- so an
     * appended pull-up is captioned "Plan says BW - the rest of this exercise
     * runs BW + 20 kg unless the plan changes it" on a set nothing prescribed.
     * That is a claim stronger than its evidence in one line of UI text: it
     * tells the lifter the plan asked for a set the plan does not know about.
     *
     * The reps and hold halves are belt-and-braces and are asserted anyway.
     * Both already answer null on a null prescription, so they pass today by
     * accident of an appended slot's `plannedReps` being null rather than
     * because anything states the rule -- and a rule enforced by accident is
     * one a later change can remove without failing anything. They are handed a
     * NON-null prescription here precisely so they cannot pass that way.
     */
    @Test
    fun `an appended set gets no caption on any box`() {
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                added = true,
                bodyweight = true,
                unit = WeightUnit.KG,
                plannedAddedKg = null,
                nextDeclaredAddedKg = null,
                shownAddedKg = 20.0,
                standsForLaterSets = true,
            ),
            "an appended body-weight set is told the plan says BW",
        )
        assertNull(
            PlanValueCaption.load(
                adHoc = false,
                added = true,
                bodyweight = false,
                unit = WeightUnit.KG,
                plannedAddedKg = 90.0,
                nextDeclaredAddedKg = 90.0,
                shownAddedKg = 100.0,
                standsForLaterSets = true,
            ),
            "an appended loaded set is told the plan says 90 kg",
        )
        assertNull(
            PlanValueCaption.reps(
                adHoc = false,
                added = true,
                plannedReps = 8,
                nextDeclaredReps = 8,
                shownReps = 6,
                standsForLaterSets = true,
            ),
            "an appended set is told the plan asked for 8 reps",
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = true,
                plannedDurationS = 45,
                nextDeclaredDurationS = 45,
                shownDurationS = 30,
                standsForLaterSets = true,
            ),
            "an appended hold is told the plan asked for 45s",
        )
    }
}
