package com.macrophage.barspeed.model

/**
 * What the rest screen's "Last set" box states about the set just finished
 * (#237).
 *
 * ## Two halves, because they are struck differently
 *
 * [values] are the FIGURES -- the count or the hold, the load -- each carrying
 * what the row was written with wherever a rest-screen correction has displaced
 * it, so the displaced figure can be drawn struck through the way the "Up next"
 * card strikes a prescription the lifter has nudged. [status] is the WORDS --
 * how the set was rated, why it ended, what it was for -- and nothing in it is
 * ever struck, because none of those keeps a previous answer to strike against:
 * re-rating a set replaces the rating outright and the app stores no history of
 * what it read before. A plain string cannot be struck halfway, which is why
 * the figures are pairs; the words have no pair to make, so they are a string.
 *
 * ## Why it is here and not beside the box
 *
 * [SessionPreviewPolicy]'s reason, and [EffortCorrectionPolicy]'s: the caller
 * is a Compose function on a screen with no reachable test seam, so a rule
 * written beside it is a rule nothing on the CI path can fail. Lifted out, every
 * case is a literal in a test that runs on every push.
 *
 * ## The pair is RECORDED against STANDING, not PLAN against standing
 *
 * [SetCardValues.of] is reused whole rather than re-spelled, so the box and the
 * "Up next" card say one set with one vocabulary -- "5 reps", "30s hold",
 * "BW + 10 kg" -- and the strike is drawn by the same code. But the two
 * surfaces compare different things, and reading the argument names literally
 * would get it backwards. On the card `planned` is what the PLAN prescribed and
 * `stated` is what the lifter has changed it to. Here the set is over and the
 * plan has nothing left to say about it: `planned` is what the ROW WAS WRITTEN
 * WITH and `stated` is the correction now standing over it. A struck figure in
 * this box means "the app recorded that and you have since said otherwise",
 * which is the only reading a finished set supports.
 *
 * ## No tempo
 *
 * The card states the tempo because a tempo is something the lifter is about to
 * do. Nothing here knows what tempo the set was PERFORMED at -- `SetFeedback`
 * carries the prescription, and the compliance verdict is a chip in the header
 * with its own scoring -- so printing the prescribed digits on a line that
 * states a record would be claiming the set was lifted to them. The figures
 * stop at the load.
 */
object LastSetRecordPolicy {
    /**
     * The figures the box states, with the recorded figure struck where a
     * correction stands.
     *
     * A TIMED SET STATES ITS HOLD AND NEVER A REP COUNT, decided here rather
     * than at the call site because it is the same mutual exclusion the two
     * correction rows already have -- one returns for a timed set, the other
     * for everything else -- and a rest screen that drew "0 reps · 45s hold"
     * for a plank would be stating a count nobody counted. [countedReps] is
     * passed through untouched on every other set, including a manual set that
     * counted none.
     *
     * @param recordedAddedKg the ADDED load the row was written with, never the
     *   body-weight-inclusive total: on body-weight work the two are different
     *   numbers and only this one may be rendered in #160's notation.
     * @param correctedAddedKg the added load standing over it, or null where no
     *   load correction has been made. Null is not zero -- a set corrected down
     *   to an empty bar is a statement and draws a strike.
     * @param countedReps what the row was written with, sensor count or manual.
     * @param correctedReps the count standing over it, or null for no correction.
     * @param recordedDurationS the seconds the row was written with, or null on
     *   a set that is not timed at all.
     * @param correctedDurationS the seconds standing over them, or null for no
     *   correction.
     */
    fun values(
        kind: ExerciseKind,
        bodyweight: Boolean,
        unit: WeightUnit,
        side: String?,
        recordedAddedKg: Double,
        correctedAddedKg: Double?,
        countedReps: Int?,
        correctedReps: Int?,
        recordedDurationS: Int?,
        correctedDurationS: Int?,
    ): List<SetCardValue> {
        val timed = recordedDurationS != null
        val recordedCount = countedReps.takeIf { !timed }
        return SetCardValues.of(
            kind = kind,
            bodyweight = bodyweight,
            timed = timed,
            unit = unit,
            side = side,
            // The arm the set was worked is frozen in the row and the rest
            // screen offers no way to correct it, so there is no second side
            // to strike the first against.
            plannedSide = null,
            plannedLoadKg = recordedAddedKg,
            statedLoadKg = correctedAddedKg,
            declaredLoadKg = recordedAddedKg,
            plannedReps = recordedCount,
            reps = correctedReps?.takeIf { !timed } ?: recordedCount,
            plannedDurationS = recordedDurationS,
            durationS = correctedDurationS ?: recordedDurationS,
            // See the object's KDoc: the prescribed tempo is not a fact about
            // how the set was lifted, and this line states facts about the set.
            plannedTempo = null,
            tempo = null,
        )
    }

