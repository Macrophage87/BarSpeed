package com.macrophage.barspeed.model

/**
 * The effort scale the set-end grid draws, and the one rule every rung of it
 * is chosen by (#187).
 *
 * ## Ask for the number the lifter can supply
 *
 * Near failure that number is a rep count; far from it, it is a load figure.
 * Never ask for the one that would be a guess. Both halves are the same
 * question -- how much was left -- asked in the unit that still carries
 * information at that distance from failure, so nothing here switches
 * construct: only the resolution changes.
 *
 * The evidence for where the crossing sits: measured mean error in calling
 * one's own reps-in-reserve mid-set is 2.05 reps at 1 RIR, 3.65 at 3 RIR and
 * 5.15 at 5 RIR (Zourdos et al., 2019), so below about three reps left the
 * error swamps the claim. A 26-study systematic review reaches the same
 * conclusion independently (Russo et al., 2025). Further from failure the
 * answerable quantity is what would have gone on the bar.
 *
 * ## There is deliberately no tile for "could have added 5 lb"
 *
 * The owner's words, and they are the governing rule rather than an accepted
 * gap: *"If you could have added 5 lbs, you're probably near a level that you
 * know the RIR for in most cases anyway."* A set with five pounds of margin
 * sits at the accurate end of the rep-count instrument, so the counted rung
 * there is not a fallback for a missing headroom tile -- it is the better
 * instrument at that distance, and a headroom tile would be the guess.
 *
 * **The gap between [HeadroomTier.ONE_INCREMENT] and three reps left is a
 * decision. Do not close it.** Closing it puts a headroom question exactly
 * where the rep count is most reliable.
 *
 * ## Why the bands are the weights they are
 *
 * The owner's equipment steps in fixed jumps: *"Dumbells and barbells
 * typically increase by 10 pounds at a time (5 pounds per side...). My
 * machines are typically in 15 lb increments."* So the tiles are ONE
 * increment and TWO increments, and one band spans a bar and a stack --
 * which is why the app never has to know which is in front of the lifter.
 * It cannot know: there is no declared equipment increment anywhere in this
 * codebase. [ImplementLoad]'s 2.5 lb is a display artefact of halving a
 * total, and `ExerciseDef.inferBarbell` is a name heuristic over
 * `NON_BARBELL_HINTS`, not a declared property. The tile therefore names a
 * WEIGHT and never "one notch".
 *
 * ## Anchors, and the gaps between them
 *
 * One `rpe` column, 1 to 10, with tiles at anchored points. The values with
 * no tile -- 2, 3 and 5 -- are valid in the range and exist so the anchors
 * SORT; they are not offered as taps because nothing distinguishes them from
 * their neighbours. That is the owner's ruling on the alternative: a split
 * categorical/numeric export would be "too hard to analyze".
 *
 * ## Warm-up is not on this scale
 *
 * A warm-up is what a set is FOR, not how it went, and the two are
 * orthogonal: a 500 lb squatter's empty-bar ramp set is a warm-up by purpose
 * and what it felt like is a separate, knowable fact. Warm-up is declared on
 * the plan and every set including a warm-up gets a rating from this scale --
 * the empty-bar ramp lands on [HeadroomTier.MUCH_MORE]. Before this scale the
 * tile recorded `warmup = true` AND `rpe = null`, discarding the effort by
 * construction.
 */
enum class EffortClaim {
    /** "How much more load, or time, would this set have taken?" */
    HEADROOM,

    /** "How close was this set to failure?" -- reps in reserve on a rep set. */
    PROXIMITY,

    /** Did not complete it. Stores no rpe. */
    FAILED,
}

/**
 * Which noun the headroom rungs ask in.
 *
 * [LOAD] on anything with a load ladder; [TIME] on a hold or a carry, where
 * load headroom is meaningless on a plank and answerable but beside the point
 * on a farmer's walk. Same tiers, same `rpe` anchors, different noun -- and
 * the externally-checkable property survives either way, which is the whole
 * reason the low end asks for a figure rather than a feeling.
 */
enum class EffortAsk { LOAD, TIME }

