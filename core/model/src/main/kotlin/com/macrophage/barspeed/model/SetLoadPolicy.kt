package com.macrophage.barspeed.model

import java.util.Locale

/**
 * Pure decisions about which load a set is recorded and pre-filled against.
 *
 * These live here rather than beside their callers in `:app` because no plain
 * JVM test can reach them where they were written. `:app` DOES have a test
 * source set as of `ed274bd`, which falsifies the sentence that stood here --
 * it said `:app` had none -- but not the conclusion: the callers sit inside an
 * `AndroidViewModel`, a `@Composable`, and file-private functions in
 * `RecordViewModel.kt` that no test in another file can name, so reaching them
 * in place would still need Robolectric on the CI path. Nothing in this file touches Android,
 * Room or a sensor. Callers pass what the plan declared and what the lifter
 * typed, and get a number back.
 *
 * Every load handled here is the ADDED load — the number a plan writes as
 * `load_kg`, which may be negative on assisted body-weight work. The load that
 * actually travelled on a body-weight movement is body weight plus that, and
 * that sum stays at the call site; nothing here knows the lifter's mass.
 */
object SetLoadPolicy {
    /**
     * The added load to record for a set, in kilograms.
     *
     * [plannedAddedKg] is the plan's declaration for the slot being recorded.
     * Null there means the plan named no load, which is contract rather than a
     * gap: `PlanSetDef.validate` never requires one, and both the schema and
     * the in-app guide tell the plan writer to omit both load fields for
     * body-weight work. So null means nothing was added, and the answer is 0.
     *
     * [typedAddedKg] is the load text field already parsed, null when it is
     * blank or not a number. It is consulted only for ad-hoc sets, where it is
     * the only declaration there is. On a plan set it is not evidence, and
     * [statedAddedKg] rather than this parameter is what carries a number the
     * lifter gave for a planned set.
     *
     * [statedAddedKg] is the added load the lifter has stated FOR THE SET BEING
     * SET UP, and null when they have stated nothing for it. It is a different
     * fact from [typedAddedKg], which is one string reused across every set of
     * a session: this one has no default that means anything, is written only
     * by a keystroke, and is re-decided by [standingStatedAddedKg] on every
     * rest transition, so it cannot outlive the span that function allows it.
     * That is what lets a plan set honour a load the lifter gave while still
     * refusing to read a value left behind by an earlier exercise.
     *
     * A 0 returned here is a real measurement of the added load, not a stand-in
     * for an unknown one, so it is a number rather than a null. Nothing
     * downstream reads it as data that was not collected: `SetAnalyzer` guards
     * bar power with `takeIf { it > 0 }`, so a zero added load on a non-
     * body-weight set suppresses the power figure instead of publishing 0 W.
     */
    fun resolve(adHoc: Boolean, plannedAddedKg: Double?, typedAddedKg: Double?, statedAddedKg: Double?): Double =
        if (adHoc) typedAddedKg ?: 0.0 else statedAddedKg ?: plannedAddedKg ?: 0.0

    /**
     * The load to pre-fill the editable load field with before the next set, or
     * null to leave the field showing whatever it showed last.
     *
     * [nextDeclaredAddedKg] must be read from the upcoming slot's live
     * `loadKg`, which carries any in-rest edit already applied to it — never
     * from `plannedLoadKg`, which is frozen at the plan's declaration and would
     * silently discard those edits.
     *
     * [hasPlannedNext] separates two facts that must not share an answer. "The
     * next planned set declares no load" is a declaration, and seeds 0. "There
     * is no next planned set at all" is the ad-hoc case and the end of a plan,
     * where the last load is the only thing to go on and carrying it forward is
     * what the lifter expects. Conflating them is what leaks one exercise's
     * load into the next, because the seeded field is baked back into the
     * upcoming slot and read straight back out as if the plan had declared it.
     *
     * [lastAddedKg] is the ADDED load of the set just finished, not the total
     * that travelled. On body-weight work the difference is the lifter's mass,
     * and seeding from the total is what makes a loadless block climb set over
     * set.
     */
    fun seedAddedKg(hasPlannedNext: Boolean, nextDeclaredAddedKg: Double?, lastAddedKg: Double?): Double? =
        if (hasPlannedNext) nextDeclaredAddedKg ?: 0.0 else lastAddedKg

