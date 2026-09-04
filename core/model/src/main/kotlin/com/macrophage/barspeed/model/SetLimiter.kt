package com.macrophage.barspeed.model

/**
 * Why a set ended, in the lifter's answer rather than the app's derivation
 * (#189).
 *
 * ## A closed vocabulary, because grouping is the whole point
 *
 * Every failed set exports identically today -- `failed: true`, `rpe: null` --
 * so two sets that ended for opposite reasons are indistinguishable, and the
 * difference is the coaching decision. Each value below earns its place by
 * changing what a coach does next; that is the test for adding one, not
 * whether it is a distinct English sentence.
 *
 * The free-text answer lives in a SEPARATE field beside this one and is never
 * a value inside it. A free string stored in this column would destroy the
 * grouping this exists for, which is the only reason it exists.
 *
 * ## Absence is a state
 *
 * There is no `UNKNOWN` member and there must not be one. A set nobody was
 * asked about, a set whose lifter skipped the question and a set recorded
 * before the column existed are all "no answer", and that is a null column,
 * not a rung of this enum. A member standing for absence would be counted as
 * an answer by exactly the grouping the enum is for.
 *
 * ## The name is not `FailureReason`
 *
 * The column, the enum and the export key are all named for the thing that
 * LIMITED the set rather than for failure, because #191 has widened the same
 * field to completed sets: a set rated at the counted end is asked what
 * limited it whether or not it failed. On this programme that widening is not
 * hypothetical: the blocks run 4011 and 3010, so on a light set the tempo is
 * deliberately the limiter. The MEMBERS are still failure-shaped, because
 * failure is what they were authored for; [SetLimiterScale] rewords the four
 * that would otherwise claim something a finished set did not do.
 */
enum class SetLimiter {
    /** The target muscle reached its limit. The load is at the edge. */
    MUSCLE,

    /** A different limiter entirely: the back had more, the hands did not. */
    GRIP,

    /** Could have continued and should not have. The opposite read from [MUSCLE]. */
    FORM,

    /** The prescribed tempo got away, or fatigue arrived early. */
    PACE,

    /** Technical or equipment, not capacity. */
    SLIP,

    /**
     * The set was set up wrong before it was ever a test of the muscle: a bad
     * body position, a seat or a pin at the wrong height, the wrong grip
     * taken (#146).
     *
     * Its own rung and not a shade of [FORM] or [SLIP], because the three
     * carry three different prescriptions. [FORM] is form degrading UNDER
     * load, which reads as reduce the load or the volume; [SLIP] is something
     * going wrong mid-set; this is an error the lifter made before the first
     * rep and corrects next session at the SAME load. Folded into either of
     * those, a positioning error reads as a capacity ceiling that is not
     * there, which is the whole of what #146 reports.
     */
    SETUP,

    /** Never a progression signal, and the one a coach most needs to see. */
    PAIN,

    /** Interrupted. Not a training signal at all -- analysis should discard the set. */
    OUTSIDE,

    /** The rest, in the lifter's own words, carried in the note beside this. */
    OTHER,

    ;

    /** The value stored in the column and published in the export. */
    val stored: String get() = name.lowercase()

