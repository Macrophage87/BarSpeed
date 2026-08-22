package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
     * A set measured on the clock whose exercise is a rep-based lift has no
     * movement to name, and an explosive lift is judged on peak velocity with
     * no tempo to follow. Both are named here because both are ways a set can
     * carry a tempo string and still never hear a lead-in -- which is what
     * makes a `prep_s` declared on them inert.
     */
    @Test
    fun `a timed rep-based lift and an explosive lift play no prep even carrying a tempo`() {
        assertFalse(LeadInPolicy.playsPrep(hasTempo = true, isTimed = true, kind = ExerciseKind.DYNAMIC))
        assertFalse(LeadInPolicy.playsPrep(hasTempo = true, isTimed = false, kind = ExerciseKind.EXPLOSIVE))
    }

    /**
     * A hold and a carry play a prep, which is the whole of this change stated
     * as one assertion.
     *
     * They reach it by a different route from a tempo'd lift and cannot reach
     * it by the same one: `PlanSetDef.validate` refuses a tempo on a timed set,
     * so `hasTempo` is false on every hold a plan can express. What makes their
     * start instant worth announcing is that the set is measured on the clock,
     * and the clock starts when the prep ends.
     *
     * The lifter's own words for what this is for: five to ten seconds to put
     * the phone down and get into position before the hang starts.
     */
    @Test
    fun `a hold and a carry play a prep`() {
        assertTrue(LeadInPolicy.playsPrep(hasTempo = false, isTimed = true, kind = ExerciseKind.HOLD))
        assertTrue(LeadInPolicy.playsPrep(hasTempo = false, isTimed = true, kind = ExerciseKind.CARRY))
    }

    // ---- which KIND of prep, and the word it ends on -------------------------

    /**
     * The whole truth table, one row per shape of set a plan or the ad-hoc path
     * can produce. Sixteen rows rather than a handful of examples, because the
     * rows that go wrong are the ones nobody thought to write out: a tempo on a
     * timed set, which `PlanFile.validate` refuses but the device path has no
     * validator for, and a rep-based set of an exercise whose kind is a hold.
     *
     * Expectations are literals. Deriving one from another call to
     * [LeadInPolicy.prepCase] would be a check that cannot fail.
     */
    @Test
    fun `every shape of set gets the prep its shape calls for`() {
        val expected: Map<Triple<Boolean, Boolean, ExerciseKind>, PrepCase> =
            mapOf(
                // A rep-based lift: a tempo is what makes a start instant worth
                // announcing, and the cadence is what the prep runs into.
                Triple(false, false, ExerciseKind.DYNAMIC) to PrepCase.NONE,
                Triple(true, false, ExerciseKind.DYNAMIC) to PrepCase.CUED,
                Triple(false, true, ExerciseKind.DYNAMIC) to PrepCase.NONE,
                Triple(true, true, ExerciseKind.DYNAMIC) to PrepCase.NONE,
                // A hold. Its rep-based row is not a mistake in this table:
                // `kind` says what the movement IS and the SET says how it is
                // measured, so a tempo'd rep set of a hold exercise is cued like
                // any other. `PlanFile.warnings` complains about the pairing
                // separately.
                //
                // The last row is the pairing no validator has seen: a tempo on
                // a timed set. `PlanFile.validate` refuses it, but the device
                // path carries whatever was typed into the tempo box, so it is
                // reachable ad hoc. It is TIMED rather than CUED because a set
                // measured on the clock never plays a TempoSchedule, so the
                // tempo has nothing to open.
                Triple(false, false, ExerciseKind.HOLD) to PrepCase.NONE,
                Triple(true, false, ExerciseKind.HOLD) to PrepCase.CUED,
                Triple(false, true, ExerciseKind.HOLD) to PrepCase.TIMED,
                Triple(true, true, ExerciseKind.HOLD) to PrepCase.TIMED,
                // A carry, which reaches every case the same way a hold does.
                Triple(false, false, ExerciseKind.CARRY) to PrepCase.NONE,
                Triple(true, false, ExerciseKind.CARRY) to PrepCase.CUED,
                Triple(false, true, ExerciseKind.CARRY) to PrepCase.TIMED,
                Triple(true, true, ExerciseKind.CARRY) to PrepCase.TIMED,
                // An explosive lift, in every shape: judged on peak velocity
                // with deliberately no cadence to open on.
                Triple(false, false, ExerciseKind.EXPLOSIVE) to PrepCase.NONE,
                Triple(true, false, ExerciseKind.EXPLOSIVE) to PrepCase.NONE,
                Triple(false, true, ExerciseKind.EXPLOSIVE) to PrepCase.NONE,
                Triple(true, true, ExerciseKind.EXPLOSIVE) to PrepCase.NONE,
            )

        expected.forEach { (shape, case) ->
            val (hasTempo, isTimed, kind) = shape
            assertEquals(
                case,
                LeadInPolicy.prepCase(hasTempo, isTimed, kind),
                "hasTempo=$hasTempo isTimed=$isTimed kind=$kind",
            )
        }
    }

    /**
     * [LeadInPolicy.playsPrep] is the yes/no half of the same rule and cannot
     * disagree with it. Checked over the same sixteen shapes rather than
     * asserted in prose, because two statements of one rule is how the import
     * gate ends up warning about a prep that plays.
     */
    @Test
    fun `playsPrep answers the same question as prepCase`() {
        ExerciseKind.entries.forEach { kind ->
            listOf(false, true).forEach { hasTempo ->
                listOf(false, true).forEach { isTimed ->
                    assertEquals(
                        LeadInPolicy.prepCase(hasTempo, isTimed, kind) != PrepCase.NONE,
                        LeadInPolicy.playsPrep(hasTempo, isTimed, kind),
                        "hasTempo=$hasTempo isTimed=$isTimed kind=$kind",
                    )
                }
            }
        }
    }

    /**
     * The word a timed set's prep ends on, which is the movement's own name.
     * `Down` and `Up` name what a dynamic set is about to do; `Hold` and `Carry`
     * do the same job for a set that has one movement lasting the whole of it.
     *
     * A rep-based lift and an explosive lift have no such word, and that is the
     * fact `prepCase` leans on: it can only answer [PrepCase.TIMED] where this
     * answers something, so no prep can end in silence.
     */
    @Test
    fun `a timed set opens on the name of the movement`() {
        assertEquals("Hold", LeadInPolicy.timedStartWord(ExerciseKind.HOLD))
        assertEquals("Carry", LeadInPolicy.timedStartWord(ExerciseKind.CARRY))
        assertNull(LeadInPolicy.timedStartWord(ExerciseKind.DYNAMIC))
        assertNull(LeadInPolicy.timedStartWord(ExerciseKind.EXPLOSIVE))
    }

    /**
     * The invariant that pairing buys: nothing can reach [PrepCase.TIMED]
     * without a word to end on.
     *
     * Weak on its own and said so: it is satisfied by a table with no TIMED row
     * in it at all. What makes it worth keeping is the row count beside it --
     * `every shape of set gets the prep its shape calls for` states which shapes
     * are TIMED, and this states that each of them can be spoken.
     */
    @Test
    fun `no shape of set plays a prep with no word to end it on`() {
        ExerciseKind.entries.forEach { kind ->
            listOf(false, true).forEach { hasTempo ->
                listOf(false, true).forEach { isTimed ->
                    if (LeadInPolicy.prepCase(hasTempo, isTimed, kind) == PrepCase.TIMED) {
                        assertNotNull(
                            LeadInPolicy.timedStartWord(kind),
                            "hasTempo=$hasTempo isTimed=$isTimed kind=$kind plays a prep that ends in silence",
                        )
                    }
                }
            }
        }
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

    // ---- what the audio-cues setting silences -------------------------------

    /**
     * The toggle silences a hold end to end, and does not silence a tempo'd
     * lift. The owner chose the split.
     *
     * A tempo'd set's voice is the feature: prescribing a tempo IS asking to be
     * paced, so the prep and the stroke calls speak whatever the toggle says. A
     * hold's voice is an aid, and the toggle declines it -- with cues off a hold
     * says nothing at all, prep included.
     */
    @Test
    fun `the toggle silences a timed set end to end and never a cued one`() {
        assertTrue(LeadInPolicy.speaks(PrepCase.CUED, audioCues = true))
        assertTrue(LeadInPolicy.speaks(PrepCase.CUED, audioCues = false))
        assertTrue(LeadInPolicy.speaks(PrepCase.TIMED, audioCues = true))
        assertFalse(LeadInPolicy.speaks(PrepCase.TIMED, audioCues = false))
        assertTrue(LeadInPolicy.speaks(PrepCase.NONE, audioCues = true))
        assertFalse(LeadInPolicy.speaks(PrepCase.NONE, audioCues = false))
    }
}