    /**
     * The added load the upcoming planned slot carries once the lifter taps
     * through to it, replacing what the plan declared.
     *
     * [declaredAddedKg] is the slot's own load, still the plan's number because
     * this slot has not been through this function yet.
     *
     * The rest screen's load field is deliberately NOT a parameter. It used to
     * be what this read, and that is the whole of #45: the field is seeded from
     * the declaration through inputValue, which quantises to 0.1 of the DISPLAY
     * unit, so reading it back replaced the plan's own number with a rounded
     * one even when the lifter never touched the box. A plan declaring 175 lb
     * recorded 79.4 against a plannedLoadKg of 79.3786647517562, and the
     * session detail screen printed a deviation the lifter did not make.
     *
     * [statedAddedKg] is what they actually said, null when they said nothing,
     * and it is the only thing that displaces the declaration. An untouched
     * field is no longer in the path at all.
     */
    fun carriedIntoNextSet(declaredAddedKg: Double?, statedAddedKg: Double?): Double? = statedAddedKg ?: declaredAddedKg

    /**
     * Whether the set just finished and the set coming up belong to the same
     * BLOCK of one exercise -- the span a load the lifter states is allowed to
     * hold for.
     *
     * A block, not an exercise. `flattenPlan` walks a session's exercises in
     * order and emits one slot per set of each, and nothing stops the same
     * movement appearing in two of them; the second block is a fresh
     * prescription rather than a continuation of the first.
     * [nextSetIndexInExercise] is 0 on exactly the first set of a block, which
     * separates the two cases without needing a block identity that no slot
     * carries.
     *
     * `isExerciseChange` is deliberately not what this reads. That flag is
     * computed as `setIdx == 0 && exerciseIdx > 0` where the queue is
     * flattened and as `prevId != slot.exercise.id` where it is reordered by
     * the switch-exercise route, so on a session running one movement in two
     * consecutive blocks the two disagree about it.
     *
     * A null on either id, or on the index, means there is no such pair: the
     * queue ran out, or the set just finished was ad-hoc and belongs to no
     * block at all. Nothing carries across that.
     */
    fun sameExerciseBlock(lastExerciseId: String?, nextExerciseId: String?, nextSetIndexInExercise: Int?): Boolean =
        lastExerciseId != null &&
            lastExerciseId == nextExerciseId &&
            nextSetIndexInExercise != null &&
            nextSetIndexInExercise > 0