    companion object {
        /**
         * The stored form back to a member, or null for anything unrecognised.
         *
         * Null rather than an exception on purpose. The column is TEXT and a
         * row written by a LATER build can carry a value this one has never
         * heard of; a reader that throws there fails while the lifter is
         * mid-session, over a set that is already safely recorded.
         */
        fun ofStored(stored: String?): SetLimiter? = entries.firstOrNull { it.stored == stored }

        /**
         * The longest free-text note that may be stored, in characters.
         *
         * Authored, not measured: it is a phone keyboard with a bar just
         * dropped, and 120 characters is about a sentence. Stated here because
         * a cap nobody wrote down is a cap that gets discovered by a truncated
         * export.
         */
        const val NOTE_MAX_CHARS = 120

        /**
         * The note as it will be STORED, which is also exactly what will be
         * published.
         *
         * ## Why anything is removed at all
         *
         * This is the first free text this app has ever put through the raw
         * archive's set manifest, and that manifest is assembled as text
         * rather than serialised: its string writer maps `"` to `'` and
         * escapes nothing else. So a backslash or a newline arriving in a note
         * does not corrupt the note -- it makes the WHOLE manifest
         * unparseable, for every set in the session. Every other value that
         * writer has ever seen is a machine-generated token: an exercise id,
         * an enum name, a tempo notation. That is checked, not assumed.
         *
         * So the note is reduced here to characters that survive both writers
         * byte for byte -- the serialised session document and the hand-built
         * manifest -- which is what makes "exported verbatim" a fact rather
         * than an intention. What is removed is the double quote and the
         * backslash; every whitespace or control character becomes a space,
         * runs of spaces collapse to one, and the result is trimmed.
         *
         * THE LIFTER SEES MOST OF THIS AS THEY TYPE, but not through this
         * function. [sanitizeForTyping] is what the field applies, because
         * a rule that is safe on a finished note is not safe on a PREFIX of
         * one -- and a value-driven field applies its rule to every prefix
         * in turn. What is left to this function is the ENDS, trimmed once,
         * here, at the write. A character silently dropped at save is a
         * character the lifter believes they recorded, and the split is
         * what keeps that count at zero for everything but the leading and
         * trailing space they cannot have meant.
         *
         * Blank comes back as null, not as `""`. An empty note is no note, and
         * absence is the state the skip already writes.
         *
         * Idempotent by construction and pinned as such: it runs at the write
         * and again at the publish boundary, over a note it may not have
         * written itself, and a second pass must not shorten a note that
         * already fits.
         */
        fun normalizeNote(raw: String?): String? {
            if (raw == null) return null
            val stripped =
                buildString(raw.length) {
                    for (ch in raw) {
                        when {
                            ch == '"' || ch == '\\' -> Unit
                            ch.isWhitespace() || ch.isISOControl() -> append(' ')
                            else -> append(ch)
                        }
                    }
                }
            val collapsed = stripped.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            val capped = collapsed.take(NOTE_MAX_CHARS).trim()
            return capped.ifEmpty { null }
        }

        /**
         * The note as the FIELD may hold it while the lifter is still typing.
         *
         * A separate transform from [normalizeNote] because the two run at
         * different moments, and only one of them is looking at a finished
         * note. A value-driven text field re-applies its transform to the
         * WHOLE accumulated value on every keystroke, so a rule that is not
         * safe on a PREFIX of the intended note deletes the lifter's
         * keystrokes as they make them.
         *
         * What it does is what [normalizeNote] does MINUS the ends: the double
         * quote and the backslash go, every other whitespace or control
         * character becomes a space, a run of spaces collapses to one, and the
         * cap is applied. The trim is left to the write, because a
         * trailing space is not junk at the end of a finished note -- it is a
         * word boundary the lifter is in the middle of typing.
         *
         * Returns a `String` and never null, because a field's value is a
         * string. Absence is [normalizeNote]'s answer to give, once, at the
         * write.
         */
        fun sanitizeForTyping(raw: String): String {
            val stripped =
                buildString(raw.length) {
                    for (ch in raw) {
                        when {
                            ch == '"' || ch == '\\' -> Unit
                            ch.isWhitespace() || ch.isISOControl() -> append(' ')
                            else -> append(ch)
                        }
                    }
                }
            // A run of spaces collapses, a single trailing space does not: it
            // is the word boundary the lifter is standing on.
            val collapsed =
                buildString(stripped.length) {
                    for (ch in stripped) {
                        if (ch == ' ' && endsWith(' ')) continue
                        append(ch)
                    }
                }
            return collapsed.take(NOTE_MAX_CHARS)
        }
    }
}

/**
 * How a limiter reads to a coach, and therefore how the page groups it.
 *
 * [WELFARE] exists because #189 requires it in as many words: pain must be
 * visually separated from the performance reasons, so that a coach scanning an
 * export or a lifter scanning the page does not have to read carefully to
 * notice it. The grouping is a fact about the answer, so it lives here beside
 * the answer rather than as a colour rule inside a Compose file that nothing
 * can test.
 */
