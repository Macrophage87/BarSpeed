package com.macrophage.barspeed.model

/**
 * How bad an import warning is for the lifter, not for the plan's author.
 *
 * [RECORDS_WRONG] is a plan that will put a wrong or missing fact into a set
 * as it is recorded, or cue the lift the wrong way round: a load key the
 * decoder dropped, a key lost at the wrong level, a drive direction left to a
 * default that is wrong for the lift. These are the ones the gate asks a
 * decision on.
 *
 * [NOTE] is everything else: the app following a declaration the plan made on
 * purpose ("The plan wins"), an extra key that carried nothing, a cue that
 * moved behind a tap. Worth sending back to whoever wrote the plan, never a
 * reason to stop.
 */
enum class PlanWarningTier { RECORDS_WRONG, NOTE }

/**
 * One import warning as the gate draws it.
 *
 * [text] is the short line for the lifter: 1-based set numbers and exercise
 * display names, never a JSON path or a raw id. [raw] is the warning exactly
 * as [PlanImport.parse] wrote it, which is what the Copy button carries back
 * to the plan's author -- the author needs the path and the key names, the
 * lifter does not.
 *
 * [rule] names which warning this was read as. [PlanWarningRule.UNRECOGNISED]
 * is a warning this file has no wording for; it is drawn as [RECORDS_WRONG]
 * with its raw text, so a warning added to [PlanFile.warnings] without a rule
 * here is shown loudly rather than folded into the notes.
 */
data class PlanWarningLine(
    val tier: PlanWarningTier,
    val text: String,
    val raw: String,
    val rule: PlanWarningRule,
)

/** Which warning a raw line was read as; see [PlanWarningLine.rule]. */
enum class PlanWarningRule(val tier: PlanWarningTier) {
    LOAD_DROPPED(PlanWarningTier.RECORDS_WRONG),
    KEY_MISSPELT(PlanWarningTier.RECORDS_WRONG),
    KEY_WRONG_LEVEL(PlanWarningTier.RECORDS_WRONG),
    PAIR_LOAD(PlanWarningTier.RECORDS_WRONG),
    START_GUESSED(PlanWarningTier.RECORDS_WRONG),
    DRIVE_DEFAULTED(PlanWarningTier.RECORDS_WRONG),
    PROGRESSION_MISMATCH(PlanWarningTier.RECORDS_WRONG),
    UNKNOWN_KEY(PlanWarningTier.NOTE),
    IMPLEMENT_UNDECLARED(PlanWarningTier.NOTE),
    START_OVERRIDDEN(PlanWarningTier.NOTE),
    KIND_OVERRIDDEN(PlanWarningTier.NOTE),
    KIND_VS_SETS(PlanWarningTier.NOTE),
    PREP_UNUSED(PlanWarningTier.NOTE),
    PREP_SHORT(PlanWarningTier.NOTE),
    SENSORS_IGNORED(PlanWarningTier.NOTE),
    STACK_INVERTED(PlanWarningTier.NOTE),
    STACK_ASSUMED(PlanWarningTier.NOTE),
    BODYWEIGHT_ASSUMED(PlanWarningTier.NOTE),
    CUE_SPLIT(PlanWarningTier.NOTE),
    CUE_BEHIND_TAP(PlanWarningTier.NOTE),
    UNRECOGNISED(PlanWarningTier.RECORDS_WRONG),
}

/**
 * Reads the warnings [PlanImport.parse] returns into what the import gate
 * shows a lifter (UX review 2026-11-06, plans F3).
 *
 * The warnings are written for the plan's author: a 0-based JSON path, the raw
 * exercise id and the key names. `sessions[0].exercises[2].sets[2]` is the set
 * Plan detail calls set 3, and every line was drawn in the same amber whether
 * it cost a recorded load or said the plan's own declaration was followed.
 * This splits them in two tiers and words each one for the person under the
 * bar, keeping the author's text for the clipboard.
 *
 * Classification reads the author's sentence, because that is what the
 * warning producers return. Every rule is pinned by `PlanWarningDisplayTest`
 * against the text the real producers write, so rewording a producer without
 * moving its rule here reds that test, and anything that still slips through
 * is [PlanWarningRule.UNRECOGNISED] and shown in the top tier.
 */
