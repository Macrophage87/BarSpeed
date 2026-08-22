package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What decides the prep before a guided set.
 *
 * The precedence and the clamp are here, in a module with a test source set,
 * rather than in `:app` where the record flow reads them. `:app` has no test
 * source set at all, and the last time per-second lead-in arithmetic lived there
 * it added a beat the prescription never asked for, on every set the app had
 * ever paced, with nothing able to assert otherwise -- that is issue 106.
 *
 * ## Literals, not the constants
 *
 * Every expectation below is a literal. Asserting `resolve(null, null) ==
 * DEFAULT_S` would pass for any value of `DEFAULT_S` including a wrong one,
 * which is a check that cannot fail.
 *
 * That leaves one thing literals cannot state -- whether the digits still have
 * the reason they were chosen for -- and it is not stated here. The relationship
 * between [LeadInPolicy.MIN_USEFUL_S] and the length of the launch phrase is
 * pinned in `:core:dsp`, the module that can see both.
 */
class LeadInPolicyTest {
    // ---- the default, and what an untouched plan does ------------------------

    /**
     * A plan that declares no prep gets five seconds, which is what every plan
     * got before prep was declarable at all. This is the whole of the
     * no-behaviour-change claim for existing plans, stated as a number.
     */
    @Test
    fun `a plan that declares nothing gets the prep every plan got before`() {
        assertEquals(5, LeadInPolicy.resolve(declaredS = null, adjustedS = null))
        assertEquals(5, LeadInPolicy.planned(declaredS = null))
    }

    // ---- precedence ---------------------------------------------------------

    @Test
    fun `the plan's declaration is used when the lifter has not adjusted`() {
        assertEquals(20, LeadInPolicy.resolve(declaredS = 20, adjustedS = null))
    }

    /**
     * The lifter's adjustment beats the plan. A plan is regenerated weekly from a
     * fresh prompt and carries no correction forward, so a plan-wins rule would
     * throw the lifter's fix away every Monday.
     */
    @Test
    fun `the lifter's adjustment beats the plan's declaration`() {
        assertEquals(2, LeadInPolicy.resolve(declaredS = 20, adjustedS = 2))
    }

    /**
     * An adjustment applies with no declaration behind it, which is the ad-hoc
     * case: no plan, so nothing to beat, and the adjustment must still win over
     * the default.
     */
    @Test
    fun `an adjustment applies with no declaration behind it`() {
        assertEquals(30, LeadInPolicy.resolve(declaredS = null, adjustedS = 30))
    }

    /**
     * An adjustment of zero is an adjustment, not an absence. This is the case a
     * nullable field exists for: `adjustedS = 0` means the lifter asked for no
     * countdown, and folding it into "nothing set" would silently give them five
     * seconds they explicitly removed.
     */
    @Test
    fun `an adjustment of zero wins, rather than reading as no adjustment`() {
        assertEquals(0, LeadInPolicy.resolve(declaredS = 20, adjustedS = 0))
    }

    /**
     * The same for a declaration of zero: a plan that says the cable machine is
     * ready instantly is making a statement, and it is not the default.
     */
    @Test
    fun `a declaration of zero wins over the default`() {
        assertEquals(0, LeadInPolicy.resolve(declaredS = 0, adjustedS = null))
        assertEquals(0, LeadInPolicy.planned(declaredS = 0))
    }

    // ---- the clamp, which is on the device path -----------------------------

    /**
     * A negative prep is pulled to zero rather than passed through.
     *
     * Load-bearing rather than defensive. `LeadInPlan.of` builds its beats with
     * `List(prepS)`, which throws `IllegalArgumentException: Illegal Capacity: -1`
     * on a negative -- inside `:app`, where nothing catches it, while a set is
     * being started. The plan path refuses a negative before this and names the
     * JSON path; this covers everything the plan path never sees, which is a
     * value read back from device settings.
     */
    @Test
    fun `a negative prep is pulled to zero rather than reaching the beat list`() {
        assertEquals(0, LeadInPolicy.resolve(declaredS = null, adjustedS = -1))
        assertEquals(0, LeadInPolicy.resolve(declaredS = -30, adjustedS = null))
        assertEquals(0, LeadInPolicy.clamp(-1))
    }

    @Test
    fun `a prep beyond the ceiling is pulled back to it`() {
        assertEquals(120, LeadInPolicy.resolve(declaredS = null, adjustedS = 600))
        assertEquals(120, LeadInPolicy.planned(declaredS = 3_600))
        assertEquals(120, LeadInPolicy.clamp(121))
    }

    @Test
    fun `the bounds themselves are inside the bounds`() {
        assertEquals(0, LeadInPolicy.clamp(0))
        assertEquals(120, LeadInPolicy.clamp(120))
    }

    // ---- which sets play a prep at all --------------------------------------

    @Test
    fun `a tempo set of an ordinary lift plays a prep`() {
        assertTrue(LeadInPolicy.playsPrep(hasTempo = true, isTimed = false, kind = ExerciseKind.DYNAMIC))
    }

    @Test
    fun `no tempo, no voice guide, no prep`() {
        assertFalse(LeadInPolicy.playsPrep(hasTempo = false, isTimed = false, kind = ExerciseKind.DYNAMIC))
    }

    /**
     * A timed set runs a stopwatch rather than a cadence, and an explosive lift
     * is judged on peak velocity with no tempo to follow. Both are named here
     * because both are ways a set can carry a tempo string and still never hear
     * a lead-in -- which is what makes a `prep_s` declared on them inert.
     */
    @Test
    fun `a timed set and an explosive lift play no prep even carrying a tempo`() {
        assertFalse(LeadInPolicy.playsPrep(hasTempo = true, isTimed = true, kind = ExerciseKind.DYNAMIC))
        assertFalse(LeadInPolicy.playsPrep(hasTempo = true, isTimed = false, kind = ExerciseKind.EXPLOSIVE))
    }

    // ---- the relationship between two constants that are both 2 -------------

    /**
     * The one assertion here that is about a constant rather than about a
     * result, and the weakest thing in this file -- said so it is not mistaken
     * for more.
     *
     * [LeadInPolicy.MIN_USEFUL_S] is 2 because the launch phrase occupies 2
     * seconds, and this states only the digit. It CANNOT catch the failure it
     * exists near: `LeadInPlan.PHRASE_S` growing to 3 while this stays at 2
     * would leave the import gate warning about the wrong number, and this test
     * would still pass. That check needs a module that can see both constants,
     * and this one cannot see `:core:dsp` at all -- it is
     * `LeadInPlanTest.the prep the import gate warns below is the length of the
     * launch phrase`, in `:core:dsp`, which is where the arrow
     * runs the right way. The same two-hop arrangement `VALID_VELOCITY_LOSS_BASES`
     * uses.
     */
    @Test
    fun `the useful minimum is two seconds`() {
        assertEquals(2, LeadInPolicy.MIN_USEFUL_S)
    }
}
