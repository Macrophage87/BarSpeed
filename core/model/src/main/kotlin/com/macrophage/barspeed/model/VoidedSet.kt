package com.macrophage.barspeed.model

/**
 * Where a set sits when the lifter asks to take it back.
 *
 * The two are different objects, not two states of one, and that is the whole
 * of why [VoidSetPolicy] and [RemoveSetControl] are separate decisions.
 * [QUEUED] is a plan for a set that has not happened: no row exists, nothing
 * was measured, and discarding it loses nothing. [RECORDED] is a row in
 * `set_records` with its analysis, its gzipped IMU, heart-rate and cue streams
 * and its place in the export.
 */
enum class SetPlace {
    /** A slot in the running queue. Nothing has been written for it. */
    QUEUED,

    /** A row in `set_records`, with everything that was captured for it. */
    RECORDED,
}

/**
 * What is true of a set once it carries the void mark, enumerated so that no
 * reader has to remember the list.
 *
 * A data class rather than five call sites each testing the same boolean: the
 * near-neighbour failure this repository keeps producing is a flag wired into
 * the first consumer and not the last, and the only defence that scales is to
 * make the consumers read one object. Every field is asked, never assumed.
 *
 * WHAT IS NOT ON THIS LIST, DECIDED RATHER THAN OVERLOOKED: a SESSION. The
 * week's session count on the home screen is unguarded on purpose, so a
 * session every set of which is voided still counts as one session. Voiding
 * says a SET did not happen; it does not say the lifter was never there, and
 * a session exists because it was started. The figures that describe WORK --
 * tonnage, set counts, the per-set sparkline and any progression read -- are
 * the ones the mark removes work from, and each of them asks a field above.
 * There is no `countsAsASession` field because nothing should read one; if a
 * later round decides a fully-voided session should stop counting, it is a
 * new field here and not a fifth boolean at the call site.
 *
 * Nor a session's heart rate. `endSession` freezes `hrAvgBpm` and `hrMaxBpm`
 * over every set row and voiding does not re-derive them, so both go on
 * including a voided set's beats -- for the reason above -- and the export's
 * `heartRate` block and the session detail header publish them unchanged.
 * That is the SECOND exclusion, and it was unnamed until round 5 named it:
 * the list above claimed one exclusion and had two.
 */
data class VoidedSetEffects(
    /**
     * The row stays where it is, in the session and in its exercise's set
     * list, at its own `orderIdx`. Always true, for both values of the mark.
     *
     * Voiding is not deletion and this field is what says so. Deleting the row
     * would take the raw streams with it -- the only copy of what the sensors
     * saw -- and would renumber nothing, so the set the lifter DID perform
     * after it would move up and stop matching the plan.
     */
    val staysInHistory: Boolean,
    /**
     * Whether `loadKg * reps` for this set is added to a volume figure.
     *
     * The week's tonnage on the home screen is the read this exists for. A
     * fabricated set contributes 0 kg there only when its load is 0; a voided
     * set that carried load would otherwise inflate the figure by work nobody
     * did.
     */
    val countsTowardVolume: Boolean,
    /**
     * Whether this set is one of the sets a progression read sees -- the set
     * count on a history row, the per-set velocity sparkline, and any later
     * read that asks what was done.
     *
     * Separate from [countsTowardVolume] because a set can carry no load and
     * still corrupt a count. Field-37 set 13 is exactly that: 0 kg, so it
     * moves no tonnage, while making a 12-set session read as 13.
     */
    val countsAsPerformed: Boolean,
    /**
     * Whether the gzipped IMU, heart-rate and cue streams stay in the archive.
     * Always true, for both values of the mark.
     *
     * Unrecoverable beats tidy. A voided set's samples are the evidence for
     * why it was voided, and nothing else in this app can reconstruct them.
     */
    val keepsRawStreams: Boolean,
    /**
     * Whether the set appears in the session document and the raw archive's
     * manifest at all. Always true, for both values of the mark.
     *
     * A voided set is published WITH its mark rather than withheld, so a
     * reader can see that the row was there and was not performed. Dropping it
     * would make the export disagree with the app's own history, and would
     * make a voided set indistinguishable from a set that was never recorded
     * -- which is a gap the document cannot represent.
     */
    val publishedInExport: Boolean,
)

