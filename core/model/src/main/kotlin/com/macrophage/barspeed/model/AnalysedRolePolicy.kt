package com.macrophage.barspeed.model

/**
 * What one unit's own stream says about whether that unit rode the load,
 * issue #278.
 *
 * MEASURED, NOT DECLARED, and that is the whole point of the type. No per-role
 * mount field exists anywhere -- not on [ExerciseDef], not on the set row, not
 * in either published document -- so on a two-unit set the second unit's mount
 * has always been INFERRED from the exercise. The owner's rule is what makes a
 * measurement possible at all: *"For the pushdown and pulldown, the weight
 * stacks shouldn't have a lot of roll."* (owner, 2026-09-12).
 *
 * `StackRollSignature` in `:core:dsp` is what produces these values and holds
 * the thresholds and their provenance. The vocabulary lives HERE because
 * [AnalysedRolePolicy] consumes it and `:core:model` cannot see `:core:dsp` --
 * the dependency points the other way. The arithmetic is in `:core:dsp` with
 * the rest of the signal processing; only the three words it can answer are
 * here.
 *
 * WHAT IT IS NOT. It is a statement about a STREAM's reported roll, never
 * about where a magnet was stuck: `roll_deg` is the sensor's own fused
 * attitude estimate, and no device has been run against this code. A unit
 * lying still on a bench reads [ON_STACK] as readily as one clipped to a
 * stack, which is why nothing consults this except on a set that has already
 * DECLARED a stack mount for one of its units.
 */
enum class StackMountSignal {
    /** The stream's roll barely moved: consistent with a unit riding the load. */
    ON_STACK,

    /** The stream's roll swept or spun: not consistent with a unit riding the load. */
    NOT_ON_STACK,

    /**
     * Nothing measured it.
     *
     * The working window held too few samples to take a range over, which is
     * a set the app has no rotation figure for either. Absence, never a low
     * number: reading "no rotation measured" as "did not rotate" is exactly
     * the reassuring answer a reader deciding whether to trust a set must not
     * be handed.
     */
    UNMEASURED,
}

/**
 * Why the analysed role of a set is the role it is (#278).
 *
 * Published beside `analysedRole` rather than left to be derived, because two
 * of the three cases are not derivable from anything else the document
 * carries. A reader can see THAT the analysed role is not the first role in
 * `expected`; it cannot see whether a signature confirmed the armed unit or
 * whether nothing looked at all, and those are different statements about how
 * far the set's figures can be trusted.
 */
enum class AnalysedRoleBasis(val published: String) {
    /**
     * The role the set armed for analysis, with nothing else consulted.
     *
     * Every single-sensor set, every two-unit set whose declaration names no
     * stack mount, and a stack-declared set whose two units could not be told
     * apart by their roll -- both still, or neither still, or one of them
     * unmeasured. The last of those is the case worth naming: it means the
     * rule RAN and declined, which is not the same as the rule not existing.
     */
    DECLARED("declared"),

    /**
     * The set declared a stack mount, both units delivered, and exactly one
     * unit's roll signature said it was the one on the stack.
     *
     * That unit is analysed whether or not it is the one the set armed. Where
     * it is the armed unit this word is a CONFIRMATION and the analysed role
     * is unchanged; where it is not, the analysis moved -- and
     * [RecordedSensors.analysedFellBack] stays FALSE, because that flag means
     * "the armed unit delivered too few frames" and is what issue #247's
     * refusal keys off.
     */
    STACK_SIGNATURE("stackSignature"),

    /**
     * The armed unit delivered too few frames to analyse and another unit
     * delivered enough, so the figures come from the one that did (#207,
     * widened by #209).
     *
     * The frame count decides before any signature does, and the reason is not
     * a preference: a buffer below [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]
     * is one `VelocityEstimator.estimate` refuses outright, so a unit whose
     * roll says "stack" cannot be analysed if it sent nothing. This is
     * [AnalysedStream.fellBack] and the same population it always named.
     */
    FALLBACK("fallback"),
}

/**
 * Which role's stream a set's figures come from, whether that is the role it
 * armed, and why -- decided together so the three cannot disagree.
 *
 * [AnalysedStream] answers the first two and is what [SensorCapturePolicy]
 * produces; this carries the third beside them rather than beside nothing. A
 * caller that asked for the role and separately asked why would be asking one
 * question twice.
 */
data class AnalysedRoleChoice(
    val role: SensorRole?,
    val fellBack: Boolean,
    val basis: AnalysedRoleBasis,
)