object PlanWarningDisplay {
    /** Every warning, top tier first, each tier in the order the import wrote it. */
    fun lines(warnings: List<String>, plan: PlanFile?): List<PlanWarningLine> =
        warnings.map { of(it, plan) }.sortedBy { it.tier.ordinal }

    /** "1 note" / "5 notes": the collapsed second tier's label. */
    fun notesLabel(count: Int): String = if (count == 1) "1 note" else "$count notes"

    /** The text a "Copy" button carries: every raw line, under a heading that survives a paste. */
    fun relayText(heading: String, warnings: List<String>): String =
        (listOf("$heading:") + warnings.map { "- $it" }).joinToString("\n")

    /** The name Plan detail shows for an exercise id. */
    fun exerciseName(id: String): String = ExerciseDef.seedById(id)?.displayName
        ?: id.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private val PATH = Regex("""^(?:sessions\[(\d+)](?:\.exercises\[(\d+)](?:\.sets\[(\d+)])?)?)?: """)

    private class Where(val session: Int?, val exercise: Int?, val set: Int?)

    fun of(raw: String, plan: PlanFile?): PlanWarningLine {
        val match = PATH.find(raw)
        val where = Where(
            match?.groupValues?.get(1)?.toIntOrNull(),
            match?.groupValues?.get(2)?.toIntOrNull(),
            match?.groupValues?.get(3)?.toIntOrNull(),
        )
        val body = if (match != null) raw.substring(match.range.last + 1) else raw
        val place = place(where, plan)
        val (rule, text) = classify(body, place, exerciseOnly(where, plan))
        return PlanWarningLine(rule.tier, text, raw, rule)
    }

    /** "Set 3 of Leg press", "Leg press", "Day B", "The plan". */
    private fun place(where: Where, plan: PlanFile?): String {
        val exercise = exerciseOnly(where, plan)
        return when {
            where.set != null -> "Set ${where.set + 1} of $exercise"
            where.exercise != null -> exercise
            where.session != null ->
                plan?.sessions?.getOrNull(where.session)?.name ?: "Session ${where.session + 1}"
            else -> "The plan"
        }
    }

    private fun exerciseOnly(where: Where, plan: PlanFile?): String {
        val si = where.session ?: return "The plan"
        val ei = where.exercise ?: return plan?.sessions?.getOrNull(si)?.name ?: "Session ${si + 1}"
        val sessions = plan?.sessions.orEmpty()
        val session = sessions.getOrNull(si)
        val exercise = session?.exercises?.getOrNull(ei)
            ?: return "Exercise ${ei + 1} of session ${si + 1}"
        val name = exerciseName(exercise.exercise)
        // Which day it is on, only when there is more than one to confuse it with.
        return if (sessions.size > 1) "$name (${session.name})" else name
    }

    private val MISSPELT = Regex(""""([^"]+)" is not a plan key - did you mean "([^"]+)"\?""")
    private val WRONG_LEVEL = Regex(""""([^"]+)" is a plan key, but not one""")
    private val UNKNOWN = Regex("""unknown key "([^"]+)" was ignored(?: \((\d+) places in this plan\))?""")
    private val PAIR = Regex("""so this plan's (.+?) means (\d+) × (.+?) in hand""")

