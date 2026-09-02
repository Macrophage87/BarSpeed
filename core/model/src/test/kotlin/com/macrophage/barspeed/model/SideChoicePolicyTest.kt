package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which side the next set works, given the plan's prescription and whatever
 * the lifter said about that one set (#215, #144).
 *
 * DIFFERENTIALS. Every case that asserts a CHOICE fails at the commit that
 * introduces it: [SideChoicePolicy.offersChoice] refuses every slot and
 * [SideChoicePolicy.carriedIntoNextSet] returns the prescription whatever is
 * stated, which is what the app answers today -- `RecordViewModel` resolves a
 * planned set's side as a copy of `slot.side` and reads the Both/Left/Right
 * selector only on an ad-hoc set.
 *
 * The cases that assert the prescription STANDS pass here, and are worth
 * exactly what #214's file said the same asymmetry was worth: nothing on their
 * own, since they would also pass against a deleted function. They are the
 * other half of the contract, and the half that catches a fix which makes the
 * lifter's choice win everywhere instead of only where they made one.
 */
class SideChoicePolicyTest {
    @Test
    fun `the offered sides are the vocabulary the plan contract accepts`() {
        // Spelled as a list here because the chips are drawn in order, and
        // pinned against the plan's own set so a word the control offers can
        // never be one the import gate would refuse on the way back in.
        assertEquals(listOf("left", "right"), SideChoicePolicy.CHOICES)
        assertEquals(PlanFile.VALID_SIDES, SideChoicePolicy.CHOICES.toSet())
    }

    @Test
    fun `a unilateral set is one a side may be stated for`() {
        assertTrue(SideChoicePolicy.offersChoice("left"))
        assertTrue(SideChoicePolicy.offersChoice("right"))
    }

    @Test
    fun `a bilateral set offers no choice at all`() {
        // The silent-no-draw rule SideArrow applies to the arrow. A control
        // here would invite the lifter to put a limb on a set that used both,
        // and the value would then be recorded.
        assertFalse(SideChoicePolicy.offersChoice(null))
    }

    @Test
    fun `a side the plan contract would refuse is not a side`() {
        assertFalse(SideChoicePolicy.offersChoice("both"))
        assertFalse(SideChoicePolicy.offersChoice("LEFT"))
        assertFalse(SideChoicePolicy.offersChoice(""))
    }

    @Test
    fun `the lifter's choice displaces the prescription for the set they made it on`() {
        assertEquals("right", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = "right"))
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "right", statedSide = "left"))
    }

    @Test
    fun `stating the side the plan already asked for changes nothing`() {
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = "left"))
    }

    @Test
    fun `a set nobody stated a side for works the side the plan prescribed`() {
        // The alternation default, and the whole of it: a plan writes
        // unilateral work one set per side, so leaving the control alone has
        // to leave the plan's own order standing.
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = null))
        assertEquals("right", SideChoicePolicy.carriedIntoNextSet(declaredSide = "right", statedSide = null))
    }

    @Test
    fun `nothing can put a limb on a bilateral set`() {
        // The control is not drawn for one, so this is unreachable through the
        // screen; it is pinned because the rule that keeps it unreachable and
        // the rule that decides the value are two different functions, and the
        // pair is what makes a stale statement harmless.
        assertNull(SideChoicePolicy.carriedIntoNextSet(declaredSide = null, statedSide = "right"))
    }

    @Test
    fun `a stated word outside the vocabulary leaves the prescription standing`() {
        // Fail towards the plan, never towards a value the export's own enum
        // would reject: `side` is published against a closed vocabulary, and a
        // document carrying "Right" validates against nothing.
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = "Right"))
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = "both"))
        assertEquals("left", SideChoicePolicy.carriedIntoNextSet(declaredSide = "left", statedSide = ""))
    }

    @Test
    fun `a slot carrying an unrecognised side keeps it rather than being corrected here`() {
        // Not this function's job, and the near neighbour of the case above:
        // a slot whose side never passed the import gate is a plan-decoding
        // question, and silently rewriting it here would hide it from the one
        // place that can report it.
        assertEquals("BOTH", SideChoicePolicy.carriedIntoNextSet(declaredSide = "BOTH", statedSide = null))
        assertEquals("BOTH", SideChoicePolicy.carriedIntoNextSet(declaredSide = "BOTH", statedSide = "left"))
    }
}
