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
 * the plan, and since #194 the lifter may also mark a set themselves on the
 * rest screen -- but by neither route is it a rung of THIS scale, and every
 * set including a warm-up gets a rating from it; the empty-bar ramp lands on
 * [HeadroomTier.MUCH_MORE]. Before this scale the tile recorded
 * `warmup = true` AND `rpe = null`, discarding the effort by construction.
 * [WarmupMarkPolicy] carries the mark, deliberately nowhere near here.
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
 * Which noun the headroom rungs ask in, and the word the export publishes for
 * it.
 *
 * [LOAD] on anything with a load ladder; [TIME] on a hold or a carry, where
 * load headroom is meaningless on a plank and answerable but beside the point
 * on a farmer's walk. Same tiers, same `rpe` anchors, different noun -- and
 * the externally-checkable property survives either way, which is the whole
 * reason the low end asks for a figure rather than a feeling.
 *
 * [REPS] and [FEEL] arrive with #244, chosen by the exercise's declared
 * [ProgressionKind] rather than by the set's kind. [REPS] is for work whose
 * only ladder is volume -- a pull-up the lifter cannot add plates to -- and
 * [FEEL] is for an exercise declared `"none"`, which holds its load and its
 * reps across its sets, so a rung there promises no quantity at all.
 *
 * [word] is what a set's `rpeScale` publishes. It is the enum name lowercased
 * today and is written out per constant anyway, because the wire vocabulary is
 * a published contract and renaming a Kotlin constant must not silently
 * redefine what a stored word means. [SessionExport.VALID_RPE_SCALES] is the
 * schema's twin of this list, pinned against it in both directions.
 */
enum class EffortAsk(val word: String) {
    LOAD("load"),
    REPS("reps"),
    TIME("time"),
    FEEL("feel"),
}

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
    ;

    companion object {
        /**
         * The rung a stored `rpe` came from, or null when it is not an
         * anchored headroom rung.
         *
         * Null on 7 through 10, which are the counted end, and null on
         * [EffortScale.UNANCHORED_RPE] -- 2, 3 and 5 -- which are valid values
         * with no tile. **That second null is a decision, not an oversight.**
         * Those three exist so the anchors sort; nothing distinguishes a 3
         * from its neighbours, so a 3 does not say which increment was left
         * and is not read as one here. Anything reading this to decide what to
         * OFFER a lifter therefore offers nothing on an unanchored value,
         * which is the honest direction: the app never asked that question and
         * has no answer to act on.
         *
         * Null on a null rpe too -- an unrated set, or a failure tile, which
         * stores none.
         */
        fun ofRpe(rpe: Int?): HeadroomTier? = entries.firstOrNull { it.rpe == rpe }
    }
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
 * Here rather than in `:app` for the reason [SetEndControlPolicy] is here: no
 * test on the CI path can reach a composable, so a scale written inside one is
 * a scale nothing can measure.
 */
object EffortScale {
    /** The lowest `rpe` a proximity-to-failure claim is made at. */
    const val PROXIMITY_FLOOR_RPE = 7

    /** `rpe` values that are valid in the column and carry no tile. */
    val UNANCHORED_RPE = setOf(2, 3, 5)