enum class SetLimiterGroup {
    /** The set ended because of what the body or the bar did. */
    PERFORMANCE,

    /** Pain, or something felt wrong. Never a progression signal. */
    WELFARE,

    /** Nothing to do with training: the set was interrupted. */
    CONTEXT,

    /** No listed answer fits; the words are the lifter's own. */
    FREE,
}

/** One tile of the reason page: what it stores, how it reads, and how it groups. */
data class SetLimiterTile(
    val limiter: SetLimiter,
    /** Gym-facing wording. Authored, never computed. */
    val label: String,
    val group: SetLimiterGroup,
)

/**
 * The reason tiles, in order, for a set of a given kind.
 *
 * Here rather than in `:app` for [EffortScale]'s reason: a page written inside
 * a composable is a page nothing in this repository can measure.
 *
 * ## The timed branch follows the precedent that already exists
 *
 * `EffortScale.tiles` branches on `timed`, so this does too rather than
 * inventing a second pattern. A hold or a carry drops [SetLimiter.PACE]
 * outright -- there is no pace to lose in a plank -- and rewords the two
 * answers whose noun changes: a hold is not "muscle failure", it is not being
 * able to hold it, and what breaks is position rather than form.
 *
 * IT DOES NOT BRANCH ON `explosive`, and that is a decision rather than an
 * omission. `EffortScale` has three ladders because #187 settled three sets of
 * wording with the owner; #189 settled two, the rep list and the hold list,
 * and nothing here has been read in a gym. A third ladder would be wording
 * this loop authored for a case nobody asked about. It is carried as a field
 * question instead.
 */
object SetLimiterScale {
    private val REP_LABELS: Map<SetLimiter, String> =
        mapOf(
            SetLimiter.MUSCLE to "Muscle failure",
            SetLimiter.GRIP to "Grip gave out",
            SetLimiter.FORM to "Form broke down",
            SetLimiter.PACE to "Lost the pace",
            SetLimiter.SLIP to "Slipped",
            SetLimiter.SETUP to "Bad setup or position",
            SetLimiter.PAIN to "Pain, or something felt wrong",
            SetLimiter.OUTSIDE to "Stopped for an outside reason",
            SetLimiter.OTHER to "Other",
        )

    /**
     * The hold and carry wording, for the members whose noun changes.
     *
     * Anything absent from this map keeps its rep wording, which is what makes
     * a reworded answer a visible diff rather than a silent divergence between
     * two full tables.
     */
    private val TIMED_LABELS: Map<SetLimiter, String> =
        mapOf(
            SetLimiter.MUSCLE to "Could not hold it any longer",
            SetLimiter.FORM to "Position broke down",
        )

    /**
     * The wording for a set that was COMPLETED rather than failed (#191).
     *
     * Highest precedence of the three tables, and read for both a rep set and
     * a hold, which is why [SetLimiter.FORM] names form OR position here: one
     * override that reads correctly on both beats a fourth table indexed by
     * two booleans.
     *
     * Only the four answers whose noun is plainly false on a finished set are
     * overridden. "Muscle failure" on a set the lifter completed is a claim
     * about something that did not happen, and the same holds for a grip that
     * did not give out and a pace that was not lost -- but "Slipped", "Bad
     * setup or position", "Pain, or something felt wrong" and "Stopped for an
     * outside reason" all read unchanged on a set that finished anyway, so
     * they are absent from this map and keep their wording. Anything absent
     * keeping its wording is what makes a reworded answer a visible diff
     * rather than a silent divergence between two full tables.
     *
     * AUTHORED, NOT MEASURED. No lifter has read these four strings in a gym.
     * They are carried as a field item, not as a settled table.
     */
    private val COMPLETED_LABELS: Map<SetLimiter, String> =
        mapOf(
            SetLimiter.MUSCLE to "The muscle was the limit",
            SetLimiter.GRIP to "Grip was the limit",
            SetLimiter.FORM to "Form or position was going",
            SetLimiter.PACE to "Holding the tempo",
        )

