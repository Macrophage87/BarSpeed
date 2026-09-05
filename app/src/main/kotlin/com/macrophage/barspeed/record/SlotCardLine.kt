package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ImplementLine
import com.macrophage.barspeed.model.WeightUnit

/**
 * The "Up next" card's second line: how to put this set's load on the
 * implement the plan declared, or null when there is nothing to say (#253).
 *
 * Five field reads and one call. Everything that could be WRONG about the line
 * -- which implement draws what, which load is divided, which bar the plates
 * come off, what an inexact load says -- is in [ImplementLine], in
 * `:core:model`, where a test runs on it every push. What is left here is the
 * argument assembly, and it is pinned too, because the near neighbour of a
 * correct decision is a correct decision handed the wrong load.
 *
 * [statedAddedKg] is what the lifter has typed for this set, and it WINS. The
 * line is an INSTRUCTION rather than a description: the card's title goes on
 * stating what the plan asked for, but telling someone to load 100 while they
 * have said 90 is telling them to do the wrong thing. It also opens a case
 * that could not arise before -- a barbell slot the plan gave no load for
 * draws a line once the lifter states one -- which is wanted: there was
 * nothing to compute from before, and there is now.
 *
 * Both readings are the ADDED load, never a body-weight-inclusive total; see
 * ImplementLoad. Nothing here consults `ExerciseDef.usesBarbell` any more:
 * that flag is a guess from the exercise id, and a guess printed beside a rack
 * is an instruction. An exercise the plan says nothing about gets no line.
 */
internal fun PlannedSlot.cardInstruction(unit: WeightUnit, statedAddedKg: Double?): String? = ImplementLine
    .forCard(
        implement = implement,
        addedKg = statedAddedKg ?: loadKg,
        implementCount = implementCount,
        unit = unit,
        barKg = barKg,
    )
