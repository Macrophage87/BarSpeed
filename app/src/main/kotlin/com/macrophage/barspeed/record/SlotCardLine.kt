package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ImplementLoad
import com.macrophage.barspeed.model.PlateMath
import com.macrophage.barspeed.model.WeightUnit

/**
 * The extra line under an "Up next" card: what to pick up, or how to load the
 * bar.
 *
 * Lifted out of `SlotCard` unchanged so it can be run by a test at all. The
 * composable it came from cannot be: nothing in this repository draws Compose,
 * so every rule below -- which of two lines wins, which load either of them
 * divides, when neither is drawn -- was held by review alone. This file is the
 * seam #253 needs before it changes any of those rules; the wording and the
 * precedence here are exactly what shipped.
 */
internal fun PlannedSlot.cardInstruction(unit: WeightUnit, statedAddedKg: Double?): String? {
    // The line is an INSTRUCTION, not a description: the card's title keeps
    // stating what the plan asked for, but telling the lifter to load 100
    // while they have said 90 would be telling them to do the wrong thing.
    // This also opens a case that could not arise before -- a barbell slot the
    // plan gave no load for draws a line once the lifter states one -- which
    // is wanted: there was nothing to compute from before, and there is now.
    //
    // Both branches read the same load, which is the ADDED load: the slot's
    // own number, or what the lifter has stated in its place. Never a
    // body-weight-inclusive total -- see ImplementLoad.
    val instructionKg = (statedAddedKg ?: loadKg)?.takeIf { it > 0 }
    return when {
        // "Pick up" REPLACES the plate line rather than sitting beside it, and
        // a DECLARED count beats an INFERRED bar, which is the precedence used
        // everywhere else here. You cannot load plates per side onto two
        // dumbbells, and usesBarbell is a guess from the exercise id while the
        // count is not guessed at all.
        ImplementLoad.count(implementCount) > 1 ->
            ImplementLoad.decomposition(instructionKg, implementCount, unit)?.let { "Pick up: $it" }
        // No plate line on body-weight work, whatever usesBarbell says. That
        // flag is inferred from the exercise id where the plan does not
        // declare it, and "pull_up" carries none of the non-barbell hints, so
        // a weighted pull-up would otherwise draw "Plates/side: 5 (20 kg bar)"
        // -- an instruction to load a bar that is not in the movement. The
        // "Pick up" line survives, because a plate held on a dip belt or a
        // pair of dumbbells on a weighted dip is a real thing to pick up.
        // #160.
        exercise.usesBarbell && !exercise.bodyweight -> instructionKg?.let { plateLine(it, unit) }
        else -> null
    }
}

/** "Plates/side: 45 + 25 + 2.5 (45 lb bar)" for barbell lifts. */
private fun plateLine(loadKg: Double, unit: WeightUnit): String? {
    val breakdown = PlateMath.perSide(loadKg, unit)
    val barText = "${trim(breakdown.barWeight)} ${unit.suffix} bar"
    return when {
        breakdown.belowBar -> "Below bar weight ($barText)"
        breakdown.platesPerSide.isEmpty() && breakdown.leftoverPerSide == 0.0 -> "Empty bar ($barText)"
        else -> {
            val plates = breakdown.platesPerSide.joinToString(" + ") { trim(it) }
            val leftover =
                breakdown.leftoverPerSide.takeIf { it > 0 }
                    ?.let { " (+${trim(it)} short)" } ?: ""
            "Plates/side: $plates$leftover ($barText)"
        }
    }
}

private fun trim(value: Double): String = if (value == Math.floor(value)) value.toInt().toString() else value.toString()
