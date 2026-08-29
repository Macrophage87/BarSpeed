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
                shownAddedKg = 100.0,
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
                shownAddedKg = 20.0,
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
                shownAddedKg = 20.0,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.reps(
                adHoc = true,
                added = false,
                plannedReps = 8,
                shownReps = 10,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = true,
                added = false,
                plannedDurationS = 45,
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
                shownReps = 6,
                standsForLaterSets = false,
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
                shownDurationS = 30,
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
                shownReps = 8,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
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
                shownReps = null,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = 45,
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
                shownReps = 10,
                standsForLaterSets = true,
            ),
        )
        assertNull(
            PlanValueCaption.hold(
                adHoc = false,
                added = false,
                plannedDurationS = null,
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
                        shownAddedKg = 100.0,
                        standsForLaterSets = reach,
                    ),
                    PlanValueCaption.reps(
                        adHoc = false,
                        added = false,
                        plannedReps = 8,
                        shownReps = 6,
                        standsForLaterSets = reach,
                    ),
                    PlanValueCaption.hold(
                        adHoc = false,
                        added = false,
                        plannedDurationS = 45,
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
}
