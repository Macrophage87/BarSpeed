package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The regime decision, over every input that changes it (#250).
 *
 * GREEN FROM BIRTH, and stated as that rather than dressed up: this is a new
 * pure symbol, so there was no behaviour for a c2 red to differ from. The
 * evidence that these pins bite is the mutation table in the commit body --
 * each row breaks one clause of `VelocityLossRegime.of` and names the tests
 * that go red -- not a red CI run that could not have existed.
 *
 * The table is written as a cross product of the four inputs rather than as
 * one assertion per interesting case, because the failure this guards against
 * is a rule read off digit 3 blindly: that is invisible unless the SAME tempo
 * is asserted against both drive directions and both planes.
 */
class VelocityLossRegimeTest {
    /**
     * [horizontal] defaults to false so that every row written before the
     * plane became an input still reads as the vertical lift it was always
     * about. A row that is about the plane says so.
     */
    private fun of(tempo: String?, concentricUp: Boolean?, kind: ExerciseKind?, horizontal: Boolean? = false) =
        VelocityLossRegime.of(tempo, concentricUp, horizontal, kind)

    // ---- 1. the three max-intent cases the owner named ----

    @Test
    fun `straight reps are max intent whichever way the drive moves`() {
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, true, ExerciseKind.DYNAMIC))
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, false, ExerciseKind.DYNAMIC))
    }

    @Test
    fun `an explosive lift is max intent even when a tempo is written on it`() {
        // Representable and contradictory: the plan schema calls an explosive
        // lift one "judged on peak velocity with no tempo", and nothing stops
        // an author writing both. The owner's rule puts kind first, so the
        // tempo does not turn a snatch into a compliance set.
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, true, ExerciseKind.EXPLOSIVE))
        assertEquals(VelocityLossRegime.MAX_INTENT, of("2010", true, ExerciseKind.EXPLOSIVE))
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30X0", true, ExerciseKind.EXPLOSIVE))
    }

    @Test
    fun `an X concentric is max intent on a drive that moves up`() {
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30X0", true, ExerciseKind.DYNAMIC))
        assertEquals(VelocityLossRegime.MAX_INTENT, of("3-0-X-0", true, ExerciseKind.DYNAMIC))
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30x0", true, ExerciseKind.DYNAMIC))
    }

    // ---- 2. controlled, which is everything else ----

    @Test
    fun `a numbered concentric digit is controlled`() {
        listOf("2011", "1120", "4010", "3010", "0000", "3-0-1_5-0".replace('_', '.')).forEach {
            assertEquals(
                VelocityLossRegime.CONTROLLED,
                of(it, true, ExerciseKind.DYNAMIC),
                "$it prescribes the drive's speed, so its velocity loss is compliance",
            )
        }
    }

    /**
     * THE CASE A DIGIT-3 READING GETS BACKWARDS.
     *
     * On a leg curl, a lat pulldown or a pushdown the drive moves DOWN, so the
     * concentric is digit 1 and digit 3 is the return. `30X0` on such a lift
     * prescribes a three-second DRIVE and an explosive RETURN -- the opposite
     * reading from the same string on a bench press -- and it is controlled.
     */
    @Test
    fun `an X in digit 3 is the eccentric on a drive that moves down, so the set is controlled`() {
        assertEquals(VelocityLossRegime.CONTROLLED, of("30X0", false, ExerciseKind.DYNAMIC))
        assertEquals(VelocityLossRegime.CONTROLLED, of("1030", false, ExerciseKind.DYNAMIC))
    }

    // ---- 2b. the plane, which decides the digit before the direction does ----

    /**
     * Horizontal work read by PHASE, the half of the rule these pins never
     * asked about until round 1 finding 2 named it.
     *
     * `TempoSchedule.of` sets `digit1IsConcentric = if (horizontal) false else
     * !concentricUp`, so on a seated row or a chest press digit 3 is the
     * concentric whatever `concentricUp` holds -- there is no up or down for a
     * positional reading to attach to. These three rows are the ones today's
     * plane-blind rule already answers correctly; the row it gets wrong is the
     * one the next commit reds.
     */
    @Test
    fun `a horizontal set with a numbered digit 3 is controlled`() {
        assertEquals(VelocityLossRegime.CONTROLLED, of("2011", true, ExerciseKind.DYNAMIC, horizontal = true))
        assertEquals(VelocityLossRegime.CONTROLLED, of("2011", false, ExerciseKind.DYNAMIC, horizontal = true))
    }

    @Test
    fun `a horizontal set with no tempo is max intent, plane or no plane`() {
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, true, ExerciseKind.DYNAMIC, horizontal = true))
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, false, ExerciseKind.DYNAMIC, horizontal = true))
    }

    /**
     * THE CASE THE PLANE-BLIND RULE GETS BACKWARDS -- round 1 finding 2.
     *
     * On a chest-supported row or a chest-press machine there is no up or
     * down for a positional reading to attach to, so digit 3 is the concentric
     * by PHASE whatever `concentric` the plan declared beside `plane`. The two
     * keys are independent in the plan schema and `SetGeometryPolicy.resolve`
     * stores whatever each one said, so `plane: horizontal` with
     * `concentric: down` is representable and nothing rejects it.
     *
     * `30X0` on such a set is an explosive DRIVE. The voice guide says so --
     * `TempoSchedule.of` sets `digit1IsConcentric = if (horizontal) false else
     * !concentricUp` -- and the tempo scorer leaves that stroke unscored for
     * the same reason. The regime must agree with them, and the pair below is
     * what makes the disagreement visible: the SAME tempo on the SAME plane
     * with the drive declared each way.
     */
    @Test
    fun `an X in digit 3 is the concentric on any horizontal set, whichever way the drive is declared`() {
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30X0", true, ExerciseKind.DYNAMIC, horizontal = true))
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30X0", false, ExerciseKind.DYNAMIC, horizontal = true))
    }

    /**
     * And because the plane answers it outright, the drive direction is not
     * needed at all on horizontal work -- unlike vertical work, where a
     * missing direction leaves an X in digit 3 undecidable.
     */
    @Test
    fun `a horizontal X concentric needs no drive direction`() {
        assertEquals(VelocityLossRegime.MAX_INTENT, of("30X0", null, ExerciseKind.DYNAMIC, horizontal = true))
        assertNull(of("30X0", null, ExerciseKind.DYNAMIC, horizontal = false), "vertical still needs one")
    }

    @Test
    fun `a horizontal hold gets no word, the same as a vertical one`() {
        assertNull(of("2011", true, ExerciseKind.HOLD, horizontal = true))
        assertNull(of(null, false, ExerciseKind.CARRY, horizontal = true))
    }

    /**
     * Field-38's own prescriptions, each with the drive direction the set was
     * actually recorded with -- read out of
     * `field-38/extracted/session.json`, not from the issue's prose.
     *
     * Every one of the session's sixteen dynamic sets lands in the controlled
     * regime, which is the whole claim: sixteen sets whose published headline
     * was a fatigue figure computed over strokes the plan had fixed the speed
     * of. The two `1120` rows are the pushdown and the lat pulldown, whose
     * drive moves DOWN, so their concentric digit is the leading `1` and not
     * the `2`; asserting them with `concentricUp = true` would pass for the
     * wrong reason.
     *
     * CORRECTION, carried forward rather than reworded away: this list was
     * written as `2011, 2010, 1120, 3010, 2012` and field-38 prescribes no
     * `2012` at all. The four below are the four the capture contains.
     */
    @Test
    fun `every tempo field-38 prescribed is controlled`() {
        // 3010: dumbbell incline press, seated overhead press.
        // 2011: chest-supported rear delt fly, seated lateral raise.
        // 2010: seated biceps curl.
        // 1120: triceps pushdown, lat pulldown -- the drive pulls DOWN.
        val prescribed = listOf("3010" to true, "2011" to true, "2010" to true, "1120" to false)
        prescribed.forEach { (tempo, concentricUp) ->
            assertEquals(
                VelocityLossRegime.CONTROLLED,
                of(tempo, concentricUp, ExerciseKind.DYNAMIC),
                "$tempo, drive ${if (concentricUp) "up" else "down"}",
            )
        }
    }

    // ---- 3. absence, which is a state and not a word ----

    @Test
    fun `a set with no stored geometry gets no word`() {
        assertNull(of("2011", null, null), "no kind: the set cannot be placed")
        assertNull(of(null, null, null))
    }

    @Test
    fun `a hold and a carry get no word, because neither is about a concentric`() {
        assertNull(of(null, true, ExerciseKind.HOLD))
        assertNull(of(null, true, ExerciseKind.CARRY))
        assertNull(of("2011", true, ExerciseKind.HOLD))
    }

    @Test
    fun `a tempo this build cannot parse gets no word, which is not the same as no tempo`() {
        // Something was prescribed and the digits do not say what. Reading it
        // as straight reps would publish maxIntent over a set that may well
        // have been a compliance set.
        assertNull(of("nonsense", true, ExerciseKind.DYNAMIC))
        assertNull(of("301", true, ExerciseKind.DYNAMIC))
        assertEquals(VelocityLossRegime.MAX_INTENT, of(null, true, ExerciseKind.DYNAMIC), "no tempo IS decidable")
    }

    /**
     * The one case where the drive direction is genuinely needed and missing.
     *
     * Digit 3 is X, so which stroke the drive is decides the answer, and no
     * geometry says. Everything else with a tempo is decidable without a
     * direction, because digit 1 has no explosive form under today's contract
     * (#258) -- so a numbered digit 3 means a numbered concentric whichever
     * way the lift goes.
     */
    @Test
    fun `an X concentric with no drive direction is undecidable rather than guessed`() {
        assertNull(of("30X0", null, ExerciseKind.DYNAMIC))
        assertEquals(
            VelocityLossRegime.CONTROLLED,
            of("2011", null, ExerciseKind.DYNAMIC),
            "a numbered digit 3 needs no direction: digit 1 cannot be X, so the drive is numbered either way",
        )
    }

    // ---- 4. the published vocabulary ----

    @Test
    fun `the wire words are the two the export publishes`() {
        assertEquals(setOf("maxIntent", "controlled"), SessionExport.VALID_VELOCITY_LOSS_REGIMES)
        assertEquals("maxIntent", VelocityLossRegime.MAX_INTENT.wireName)
        assertEquals("controlled", VelocityLossRegime.CONTROLLED.wireName)
    }

    @Test
    fun `velocity loss leads on a max-intent set and not on a controlled one`() {
        assertTrue(VelocityLossRegime.MAX_INTENT.readsVelocityLoss)
        assertTrue(!VelocityLossRegime.CONTROLLED.readsVelocityLoss)
    }
}