    /**
     * The added load the lifter STATED that still stands for the set coming up,
     * or null when that set is offered whatever its own slot declares.
     *
     * A load typed for one set used to reach that set and no further: the rest
     * transition cleared the statement and re-seeded the load field from the
     * plan, so a lifter who moved up in weight and did not retype it on every
     * subsequent set had the prescription recorded against reps done at another
     * load, with nothing on screen marking the change. #124.
     *
     * [statedAddedKg] is the statement as it stood when the set that just
     * finished was written: what the lifter typed for it, null when they typed
     * nothing. Zero is a statement rather than an absence, the same way it is
     * in [resolve] -- a lifter who stripped the bar has said something -- so
     * this parameter is tested against null and never for truthiness.
     *
     * [sameExerciseBlock] bounds the carry; see that function for what a block
     * is.
     *
     * [lastDeclaredAddedKg] and [nextDeclaredAddedKg] are the two slots'
     * `plannedLoadKg`, frozen at what the plan declared and never written back
     * to. NOT their `loadKg`, which carries the statement itself once
     * [carriedIntoNextSet] has baked it in -- comparing those two would compare
     * a number against itself. A plan that declares a DIFFERENT load for the
     * next set is prescribing a change, and it is that number the lifter is
     * offered: a warm-up corrected upward must not become the working set's
     * load, and a block written 60/80/100 must still climb after its opener is
     * adjusted. One keystroke still displaces it, exactly as before.
     *
     * A null on one side only is not that case and is not claimed to be: the
     * plan declared no load for one of the two sets rather than a different
     * one. Null compares unequal to a number, so the carry drops there too --
     * the right direction, and stated as what it is rather than as a
     * prescription. Null on BOTH sides compares equal, which is the loadless
     * block carrying an added load the lifter supplied.
     *
     * The two declarations are compared with `==` on Double, which is exact
     * here rather than approximate: both are `PlanSetDef.resolvedLoadKg`
     * applied to the plan's own text, so two slots declaring the same load in
     * the same unit hold the same bits, and nothing arithmetic happens to
     * either on the way in. In the SAME UNIT: `resolvedLoadKg` is `loadKg ?:
     * loadLb?.let { it / LB_PER_KG }`, so one slot written `load_lb: 90` and
     * the next written `load_kg: 40.8233133` are the same weight and compare
     * unequal. The carry drops there, which is the safe direction and not the
     * intended one.
     *
     * A carried load does not touch `plannedLoad_kg`. The plan's prescription
     * is stored beside the load actually recorded for every set, so a carry is
     * visible afterwards as a deviation on each set it reached.
     *
     * [bodyweight] IS NOT READ ON THIS COMMIT. It is whether the movement the
     * carry lands on is body-weight work, taken here ahead of the arithmetic
     * that will read it: a carry that computes a number rather than passing
     * one through needs a floor, and the floor is [correctedAddedKg]'s --
     * signed for body-weight work, where negative is band or machine
     * assistance, and clamped at zero for loaded work, where a bar cannot
     * weigh less than nothing. Taking the fact in this commit keeps the
     * signature change out of the commit that changes the behaviour, so that
     * one is a diff of this expression alone. #143.
     */
    // Suppressed for exactly one commit: [bodyweight] is taken here and read
    // by the commit that replaces the expression below. The suppression goes
    // with the expression, so a parameter left permanently unread would fail
    // detekt the moment the fix stopped needing it.
    @Suppress("UnusedParameter")
    fun standingStatedAddedKg(
        statedAddedKg: Double?,
        sameExerciseBlock: Boolean,
        lastDeclaredAddedKg: Double?,
        nextDeclaredAddedKg: Double?,
        bodyweight: Boolean,
    ): Double? = statedAddedKg?.takeIf { sameExerciseBlock && lastDeclaredAddedKg == nextDeclaredAddedKg }

    /**
     * The load actually borne by a lifter's body on a body-weight movement:
     * their own mass plus whatever was added, which the plan and the lifter
     * may state as negative for band or machine assistance. Loaded work has
     * no body in the path, so this is [addedKg] unchanged.
     *
     * [bodyWeightKg] null means the lifter has never recorded a body weight
     * -- #61's silent-default gap, not fixed here. The body-weight term is
     * simply 0 in that state, so a body-weight set with no recorded body
     * weight records its added load alone. This function does not widen
     * that gap or narrow it: whatever [bodyWeightKg] holds when it runs is
     * exactly what both the actual load and its paired planned load below
     * are computed from, so the two stay on the same scale regardless of
     * whether it is null.
     */
    fun totalKg(bodyweight: Boolean, bodyWeightKg: Double?, addedKg: Double): Double =
        if (bodyweight) (bodyWeightKg ?: 0.0) + addedKg else addedKg

    /**
     * The planned load to store beside [totalKg] when a finished set is
     * written, so the two are comparable.
     *
     * Runs [plannedAddedKg] through the same [totalKg] that computed the
     * actual load, from the same [bodyWeightKg] reading, so a set recorded
     * exactly as the plan prescribed cannot disagree with itself. A null
     * [plannedAddedKg] -- a plan slot that declared no load at all -- stays
     * null rather than becoming a declared zero: there is no planned load to
     * pair on either scale. #25.
     */
    fun recordedPlannedLoadKg(bodyweight: Boolean, bodyWeightKg: Double?, plannedAddedKg: Double?): Double? =
        plannedAddedKg?.let { totalKg(bodyweight, bodyWeightKg, it) }