/**
 * How much more the set would have taken, in equipment increments.
 *
 * The `rpe` each tier records is fixed here rather than at the call site, so
 * revising a caption after a field session is a data change and revising an
 * anchor is a visible one.
 *
 * [ONE_INCREMENT] records 6, which is the one anchor carrying historical
 * baggage: a pre-v0.1.45 `6` meant "4+ reps left" on the old ladder and 46%
 * of the owner's ratings sit on it. It is reused rather than moved to 5
 * because the standard RPE-to-RIR chart puts 6 at 4 RIR -- comfortably short
 * of failure but working, roughly the state "could have added one increment"
 * describes -- so historical values stay interpretable on the same ruler.
 * What a reader must NOT assume is that the two mean the same thing exactly:
 * 6 was also the FLOOR of the old scale and absorbed everything the new 1 and
 * 4 now take, so a pre-v0.1.45 6 is the union of all three. The published
 * export version log says this in the same terms.
 */
enum class HeadroomTier(val rpe: Int) {
    /** Three or more increments, or no way to say. */
    MUCH_MORE(1),

    /** Two plate pairs, or two notches on a stack. */
    TWO_INCREMENTS(4),

    /** One plate pair, or one notch on a stack. */
    ONE_INCREMENT(6),
}

/** One tile of the effort grid: what it stores and the words it says. */
data class EffortTile(
    /** The value stored in the `rpe` column, or null on [EffortClaim.FAILED]. */
    val rpe: Int?,
    val claim: EffortClaim,
    /** Gym-facing wording. Authored, never computed -- see [EffortScale]. */
    val label: String,
)

/**
 * The tiles, in order, for a set of a given kind, and the authored caption
 * table they are built from.
 *
 * Here rather than in `:app` for the reason [SetEndControlPolicy] is here:
 * `:app` has one test file over one pure function and nothing that can reach
 * a composable, so a scale written inside one is a scale nothing can measure.
 */
object EffortScale {
    /** The lowest `rpe` a proximity-to-failure claim is made at. */
    const val PROXIMITY_FLOOR_RPE = 7

    /** `rpe` values that are valid in the column and carry no tile. */
    val UNANCHORED_RPE = setOf(2, 3, 5)

    /**
     * Every figure in every caption is a whole multiple of this.
     *
     * 5 is the smallest jump either gym offers -- 5 lb per side on a bar, a
     * 5 kg step on a plate-loaded machine -- and no conversion of a figure
     * from the other unit lands on one. That is what makes the authoring rule
     * testable rather than merely stated.
     */
    const val GYM_INCREMENT_MULTIPLE = 5

    /**
     * THE CAPTION TABLE. Authored per unit, never converted.
     *
     * The owner settling it: *"The 'notch' is typically similar. You can
     * probably say you can go up 5 kg/10 lbs, but not 4.72 kg."* That is not
     * a rounding preference -- it is the same rule the whole scale rests on.
     * The tile names a weight the lifter can actually put on the bar, and
     * `4.72 kg` is not a weight anybody can add, so a caption computed by
     * converting the pound figure would ask a question with no answerable
     * answer, which is precisely the failure this scale exists to remove at
     * the low end.
     *
     * A kg gym steps in 5s, so the kg column is single figures where the lb
     * column needs a band; the band exists in lb only because the owner meets
     * two different increments, 10 on a bar and 15 on his machines, and one
     * tile has to span both.
     *
     * **Never route this through [WeightUnit.format].** Doing so ships
     * "Could have added 4.5 kg". `EffortScaleTest` reds if any caption
     * carries a figure that is not a whole multiple of
     * [GYM_INCREMENT_MULTIPLE], which is what a conversion produces.
     */
    private val LOAD_CAPTIONS: Map<Pair<HeadroomTier, WeightUnit>, String> =
        mapOf(
            (HeadroomTier.ONE_INCREMENT to WeightUnit.LB) to "Could have added 10-15 lb",
            (HeadroomTier.TWO_INCREMENTS to WeightUnit.LB) to "Could have added 20-30 lb",
            (HeadroomTier.MUCH_MORE to WeightUnit.LB) to "Could have added much more",
            (HeadroomTier.ONE_INCREMENT to WeightUnit.KG) to "Could have added 5 kg",
            (HeadroomTier.TWO_INCREMENTS to WeightUnit.KG) to "Could have added 10 kg",
            (HeadroomTier.MUCH_MORE to WeightUnit.KG) to "Could have added much more",
        )

