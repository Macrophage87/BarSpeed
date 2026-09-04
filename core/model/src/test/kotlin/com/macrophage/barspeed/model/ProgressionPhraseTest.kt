package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a full-session view says an exercise steps up by, before START (#235).
 *
 * DIFFERENTIAL. The previous commit's two GAP characterizations -- every kind
 * saying the empty string -- are deleted rather than reworded, because they
 * stated the defect and the defect is what these assertions remove. Nothing
 * here passes at the commit that introduces it.
 *
 * The defect they held: the plan's `progression` key is read once, at flatten
 * time (`PlanQueue.flattenPlan` calls [ProgressionKind.ofPlan]) into each
 * queued set, and drawn once, by the post-set headroom grid. So the dimension
 * an exercise moves on is unreadable until the lifter has already rated a set
 * of it -- and for an exercise declared [ProgressionKind.NONE], which shows no
 * grid by design, it is never readable at all.
 *
 * The words are the owner's, with one stem un-elided: the ask reads
 * *"steps up by weight", "by reps", "by time", or "holds load"*, which is one
 * verb serving three of the four. Each of these is drawn ALONE on an exercise
 * header with no neighbouring phrase to borrow a verb from, so "by reps" on
 * its own would name a dimension without saying what happens to it. The stem
 * is repeated instead. The two phrases quoted whole -- "steps up by weight"
 * and "holds load" -- are the owner's exactly.
 */
class ProgressionPhraseTest {
    @Test
    fun `each kind names its own dimension`() {
        assertEquals("steps up by weight", ProgressionKind.WEIGHT.phrase())
        assertEquals("steps up by reps", ProgressionKind.REPS.phrase())
        assertEquals("steps up by time", ProgressionKind.TIME.phrase())
    }

    /**
     * NONE is not "no opinion" -- it is the opinion that this exercise holds
     * what it holds -- so it says something rather than saying nothing. An
     * exercise declared `"none"` is the one case a lifter could never read
     * before: it shows no post-set grid by design, so its dimension was
     * unreadable at every moment of the session.
     */
    @Test
    fun `a hold says it holds, rather than saying nothing`() {
        assertEquals("holds load", ProgressionKind.NONE.phrase())
    }

    /**
     * The omitted case, and the decision #235 asks to be pinned: an exercise
     * whose plan declared no `progression` reads EXACTLY as one that declared
     * `"weight"`, because that is what the omission means -- every plan
     * written against schema 1.10 or earlier says "weight" by saying nothing.
     *
     * What the lifter can therefore tell apart before lifting is an omission
     * from a declared `"none"`, which is the distinction the issue asks for.
     * What they cannot tell apart is an omission from a declared `"weight"`,
     * which is not a loss: the two are the same instruction.
     */
    @Test
    fun `an omitted declaration reads as the weight it means`() {
        assertEquals("steps up by weight", ProgressionKind.ofPlan(null).phrase())
        assertEquals(ProgressionKind.WEIGHT.phrase(), ProgressionKind.ofPlan(null).phrase())
    }

    /**
     * Stated as a property so a copy-paste cannot pass: four kinds, four
     * phrases, none of them empty. A phrase that duplicated another would draw
     * two different plans identically on the header the lifter reads before
     * pressing START.
     */
    @Test
    fun `no two kinds say the same thing and none of them says nothing`() {
        val phrases = ProgressionKind.entries.map { it.phrase() }
        assertEquals(ProgressionKind.entries.size, phrases.toSet().size, "phrases collide: $phrases")
        assertTrue(phrases.none { it.isBlank() }, "a kind says nothing: $phrases")
    }
}