    /**
     * Every figure in every LOAD caption is a whole multiple of this.
     *
     * 5 is the smallest jump either gym offers -- 5 lb per side on a bar, a
     * 5 kg step on a plate-loaded machine -- and no conversion of a figure
     * from the other unit lands on one. That is what makes the authoring rule
     * testable rather than merely stated.
     *
     * IT SAID "every figure in every caption" and that is FALSE from #244: the
     * REPS row names 3 and 4, which are rep counts and not weights, and the
     * rule was never about them. It is a rule about what a lifter can put on a
     * bar, so it applies to [LOAD_CAPTIONS] and to `NextSetNudgePolicy`'s
     * pound row, and to nothing else. `EffortScaleTest`'s check runs over the
     * load and time ladders only, which is what it always ran over.
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
     * The figures are NOT measured. Nothing in this repository holds a capture
     * of a lifter reporting how much longer a hold could have run. They are the
     * owner's own anchors on #244 -- *"For holds let's do 15 sec and 30 sec."*
     * -- and they REPLACE the "15-30 s longer" and "about a minute longer" this
     * table carried from #187. That earlier pair was the owner's recommendation
     * too and was not wrong; it was coarser, and the 15 / 30 pair puts the
     * add-a-step point on rung 4 where the reps row now puts it. Revising them
     * after a session is a change to this map and nothing else, which is why
     * the table is here rather than inline at the tile.
     *
     * **The load table's justification does not transfer, and that is an open
     * question rather than a settled one.** The load bands are fixed BECAUSE
     * the equipment is quantised: the owner deleted proportional bands for
     * exactly that reason, since you cannot add 8% to a machine. A hold is not
     * quantised, so a fixed band is 75-150% of a 20 s plank and under 20% of a
     * three-minute carry. Whether the time rungs should scale with the target
     * is unanswered here and is carried as a field item.
     */
    private val TIME_CAPTIONS: Map<HeadroomTier, String> =
        mapOf(
            HeadroomTier.ONE_INCREMENT to "Could have gone about 15 s longer",
            HeadroomTier.TWO_INCREMENTS to "Could have gone about 30 s longer",
            HeadroomTier.MUCH_MORE to "Could have gone much longer",
        )

    /**
     * The same three tiers asked in REPS, for work whose only ladder is
     * volume -- a pull-up the lifter cannot add plates to (#244).
     *
     * THE ROWS START ABOVE THE COUNTED END, and that is the owner's own
     * correction to #244's body: *"1-2 reps of headroom is typically the
     * target. We'd want more than that. RPE 6 is 3 reps. One notch down is
     * probably when you'd add a rep or two though."* The body's table put
     * "1-2 more reps" on rung 6; the counted end already covers one, two and
     * three left at 9, 8 and 7, and one or two in reserve is the TARGET rather
     * than headroom. So the add-a-rep point is rung 4, one notch below 6.
     *
     * **THIS CLOSES THE GAP [EffortScale]'S KDOC SAYS NOT TO CLOSE, and the
     * exception is argued rather than overlooked.** That rule -- *"the gap
     * between ONE_INCREMENT and three reps left is a decision. Do not close
     * it."* -- exists because a headroom question sitting where the rep count
     * is most reliable asks for a guess when a better instrument is right
     * there. On a reps-progression exercise the headroom question IS a rep
     * count, so it is the better instrument rather than the guess, and the
     * justification does not transfer. What DOES survive is a real ambiguity:
     * rpe 7 says "3 reps left" and rpe 6 says "About 3-4 reps left", so a
     * lifter with exactly three left meets two tiles. Which one they tap has
     * never been observed and is a field item, not a solved problem.
     *
     * Unit-free, for [TIME_CAPTIONS]' reason: a rep is a rep in both gyms.
     */
    private val REPS_CAPTIONS: Map<HeadroomTier, String> =
        mapOf(
            HeadroomTier.ONE_INCREMENT to "About 3-4 reps left",
            HeadroomTier.TWO_INCREMENTS to "Five or more reps left",
            HeadroomTier.MUCH_MORE to "Many more reps left",
        )

    /**
     * The same three tiers asked as a FEELING, for an exercise declared
     * `"none"` (#244).
     *
     * NO QUANTITY ANYWHERE, which is the whole of this row. A `"none"`
     * exercise holds its load and its reps across its sets and
     * [NextSetNudgePolicy.options] offers it nothing however it was rated, so
     * a rung promising an increment promises something the app then refuses.
     * The rungs still SORT -- 1 easier than 4 easier than 6 -- because the
     * stored `rpe` and its ordering are unchanged on every progression.
     *
     * THE OWNER'S THREE PHRASES, WITH TWO OF THEM SWAPPED, and the swap is a
     * correction to #244's body rather than a preference. That table puts
     * "easy, had plenty left" on rung 6 and "comfortable, some left" on rung
     * 4, which inverts the ordering the same paragraph demands: plenty left is
     * EASIER than some left, so as written the harder rung claimed more in
     * reserve. The words are his; the order is the issue's own prose.
     *
     * Unit-free, and digit-free: `HeadroomScaleDifferentialTest` reds if any
     * of these three ever names a number.
     */
    private val FEEL_CAPTIONS: Map<HeadroomTier, String> =
        mapOf(
            HeadroomTier.ONE_INCREMENT to "Comfortable — some left",
            HeadroomTier.TWO_INCREMENTS to "Easy — plenty left",
            HeadroomTier.MUCH_MORE to "Very easy",
        )

