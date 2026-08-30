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
 * LIMITED the set rather than for failure, because #191 widens the same field
 * to completed sets. On this programme that widening is not hypothetical: the
 * blocks run 4011 and 3010, so on a light set the tempo is deliberately the
 * limiter. Naming it for failure today would oblige renaming a released field
 * then. The values here are failure-shaped because failure is what is asked
 * about today; the field is not.
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
         * than an intention. What is removed is the double quote, the
         * backslash, and every control character; runs of whitespace collapse
         * to one space and the result is trimmed.
         *
         * THE LIFTER SEES THE RESULT AS THEY TYPE, because the text field
         * applies this on every keystroke. A character silently dropped at
         * save is a character the lifter believes they recorded.
         *
         * Blank comes back as null, not as `""`. An empty note is no note, and
         * absence is the state the skip already writes.
         *
         * Idempotent by construction and pinned as such: it runs at the field
         * and again at the write, and a second pass must not shorten a note
         * that already fits.
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
         * quote, the backslash and every control character go, other
         * whitespace becomes a space, a run of spaces collapses to one, and
         * the cap is applied. The trim is left to the write, because a
         * trailing space is not junk at the end of a finished note -- it is a
         * word boundary the lifter is in the middle of typing.
         *
         * Returns a `String` and never null, because a field's value is a
         * string. Absence is [normalizeNote]'s answer to give, once, at the
         * write.
         */
        fun sanitizeForTyping(raw: String): String = normalizeNote(raw) ?: ""
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

    /** Which reading each answer gets. [SetLimiterGroup] carries why. */
    private val GROUPS: Map<SetLimiter, SetLimiterGroup> =
        mapOf(
            SetLimiter.MUSCLE to SetLimiterGroup.PERFORMANCE,
            SetLimiter.GRIP to SetLimiterGroup.PERFORMANCE,
            SetLimiter.FORM to SetLimiterGroup.PERFORMANCE,
            SetLimiter.PACE to SetLimiterGroup.PERFORMANCE,
            SetLimiter.SLIP to SetLimiterGroup.PERFORMANCE,
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
    fun tiles(timed: Boolean): List<SetLimiterTile> {
        val offered = SetLimiter.entries.filter { !(timed && it == SetLimiter.PACE) }
        return offered.map { limiter ->
            SetLimiterTile(
                limiter = limiter,
                label = label(limiter, timed),
                group = GROUPS.getValue(limiter),
            )
        }
    }

    /** The wording one answer reads with, for the rest-screen line. */
    fun label(limiter: SetLimiter, timed: Boolean): String =
        (if (timed) TIMED_LABELS[limiter] else null) ?: REP_LABELS.getValue(limiter)
}

/**
 * WHERE the reason page is drawn, which is not the same question as whether it
 * is drawn at all.
 *
 * The rest screen has two places it can appear and they answer to different
 * events. [PROMPT] is the page the app opened by itself, and it belongs at the
 * top of the rest screen, because the screen scrolls to 0 on entering RESTING
 * and a question below the fold is a question the lifter starts the next set
 * without seeing. [CORRECTION] is the page the lifter opened by tapping the
 * reason row, and it belongs immediately under that row -- adjacent to the
 * finger that asked for it.
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

    /** Drawn under the reason row, where the lifter tapped to open it. */
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
     * It opens on a FAILED set that carries no answer yet and whose lifter has
     * not already dismissed it. All three conditions matter:
     *
     * - `failed` is the EFFECTIVE verdict, the lifter's own tap OR-ed with the
     *   derived shortfall. The derived case is the one that most needs asking:
     *   a set ended with END SET EARLY records no tap at all, and "stopped for
     *   an outside reason" is exactly what makes that record honest.
     * - a set that already carries an answer is not asked again, or a
     *   correction would be undone by the next recomposition.
     * - [dismissed] is the skip. Skipping must leave absence standing and must
     *   not re-ask; the page is one tap to leave and the lifter is standing
     *   over a bar.
     *
     * The row itself stays reachable afterwards either way -- see
     * [offersCorrection] -- so a skip is not a door that locks.
     */
    fun prompts(failed: Boolean, limiter: SetLimiter?, dismissed: Boolean): Boolean =
        failed && limiter == null && !dismissed

    /**
     * Whether the rest screen offers the reason row at all for the set just
     * stored.
     *
     * A failed set, or any set already carrying an answer. The second half is
     * not redundant: a lifter who answers and then wants to change it must
     * still reach the row, and #191 will make an answer reachable on sets that
     * never failed.
     */
    fun offersCorrection(failed: Boolean, limiter: SetLimiter?): Boolean = failed || limiter != null

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
    fun lineText(limiter: SetLimiter?, note: String?, timed: Boolean): String {
        if (limiter == null) return NOT_GIVEN
        val normalized = SetLimiter.normalizeNote(note)
        return if (limiter == SetLimiter.OTHER && normalized != null) {
            normalized
        } else {
            SetLimiterScale.label(limiter, timed)
        }
    }

    /**
     * Where the reason page is drawn for the set just stored, if anywhere.
     *
     * [changing] is the lifter's own tap on the reason row and it WINS over an
     * automatic offer, for two reasons that point the same way: the page they
     * asked for must appear where they asked for it, and a page drawn in both
     * places at once is two pages.
     *
     * Lifted out of the rest screen rather than written as an `if` beside the
     * two call sites, which is the whole point: the placement is the defect
     * this function exists because of, and `:app` has no composable test, so a
     * placement rule written there is a rule nothing can fail.
     */
    fun placement(
        failed: Boolean,
        limiter: SetLimiter?,
        dismissed: Boolean,
        changing: Boolean,
    ): SetLimiterPagePlacement = if (changing || prompts(failed, limiter, dismissed)) {
        SetLimiterPagePlacement.CORRECTION
    } else {
        SetLimiterPagePlacement.NONE
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

    /** The line's wording for a set carrying no reason at all. */
    const val NOT_GIVEN = "Not given"
}