    // ---- Correcting the load of the set that has just been recorded (#205) ----
    //
    // THE SET JUST FINISHED, FROM THE REST SCREEN, AND NOTHING ELSE. Editing an
    // arbitrary past set from the history screen is a larger and different
    // thing -- it has no standing load to reconcile, no rest window bounding
    // it, and no `SetRatingTracker` already pointed at the row -- and it is
    // deliberately out of scope here. Every function below assumes the row
    // being corrected is the one the rest screen is resting after.
    //
    // WHY LOAD AND NOT SOMETHING ELSE. It is the only value in a set the app
    // cannot observe. Reps are counted, seconds are clocked, effort and the
    // limiter are asked; the load is whatever was typed or carried BEFORE the
    // set ran, so a lifter who set the app to 60 kg and put 65 on the bar had
    // no way to say so once the set was over.
    //
    // BODY-WEIGHT WORK IS CORRECTED ON THE ADDED PORTION, like every other
    // load in this object, and the added portion may be negative for band or
    // machine assistance. [correctedTotalKg] is what puts it back on the
    // body-weight-inclusive scale the row stores.

    /**
     * How much one tap of the rest screen's load correction moves the recorded
     * load, in kilograms.
     *
     * A plate rather than a unit. 2.5 kg and 5 lb are the smallest change most
     * gyms can actually make to a bar, and a step the lifter cannot load is a
     * step that always needs a second tap to undo. Named in the DISPLAY unit
     * and converted, so the number on screen moves by a round figure whichever
     * chip the lifter is on -- 2.5 kg renders as "5.5 lb", and a lb-unit
     * session stepping by that would read as a stutter.
     *
     * NOT SNAPPED to a multiple of itself. A set recorded at 62 kg steps to
     * 64.5, not to 62.5: snapping would move a figure by up to a whole step
     * that the lifter did not ask to move, on the one value in the set nothing
     * else can check. A large correction is therefore several taps, which is
     * the accepted cost of the control being a stepper; an arbitrary-value
     * entry box is a bigger change and is not made here.
     */
    fun correctionStepKg(unit: WeightUnit): Double = unit.toKg(if (unit == WeightUnit.KG) 2.5 else 5.0)

    /**
     * The ADDED load the finished set stands at after one tap of the
     * correction, in kilograms.
     *
     * [recordedAddedKg] is what stands now -- the load the set was recorded
     * with, or the last correction, so repeated taps accumulate the way the
     * hold correction's do.
     *
     * THE FLOOR IS AT ZERO FOR LOADED WORK AND ABSENT FOR BODY-WEIGHT WORK,
     * and the asymmetry is the whole reason [bodyweight] is a parameter. A
     * barbell cannot weigh less than nothing, so a loaded set corrected below
     * zero clamps to zero -- an empty bar, which [resolve] already treats as a
     * real measurement rather than an absence. A body-weight set's added load
     * is signed by contract: negative is a band or an assist machine taking
     * weight off, `PlanFile.validate` passes `allowNegativeLoad =
     * SetGeometryPolicy.bodyweightMount(...)` -- the seeded answer, not the
     * raw declaration -- on exactly this population, and clamping it at zero
     * would make assistance unsayable.
     *
     * There is no ceiling on either. Nothing here knows what a plate rack
     * holds, and a bound invented from nothing would reject a real set.
     */
    fun correctedAddedKg(recordedAddedKg: Double, deltaKg: Double, bodyweight: Boolean): Double {
        val next = recordedAddedKg + deltaKg
        return if (bodyweight) next else Math.max(next, 0.0)
    }

