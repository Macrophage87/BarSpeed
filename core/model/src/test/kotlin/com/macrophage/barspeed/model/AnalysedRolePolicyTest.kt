package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which unit of a two-unit set the figures come from once the set has declared
 * where one unit was mounted, and why the answer is the answer. Issue #278.
 *
 * Every case here is synthetic, because the point of each is to hold
 * everything but one input still. The same rule over real streams --
 * eighteen stack-declared two-unit sets, seven of them committed as fixtures
 * -- is `StackMountFieldTest` in `:core:dsp`, which is also where the
 * thresholds behind [StackMountSignal] are pinned.
 *
 * WHAT NONE OF THESE CLAIMS: that a signal is correct about a mount. A
 * [StackMountSignal] is a reading of a stream, this file hands it in as a
 * given, and no test anywhere can say where a magnet was stuck.
 */
class AnalysedRolePolicyTest {
    private val both = listOf(SensorRole.A, SensorRole.B)
    private val delivered = mapOf(SensorRole.A to 100, SensorRole.B to 100)

    private fun choose(
        armed: SensorRole? = SensorRole.A,
        expected: List<SensorRole> = both,
        frames: Map<SensorRole, Int> = delivered,
        stack: Boolean = true,
        signals: Map<SensorRole, StackMountSignal> = emptyMap(),
    ) = AnalysedRolePolicy.choose(armed, expected, frames, stack, signals)

    /**
     * The ordinary one-sensor set: no role in play, nothing to choose between,
     * and the basis says the role was the one the set armed.
     */
    @Test
    fun `a set with no role in play is analysed as declared`() {
        val choice = choose(armed = null, expected = emptyList(), frames = emptyMap())

        assertEquals(AnalysedRoleChoice(null, fellBack = false, basis = AnalysedRoleBasis.DECLARED), choice)
    }

    /**
     * NO STACK DECLARATION, NOTHING CONSULTED. On a barbell set both units
     * rotate alike -- field-43's deadlift pair measures 367.2 and 17.2 degrees
     * over the same window -- so a rule that read the signature there would
     * choose between two units on one bar by whichever noise crossed a
     * threshold.
     */
    @Test
    fun `a set that declares no stack mount keeps its armed unit whatever the roll says`() {
        val choice =
            choose(
                stack = false,
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /**
     * THE DEFECT #278 EXISTS FOR. The set declares a stack mount, the armed
     * unit's roll says it was not on the stack, the partner's says it was, and
     * the figures come from the partner -- the unit the declaration actually
     * describes.
     */
    @Test
    fun `the analysis moves to the unit whose roll says it rode the stack`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.ON_STACK,
                ),
            )

