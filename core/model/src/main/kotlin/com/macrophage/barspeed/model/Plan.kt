package com.macrophage.barspeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Root of an imported training plan; contract is docs/schemas/plan.schema.json. */
@Serializable
data class PlanFile(
    val schemaVersion: String,
    val planName: String,
    val notes: String? = null,
    /**
     * The lifter's body weight in kilograms, as it stood when the plan was
     * written, issue #161. Declared on the PLAN and not on a session: a plan is
     * authored in one sitting at one body weight, and the app stores exactly
     * one figure, so a per-session key would raise a question about which one
     * wins that has no honest answer.
     *
     * Nullable, and null is not a lesser value than absent -- they are the same
     * statement, which is why this is a nullable field rather than a
     * default-carrying one: the decoder cannot tell them apart, so nothing
     * downstream can start treating them differently. Both mean "not known",
     * and neither writes anything.
     *
     * Applied by [PlanBodyWeightPolicy], which is where the acceptance rule and
     * the conversion live. What it is applied TO is a DataStore preference in
     * `:app`, not a database column, so nothing here touches Room.
     */
    @SerialName("bodyweight_kg") val bodyweightKg: Double? = null,
    /** The same figure in pounds; converted to kilograms by [PlanBodyWeightPolicy]. */
    @SerialName("bodyweight_lb") val bodyweightLb: Double? = null,
    val sessions: List<PlanSessionDef>,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            errors += "Unsupported schemaVersion '$schemaVersion' " +
                "(expected one of ${SUPPORTED_SCHEMA_VERSIONS.joinToString()})"
        }
        if (planName.isBlank()) errors += "planName must not be blank"
        // Refused rather than resolved, both of them, for the reason the load
        // pair is refused: whichever way a contradiction were silently settled,
        // the figure it settled on would become the base load of every
        // bodyweight set until someone noticed, and no recording afterwards
        // says what the lifter actually weighed. A zero is the same mistake
        // wearing absence's clothes -- the plan HAS a way to say "not known",
        // and it is the key not being there.
        if (bodyweightKg != null && bodyweightLb != null) {
            errors += "bodyweight_kg and bodyweight_lb must not both be declared - " +
                "give the weight once, in one unit"
        }
        bodyweightKg?.let { if (it <= 0) errors += nonPositiveBodyweight("bodyweight_kg") }
        bodyweightLb?.let { if (it <= 0) errors += nonPositiveBodyweight("bodyweight_lb") }
        if (sessions.isEmpty()) errors += "Plan must contain at least one session"
        sessions.forEachIndexed { si, session ->
            if (session.name.isBlank()) errors += "sessions[$si].name must not be blank"
            if (session.exercises.isEmpty()) errors += "sessions[$si] must contain at least one exercise"
            session.exercises.forEachIndexed { ei, exercise ->
                if (exercise.exercise.isBlank()) errors += "sessions[$si].exercises[$ei].exercise must not be blank"
                // Tier two of three on the one length limit in this contract.
                // The published schema states maxLength for whatever wrote the
                // document; this names the path for a document that reached the
                // app anyway; and the rest screen caps lines whatever either of
                // them let through. An error rather than a warning because the
                // cap is the mechanism -- a cue that keeps growing takes back
                // the screen the split exists to clear -- and it can refuse no
                // plan written before schema 1.8, the key not having existed.
                exercise.description?.let {
                    if (it.length > DESCRIPTION_MAX_CHARS) {
                        errors += "sessions[$si].exercises[$ei].description is ${it.length} characters, " +
                            "over the $DESCRIPTION_MAX_CHARS-character limit - move the rest into " +
                            "\"additional_notes\", which has no limit"
                    }
                }
                if (exercise.start != null && exercise.start !in VALID_STARTS) {
                    errors += "sessions[$si].exercises[$ei].start must be one of ${VALID_STARTS.joinToString()}"
                }
                if (exercise.concentric != null && exercise.concentric !in VALID_CONCENTRIC) {
                    errors += "sessions[$si].exercises[$ei].concentric must be \"up\" or \"down\""
                }
                exercise.travelRatio?.let {
                    if (it <= 0.0) errors += "sessions[$si].exercises[$ei].travelRatio must be positive"
                }
                // Refused rather than silently corrected. [ImplementLoad.count]
                // coerces a 0 so nothing can divide by it, but a 0 does not say
                // what the plan's author meant, and reading it as 1 would put a
                // figure on screen that nobody declared. No maximum: a bound
                // contradicting the prose beside it is worse than none.
                exercise.implementCount?.let {
                    if (it < 1) errors += "sessions[$si].exercises[$ei].implementCount must be at least 1"
                }
                if (exercise.plane != null && exercise.plane !in VALID_PLANES) {
                    errors += "sessions[$si].exercises[$ei].plane must be \"vertical\" or \"horizontal\""
                }
                if (exercise.kind != null && exercise.kind !in VALID_KINDS) {
                    errors += "sessions[$si].exercises[$ei].kind must be one of ${VALID_KINDS.joinToString()}"
                }
                exercise.prepS?.let {
                    if (it < LeadInPolicy.MIN_S || it > LeadInPolicy.MAX_S) {
                        errors += "sessions[$si].exercises[$ei].prep_s must be between " +
                            "${LeadInPolicy.MIN_S} and ${LeadInPolicy.MAX_S} seconds"
                    }
                }
                // Still refused rather than clamped, though the key is inert
                // since 1.10 (#198). The bound is not a taste: the app runs
                // one collector per stream and knows two roles, so a 3 has no
                // client, no journal file and no column value. Keeping the
                // error means a document that failed before still fails, and
                // the author of a 3 is told they misunderstood something --
                // which is more useful than accepting a figure into a key that
                // does nothing. What the declaration no longer does is said at
                // the gate by `sensorsInert`, not here.
                exercise.sensors?.let {
                    if (it < SensorCapturePolicy.MIN_COUNT || it > SensorCapturePolicy.MAX_COUNT) {
                        errors += "sessions[$si].exercises[$ei].sensors must be " +
                            "${SensorCapturePolicy.MIN_COUNT} or ${SensorCapturePolicy.MAX_COUNT}"
                    }
                }
                if (exercise.sets.isEmpty()) errors += "sessions[$si].exercises[$ei] must contain at least one set"
                exercise.sets.forEachIndexed { xi, set ->
                    // On bodyweight work the load is what was ADDED, and a band or
                    // assist machine subtracts — so negatives are meaningful there.
                    errors += set.validate(
                        "sessions[$si].exercises[$ei].sets[$xi]",
                        allowNegativeLoad = exercise.bodyweight,
                    )
                }
            }
        }
        return errors
    }

    /**
     * Non-blocking notes shown at the import gate. A declaration always wins —
     * machines of the same movement pattern really do start at opposite ends —
     * but it is worth saying out loud when a plan pins something that
     * contradicts how the built-in lift is normally performed, because a
     * mis-declared direction inverts the voice guide's whole cadence and a
     * mis-declared kind changes which numbers the set is judged on. It is
     * worth saying just as loudly when the plan made no decision at all: the
     * app is then guessing the same direction, and nothing at the gate said so.
     *
     * Grouped by what each warning is really about, not by where it appears in
     * the plan, because each group prompts a different question back to
     * whoever wrote it. An undeclared start comes first among the direction
     * and kind warnings, because it is the one case here where the plan made
     * no decision for the app to follow at all. Overriding a built-in comes
     * next: that is the app being told to ignore something it ships with, and
     * this is the only sign it happened.
     *
     * The two coaching-text warnings come last, because nothing is lost in
     * either: every word the plan wrote is still reachable on screen. What they
     * report is narrower — which part of it the lifter reads without tapping,
     * a choice the app made on the author's behalf.
     */
    fun warnings(): List<String> = eachExercise(::pairVsLoad) +
        eachExercise(::startUndeclared) +
        eachExercise(::startVsSeed) +
        eachExercise(::kindVsSeed) +
        eachExercise(::kindVsShape) +
        eachExercise(::kindVsInference) +
        eachExercise(::prepVsGuide) +
        eachExercise(::prepVsPhrase) +
        eachExercise(::sensorsInert) +
        eachExercise(::cueSplit) +
        eachExercise(::cueBehindTapOnly)

    /**
     * A declared pair, stated back with BOTH figures in the plan's own unit.
     *
     * First in the list because it is the only warning here about a LOAD. The
     * others describe how a set will be measured and are recoverable from the
     * persisted capture afterwards; this one is the last surface before a
     * number is recorded that cannot be reconstructed from anything. The
     * mistake it exists to catch is a figure that is right for one hand and
     * half of what was lifted: `"load_lb": 40` with `"implementCount": 2` on a
     * set really done at two 40s records 40, and nothing in the app can tell
     * that afterwards from a real 40 lb set.
     *
     * Quoted in the plan's own unit and its own figure. The question being put
     * back to the author is about what they wrote; the app renders in whatever
     * unit the lifter has selected, so a warning naming a converted figure
     * would be a claim about a screen it cannot see.
     *
     * Silent when no set declares a positive load. There is then no number to
     * have got backwards, and a warning with nothing in it is how a warning
     * decays into noise.
     */
    private fun pairVsLoad(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val n = exercise.implementCount ?: return null
        if (n < 2) return null
        val set = exercise.sets.firstOrNull { (it.loadLb ?: it.loadKg ?: 0.0) > 0 } ?: return null
        val unit = if (set.loadLb != null) "lb" else "kg"
        val total = set.loadLb ?: set.loadKg ?: return null
        val whole = "${plainNumber(total)} $unit"
        return "sessions[$si].exercises[$ei]: ${exercise.exercise} declares " +
            "\"implementCount\": $n, so this plan's $whole means $n × ${plainNumber(total / n)} $unit " +
            "in hand, not $whole in each. load_kg/load_lb is always the TOTAL across everything " +
            "held — if $whole was what was on one of them, write the total of all $n here instead."
    }

    private fun eachExercise(warn: (Int, Int, PlanExerciseDef) -> String?): List<String> = sessions
        .flatMapIndexed { si, session ->
            session.exercises.mapIndexedNotNull { ei, exercise -> warn(si, ei, exercise) }
        }

    /**
     * `start` left undeclared on an exercise the app does not ship, so which
     * end of the range each rep opens from is guessed off the id rather than
     * known — the guess that turned five sets of a real leg-press session
     * backwards, with nothing at the import gate to say it was a guess.
     *
     * Fires only where the guess is both real and consumed. Real: a built-in
     * id already carries a genuine value from [ExerciseDef.SEED], so nothing
     * is being guessed there — that is why this checks [ExerciseDef.seedById]
     * rather than reading [PlanExerciseDef.startsAtTop], which would report a
     * guess even for a seeded id. Consumed: `RepSegmenter` (`:core:dsp`) reads
     * the resolved direction to open every rep on every set that is not
     * timed, tempo or no tempo, but `RecordViewModel.runSetWrite` skips
     * segmentation outright for a timed set and grades it on the clock
     * instead — so a direction no timed set ever reaches is not worth
     * flagging, and an exercise warns only when at least one of its sets is
     * not timed.
     *
     * Checks [PlanExerciseDef.start] directly rather than
     * [PlanExerciseDef.startPhaseOverride], which also reads null for an
     * unrecognised value such as `"sideways"`. That case wrote something and
     * got it wrong — `validate()` already reports it as an error — and must
     * not also be reported here as nothing having been written at all.
     */
    private fun startUndeclared(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        if (exercise.start != null) return null
        if (ExerciseDef.seedById(exercise.exercise) != null) return null
        if (exercise.sets.all { it.isTimed }) return null
        return "sessions[$si].exercises[$ei]: ${exercise.exercise} does not declare \"start\", and is " +
            "not one of the app's built-in exercises, so the app is guessing which end of the range it " +
            "begins at from the id alone - the guess decides which direction opens a rep and, on a set " +
            "carrying a tempo, the voice guide's first call. Declare \"start\": \"top\" or \"bottom\" " +
            "to replace it."
    }

    private fun startVsSeed(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val seed = ExerciseDef.seedById(exercise.exercise) ?: return null
        val declared = exercise.startPhaseOverride ?: return null
        if (declared == seed.startsWith) return null
        val natural = if (seed.startsAtTop) "the top" else "the bottom"
        val asked = if (exercise.startsAtTop) "the top" else "the bottom"
        return "sessions[$si].exercises[$ei]: ${exercise.exercise} normally starts at $natural, " +
            "but this plan starts it at $asked — the voice guide will follow the plan."
    }

    private fun kindVsSeed(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val declared = exercise.kindOverride ?: return null
        val seed = ExerciseDef.seedById(exercise.exercise) ?: return null
        if (declared == seed.kind) return null
        return "sessions[$si].exercises[$ei]: ${exercise.exercise} is built in as ${seed.kind.description}, " +
            "but this plan declares \"kind\": \"${exercise.kind}\" - the app will follow the plan."
    }

    /**
     * A declared kind and the set's own shape are allowed to disagree and both
     * still hold: the declaration decides what the movement is, the shape
     * decides how the set is measured. Saying so is the point — a plan that
     * declares a hold and prescribes reps is going to be counted in reps, and
     * whoever wrote it should know that before the lifter is under the bar.
     */
    private fun kindVsShape(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val declared = exercise.kindOverride ?: return null
        val timedKind = declared == ExerciseKind.HOLD || declared == ExerciseKind.CARRY
        val mismatched = exercise.sets.firstOrNull { it.isTimed != timedKind } ?: return null
        val prescribed = if (mismatched.isTimed) "duration_s" else "reps"
        val measured = if (mismatched.isTimed) "on the clock" else "on reps"
        return "sessions[$si].exercises[$ei]: this plan declares \"kind\": \"${exercise.kind}\" " +
            "but prescribes $prescribed - the set is measured $measured either way. " +
            "Kind sets the movement type, not the timing."
    }

    /**
     * A prep declared on an exercise no set of which plays one.
     *
     * Nothing is lost and nothing is wrong, but the plan's author asked for
     * something and nothing happens, and the import gate is the only place that
     * can tell them. Not hypothetical: in the published `plan.example.json`,
     * `hanging_leg_raise` and `band_assisted_pull_up` are both rep-based with no
     * tempo, and a cable machine -- the example that motivated a declarable prep
     * -- is among the likeliest to be given one and no tempo.
     *
     * The holds and carries in that same example used to be listed here and are
     * not any more: a timed set of a hold or a carry plays a prep, so a
     * declaration on one is live.
     */
    private fun prepVsGuide(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        if (exercise.prepS == null || exercise.playsAnyPrep) return null
        return "sessions[$si].exercises[$ei]: \"prep_s\" is declared on ${exercise.exercise}, " +
            "but no set of it plays a prep - a prep plays before a set carrying a tempo unless " +
            "the lift is explosive, and before a timed hold or carry - so no prep is played and " +
            "the declaration has no effect."
    }

    /**
     * A prep too short to fit the launch phrase, naming what gets dropped.
     *
     * A warning rather than an error: a two-second cable set is exactly what a
     * declarable prep was asked for, and a lifter who wants no countdown should
     * be able to have none. What the plan's author may not know is that below
     * the phrase length the countdown is not what goes -- the phrase is fixed to
     * the END of the prep, so at 1 second `Ready` is dropped and only `Brace`
     * survives. Reachable on a hold since preps reached them, which is why the
     * warning names the set beginning rather than a first movement call.
     *
     * Silent on an inert declaration: how short a prep that never plays would
     * have been is not a fact about anything, and two complaints about one
     * mistake bury the one that matters.
     */
    private fun prepVsPhrase(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val prep = exercise.prepS ?: return null
        if (!exercise.playsAnyPrep || prep >= LeadInPolicy.MIN_USEFUL_S) return null
        if (prep == 0) {
            return "sessions[$si].exercises[$ei]: \"prep_s\": 0 means nothing at all is spoken " +
                "before the set begins."
        }
        return "sessions[$si].exercises[$ei]: \"prep_s\": $prep is shorter than the launch " +
            "phrase - the prep says only \"Brace\", and the \"Ready\" beat two seconds before " +
            "the set begins is dropped."
    }

    /**
     * A `sensors` declaration, saying it no longer decides anything (#198).
     *
     * Sits with the inert-declaration warnings because that is what it is: the
     * same shape as [prepVsGuide], which reports a `prep_s` on an exercise
     * that plays no prep. The plan looks like it said something and the app
     * does something else -- here, records from whatever is connected.
     *
     * ONE warning per exercise however many places declare it. The key can be
     * written on the exercise and on every set, and a plan carrying it on six
     * sets of one lift would otherwise produce six identical lines, which is
     * how a gate becomes something the eye skips.
     */
    private fun sensorsInert(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        if (exercise.sensors == null && exercise.sets.none { it.sensors != null }) return null
        return "sessions[$si].exercises[$ei]: \"sensors\" is declared on ${exercise.exercise}, " +
            "but the app records from whatever sensors are connected -- one stream from one, two " +
            "from two that are labelled A and B -- so the declaration is ignored and has no effect."
    }

    /**
     * Both coaching keys on one exercise, saying which of them the lifter reads
     * without touching the phone.
     *
     * Nothing is dropped — [PlanNoteDisplay.forSet] puts `notes` behind the
     * expand tap rather than discarding it — so this is a warning. What the
     * author cannot otherwise know is that the app picked between the two on
     * their behalf, and picked the newer key.
     */
    private fun cueSplit(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        if (exercise.description.isNullOrBlank() || exercise.notes.isNullOrBlank()) return null
        return "sessions[$si].exercises[$ei]: ${exercise.exercise} declares both \"description\" and " +
            "\"notes\" - \"description\" is what shows between sets and \"notes\" moves behind the expand " +
            "tap. Neither is dropped, but only one of them is read at a glance."
    }

    /**
     * An exercise whose entire cue is behind the expand tap.
     *
     * Legal, and left alone on screen: promoting `additional_notes` to the
     * visible line would put the paragraph back exactly where this split took
     * it from. But an author who wrote only that key almost certainly did not
     * mean the lifter to see nothing between sets, and the import gate is the
     * one surface that can ask before the plan is used.
     */
    private fun cueBehindTapOnly(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        if (exercise.additionalNotes.isNullOrBlank()) return null
        if (!exercise.description.isNullOrBlank() || !exercise.notes.isNullOrBlank()) return null
        return "sessions[$si].exercises[$ei]: \"additional_notes\" is declared on ${exercise.exercise} with " +
            "no \"description\" and no \"notes\", so nothing this exercise declares shows until the lifter " +
            "expands it. Put the line that decides how the set is performed in \"description\"."
    }

    private fun kindVsInference(si: Int, ei: Int, exercise: PlanExerciseDef): String? {
        val declared = exercise.kindOverride ?: return null
        if (ExerciseDef.seedById(exercise.exercise) != null) return null
        val guess = ExerciseDef.inferKind(exercise.exercise)
        if (declared == guess) return null
        return "sessions[$si].exercises[$ei]: this plan declares \"kind\": \"${exercise.kind}\" for " +
            "${exercise.exercise}; the app would have guessed ${guess.name.lowercase()} from the id. " +
            "The plan wins."
    }

    companion object {
        const val SCHEMA_VERSION = "1.10"

        /**
         * `"1.10"` is not the number 1.1 -- a reader parsing this as a float
         * collides them, and they are different contracts.
         */
        val SUPPORTED_SCHEMA_VERSIONS =
            setOf("1.0", "1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "1.10")
        val VALID_SIDES = setOf("left", "right")

        /** "top"/"bottom" name the start position; "down"/"up" the first movement. */
        val VALID_STARTS = setOf("top", "bottom", "up", "down")
        val VALID_CONCENTRIC = setOf("up", "down")
        val VALID_PLANES = setOf("vertical", "horizontal")

        /**
         * The declarable exercise kinds, lowercased [ExerciseKind] names. This
         * is the first plan vocabulary that is 1:1 with a Kotlin enum, so it is
         * the first that can be pinned in both directions rather than only
         * against the published schema.
         */
        val VALID_KINDS = setOf("dynamic", "hold", "carry", "explosive")

        /**
         * How many characters of [PlanExerciseDef.description] the rest screen
         * has room for without the cue taking the space the next-set controls
         * need.
         *
         * The figure is an ESTIMATE from declared sizes, not a measurement of a
         * device: the `SlotCard` note draws in `bodySmall` (12sp) inside a card
         * padded 14dp, on a screen padded 16dp, so a 411dp phone leaves ~351dp
         * of text width; at roughly 6.4dp of advance per character that is ~55
         * characters a line, and the owner's calibration for this cue is four
         * lines. 4 × 55 = 220. What is NOT claimed is that 220 characters
         * renders as four lines on any particular phone at any particular font
         * scale — it does not at fontScale 2.0, which is why the display caps
         * lines as well and why that cap is the third tier rather than this
         * being the only one.
         *
         * Enforced by [validate] and published as `maxLength` on
         * `description` in `docs/schemas/plan.schema.json`; the two are pinned
         * equal by `SchemaContractTest`.
         */
        const val DESCRIPTION_MAX_CHARS = 220
    }
}