/**
 * Voiding a recorded set: marking a row as one the lifter did not perform,
 * without destroying it (#60).
 *
 * THE CASE THIS IS FOR is field-37 set 13. On 2026-09-02, app 0.1.48, the plan
 * held two dead hangs and the app re-armed the finished slot (#195, fixed in
 * v0.1.49), writing a thirteenth set nobody performed: a 0-second failed hang
 * carrying a copy of the twelfth set's prescription. Nothing in the app could
 * remove or mark it. The only write that could take it out was
 * `DELETE FROM sessions`, which takes the whole session and every gzipped
 * stream in it, so the lifter's choices were a permanent false row or the loss
 * of the twelve real sets recorded beside it.
 *
 * THE DIFFERENCE FROM [RemoveSetControl], stated here because the two controls
 * sit one screen apart and answer the same-sounding question. That one removes
 * a QUEUED appended set: a plan for a set that has not happened, discarded
 * before anything is written, and its own KDoc draws the boundary in as many
 * words -- *"Removing a RECORDED set would delete a row of training history
 * with its samples, its raw stream and its export entry. Only the first is
 * offered here."* This one is what the other half needs, and it is not a
 * removal at all: nothing is deleted, and that is what makes it available on a
 * row.
 *
 * WHICH SETS ARE VOIDABLE: every RECORDED set, INCLUDING a prescribed one.
 * [RemoveSetControl] refuses a prescribed set deliberately, because a plan's
 * set count is how a coach reads adherence and dropping prescribed sets would
 * corrupt exactly that reading. The opposite is true here, and the case is the
 * proof: field-37 set 13 IS a prescribed set -- the app copied a prescription
 * onto it -- and it is the row this exists for. A prescribed set the lifter
 * never performed is a hole in adherence whichever way it is stored, and the
 * choice is only whether the document says so. Refusing to mark it would leave
 * the count right and every figure under it wrong.
 *
 * IT IS REVERSIBLE, and that is a data-safety property rather than a
 * convenience. Voiding the wrong set must cost a tap to undo, or the control
 * becomes one more irreversible action on a screen whose only other
 * irreversible action already destroys a session.
 *
 * Nothing here touches Android, Room or a sensor.
 */
object VoidSetPolicy {
    /**
     * Whether the lifter may void this set.
     *
     * A recorded row and nothing else. The surface is the session detail
     * screen, which is reached from history, so in practice the session has
     * ended -- but the eligibility is a property of the ROW and is
     * deliberately not gated on the session having ended. A session the app
     * died in the middle of never gets an `endedAtMs`, and gating on it would
     * make exactly those sessions -- the ones most likely to hold a set that
     * did not happen -- the ones that can never be marked.
     */
    fun voidable(place: SetPlace): Boolean = place == SetPlace.RECORDED

    /**
     * What the mark means, for both of its values.
     *
     * The three fields that do not move are stated for the unvoided set too,
     * rather than left implicit, so that a reader comparing the two answers
     * sees that voiding changes what the set COUNTS TOWARD and nothing about
     * what is kept or published.
     */
    fun effects(voided: Boolean): VoidedSetEffects = VoidedSetEffects(
        staysInHistory = true,
        countsTowardVolume = !voided,
        countsAsPerformed = !voided,
        keepsRawStreams = true,
        publishedInExport = true,
    )

    /**
     * The sets an aggregate read may count, out of the sets a session holds.
     *
     * Generic over the caller's own row type and reading [effects] rather than
     * the boolean, so the volume figure, the set count and the velocity
     * sparkline apply ONE rule. Order is preserved; nothing is sorted.
     */
    fun <T> performed(sets: List<T>, voided: (T) -> Boolean): List<T> =
        sets.filter { effects(voided(it)).countsAsPerformed }