        assertEquals(SensorRole.B, choice.role)
        assertEquals(AnalysedRoleBasis.STACK_SIGNATURE, choice.basis)
    }

    /**
     * AND IT DOES NOT SET THE FALLBACK FLAG, which is asserted on its own
     * because a coupling is what would break here.
     * `RecordedSensors.analysedFellBack` means the armed unit delivered too few
     * frames, `SetAnalyzer` refuses a fallback under a mount-specific
     * declaration on exactly that flag (#247), and a signature flip riding it
     * would blank every set this change exists to repair.
     */
    @Test
    fun `a signature flip is not a fallback`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.ON_STACK,
                ),
            )

        assertFalse(choice.fellBack, "the flip set the flag #247's refusal keys off")
        assertTrue(choice.role != SensorRole.A, "the analysis did not move at all")
    }

    /**
     * The armed unit IS the one on the stack: nothing moves, and the basis says
     * the signature confirmed it rather than that nobody looked. Those are
     * different statements about the same row and neither is derivable from the
     * other keys -- which is why the basis is stored rather than inferred.
     */
    @Test
    fun `a signature that confirms the armed unit moves nothing and still says so`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.ON_STACK,
                    SensorRole.B to StackMountSignal.NOT_ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.STACK_SIGNATURE, choice.basis)
        assertFalse(choice.fellBack)
    }

    /**
     * NEITHER UNIT QUALIFYING LEAVES THE SET EXACTLY AS IT IS TODAY, and that
     * is the failure direction the whole rule is built around. Field-41's three
     * cable face pulls are the real instance: the handle unit sweeps 72 to 211
     * degrees and the unit on the stack still reads 12.9 to 27.1, which is over
     * the bound.
     */
    @Test
    fun `neither unit qualifying leaves the armed unit analysed`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.NOT_ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /**
     * BOTH UNITS QUALIFYING LEAVES IT ALONE TOO, and this is not a corner: it
     * is the ordinary shape of a machine the lifter puts both units on.
     * Field-43's three seated leg curls measure 0.3 to 1.6 degrees on both
     * roles at once.
     */
    @Test
    fun `both units qualifying leaves the armed unit analysed`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.ON_STACK,
                    SensorRole.B to StackMountSignal.ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /** An unmeasured partner is not a stack candidate: absence, never a flat reading. */
    @Test
    fun `an unmeasured partner cannot take the analysis`() {
        val choice =
            choose(
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.UNMEASURED,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /** A role with no entry at all reads the same way, rather than defaulting to a verdict. */
    @Test
    fun `a set nothing measured is analysed as declared`() {
        val choice = choose(signals = emptyMap())

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /**
     * FRAMES DECIDE FIRST. The armed unit's roll says it rode the stack and it
     * delivered three frames; `VelocityEstimator.estimate` refuses anything
     * below [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES], so pointing the DSP at
     * it would publish an empty summary over the partner's full capture -- #209
     * exactly. The fallback stands and carries its own basis.
     */
    @Test
    fun `a stack signature cannot point the analysis at a buffer the DSP refuses`() {
        val choice =
            choose(
                frames = mapOf(SensorRole.A to 3, SensorRole.B to 100),
                signals = mapOf(
                    SensorRole.A to StackMountSignal.ON_STACK,
                    SensorRole.B to StackMountSignal.NOT_ON_STACK,
                ),
            )

        assertEquals(SensorRole.B, choice.role)
        assertTrue(choice.fellBack, "the armed unit sent three frames and the flag says otherwise")
        assertEquals(AnalysedRoleBasis.FALLBACK, choice.basis)
    }

    /**
     * And the mirror: a PARTNER too short to analyse cannot take the analysis
     * on the strength of its roll either. Both units have to have delivered
     * before there is a choice to make at all.
     */
    @Test
    fun `a partner below the frame bound cannot take the analysis on its roll`() {
        val choice =
            choose(
                frames = mapOf(SensorRole.A to 100, SensorRole.B to 3),
                signals = mapOf(
                    SensorRole.A to StackMountSignal.NOT_ON_STACK,
                    SensorRole.B to StackMountSignal.ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertFalse(choice.fellBack)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis)
    }

    /**
     * A SIGNATURE VERDICT NEEDS TWO STREAMS TO COMPARE, so a set where only the
     * armed unit delivered reads `declared` even though that unit's own roll
     * says it rode the stack.
     *
     * Found by mutation, not by design: relaxing the both-delivered guard to
     * "at least one delivered" left every other case in this file green,
     * because each of them has two analysable roles. What the relaxed rule
     * would publish is `stackSignature` on a set where nothing was compared --
     * a claim that a rule chose between two units when there was only one.
     */
    @Test
    fun `a set where only the armed unit delivered is analysed as declared`() {
        val choice =
            choose(
                frames = mapOf(SensorRole.A to 100, SensorRole.B to 3),
                signals = mapOf(
                    SensorRole.A to StackMountSignal.ON_STACK,
                    SensorRole.B to StackMountSignal.NOT_ON_STACK,
                ),
            )

        assertEquals(SensorRole.A, choice.role)
        assertEquals(AnalysedRoleBasis.DECLARED, choice.basis, "a verdict was claimed with nothing to compare")
    }

    /** The published vocabulary, 1:1 with the enum so neither can move alone. */
    @Test
    fun `the basis vocabulary is the three words the schema publishes`() {
        assertEquals(
            listOf("declared", "stackSignature", "fallback"),
            AnalysedRoleBasis.entries.map { it.published },
        )
    }
}