/**
 * Which unit of a two-unit set the figures are computed from, when the set
 * declared where ONE of them was mounted (#278).
 *
 * ## The defect this exists for
 *
 * A set's declaration -- `sensorOnStack`, `sensorInverted`, `travelRatio` --
 * describes the mount of the ARMED unit and there is no per-role mount field
 * to describe the other. On field-42's three seated cable rows the armed unit
 * was the one clipped to the rotating HANDLE while `sensorOnStack` was
 * declared true, and the analysis read 3, 4 and 2 reps of the 8 the lifter
 * performed. The partner unit, sitting on the stack, is the one the
 * declaration actually describes.
 *
 * ## The rule, in order
 *
 * 1. FRAMES FIRST. [SensorCapturePolicy.analysedFrom] decides, and where it
 *    fell back that answer stands with basis [AnalysedRoleBasis.FALLBACK]. A
 *    unit the DSP cannot run on cannot be analysed whatever its roll says.
 * 2. NO STACK DECLARATION, NOTHING TO CHECK: basis
 *    [AnalysedRoleBasis.DECLARED]. The signature says only where a unit was
 *    NOT, and on a barbell set both units rotate alike -- field-43's deadlifts
 *    measure 367.2 and 17.2 degrees over their working windows -- so consulting
 *    it there would choose between two units on one bar by noise.
 * 3. BOTH UNITS MUST HAVE DELIVERED. With one stream there is no choice to
 *    make, and with a partner below [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]
 *    the alternative is a buffer the analysis would refuse: basis
 *    [AnalysedRoleBasis.DECLARED].
 * 4. EVERY ANALYSABLE UNIT MUST HAVE BEEN READ. A role whose signal is
 *    [StackMountSignal.UNMEASURED], or which has no entry at all, is one
 *    nothing measured -- absence, not a failed candidate -- so there is no
 *    pair to tell apart and no verdict to publish: basis
 *    [AnalysedRoleBasis.DECLARED]. Reading it as "not on the stack" would
 *    leave the unit that WAS read as the only candidate and move the analysis
 *    on one of the two streams.
 * 5. EXACTLY ONE UNIT'S SIGNATURE SAYS STACK: that unit is analysed, basis
 *    [AnalysedRoleBasis.STACK_SIGNATURE].
 * 6. NEITHER OR BOTH: the armed unit stands, basis
 *    [AnalysedRoleBasis.DECLARED]. Both is the ordinary shape of a machine
 *    whose two units are both on the stack -- field-43's seated leg curls
 *    measure 1.6 and 0.3 degrees -- and neither happens on a set where both
 *    units were handled. The choice today's build makes is kept in both, so
 *    the failure direction of this whole rule is "the set is analysed exactly
 *    as it is now".
 *
 * ## What it deliberately does not do
 *
 * IT DOES NOT SET [AnalysedStream.fellBack]. That flag means the armed unit
 * delivered too few frames, `SetAnalyzer` refuses a fallback under a
 * mount-specific declaration on it (#247), and the refusal must go on firing
 * for exactly the population it fired on before. A signature flip is a
 * different fact and is said in [AnalysedRoleChoice.basis].
 *
 * IT DOES NOT SWAP THE GEOMETRY. The declaration is unchanged; what changes is
 * which stream it is applied to. That is the repair #278 asks for: a stack
 * declaration read against the unit that measurably sat on the stack.
 *
 * IT DECIDES NOTHING RETROACTIVELY, for [SensorCapturePolicy.analysedStream]'s
 * reason: this runs when a set is recorded and the analysis it selects the
 * stream for is frozen into that row.
 */
object AnalysedRolePolicy {
    /**
     * @param armed the role the set armed for analysis, null on a set whose
     *   single stream carries no role.
     * @param expected the roles the set armed, in order.
     * @param framesByRole how many frames each role delivered; a missing key
     *   delivered nothing, exactly as [SensorCapturePolicy.analysable] reads
     *   it.
     * @param declaresStackMount whether the set's own declaration says a unit
     *   was on the stack -- `ExerciseDef.sensorOnStack` as resolved for the
     *   set, never a guess from the exercise's name.
     * @param signalByRole what each role's own stream says about riding the
     *   load; a missing key is [StackMountSignal.UNMEASURED].
     */
    fun choose(
        armed: SensorRole?,
        expected: List<SensorRole>,
        framesByRole: Map<SensorRole, Int>,
        declaresStackMount: Boolean = false,
        signalByRole: Map<SensorRole, StackMountSignal> = emptyMap(),
    ): AnalysedRoleChoice {
        val frames = SensorCapturePolicy.analysedFrom(armed, expected, framesByRole)
        if (frames.fellBack) {
            return AnalysedRoleChoice(frames.role, fellBack = true, basis = AnalysedRoleBasis.FALLBACK)
        }
        val declared = AnalysedRoleChoice(frames.role, fellBack = false, basis = AnalysedRoleBasis.DECLARED)
        if (!declaresStackMount || armed == null) return declared
        val analysable = SensorCapturePolicy.analysable(expected, framesByRole)
        if (analysable.size < 2) return declared
        val read = analysable.map { signalByRole[it] ?: StackMountSignal.UNMEASURED }
        if (read.any { it == StackMountSignal.UNMEASURED }) return declared
        val onStack = analysable.filter { signalByRole[it] == StackMountSignal.ON_STACK }
        val single = onStack.singleOrNull() ?: return declared
        return AnalysedRoleChoice(single, fellBack = false, basis = AnalysedRoleBasis.STACK_SIGNATURE)
    }
}