    /**
     * ORDER MATTERS: first match wins. The RECORDS_WRONG checks (the key
     * findings, the pair load, then start, drive and progression) must stay
     * ahead of the NOTE checks, so a sentence that happens to share a phrase
     * with a note is never demoted into the collapsed notes. Within the notes,
     * "rides the weight stack with its drive" must precede the plainer
     * "sensor rides the weight stack".
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun classify(body: String, place: String, exercise: String): Pair<PlanWarningRule, String> {
        MISSPELT.find(body)?.let { m ->
            return if ("records as bodyweight" in body) {
                PlanWarningRule.LOAD_DROPPED to "$place has no load: it would record as body weight."
            } else {
                PlanWarningRule.KEY_MISSPELT to
                    "$place: \"${m.groupValues[1]}\" is misspelt (should be \"${m.groupValues[2]}\"), so it is ignored."
            }
        }
        WRONG_LEVEL.find(body)?.let { m ->
            val key = m.groupValues[1]
            return PlanWarningRule.KEY_WRONG_LEVEL to
                if (key == "notes" && "a comment on a single set is \"note\"" in body) {
                    "$place: its note is in the wrong place, so it won't show."
                } else {
                    "$place: \"$key\" is in the wrong place, so it is ignored."
                }
        }
        UNKNOWN.find(body)?.let { m ->
            val count = m.groupValues[2].toIntOrNull()
            val more = if (count != null) " ($count places)" else ""
            return PlanWarningRule.UNKNOWN_KEY to "$place: extra field \"${m.groupValues[1]}\" ignored$more."
        }
        PAIR.find(body)?.let { m ->
            val (whole, n, each) = m.destructured
            return PlanWarningRule.PAIR_LOAD to
                "$exercise: $whole is the total for all $n ($n × $each). " +
                "If $whole is one of them, it would record too little."
        }
        return when {
            "does not declare \"start\"" in body ->
                PlanWarningRule.START_GUESSED to
                    "$exercise: start position not given, so the app guesses it. " +
                    "A wrong guess reads every rep backwards."
            "pulls DOWN, and the plan did not say" in body ->
                PlanWarningRule.DRIVE_DEFAULTED to
                    "$exercise: the plan doesn't say it pulls down, so every rep would be read off the wrong stroke."
            "\"progression\": " in body && "the set is asked the wrong question" in body ->
                PlanWarningRule.PROGRESSION_MISMATCH to
                    "$exercise: progression doesn't match its sets, " +
                    "so the rating after each set asks the wrong question."
            "but no \"implement\"" in body ->
                PlanWarningRule.IMPLEMENT_UNDECLARED to "$exercise: no equipment type, so no loading line is shown."
            "normally starts at" in body ->
                PlanWarningRule.START_OVERRIDDEN to "$exercise: the plan changes where it starts. The plan is followed."
            "the app would have guessed" in body ||
                ("is built in as" in body && "declares \"kind\"" in body) ->
                PlanWarningRule.KIND_OVERRIDDEN to
                    "$exercise: the plan sets its movement type. The plan is followed."
            "either way" in body && "is measured" in body ->
                PlanWarningRule.KIND_VS_SETS to
                    "$exercise: movement type and sets disagree. Sets are measured as written."
            "no set of it plays a prep" in body ->
                PlanWarningRule.PREP_UNUSED to "$exercise: prep time is set but never used."
            "\"prep_s\": " in body && ("before the set begins" in body || "launch phrase" in body) ->
                PlanWarningRule.PREP_SHORT to "$exercise: short prep, so the countdown is cut."
            "\"sensors\" is declared" in body ->
                PlanWarningRule.SENSORS_IGNORED to
                    "$exercise: the plan's sensor choice is ignored. Whatever is connected records."
            "rides the weight stack with its drive" in body ->
                PlanWarningRule.STACK_INVERTED to
                    "$exercise: sensor on the stack is read flipped, to match the pull."
            "sensor rides the weight stack" in body ->
                PlanWarningRule.STACK_ASSUMED to "$exercise: recorded with the sensor on the weight stack."
            "is built in as body-weight work" in body ->
                PlanWarningRule.BODYWEIGHT_ASSUMED to "$exercise: recorded as body weight plus any added load."
            "declares both \"description\" and" in body ->
                PlanWarningRule.CUE_SPLIT to "$exercise: only the description shows between sets. Notes are a tap away."
            "\"additional_notes\" is declared" in body ->
                PlanWarningRule.CUE_BEHIND_TAP to "$exercise: its notes only show after a tap."
            else -> PlanWarningRule.UNRECOGNISED to if (place == "The plan") body else "$place: $body"
        }
    }
}