    /**
     * The body-weight-inclusive total to store on the row once the added load
     * has been corrected -- the scale set_records.loadKg and the export's
     * load_kg are on, and which [totalKg] put the original one there on.
     *
     * NO BODY WEIGHT IS READ HERE, and that is deliberate rather than an
     * omission. The body-weight term is recovered as [recordedTotalKg] minus
     * [recordedAddedKg], the difference the write itself produced, so the
     * correction moves the total by exactly the correction and by nothing
     * else. Calling [totalKg] again with a fresh bodyWeightKg would fold in
     * any change the lifter made to their recorded body weight between
     * finishing the set and correcting it, and would silently rewrite a set
     * they only meant to add a plate to. On loaded work the two arguments are
     * the same Double and the difference is exactly zero, so this returns
     * [correctedAddedKg] unchanged.
     */
    fun correctedTotalKg(recordedTotalKg: Double, recordedAddedKg: Double, correctedAddedKg: Double): Double =
        recordedTotalKg - recordedAddedKg + correctedAddedKg

    /**
     * Whether the set coming up is a continuation of the block just finished,
     * asked where BOTH kinds of session can answer it.
     *
     * [sameExerciseBlock] is the plan session's answer and needs a declared
     * slot on either side. An ad-hoc set has none, so asking it there returns
     * false for every ad-hoc session -- and ad-hoc is exactly the session where
     * one load repeats set after set, because nothing re-seeds the load box
     * from a declaration. Handing [carryFollowsCorrection] that false is not
     * conservative; it is the feature switched off where it is used most.
     *
     * So the question is asked of whichever thing decides the coming set. With
     * a planned slot -- which is exactly when [nextExerciseId] is non-null --
     * that is the plan. Without one it is the exercise the lifter has selected
     * on the rest screen: the chips are right there and a tap on one is a
     * decision about a different movement, which is the same boundary a block
     * edge is.
     *
     * THE COMING SLOT'S OWN IDENTITY IS TAKEN HERE, never an answer computed
     * for it earlier. The rest screen can replace the slot coming up -- switch
     * exercise, or append a set -- so a block answer frozen at the rest
     * transition is about a slot that is no longer next, and where the two
     * declarations render equal the carry then writes the corrected load onto
     * a different movement.
     *
     * A null on either id means there is nothing to compare and nothing
     * carries.
     */
    fun correctionCarryBlock(
        lastExerciseId: String?,
        nextExerciseId: String?,
        nextSetIndexInExercise: Int?,
        comingExerciseId: String?,
    ): Boolean = if (nextExerciseId != null) {
        sameExerciseBlock(lastExerciseId, nextExerciseId, nextSetIndexInExercise)
    } else {
        lastExerciseId != null && lastExerciseId == comingExerciseId
    }

    /**
     * WHAT THE CORRECTION DOES TO THE CARRY, which #205 asks be decided rather
     * than left implied. True when the added load standing for the set coming
     * up must move with this correction; false when it must be left alone.
     *
     * THE ANSWER IS "ONLY WHERE IT WOULD OTHERWISE REPEAT THE SAME WRONG
     * NUMBER." The plates are still on the bar. A lifter who set the app to 60
     * and loaded 65 has 65 standing there for the next set too, so leaving the
     * carry alone hands them the same wrong number again and a second
     * correction to make -- and the fix that only fixes the reported instance
     * is the defect this repo produces most. But the carry is also how a plan
     * prescribes a change, and how a lifter states a load for the set coming
     * up before touching this control at all, so overwriting it
     * unconditionally would let a correction to the past silently displace a
     * decision about the future.
     *
     * So the two cases are separated by what is standing, WITHIN THE BLOCK.
     * [standingAddedKg] is the added load the coming set would currently be
     * recorded at -- the lifter's standing statement where
     * [standingStatedAddedKg] let one through, otherwise whatever the load box
     * was seeded with. Where that is the number being corrected AWAY from and
     * [sameExerciseBlock] holds, the app is about to repeat the mistake and the
     * carry follows. Where it is anything else -- a plan prescribing 80 next, a
     * load already retyped during the rest -- it is a separate statement about
     * a different set and is left exactly as it is. Null is not a number being
     * repeated and never follows.
     *
     * [sameExerciseBlock] IS THE SAME BOUND THE OTHER CARRIES TAKE, and for the
     * same reason: #124 leaked one exercise's load into the next. Past the end
     * of a block the standing statement is gone and the box has already been
     * seeded from the next slot's declaration, so equality alone would fire the
     * carry across the exercise change -- guaranteed on two consecutive
     * body-weight blocks, where both sides are nothing added.
     *
     * COMPARED AT THE BOX'S OWN RESOLUTION, not on the Double. The load box
     * quantises to 0.1 of the display unit, which is the whole of #45, so what
     * is standing is routinely a rounded copy of what was recorded and
     * compares unequal to it exactly. Comparing the rendered values makes the
     * rule the same rule at both units, the way [BodyweightLoadDisplay.label]
     * already decides the same question about a rendered zero.
     *
     * THE CAPTION SAYS WHEN THIS IS TRUE. A control that quietly changes a
     * second thing is worse than one that changes nothing, so the answer here
     * is what [correctionCaption] renders.
     */
    fun carryFollowsCorrection(
        standingAddedKg: Double?,
        recordedAddedKg: Double,
        unit: WeightUnit,
        sameExerciseBlock: Boolean,
    ): Boolean = standingAddedKg != null &&
        sameExerciseBlock &&
        unit.inputValue(standingAddedKg) == unit.inputValue(recordedAddedKg)

