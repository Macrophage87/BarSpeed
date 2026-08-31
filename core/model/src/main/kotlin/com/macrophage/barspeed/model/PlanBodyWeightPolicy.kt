package com.macrophage.barspeed.model

/**
 * Whether an imported plan's declared body weight is written, and what figure
 * (issue #161).
 *
 * Here rather than beside the write for [SetLoadPolicy]'s reason: the write
 * itself is a DataStore edit in `:app` that no test on the CI path can
 * execute, so a rule left there is a rule nothing can run against. The
 * decision is a pure function of two nullable numbers and is executed on every
 * push; `:app` is left with the edit and nothing else.
 *
 * The rule the owner set, in one sentence: the stored body weight is the most
 * recent write from EITHER source, the app's own dialog or a plan import, with
 * no hierarchy between them. So there is nothing here about precedence -- an
 * accepted value simply overwrites, and the dialog overwrites it back. What
 * this decides is only whether a document HAS a value to write.
 */
object PlanBodyWeightPolicy {
    /**
     * The body weight to write on import, or null to leave the stored figure
     * alone.
     *
     * Null covers three cases that are one case to the lifter: the plan omitted
     * the key, wrote it as null, or wrote something that is not a weight. The
     * app's stored value stands in all three, and nothing is said about it,
     * because the plan made no claim.
     */
    fun acceptedKg(plan: PlanFile): Double? = acceptedKg(plan.bodyweightKg, plan.bodyweightLb)

    /**
     * The same decision over the two declared figures, so it can be exercised
     * without building a whole document.
     *
     * [kg] wins when both are somehow present. That combination is refused by
     * [PlanFile.validate] and cannot reach a staged plan, so this is a
     * tie-break for a document that does not import rather than a rule anyone
     * can author against -- and kg is the canonical unit, so it is the one that
     * survives a contradiction.
     *
     * Non-positive is not accepted here either, though [PlanFile.validate]
     * already refuses it. The two guards fail differently on purpose: the
     * validator refuses the whole document with a message naming the key, and
     * this one declines to write. A 0 reaching storage would be an absence
     * dressed as a number, and it would land in the one place the app reads
     * as the base load of every bodyweight set.
     */
    fun acceptedKg(kg: Double?, lb: Double?): Double? {
        val declared = kg ?: lb?.let { it / WeightUnit.LB_PER_KG }
        return declared?.takeIf { it > 0 && it.isFinite() }
    }

    /**
     * What the import gate says when a plan changed the stored body weight.
     *
     * The number is stated in the lifter's own display unit, because the point
     * of the line is that the figure the app will now use is recognisable: this
     * value is the base load of every bodyweight set, and a lifter who reads it
     * in a unit they do not weigh themselves in cannot tell a right figure from
     * a wrong one at a glance.
     *
     * It says the change ALREADY happened, in the past tense, because it has:
     * the write is made when the document is accepted, not when the plan is
     * approved. Discarding the plan does not put the old weight back, and the
     * sentence says so rather than leaving the lifter to find out.
     */
    fun appliedLine(kg: Double, unit: WeightUnit): String =
        "Body weight set to ${unit.format(kg)} from this plan, replacing what was stored. " +
            "It stays even if you discard the plan; change it on the home screen."
}
