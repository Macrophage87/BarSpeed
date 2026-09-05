package com.macrophage.barspeed.model

/**
 * What KIND of object a set's load sits on, as the plan declares it
 * (`implement`, schema 1.12, issue #253).
 *
 * DECLARED, never inferred. The app used to decide this from the exercise id
 * through `ExerciseDef.usesBarbell`, and an id is a guess: a barbell lift with
 * a name the matcher does not know got no loading at all, and a cable exercise
 * whose id happened to look dynamic could be told to load plates onto a bar
 * that is not in the movement. A guess is the wrong shape for an INSTRUCTION,
 * so an omitted key resolves to [OTHER] and [OTHER] says nothing.
 *
 * [ofPlan] is total, and an unrecognised word resolves to [OTHER] rather than
 * throwing -- which is the same fail direction as absence and for the same
 * reason: the failure mode of saying nothing is a lifter doing their own plate
 * arithmetic, and the failure mode of guessing is a lifter loading the wrong
 * bar. [PlanFile.validate] refuses the unrecognised word with the path named,
 * so it should never reach here at all.
 */
enum class Implement {
    /** A straight bar loaded with plates per side. */
    BARBELL,

    /**
     * A PAIR of dumbbells. A single dumbbell is [OTHER] -- there is nothing to
     * say about how to load it that the load figure does not already say.
     */
    DUMBBELL,

    /** Everything else: machines, cables, kettlebells, one dumbbell, bodyweight. */
    OTHER,
    ;

    companion object {
        /** An omitted, null or unrecognised declaration is [OTHER]. */
        fun ofPlan(declared: String?): Implement = when (declared?.lowercase()) {
            "barbell" -> BARBELL
            "dumbbell" -> DUMBBELL
            else -> OTHER
        }
    }
}

/**
 * The "Up next" card's second line: how to put the declared load on the
 * declared implement, or null when there is nothing useful to say (#253).
 *
 * A pure decision in `:core:model` because the card it draws on is a Compose
 * function in `:app` that no test in this repository can reach. What the
 * screen keeps is one call and the five values it passes; everything that
 * could be WRONG about the line -- which implement draws what, which load is
 * divided, which bar the plates come off, what an inexact load says -- is
 * here, where it runs on every push.
 *
 * Every load reaching this object is the ADDED load, the same figure
 * [ImplementLoad] takes, and never [SetLoadPolicy.totalKg]: a weighted dip at
 * 20 kg on an 80 kg lifter must not be told to load 100 kg onto a bar. Nothing
 * here returns a number; the only outputs are strings, so no divide and no
 * rounding in this file can reach anything that is stored, exported or summed.
 *
 * The bar weight is NOT part of the card's load figure and never has been. The
 * card states the total the plan asked for; this line says how to reach it,
 * and the bar is part of that total.
 */
object ImplementLine {
    /** What a declared [Implement.DUMBBELL] means when the plan states no count. */
    const val DUMBBELL_PAIR = 2

    /**
     * How many objects to divide the load across, with [Implement.DUMBBELL]
     * supplying the pair the word already means.
     *
     * One implementation, two callers -- this and
     * [PlanExerciseDef.resolvedImplementCount] -- because a second copy of
     * "a dumbbell means two" is a second thing to forget to change. A declared
     * count still wins: a plan that writes 3 gets 3, and
     * [PlanFile.validate] refuses the one contradiction that is not a
     * quantity question, a `dumbbell` declared with a count of 1.
     */
    fun resolvedCount(implement: Implement, declared: Int?): Int = if (implement == Implement.DUMBBELL) {
        declared?.takeIf { it >= 1 } ?: DUMBBELL_PAIR
    } else {
        ImplementLoad.count(declared)
    }

    /**
     * The line, or null for "draw nothing".
     *
     * Null in four distinct cases that all mean the card has nothing to add:
     * the implement is [Implement.OTHER] or undeclared, a barbell set has no
     * positive load to put on a bar, a dumbbell set has no positive load to
     * split, or a dumbbell resolves to fewer than two objects -- which
     * [PlanFile.validate] refuses, so it arrives only from a plan that got
     * past a build that did not.
     *
     * [addedKg] is the load the lifter has STATED where they have stated one,
     * and the plan's figure otherwise: the line is an instruction, and telling
     * someone to load 100 while they have said 90 is telling them to do the
     * wrong thing.
     */
    fun forCard(
        implement: Implement,
        addedKg: Double?,
        implementCount: Int?,
        unit: WeightUnit,
        barKg: Double?,
    ): String? {
        val load = addedKg?.takeIf { it > 0 } ?: return null
        return when (implement) {
            Implement.OTHER -> null
            Implement.DUMBBELL ->
                ImplementLoad.decomposition(load, resolvedCount(implement, implementCount), unit)
                    ?.let { "$it dumbbells" }
            Implement.BARBELL -> barbellLine(load, unit, barKg)
        }
    }

    /**
     * "45 + 25 + 5 per side", and the three cases that are not a list of
     * plates.
     *
     * The bar is named only when it is NOT the unit's default. Naming a 45 lb
     * bar on every barbell line costs a third of the width for a fact every
     * lifter already assumes; naming a 35 lb one is the difference between a
     * right and a wrong total, and there is no other place on the card the
     * plan's `bar_lb` would show up.
     *
     * "short per side" says per side twice on purpose. The remainder
     * [PlateMath] reports is what one side is missing, and a lifter reading it
     * while loading one side has to know which of the two numbers it is.
     */
    private fun barbellLine(addedKg: Double, unit: WeightUnit, barKg: Double?): String {
        val breakdown = PlateMath.perSide(addedKg, unit, barKg)
        if (breakdown.belowBar) return "Below the ${barText(breakdown, unit)}"
        val plates = breakdown.platesPerSide
        val custom = breakdown.barWeight != PlateMath.defaultBar(unit)
        // A bar with nothing on it and a remainder is not "empty bar" alone:
        // that shape is a load a couple of pounds over the bar with no plate
        // small enough, and dropping the remainder would round the
        // instruction down in silence. The old plate line dropped the WORDS
        // instead and rendered "Plates/side:  (+1 short)", an empty list and
        // all.
        val base =
            when {
                plates.isNotEmpty() -> "${plates.joinToString(" + ") { plain(it) }} per side"
                custom -> "Empty ${barText(breakdown, unit)}"
                else -> "Empty bar"
            }
        val short =
            breakdown.leftoverPerSide.takeIf { it > 0 }
                ?.let { ", ${plain(it)} ${unit.suffix} short per side" } ?: ""
        val bar = if (custom && plates.isNotEmpty()) ", ${barText(breakdown, unit)}" else ""
        return "$base$short$bar"
    }

    private fun barText(breakdown: PlateBreakdown, unit: WeightUnit): String =
        "${plain(breakdown.barWeight)} ${unit.suffix} bar"

    /**
     * A figure already IN the display unit, without the trailing ".0" a Double
     * prints. Not [WeightUnit.format], which converts from kilograms and would
     * turn a 45 lb plate into 99 lb.
     */
    private fun plain(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else value.toString()
}