    /**
     * Which noun this set's headroom rungs ask in.
     *
     * A DECLARED SEAM AT THIS COMMIT. It answers what the two-branch
     * expression inside [tiles] answered before it existed -- the set's KIND
     * and nothing else -- and ignores [progression] entirely. #244's fix is
     * the body of this one function; every caller is already wired to it, so
     * the change reaches the screen, the write and the export at once rather
     * than three times.
     *
     * A NULL [progression] is an AD-HOC set, which has no plan at all: every
     * call site passes `slot?.progression`, so a null reaches here only when
     * the SLOT is null. An exercise whose plan declared nothing never arrives
     * here as null -- `PlannedSlot.progression` is a non-null
     * [ProgressionKind] defaulting to WEIGHT, resolved by
     * [ProgressionKind.ofPlan] when the plan is flattened, because that is
     * what an omitted key MEANS. Both land on [ProgressionKind.WEIGHT], which
     * is why nothing recorded against a plan written before schema 1.11 is
     * asked a different question than it was.
     * Taking the null here rather than resolving it at the call site is what
     * makes the ad-hoc case pinnable: `:app` has no reachable test seam, so a
     * `?: WEIGHT` written there is a rule nothing on the CI path can fail.
     */
    fun askFor(timed: Boolean, progression: ProgressionKind?): EffortAsk =
        when (progression ?: ProgressionKind.WEIGHT) {
            // WEIGHT DEFERS TO THE SET'S KIND and the other three do not.
            // That asymmetry is the design, not an oversight: WEIGHT is what
            // an OMITTED key resolves to, so every plan written before schema
            // 1.11 declares it by saying nothing -- and a hold from such a
            // plan has always been asked in seconds. Letting the declaration
            // win here would move every legacy plank onto load headroom,
            // which is this issue's own defect in the other direction.
            //
            // The cost, stated rather than hidden: a hold whose plan
            // DELIBERATELY says "weight" -- a weighted plank -- is asked in
            // seconds while #214's grid offers it pounds. Those are two
            // different questions ("how much was left" and "what should
            // change next set") and the app has never been able to tell that
            // declaration from an omitted one, because `ProgressionKind.ofPlan`
            // collapses them before this is reached. Raised, not folded in.
            ProgressionKind.WEIGHT -> if (timed) EffortAsk.TIME else EffortAsk.LOAD
            ProgressionKind.REPS -> EffortAsk.REPS
            ProgressionKind.TIME -> EffortAsk.TIME
            ProgressionKind.NONE -> EffortAsk.FEEL
        }

    /**
     * The scale word an export publishes for one set, or null to omit the key.
     *
     * WITHHELD FROM A SET CARRYING NO RATING, which is `failedByLifter`'s rule
     * and the same argument: the column is written on every set the app
     * records, because the app always knows which grid it drew, but a word
     * with no number beside it says only which tiles were on screen -- not a
     * fact about the set -- and it reads as a rating that was never given.
     *
     * Here rather than at the two export writers because there ARE two, the
     * session document and the archive's manifest, and a rule inlined at both
     * is a rule that can come to differ. `RpeScalePublishedTest` asserts both
     * writers against it.
     */
    fun publishedScale(rpe: Int?, scale: String?): String? = if (rpe == null) null else scale