    /**
     * The same three tiers asked in seconds, for a hold or a carry.
     *
     * Unit-free: a second is a second in both gyms, so there is one column
     * here where the load table needs two. The wording says "gone" rather
     * than "held" because the same tile is drawn for a farmer's walk.
     *
     * The figures are NOT measured. Nothing in this repository holds a
     * capture of a lifter reporting how much longer a hold could have run,
     * so 15-30 s and about a minute are a first authoring of the same
     * one-step / two-step shape the load table uses. Revising them after a
     * session is a change to this map and nothing else, which is why the
     * table is here rather than inline at the tile.
     */
    private val TIME_CAPTIONS: Map<HeadroomTier, String> =
        mapOf(
            HeadroomTier.ONE_INCREMENT to "Could have gone 15-30 s longer",
            HeadroomTier.TWO_INCREMENTS to "Could have gone about a minute longer",
            HeadroomTier.MUCH_MORE to "Could have gone much longer",
        )

    /**
     * The caption for one headroom rung, read from the table.
     *
     * [unit] is ignored for [EffortAsk.TIME]: seconds are the same in both
     * units, and taking the argument anyway is better than a second entry
     * point that could disagree about which tiers exist.
     */
    fun headroomCaption(tier: HeadroomTier, ask: EffortAsk, unit: WeightUnit): String = when (ask) {
        EffortAsk.TIME -> checkNotNull(TIME_CAPTIONS[tier]) { "no time caption for $tier" }
        EffortAsk.LOAD -> checkNotNull(LOAD_CAPTIONS[tier to unit]) { "no load caption for $tier in $unit" }
    }

    /**
     * The tiles for one set, easiest first, with the failure tile last.
     *
     * [timed] and [explosive] are the two branches `rpeOptions` already made
     * before this object existed, kept rather than replaced: "reps left"
     * means nothing for a plank or a snatch. What changed is that the low end
     * of all three ladders now asks a headroom question instead of offering a
     * fourth rung of the same felt scale, and that the warm-up tile is gone.
     *
     * The proximity wording of the timed and explosive ladders is UNCHANGED
     * from what shipped: those four rungs sit where the lifter's own report is
     * accurate, they were not what #187 was about, and redefining an anchor
     * predictably shifts the ratings people give (Okhamafe et al., 2026) -- so
     * they are left alone deliberately rather than by omission.
     */
    fun tiles(timed: Boolean, explosive: Boolean, unit: WeightUnit): List<EffortTile> {
        val ask = if (timed) EffortAsk.TIME else EffortAsk.LOAD
        val headroom =
            listOf(HeadroomTier.MUCH_MORE, HeadroomTier.TWO_INCREMENTS, HeadroomTier.ONE_INCREMENT)
                .map { EffortTile(it.rpe, EffortClaim.HEADROOM, headroomCaption(it, ask, unit)) }
        val proximity =
            when {
                timed ->
                    listOf(
                        7 to "Had more in me",
                        8 to "A little left",
                        9 to "Seconds left",
                        10 to "Hit my limit",
                    )
                explosive ->
                    listOf(
                        7 to "Fast and crisp",
                        8 to "Speed dropping",
                        9 to "Grindy",
                        10 to "Barely made it",
                    )
                else ->
                    listOf(
                        7 to "3 reps left",
                        8 to "2 reps left",
                        9 to "1 rep left",
                        10 to "Nothing left",
                    )
            }.map { (rpe, text) -> EffortTile(rpe, EffortClaim.PROXIMITY, text) }
        val failText =
            when {
                timed -> "Broke early - failed"
                explosive -> "Missed the lift"
                else -> "Failed the set"
            }
        return headroom + proximity + EffortTile(null, EffortClaim.FAILED, failText)
    }
}