    /**
     * What the correction says it changes, drawn under the row.
     *
     * IT NAMES THE SET JUST FINISHED, FIRST AND ALWAYS. #188 is the
     * neighbouring control on this same screen that named the upcoming
     * exercise when it meant the finished one, and a load control read as
     * "set the load for the next set" would be tapped by a lifter trying to do
     * the opposite of what it does. SetLoadCorrectionTest pins the word "next"
     * out of the case where it would be false.
     *
     * [carryFollows] is [carryFollowsCorrection]'s answer, and the second
     * clause appears only when it is true -- because in that case the tap
     * really does change two things, and the lifter is entitled to know which.
     */
    fun correctionCaption(carryFollows: Boolean): String = if (carryFollows) {
        "Corrects the set you just finished, and the load offered for the next one"
    } else {
        "Corrects the set you just finished"
    }

    /**
     * The row's own label, which says whether the figure beside it is still
     * the one the set was recorded with.
     *
     * The same arrangement the rep and the hold corrections use one row up --
     * "Reps counted" against "Reps (corrected)" -- because what is stored is
     * no longer what was recorded and the screen has to say which it is
     * showing.
     */
    fun correctionLabel(corrected: Boolean): String = if (corrected) "Load (corrected)" else "Load recorded"

    /**
     * The finest increment the load field is re-rendered to when the kg/lb chip
     * is tapped, named in [unit]'s own scale.
     *
     * A DISPLAY step, not the plate step [correctionStepKg] uses. The chip does
     * not change the load, so this cannot be 2.5 kg / 5 lb: snapping a
     * conversion to a plate would move the number the lifter typed by up to
     * half a plate on an action that was supposed to move nothing. 0.25 kg and
     * 0.5 lb are the smallest figures a gym's micro-plates can add to a bar in
     * a pair, so a converted field never shows a load nobody could load, and
     * they are close to the same resolution as each other -- 0.5 lb is
     * 0.2268 kg -- so the field does not get visibly coarser or finer for
     * having been converted.
     *
     * The alternative, rendering the exact conversion, puts "220.46226218" in
     * an edit box the lifter has to read at arm's length between sets.
     */
    fun displayStep(unit: WeightUnit): Double = if (unit == WeightUnit.KG) 0.25 else 0.5

    /**
     * What the load field reads after the kg/lb chip is tapped, and the kg it
     * denoted before the tap.
     *
     * [kg] is the load the OLD text named, parsed under the OLD unit, and null
     * when the field named no number. It is the quantity the tap promised not
     * to move, so it is returned rather than left implicit: the whole content
     * of #77 is that a display action changed it.
     */
    data class ConvertedLoad(val text: String, val kg: Double?)