    /**
     * The words under the figures: how the set was rated, why it ended, and
     * what it was for.
     *
     * THREE CLAUSES, JOINED BY " · ", AND EVERY ONE OF THEM IS A STRING
     * A ROW ON THIS SCREEN ALREADY DRAWS. Nothing is authored here: the effort
     * wording is [EffortCorrectionPolicy.lineText]'s, the reason is
     * [SetLimiterPolicy.lineLabel] and [SetLimiterPolicy.lineText]'s, and the
     * warm-up word is the one [WarmupMarkPolicy] already decides the truth of.
     * The box replaces those rows and must not reword them on the way.
     *
     * THE COLON AFTER THE REASON LABEL IS THE ONE MARK THIS ADDS, and it earns
     * its place: the label and its answer are ONE statement, and joined with
     * the same " · " as their neighbours, "Failed · Ended ·
     * Not given" reads as three peers instead of two.
     *
     * THE EFFORT CLAUSE IS ALWAYS SAID, including its named absence. An unrated
     * set is not an RPE of zero, and a blank there reads as the app having lost
     * the rating -- the reason [EffortCorrectionPolicy.NOT_RATED] exists.
     *
     * THE REASON CLAUSE FOLLOWS [SetLimiterPolicy.offersCorrection] AND NOT THE
     * PRESENCE OF AN ANSWER, which is why [rpe] is taken beside the wording of
     * it. An unanswered failure is exactly the set the lifter still has
     * something to do about, and the row this box replaces says so in amber
     * with SAY WHY beside it; dropping the clause because the answer is missing
     * would take an actionable gap off the screen. A set nobody would be asked
     * about carries no clause at all rather than an empty one.
     *
     * THE WARM-UP WORD IS SAID ONLY WHERE THE SET WAS ONE. "Working set" is the
     * ordinary case and stating it on every set would spend a third of the line
     * on the absence of news; the popup behind the Correct button states both
     * readings, and states the plan's declaration beside the lifter's mark
     * where the two disagree. [warmupDeclared] and [warmupMark] are taken as
     * the two facts [WarmupMarkPolicy] keeps apart, never pre-composed by the
     * caller.
     *
     * [ratedDescription] is the gym-facing wording of whichever effort tile the
     * lifter's own rating lit, or null for a set carrying no rating at all.
     */
    fun status(
        ratedDescription: String?,
        rpe: Int?,
        failed: Boolean,
        limiter: SetLimiter?,
        limiterNote: String?,
        timed: Boolean,
        warmupDeclared: Boolean,
        warmupMark: Boolean?,
    ): String {
        val clauses = mutableListOf(EffortCorrectionPolicy.lineText(ratedDescription, failed))
        if (SetLimiterPolicy.offersCorrection(failed, rpe, limiter)) {
            val label = SetLimiterPolicy.lineLabel(failed)
            clauses += "$label: ${SetLimiterPolicy.lineText(limiter, limiterNote, timed, failed)}"
        }
        if (WarmupMarkPolicy.effective(warmupDeclared, warmupMark)) clauses += WARM_UP
        return clauses.joinToString(SEPARATOR)
    }

    /**
     * What the box calls a set the lifter did preparatory work in.
     *
     * The word the correction popup's own warm-up row draws, so a set that
     * reads "Warm-up" in the box reads "Warm-up" in the popup that changes it.
     */
    const val WARM_UP = "Warm-up"

    /** The separator every row on this screen already uses between a label and its answer. */
    private const val SEPARATOR = " · "
}