    // [SetLimiter.SETUP] is deliberately NOT in that map. A hold is set up in
    // a position too -- a hand position on a dead hang, a bench at the wrong
    // height -- so the answer applies unchanged, and the rep wording already
    // reads correctly for one. It is also what keeps it distinguishable from
    // the reworded [SetLimiter.FORM] tile beside it: "Bad setup or position"
    // is where the set STARTED, "Position broke down" is what happened to it.

    /** Which reading each answer gets. [SetLimiterGroup] carries why. */
    private val GROUPS: Map<SetLimiter, SetLimiterGroup> =
        mapOf(
            SetLimiter.MUSCLE to SetLimiterGroup.PERFORMANCE,
            SetLimiter.GRIP to SetLimiterGroup.PERFORMANCE,
            SetLimiter.FORM to SetLimiterGroup.PERFORMANCE,
            SetLimiter.PACE to SetLimiterGroup.PERFORMANCE,
            SetLimiter.SLIP to SetLimiterGroup.PERFORMANCE,
            SetLimiter.SETUP to SetLimiterGroup.PERFORMANCE,
            SetLimiter.PAIN to SetLimiterGroup.WELFARE,
            SetLimiter.OUTSIDE to SetLimiterGroup.CONTEXT,
            SetLimiter.OTHER to SetLimiterGroup.FREE,
        )

    /**
     * The tiles for one set, performance answers first, then pain, then the
     * outside reason, then Other.
     *
     * PAIN SITS AFTER THE PERFORMANCE ANSWERS AND BEFORE THE REST, so the
     * group boundary the page draws around it is a boundary in this list and
     * not an index the drawing code counts to. Order is named here, never
     * counted to at the call site.
     */
    fun tiles(timed: Boolean, failed: Boolean): List<SetLimiterTile> {
        val offered = SetLimiter.entries.filter { !(timed && it == SetLimiter.PACE) }
        return offered.map { limiter ->
            SetLimiterTile(
                limiter = limiter,
                label = label(limiter, timed, failed),
                group = GROUPS.getValue(limiter),
            )
        }
    }

    /**
     * The wording one answer reads with, for the rest-screen line.
     *
     * Three tables, in precedence order: the completed-set override, then the
     * hold rewording, then the rep table which is the only complete one. A
     * completed HOLD therefore reads the completed override where one exists
     * and the hold wording where it does not, which is the ordering that lets
     * [COMPLETED_LABELS] hold four entries instead of eight.
     */
    fun label(limiter: SetLimiter, timed: Boolean, failed: Boolean): String =
        (if (!failed) COMPLETED_LABELS[limiter] else null)
            ?: (if (timed) TIMED_LABELS[limiter] else null)
            ?: REP_LABELS.getValue(limiter)
}

/**
 * WHERE the reason page is drawn, which is not the same question as whether it
 * is drawn at all.
 *
 * [PROMPT] is the page the app opened by itself, and it belongs at the top of
 * the rest screen, because the screen scrolls to 0 on entering RESTING and a
 * question below the fold is a question the lifter starts the next set without
 * seeing.
 *
 * [CORRECTION] WAS the page the lifter opened by tapping the reason row, drawn
 * immediately under that row. #237 deleted the row, and `RestingStage`, the
 * only `:app` caller of [SetLimiterPolicy.placement], passes `changing =
 * false`. The member is left standing and SetLimiterPolicyTest still pins it.
 *
 * An enum rather than two booleans, so that "both at once" is not a state the
 * caller can construct. Two copies of the page on one screen is the failure
 * this shape removes rather than guards against.
 */
enum class SetLimiterPagePlacement {
    /** Not drawn. */
    NONE,

    /** Drawn high, where a lifter entering the rest period is already looking. */
    PROMPT,

    /** Drawn under the reason row. No caller has returned it since #237. */
    CORRECTION,
}