    /**
     * The caption for one headroom rung, read from the table.
     *
     * [unit] is ignored for every ask but [EffortAsk.LOAD]: seconds, reps and
     * feelings are the same in both units, and taking the argument anyway is
     * better than a second entry point that could disagree about which tiers
     * exist.
     */
    fun headroomCaption(tier: HeadroomTier, ask: EffortAsk, unit: WeightUnit): String = when (ask) {
        EffortAsk.TIME -> checkNotNull(TIME_CAPTIONS[tier]) { "no time caption for $tier" }
        EffortAsk.REPS -> checkNotNull(REPS_CAPTIONS[tier]) { "no reps caption for $tier" }
        EffortAsk.FEEL -> checkNotNull(FEEL_CAPTIONS[tier]) { "no feel caption for $tier" }
        EffortAsk.LOAD -> checkNotNull(LOAD_CAPTIONS[tier to unit]) { "no load caption for $tier in $unit" }
    }

    /**
     * The tiles for one set, easiest first, with the failure tile last.
     *
     * [ask] is [askFor]'s answer and decides the HEADROOM wording alone: the
     * counted end and the failure tile below are the set's KIND's business and
     * do not move with it. It is taken rather than derived here because the
     * same answer is FROZEN onto the row and onto the rest screen's feedback
     * when the set is written (#244) -- one resolution, three readers, so the
     * tile the lifter tapped, the tile the correction popup lights and the
     * word the archive publishes cannot disagree. Pass [askFor]'s answer and
     * nothing else: a hand-built pair can say TIME rungs on a rep ladder.
     *
     * [timed] and [explosive] are the two branches `rpeOptions` already made
     * before this object existed, kept rather than replaced: "reps left"
     * means nothing for a plank or a snatch. What changed is that the low end
     * of all three ladders now asks a headroom question instead of offering a
     * fourth rung of the same felt scale, and that the warm-up tile is gone.
     *
     * The proximity wording of the timed and explosive ladders is UNCHANGED
     * from what shipped in v0.1.44, character for character, intensity prefix
     * and em dash included: those four rungs each sit where the lifter's own
     * report is accurate, they were not what #187 was about, and redefining an
     * anchor predictably shifts the ratings people give (Okhamafe et al.,
     * 2026) -- so they are left alone deliberately rather than by omission.
     * `EffortScaleTest` pins the eight strings as literals transcribed from
     * v0.1.44 (tag `7cf6e8c3cc546ab8d64c9fb2be86de2129250b43`), so a reword
     * becomes a visible diff on a test rather than a quiet change to what a
     * stored 9 means. Nothing reads the tag at test time; the transcription
     * is the pin.
     *
     * The REP ladder's four counted rungs DID move, from "Solid — 3 reps
     * left" to "3 reps left" and so on: they are the strings the owner's own
     * settled table on #187 names, and that ladder is the one whose low end
     * this scale replaces. The intensity prefix goes with the rung it
     * qualified rather than being kept beside three headroom captions that
     * have no counterpart for it.
     */
    fun tiles(timed: Boolean, explosive: Boolean, unit: WeightUnit, ask: EffortAsk): List<EffortTile> {
        val headroom =
            listOf(HeadroomTier.MUCH_MORE, HeadroomTier.TWO_INCREMENTS, HeadroomTier.ONE_INCREMENT)
                .map { EffortTile(it.rpe, EffortClaim.HEADROOM, headroomCaption(it, ask, unit)) }
        val proximity =
            when {
                timed ->
                    listOf(
                        7 to "Solid — had more in me",
                        8 to "Hard — a little left",
                        9 to "Very hard — seconds left",
                        10 to "Max — hit my limit",
                    )
                explosive ->
                    listOf(
                        7 to "Solid — fast and crisp",
                        8 to "Hard — speed dropping",
                        9 to "Very hard — grindy",
                        10 to "Max — barely made it",
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
                timed -> "Broke early — failed"
                explosive -> "Missed the lift"
                else -> "Failed the set"
            }
        return headroom + proximity + EffortTile(null, EffortClaim.FAILED, failText)
    }
}