    /**
     * The load field re-rendered for a new display unit.
     *
     * Tapping the kg/lb chip is the lifter asking to SEE the same weight in
     * other units. Until this converted, the text was left alone and re-parsed
     * under the new unit, so a field reading `100` with a kg chip became 100
     * POUNDS -- 45.36 kg -- the moment the chip was tapped, and that was the
     * number the set was recorded with. Load is metadata the IMU stream cannot
     * reconstruct, so unlike a wrong derived figure the wrong number was
     * permanent (#77).
     *
     * Text in, text out, because the field is a string and the string is the
     * declaration for an ad-hoc set. [typed] that names no number -- blank, a
     * lone minus, a lifter mid-retype -- passes through untouched rather than
     * being replaced with a zero: a field stating nothing goes on stating
     * nothing, and this function has no business finishing someone's typing.
     * [from] equal to [to] is the identity for the same reason.
     *
     * NOTHING HERE WRITES A STATED LOAD. [ConvertedLoad.kg] is reported so a
     * caller can name the quantity the tap promised not to move; it is not a
     * value to seed `statedLoadKg` with. That field means "the lifter said
     * this", it is already in kilograms and therefore already right, and a
     * chip tap is not a keystroke.
     *
     * NOT EXACT, AND THE INEXACTNESS IS BOUNDED AND DELIBERATE. The new text
     * is snapped to [displayStep] of [to], so the kg the new text names can
     * differ from [ConvertedLoad.kg] by up to half a step -- at most 0.125 kg
     * converting to kg, at most 0.25 lb (0.113 kg) converting to lb. That is
     * the same kind of lossiness `WeightUnit.inputValue` already has at every
     * other seed site and that `WeightUnitTest` pins (#45); what changed is
     * its size, from a factor of 2.2 to a tenth of a kilo. The alternative,
     * rendering the exact conversion, puts `220.46226218` in an edit box the
     * lifter reads at arm's length between sets.
     *
     * STABLE WITHIN TWO TAPS, NOT ONE -- the property that matters for a chip
     * the lifter can tap twice. Every text this returns lies on a step
     * lattice, but the two lattices are not the same width: 0.25 kg is 0.5512
     * lb, wider than the 0.5 lb step. A KG-lattice text IS a fixed point --
     * converting it to lb and back always returns it, checked over 0-450 kg
     * by 0.25 (1801 values, 0 fail) -- but an LB-lattice text need not be: 93
     * of 1001 values checked over 0-500 lb by 0.5 do not survive
     * lb-to-kg-to-lb. So a hand-typed value that quantises into LB on its
     * first tap can still move again on the second: 47.7 kg taps to `105`
     * lb, then `47.75` kg, then `105.5` lb -- three renderings a tenth of a
     * kilo apart, not the one settled value the earlier claim here promised.
     * From the kg side onward it is exact. `SetLoadUnitToggleTest` sweeps the
     * kg-lattice-seeded case exhaustively and pins the lb-lattice case
     * directly, seeded on that lattice with no kg value ever entering it.
     *
     * RENDERED HERE RATHER THAN BY `WeightUnit.inputValue`, which quantises to
     * 0.1 of the display unit and so cannot write a quarter at all: 8 lb
     * converts to 3.75 kg, and `inputValue` gives `3.6` from the exact
     * conversion or `3.8` from the quarter. A coarser grid than the one just
     * chosen, applied after it.
     */
    fun convertedLoad(typed: String, from: WeightUnit, to: WeightUnit): ConvertedLoad {
        val kg = from.parseToKg(typed)
        if (kg == null || from == to) return ConvertedLoad(typed, kg)
        val step = displayStep(to)
        val snapped = Math.round(to.fromKg(kg) / step) * step
        val text =
            if (snapped == Math.floor(snapped)) {
                snapped.toInt().toString()
            } else {
                // Two places, because the grid is quarters; trailing zeros go
                // so 220.50 reads as 220.5. A value off the whole numbers has
                // a non-zero second or first decimal by construction, so this
                // can never leave a bare trailing point.
                String.format(Locale.US, "%.2f", snapped).trimEnd('0')
            }
        return ConvertedLoad(text, kg)
    }
}