/**
 * When the reason page is offered, where it is drawn, and what the rest screen
 * says once it has been answered or skipped.
 *
 * In `:core:model` for the reason [EffortCorrectionPolicy] is: the rest screen
 * is a Compose file with no reachable test seam, so a rule written
 * beside its caller is a rule nothing enforces.
 */
object SetLimiterPolicy {
    /**
     * Whether the page opens by itself for the set that has just been stored.
     *
     * It opens on a set that WAS LIMITED BY SOMETHING -- a failure, or a
     * completed set the lifter rated at the counted end -- and that carries no
     * answer yet, and whose lifter has not already dismissed it. Every
     * condition matters:
     *
     * - `failed` is the EFFECTIVE verdict, the lifter's own tap OR-ed with the
     *   derived shortfall. The derived case is the one that most needs asking:
     *   a set ended with END SET EARLY records no tap at all, and "stopped for
     *   an outside reason" is exactly what makes that record honest. A failed
     *   set is asked whatever it is rated, INCLUDING an unrated one: the
     *   failure tile stores no rpe at all.
     * - [ratedNearFailure] is the widening (#191). A completed set rated 7
     *   through 10 was a test of something and only the lifter knows what; a
     *   set rated in the headroom rungs was not, and asking is a tap that says
     *   what a reader could already assume.
     * - a set that already carries an answer is not asked again, or a
     *   correction would be undone by the next recomposition.
     * - [dismissed] is the skip. Skipping must leave absence standing and must
     *   not re-ask; the page is one tap to leave and the lifter is standing
     *   over a bar.
     *
     * The correction stays reachable afterwards either way -- see
     * [offersCorrection] -- so a skip is not a door that locks.
     */
    fun prompts(failed: Boolean, rpe: Int?, limiter: SetLimiter?, dismissed: Boolean): Boolean =
        (failed || ratedNearFailure(rpe)) && limiter == null && !dismissed

    /**
     * The rating a COMPLETED set has to carry before it is asked at all
     * (#191).
     *
     * The counted rungs, 7 through 10, read off [EffortScale] rather than
     * restated here so the rungs #187 settled and the rungs this asks about
     * cannot drift apart. Everything else is false, and each for its own
     * reason: a headroom rung's whole content is that there was room left, so
     * nothing limited the set; 2, 3 and 5 are valid values with no tile, never
     * offered, so the app has no reading to act on; and a null is an unrated
     * set, with no rung to decide on.
     *
     * ONE HALF OF THE ANSWER, NEVER THE WHOLE OF IT. A failed set is asked
     * whatever it is rated -- see [prompts] -- because the failure tile stores
     * no rpe, so a rule that consulted only the rating would stop asking the
     * one set that has been asked since #189.
     */
    fun ratedNearFailure(rpe: Int?): Boolean = rpe != null && rpe >= EffortScale.PROXIMITY_FLOOR_RPE

    /**
     * Whether the rest screen offers a reason correction at all for the set
     * just stored.
     *
     * Any set that would be asked -- a failure, or a completed set rated at
     * the counted end -- or any set already carrying an answer whatever it was
     * rated. The last clause is not redundant twice over: a lifter who answers
     * and then wants to change it must still reach it, and a set whose
     * rating is corrected downward after an answer was given must not have the
     * answer become unreachable.
     */
    fun offersCorrection(failed: Boolean, rpe: Int?, limiter: SetLimiter?): Boolean =
        failed || ratedNearFailure(rpe) || limiter != null

    /**
     * The whole caption over the page of tiles.
     *
     * Here rather than in the composable because the two cases ask different
     * questions and one of them is new: "Why did that set end?" over a set
     * the lifter finished is a question about something that did not happen.
     *
     * The `optional` half is part of the string rather than appended at the
     * call site, so what the lifter reads is pinned end to end. It says
     * optional in both cases and must go on doing so: a completed set is
     * asked once, and an unanswered one is a set nobody was asked about
     * rather than a gap in the record.
     */
    fun pageTitle(failed: Boolean): String =
        if (failed) "Why did that set end? · optional" else "What limited that set? · optional"

