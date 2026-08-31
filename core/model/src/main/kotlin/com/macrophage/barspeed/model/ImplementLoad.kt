package com.macrophage.barspeed.model

/**
 * How one declared load is SHOWN when the lifter is holding more than one
 * object at a time — a pair of dumbbells, two kettlebells, a two-handled
 * carry.
 *
 * DIVIDE ONLY. Every load in this app is the TOTAL added load across
 * everything held, before this file existed and after it. Nothing here
 * multiplies a load, and nothing here returns a number that is stored,
 * exported or summed: [decomposition] returns a string, its only callers are
 * screens, and [PlanSetDef.resolvedLoadKg] takes no implement count. Every
 * division by an implement count in this feature terminates in a String and
 * reaches nothing that is stored, exported or summed. That is the whole
 * safety case and it is mechanical rather than a promise — a count cannot
 * corrupt a measurement it is not in scope to reach.
 *
 * Every load here is the ADDED load — the number a plan writes as `load_kg`,
 * which may be negative on assisted body-weight work. It is NEVER the
 * body-weight-inclusive figure [SetLoadPolicy.totalKg] returns: halving that
 * would print "2 × 50 kg" for a 20 kg weighted dip at 80 kg body weight.
 * No test on the CI path reaches those call sites, so WHICH value a call site
 * passes is held by this KDoc, by the parameter name and by review — not by any test that can
 * run in this repository.
 *
 * Beside [SetLoadPolicy] rather than inside it, deliberately. SetLoadPolicy's
 * own KDoc promises callers "get a number back", and each of its five
 * functions returns a load that is then RECORDED. Putting a display divide in
 * that object would make "no implement count can move a recorded load" an
 * argument about call graphs; keeping it here makes it a file that is not in
 * the diff. The cost is discoverability, and it is real: someone looking for
 * load arithmetic will look at SetLoadPolicy first and find no mention of
 * this. That trade is taken on purpose.
 *
 * THE ROUNDING IS NOT EXACT, AND IS NOT HIDDEN. [WeightUnit.format] quantises
 * to a tenth of the DISPLAY unit AFTER converting, so n times the rendered
 * per-implement figure need not read back as the rendered total: an 80 lb
 * total shown in kilograms renders "36.3 kg" and its half renders "18.1 kg",
 * and 18.1 + 18.1 is 36.2. The artefact is bounded at 0.05 of the display unit
 * per implement, an order of magnitude under the smallest real dumbbell
 * increment (2.5 lb / 1.25 kg), and it is a display artefact alone: the total
 * on screen is always the stored number and is never re-derived from the
 * rounded parts.
 */
object ImplementLoad {
    /**
     * How many objects a declared count means, with absence and nonsense both
     * coerced to one.
     *
     * 0 and negatives coerce rather than propagate because the result is a
     * divisor. [PlanFile.validate] rejects them with the JSON path named and
     * the published schema's `minimum: 1` rejects them at ajv, so neither
     * should arrive — but one that did would otherwise divide by zero and
     * render "2147483647 kg" into a line a lifter reads with a bar in their
     * hands.
     */
    fun count(declared: Int?): Int = declared?.takeIf { it >= 1 } ?: 1

    /**
     * The added load on each object, in kilograms.
     *
     * This division happens in KILOGRAMS, upstream of the conversion at the
     * display boundary inside [WeightUnit.format]. Two consequences worth
     * not "improving": halving a double is exact, so dividing here loses
     * nothing; and dividing before the conversion makes the answer the same
     * figure whichever unit the lifter displays, so a pound-authored plan
     * read in kilograms agrees with itself.
     *
     * No `:app` call site takes this today — [decomposition] is what the
     * screens use. It is public because it is the kilogram-level statement of
     * the rule (divide, never multiply) and because [count]'s coercion is
     * worth pinning with no string in the way.
     */
    fun perImplementAddedKg(totalAddedKg: Double, declared: Int?): Double = totalAddedKg / count(declared)

    /**
     * "2 × 40 lb" for a pair, or null when there is nothing to split.
     *
     * Null in three distinct cases that all mean "say nothing extra": no count
     * was declared, one object was declared, or there is no positive added
     * load to divide. Absent stays a distinct state from 1 on purpose. A plan
     * writer holding two UNEVEN dumbbells is told to write the true total and
     * omit the key; the app then shows that total by itself rather than
     * asserting a split — "2 × 42.5 lb" for a 40 and a 45 would be a
     * statement about the lifter's hands that was never true.
     *
     * [totalAddedKg] is the ADDED load, never a body-weight-inclusive total.
     * See this object's KDoc.
     */
    fun decomposition(totalAddedKg: Double?, declared: Int?, unit: WeightUnit): String? {
        val n = count(declared)
        if (n < 2) return null
        val total = totalAddedKg?.takeIf { it > 0 } ?: return null
        return "$n × ${unit.format(perImplementAddedKg(total, declared))}"
    }
}