/**
 * A figure quoted back to a plan's author the way they wrote it, without the
 * trailing ".0" a Double prints. Local to this file because it exists only for
 * [PlanFile.warnings]: WeightUnit.format is the app's renderer and converts,
 * which is exactly what a message about the plan's own text must not do.
 */
private fun plainNumber(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else value.toString()

/**
 * Named for both bodyweight keys, so the two messages cannot drift apart. It
 * says what to write instead, because the alternative to a refused zero is not
 * a different number -- it is the key not being there.
 */
private fun nonPositiveBodyweight(key: String): String =
    "$key must be positive - omit it, or write null, when the weight is not known"

@Serializable
data class PlanSessionDef(
    val name: String,
    val notes: String? = null,
    val exercises: List<PlanExerciseDef>,
)

@Serializable
data class PlanExerciseDef(
    val exercise: String,
    /**
     * The whole coaching cue, in the shape every plan written before schema
     * 1.8 states it. Still accepted, still decoded, and still what the lifter
     * sees when no [description] is declared — a plan already staged must not
     * lose text because a newer key exists.
     */
    val notes: String? = null,
    /**
     * The part of the cue the lifter reads between sets without touching the
     * phone, capped at [PlanFile.DESCRIPTION_MAX_CHARS] characters so it cannot
     * take the screen the rest of the rest screen needs.
     *
     * Wins the visible line over [notes] when both are declared, and the
     * import gate says so. Over-length is an error naming the path, not a
     * truncation: the app never cuts a coach's sentence in half to fit.
     */
    val description: String? = null,
    /**
     * Everything the cue says beyond [description] — the paragraph, the safety
     * detail, the setup ritual. Reachable only by expanding the note, so
     * nothing that decides how the next set is performed belongs here.
     *
     * No length limit — it is where the overflow goes, and capping it is how
     * text starts getting deleted to fit. Declared on its own, with no
     * [description] and no [notes], it stays behind the tap and the import gate
     * warns rather than the app overruling the author on screen.
     */
    @SerialName("additional_notes") val additionalNotes: String? = null,
    /**
     * Where the lift begins in space, so the app knows which way the first
     * movement of every rep goes: `"top"` (squat, bench, leg curl — first
     * movement is down) or `"bottom"` (press from the rack, deadlift, leg
     * press — first movement is up). Legacy `"down"`/`"up"` name the first
     * movement directly and mean the same thing. Omitted → inferred from the id.
     *
     * Machines of the same movement pattern genuinely differ here, which is why
     * it is declared rather than inferred.
     */
    val start: String? = null,
    /**
     * Which way the concentric (driving) phase moves: `"up"` (default) or
     * `"down"` for leg curls, pulldowns and pushdowns. **Independent of
     * [start]** — that says which phase comes first, this says which direction
     * the drive goes — and it is what decides which tempo digit is the
     * eccentric.
     */
    val concentric: String? = null,
    /**
     * True when the sensor moves OPPOSITE to the load the lifter drives — the
     * usual case on a cable machine, where the sensor rides the weight stack, so
     * pulling the handle down sends the stack (and the sensor) up. Without this
     * every phase label and every velocity on that exercise is backwards.
     */
    val sensorInverted: Boolean = false,
    /**
     * True when the sensor rides the cable weight stack rather than the handle.
     * The stack travels VERTICALLY however the lifter moves, so this — not
     * [plane] — is what decides which axis is measured.
     */
    val sensorOnStack: Boolean = false,
    /**
     * Lifter-side travel per unit of sensor travel, for pulleys that do not move
     * 1:1 — a 2:1 cable moves the handle twice as far as the stack, so `2`.
     * Defaults to 1.
     */
    val travelRatio: Double? = null,
    /**
     * Which plane the movement travels in: `"vertical"` (default — squats,
     * presses, curls) or `"horizontal"` (seated rows, chest-press machines,
     * horizontal cable work). Vertical analysis measures the wrong axis
     * entirely on a horizontal machine, so declare it.
     */
    val plane: String? = null,
    /**
     * True when the lifter's own body is the load — pull-ups, dips, push-ups.
     * The set's load is then what was ADDED, and may be negative for assistance
     * (band or machine). Total load is body weight plus that.
     */
    val bodyweight: Boolean = false,
    /**
     * How many IDENTICAL objects are held at once — 2 for a pair of dumbbells
     * or a two-handled carry, 1 (or omitted) for a barbell, a machine, a
     * goblet squat, a single-arm row, or one dumbbell held on a weighted dip.
     *
     * DISPLAY ONLY. [PlanSetDef.loadKg] and [PlanSetDef.loadLb] are always the
     * TOTAL across everything held, with this key and without it, and
     * [PlanSetDef.resolvedLoadKg] does not read this — see [ImplementLoad].
     *
     * Omit it when the objects are NOT identically loaded; the app then shows
     * the total alone rather than inventing a split. That is why this is
     * nullable: absent means "no split to show" and is a different fact from
     * a declared 1.
     *
     * Never derived from [PlanSetDef.side] and never guessed from the exercise
     * id. A rear-foot-elevated split squat holding two dumbbells is unilateral
     * with two objects; a suitcase carry is unilateral with one.
     */
    val implementCount: Int? = null,
    /** Accessory work that may be dropped when a session runs long. */
    val optional: Boolean = false,
    /**
     * How the movement is performed: `"dynamic"`, `"hold"`, `"carry"` or
     * `"explosive"`. Omitted → the built-in definition if there is one, then a
     * guess from the id, which matches word fragments and gets ordinary names
     * wrong.
     *
     * This says what the movement IS, not how a set of it is measured. Whether
     * a set runs on reps or on the clock comes from the set's own shape — see
     * [PlanSetDef.isTimed]. Kind decides the peak-velocity readout, whether the
     * voice guide runs a tempo, and whether plate math applies.
     */
    val kind: String? = null,
    /**
     * Seconds of prep before each set of this exercise: the pause between the
     * lifter starting the set and the set actually beginning.
     *
     * What the plan's author is estimating is the time the LIFTER'S HANDS ARE
     * UNAVAILABLE -- straps, chalk, a hook grip, a belt, lying down on a machine
     * -- not the walk to the rack or the loading of plates, which happen before
     * the set is started at all. A Romanian deadlift with straps is not ready
     * five seconds after START; a cable machine is ready in two.
     *
     * `Int?` and never `Int = 0`: omitted and zero are different states.
     * Omitted means the app's default; zero means this machine is ready
     * instantly and nothing should be spoken. Omitted is resolved by
     * [LeadInPolicy], which is also where the bounds and the precedence live.
     *
     * Applies to a set carrying a tempo, where the prep runs into the first
     * movement call, and to a timed set of a hold or a carry, where it runs into
     * the word that starts the clock. Declared anywhere else it is inert, and
     * [PlanFile.warnings] says so at the import gate.
     *
     * The lifter can change it in the app, and the change is reported back in
     * the session export as `prep_s` beside `plannedPrep_s`. A plan being
     * authored from an export should take what was played rather than re-guess.
     */
    @SerialName("prep_s") val prepS: Int? = null,
    /**
     * ACCEPTED AND INERT since schema 1.10. Declared here so a plan written
     * against 1.8 or 1.9 still imports; it decides nothing (#198).
     *
     * The app records from whatever accelerometers are connected: one
     * connected bar sensor writes one stream, two connected and labelled A and
     * B write two, on every set of every exercise. The owner's ruling that
     * retired it: *"If you've got one use one, if you've got two, use both."*
     * The count was the wrong way round for a capture decision -- recording is
     * cheap and irreversible, not recording is free and unrecoverable -- and
     * on machine work both units go on the same stack, where the second stream
     * is a second measurement of one motion rather than idle data.
     *
     * KEPT rather than removed from the contract, and that is a trade rather
     * than tidiness: a plan is a document a coach writes by hand, and failing
     * an import over a key that used to be correct costs more than an accepted
     * key that does nothing. What pays for it is [PlanFile.warnings], which
     * says at the import gate that the declaration has no effect -- an
     * accepted key nobody is told about is the lie this arrangement would
     * otherwise be.
     *
     * Still validated to 1 or 2 by [PlanFile.validate]: loosening a published
     * bound is a change nobody asked for, and a 3 says its author believed
     * something worth correcting.
     *
     * [SensorCapturePolicy.roster] is where capture is decided and pinned, and
     * it takes no count at all.
     */
    val sensors: Int? = null,
    val sets: List<PlanSetDef>,
) {
    /**
     * True when at least one set of this exercise plays a prep. The predicate is
     * [LeadInPolicy.playsPrep], stated once for the import gate, the record flow
     * and the screen alike.
     */
    val playsAnyPrep: Boolean
        get() = sets.any { LeadInPolicy.playsPrep(it.tempo != null, it.isTimed, effectiveKind) }

    /** True when the drive goes up; plans may pin it, otherwise inferred from the id. */
    val concentricUp: Boolean
        get() = when (concentric) {
            "down" -> false
            "up" -> true
            // Never guessed from the id: see ExerciseDef.concentricUp.
            else -> true
        }

    /** Declared first-movement direction, when the plan pins one. */
    private val startsAtTopOverride: Boolean?
        get() = when (start) {
            "top", "down" -> true
            "bottom", "up" -> false
            else -> null
        }

    /** Where this exercise begins, declared or inferred from the id. */
    val startsAtTop: Boolean
        get() = startsAtTopOverride
            ?: ((ExerciseDef.inferStartPhase(exercise) == StartPhase.ECCENTRIC) == concentricUp)

    /** Declared kind, when the plan pins one and names a kind that exists. */
    val kindOverride: ExerciseKind?
        get() = kind?.let { declared ->
            ExerciseKind.entries.firstOrNull { it.name.equals(declared, ignoreCase = true) }
        }

    /**
     * The kind to track this exercise as. A declaration always wins — over the
     * built-in definition as well as over the guess — because the plan's author
     * knows which movement they meant and the app is guessing from a string.
     * Every disagreement is warned about at the import gate rather than
     * silently resolved.
     */
    val effectiveKind: ExerciseKind
        get() = kindOverride
            ?: ExerciseDef.seedById(exercise)?.kind
            ?: ExerciseDef.inferKind(exercise)

    /**
     * Start-phase override, when the plan pins one. Combines the two declared
     * properties: the first phase is the concentric when the direction the lift
     * starts moving is the direction the drive goes.
     */
    val startPhaseOverride: StartPhase?
        get() {
            val top = startsAtTopOverride ?: return null
            return if (top == concentricUp) StartPhase.ECCENTRIC else StartPhase.CONCENTRIC
        }
}

@Serializable
data class PlanSetDef(
    /** Rep count for dynamic sets; exactly one of reps / duration_s must be present. */
    val reps: Int? = null,
    /** Duration for timed sets (planks, carries); exactly one of reps / duration_s. */
    @SerialName("duration_s") val durationS: Int? = null,
    /** Load in kilograms; at most one of load_kg / load_lb. Omit both for bodyweight. */
    @SerialName("load_kg") val loadKg: Double? = null,
    /** Load in pounds; converted to kilograms on import. */
    @SerialName("load_lb") val loadLb: Double? = null,
    val tempo: String? = null,
    /** For unilateral work: "left" or "right". Emit one set per side. */
    val side: String? = null,
    /** Commentary for this set alone (the exercise-level `notes` covers all its sets). */
    val note: String? = null,
    @SerialName("targetMeanConcentricVelocity_mps") val targetMeanConcentricVelocityMps: Double? = null,
    @SerialName("velocityLossStop_pct") val velocityLossStopPct: Double? = null,
    @SerialName("rest_s") val restS: Int? = null,
    /**
     * ACCEPTED AND INERT since schema 1.10, exactly as
     * [PlanExerciseDef.sensors] is, and for the reasons stated there (#198).
     *
     * It used to override the exercise's declaration for one set. There is no
     * declaration left to override: capture is decided by the connected
     * hardware on every set.
     */
    val sensors: Int? = null,
    /**
     * This set is preparatory: a ramp set, a warm-up, work that is not the
     * training stimulus (#187).
     *
     * DECLARED, not rated. Warm-up-ness is what a set is FOR; how it went is a
     * separate fact and the effort scale carries that one. Until schema 1.9
     * the only producer was an effort TILE, which stored `warmup = true` and
     * `rpe = null` together -- so the effort of every warm-up was discarded by
     * construction, and a 500 lb squatter's empty-bar ramp set could not
     * record that it felt like nothing. Now the coach who wrote the ramp
     * declares it and the lifter still rates it.
     *
     * Per set rather than per exercise, because a block ramps: the warm-up
     * singles and the working set are sets of the same exercise. An exercise
     * whose every set is preparatory declares it on every set, which is
     * verbose and unambiguous, and is preferred to a second inherited level
     * that a set would then have to be able to override.
     *
     * Omitted means false. There is no way to say "not known": a plan that
     * does not mark a set is a plan whose author considered it working, and
     * every plan written before 1.9 is read exactly that way.
     */
    val warmup: Boolean = false,
) {
    /** Canonical load in kilograms regardless of which unit the plan used. */
    val resolvedLoadKg: Double?
        get() = loadKg ?: loadLb?.let { it / WeightUnit.LB_PER_KG }

    val isTimed: Boolean get() = durationS != null

    fun validate(path: String, allowNegativeLoad: Boolean = false): List<String> {
        val errors = mutableListOf<String>()
        if (reps == null && durationS == null) {
            errors += "$path must have reps (dynamic set) or duration_s (hold/carry)"
        }
        if (reps != null && durationS != null) {
            errors += "$path must not have both reps and duration_s"
        }
        reps?.let { if (it <= 0) errors += "$path.reps must be positive" }
        durationS?.let { if (it <= 0) errors += "$path.duration_s must be positive" }
        if (loadKg != null && loadLb != null) {
            errors += "$path must not have both load_kg and load_lb"
        }
        if (!allowNegativeLoad && ((loadKg ?: 0.0) < 0 || (loadLb ?: 0.0) < 0)) {
            errors += "$path load must be >= 0 (negative load means assistance, " +
                "which requires \"bodyweight\": true on the exercise)"
        }
        if (tempo != null && durationS != null) {
            errors += "$path.tempo does not apply to timed sets"
        }
        if (side != null && side !in PlanFile.VALID_SIDES) {
            errors += "$path.side must be \"left\" or \"right\""
        }
        tempo?.let {
            if (Tempo.parseOrNull(it) == null) errors += "$path.tempo '$it' is not valid tempo notation"
        }
        velocityLossStopPct?.let {
            if (it <= 0 || it > 100) errors += "$path.velocityLossStop_pct must be in (0, 100]"
        }
        // The same bound the exercise block is held to, stated at the same
        // strength. A set-level 3 that the exercise never declared would
        // otherwise reach the record flow through a different door.
        sensors?.let {
            if (it < SensorCapturePolicy.MIN_COUNT || it > SensorCapturePolicy.MAX_COUNT) {
                errors += "$path.sensors must be ${SensorCapturePolicy.MIN_COUNT} " +
                    "or ${SensorCapturePolicy.MAX_COUNT}"
            }
        }
        return errors
    }
}