    /** What the rest-screen reason line is labelled, before the answer. */
    fun lineLabel(failed: Boolean): String = if (failed) "Ended" else "Limited by"

    /**
     * The wording on the button that opened the page from the reason row.
     * No `:app` caller since #237.
     *
     * "Say why" is the failure wording and stays exactly as it shipped. A
     * completed set is not being asked why it ended, so it reads "Answer";
     * either, once answered, reads "Change".
     */
    fun lineAction(failed: Boolean, limiter: SetLimiter?): String = when {
        limiter != null -> "Change"
        failed -> "Say why"
        else -> "Answer"
    }

    /**
     * What the rest-screen reason line reads.
     *
     * The unanswered case is a NAMED absence and not a blank, for the reason
     * `EffortCorrectionPolicy.NOT_RATED` is one: a gap reads as the app having
     * lost the answer.
     *
     * An `other` answer reads as the lifter's own words where there are any,
     * because the enum member's own label says nothing a coach can use. Where
     * there are none -- Other tapped, note left empty -- it falls back to the
     * tile's wording rather than printing an empty quotation.
     */
    fun lineText(limiter: SetLimiter?, note: String?, timed: Boolean, failed: Boolean): String {
        if (limiter == null) return NOT_GIVEN
        val normalized = SetLimiter.normalizeNote(note)
        return if (limiter == SetLimiter.OTHER && normalized != null) {
            normalized
        } else {
            SetLimiterScale.label(limiter, timed, failed)
        }
    }

    /**
     * Where the reason page is drawn for the set just stored, if anywhere.
     *
     * [changing] WAS the lifter's own tap on the reason row and it WINS over
     * an automatic offer, for two reasons that point the same way: the page
     * they asked for must appear where they asked for it, and a page drawn in
     * both places at once is two pages. The only `:app` caller passes it false
     * since #237.
     *
     * Lifted out of the rest screen rather than written as an `if` beside the
     * two call sites, which is the whole point: the placement is the defect
     * this function exists because of, and `:app` has no composable test, so a
     * placement rule written there is a rule nothing can fail.
     */
    fun placement(
        failed: Boolean,
        rpe: Int?,
        limiter: SetLimiter?,
        dismissed: Boolean,
        changing: Boolean,
    ): SetLimiterPagePlacement = when {
        changing -> SetLimiterPagePlacement.CORRECTION
        prompts(failed, rpe, limiter, dismissed) -> SetLimiterPagePlacement.PROMPT
        else -> SetLimiterPagePlacement.NONE
    }

    /**
     * Whether leaving the page is a SKIP -- true only where no answer stands.
     *
     * Skipping an answered set records nothing and clears nothing, so a foot
     * captioned "records no reason" over a stored answer describes an action
     * the app does not perform: the answer stays in the row and stays in the
     * export. Where an answer stands the way out is a CLEAR, which is the
     * caller `SessionRepository.setLimiter`'s null case has always been
     * documented as having.
     */
    fun leavesPageAsSkip(limiter: SetLimiter?): Boolean = limiter == null

    /**
     * Whether confirming a correction RETRACTS the answer that stood.
     *
     * True only where an answer stood and the draft carries none. A retraction
     * is two writes and not one: the stored answer goes back to null, and the
     * rest screen has to be told the page was already offered. At bc0661c8 the
     * CLEAR foot ran `limitLastSet(null)` AND `onSkip()`, and [prompts] is true
     * again for a failed set once [limiter] is null and `dismissed` is false --
     * so the null written alone re-opens the page the retraction just cleared.
     *
     * Here rather than as an `if` at the confirm, for the reason every other
     * rule on that screen was lifted out for: `:app` has no reachable test
     * seam for a composable.
     */
    fun retractsStoredReason(stored: SetLimiter?, draft: SetLimiter?): Boolean = stored != null && draft == null

    /** The line's wording for a set carrying no reason at all. */
    const val NOT_GIVEN = "Not given"
}
