package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The seams #244 is built on, pinned at the commit that declares them.
 *
 * GREEN HERE ON PURPOSE. [EffortScale.askFor] and
 * [NextSetNudgePolicy.suggestedStep] both exist at this commit answering what
 * the app already answered -- the set's KIND for the first, nothing at all for
 * the second -- so what these assertions record is the BEFORE. The
 * differentials that red against them live in `HeadroomScaleDifferentialTest`
 * and arrive next.
 *
 * The wire-vocabulary pin below is not a seam and is expected to hold
 * permanently: [SessionExport.VALID_RPE_SCALES] and [EffortAsk] are two
 * statements of one closed list, one of them published, and this is the only
 * thing that can see both.
 */
class HeadroomScaleContractTest {
    /**
     * The published word list and the enum that produces it agree, in BOTH
     * directions.
     *
     * Both directions because the two failures differ: a word in the schema
     * with no enum constant is a value the app can never write, and an enum
     * constant with no word is a rating the app can take and the archive
     * cannot name.
     */
    @Test
    fun `the published scale words are exactly the asks the app can make`() {
        assertEquals(
            EffortAsk.entries.map { it.word }.toSet(),
            SessionExport.VALID_RPE_SCALES,
            "VALID_RPE_SCALES and EffortAsk disagree about which scales exist",
        )
        assertEquals(
            EffortAsk.entries.size,
            SessionExport.VALID_RPE_SCALES.size,
            "two asks share one published word, so the archive cannot tell them apart",
        )
    }

    /**
     * No word is a Kotlin name that happens to lowercase correctly by
     * accident -- each is spelled out on its constant.
     */
    @Test
    fun `every scale word is lower case and matches its constant`() {
        EffortAsk.entries.forEach {
            assertEquals(it.name.lowercase(), it.word, "${it.name} publishes a word that is not its own name")
        }
    }

    // ---- the seams, answering what the app answers today ----

    /**
     * The ask is chosen by the set's KIND alone at this commit, whatever the
     * exercise declares. That is the defect; this records it as the seam's
     * current body so the next commit's differentials have something exact to
     * fail against.
     */
    @Test
    fun `askFor ignores the progression and answers from the set kind`() {
        val declarations: List<ProgressionKind?> = ProgressionKind.entries + listOf(null)
        declarations.forEach {
            assertEquals(EffortAsk.LOAD, EffortScale.askFor(timed = false, progression = it), "dynamic, $it")
            assertEquals(EffortAsk.TIME, EffortScale.askFor(timed = true, progression = it), "timed, $it")
        }
    }

    /**
     * A null declaration and a declared `"weight"` are the SAME answer, and
     * they must stay the same answer after the fix.
     *
     * This is the half of the seam that does not move: an omitted
     * `progression` key means weight ([ProgressionKind.ofPlan]), so no set
     * recorded against a plan written before schema 1.11 is asked a different
     * question than it was.
     */
    @Test
    fun `an omitted declaration and an ad-hoc set ask exactly what weight asks`() {
        listOf(false, true).forEach { timed ->
            assertEquals(
                EffortScale.askFor(timed, ProgressionKind.WEIGHT),
                EffortScale.askFor(timed, null),
                "an omitted progression asks a different question from a declared weight (timed=$timed)",
            )
        }
    }

    /** Nothing is suggested at this commit; the grid draws six equal tiles. */
    @Test
    fun `suggestedStep suggests nothing yet`() {
        val offered =
            NextSetNudgePolicy.options(
                tier = HeadroomTier.ONE_INCREMENT,
                failed = false,
                warmup = false,
                setsLeftInExercise = 3,
                progression = ProgressionKind.WEIGHT,
                unit = WeightUnit.LB,
            )
        HeadroomTier.entries.forEach {
            assertNull(NextSetNudgePolicy.suggestedStep(it, offered), "$it already suggests a step")
        }
    }
}