    /**
     * Volume in kilograms over a session's sets, with the voided ones out.
     *
     * `loadKg * reps` is the arithmetic the home screen's week tonnage already
     * uses, moved here so the exclusion cannot be applied in one of the two
     * places that iterate the same list. It is stated in kg because the row
     * stores kg; the pounds conversion is a display decision and stays at the
     * screen.
     *
     * IT READS [VoidedSetEffects.countsTowardVolume] AND NOT [performed].
     * Writing it as `performed(sets) { it.voided }.sumOf { … }` compiles, is
     * shorter, gives the same answers today, and is what this function said
     * until a mutation run caught it: flipping `countsTowardVolume` to a
     * constant `true` left every volume test green, because nothing read that
     * field. The two questions are declared apart in [VoidedSetEffects] and
     * have to be ASKED apart, or one of them is decoration that reads as a
     * rule.
     */
    fun volumeKg(sets: List<VolumeSet>): Double =
        sets.filter { effects(it.voided).countsTowardVolume }.sumOf { it.loadKg * it.reps }

    /**
     * The reason, as it is stored and published, or null.
     *
     * [SetLimiter.normalizeNote] and not a second spelling of it. The reason
     * reaches both export writers, and one of them -- the raw archive's
     * manifest -- is assembled as TEXT whose string writer escapes nothing, so
     * a backslash or a newline in this string does not corrupt the reason, it
     * makes the whole manifest unparseable for every set in the session. That
     * rule is already written down once, for the limiter note, against the
     * same two writers; delegating is what keeps it from being written down
     * twice and drifting.
     *
     * Blank comes back as null. A voided set with no reason is the ordinary
     * case -- the reason is optional -- and an empty string in the column
     * would publish a key saying nothing.
     */
    fun reason(raw: String?): String? = SetLimiter.normalizeNote(raw)

    /**
     * The reason as the text field may hold it mid-keystroke, for
     * [SetLimiter.sanitizeForTyping]'s reason: a rule that is safe on a
     * finished note deletes keystrokes when applied to every prefix of one.
     */
    fun reasonAsTyped(raw: String): String = SetLimiter.sanitizeForTyping(raw)

    /**
     * What the control on the set card says.
     *
     * It opens on the fact rather than on the operation, as
     * [RemoveSetControl.label] does: the lifter is answering a question about
     * what happened, not choosing a verb. The unvoid wording names the mark it
     * takes off, so the two are not read as the same button toggling an
     * unnamed state.
     */
    fun label(voided: Boolean): String = if (voided) {
        "Did perform this set"
    } else {
        "Didn't perform this set?"
    }

    /**
     * The chip drawn on a voided set's card, and the word the archive is
     * described with.
     *
     * "NOT PERFORMED" and not "VOID": the lifter reads the card, and the
     * question they are answering is whether the set happened. VOID is the
     * name of the mark, which is what the column and the export key are called.
     */
    const val CHIP = "NOT PERFORMED"

    /**
     * What the confirmation says before the mark goes on.
     *
     * It states the two things a lifter cannot see from the card: that nothing
     * is deleted, and what the mark actually changes. A control that says only
     * "are you sure" on a screen whose other destructive action deletes the
     * session invites reading this one as the same kind of act.
     *
     * [setNumber] is the set's position in the SESSION, and the sentence says
     * so rather than pairing it with the exercise. The only caller,
     * `SessionDetailScreen`'s `VoidRow`, passes `record.orderIdx + 1`, and
     * `orderIdx` is written from `RecordUiState.setsCompleted` -- one counter
     * per session, never reset when the exercise changes. Pairing that number
     * with the exercise name produced "Set 13 of Rope Dead Hang" for a set
     * whose exercise has no thirteenth set, which is a worse thing for a
     * confirmation to say than saying nothing: the lifter is being asked to
     * identify the set they are about to mark. Do not "fix" this back to
     * `Set N of <exercise>` without giving the caller a per-exercise counter
     * first -- there is not one.
     */
    fun confirmation(exerciseName: String, setNumber: Int): String =
        "Mark this $exerciseName set -- set $setNumber of the session -- as not " +
            "performed. The set stays in this session with its sensor data and is " +
            "still exported, marked; it stops counting toward volume and progression. " +
            "You can undo this."
}

/**
 * One set as a volume read sees it: what it was loaded with, how many reps it
 * counted, and whether it was performed.
 *
 * A projection carried into [VoidSetPolicy.volumeKg] rather than the row
 * itself, because `:core:model` cannot see a Room entity and the arithmetic
 * has to be pinnable on the CI path.
 */
data class VolumeSet(val loadKg: Double, val reps: Int, val voided: Boolean)
