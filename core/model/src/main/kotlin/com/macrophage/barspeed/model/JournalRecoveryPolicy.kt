package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/**
 * What a set journal must know, from the moment its set begins, for a capture
 * the process did not live to store to be written back into History as a set
 * row (#100).
 *
 * Every field is a fact that exists only at recording time and that no stream
 * in the journal can supply afterwards: the definition the set was armed under,
 * the load it began against, whether it was timed, which prep it ran and which
 * sensors it was armed for. `SetJournalHeader` carries one of these, written
 * and closed before the first sample line exactly as the rest of the header is.
 *
 * NULL ON THE HEADER IS A REFUSAL, NOT A GAP TO FILL. A header written by a
 * build older than this one carries none, and [JournalRecoveryPolicy.refusal]
 * answers [RecoveryRefusal.NOT_JOURNALED] for it: such a capture is still
 * offered as a zip or a discard, which is #42 stage 1 in full, but nothing
 * invents the load, the definition or the prep it would need to become a row.
 *
 * [loadKg] is on the body-weight-inclusive scale a stored set's `loadKg` is on,
 * resolved when the set BEGAN. A recorded set resolves it when the set ends; the
 * two differ where the load was changed after START, and a recovered row then
 * carries the load the set began at -- the only one a kill leaves on the disk.
 * Whether the record screen offers a load change while a set is running is
 * not established here: an earlier statement of this KDoc said it did not,
 * and nothing was read to support that, so it is withdrawn.
 *
 * [geometry] and [stackRuleApplied] are the plan slot's declaration as the set
 * began, the pair `SetGeometryPolicy.analysedUnder` is handed at set end; the
 * analysed stream's own roll is read again from the recovered capture.
 * [armedSensors] is `SensorCapturePolicy.recorded(roster)` at the same moment,
 * null on the ordinary one-sensor set exactly as it is there.
 *
 * [counter] is who counts the set, `CountingPolicy.counterFor` as `beginSet`
 * froze it (#100 review round 1). It is the one fact that says what the
 * journaled rep marks ARE: on a [RepCounter.MANUAL] or [RepCounter.METRONOME]
 * set they are the tally the row's stated count is written from, exactly as
 * `RepCountPolicy.recorded` writes it at set end, and a [RepCounter.SENSOR]
 * set journals none. Required, with no default: a header that cannot say who
 * counted its set cannot say what its marks mean, and the header reader drops
 * facts it cannot decode to null rather than guessing.
 *
 * [tempo] is the tempo the set was performed to, the text `endSet` writes as
 * the row's `tempo` -- the slot's, or the typed one on an ad-hoc set -- read
 * when the set began; it is what lets the export say the guide counted a
 * guided set. [warmup], [added] and [plannedSide] are the frozen slot's
 * declarations, read off it at START exactly as `endSet` reads them off the
 * same frozen slot. [plannedPrepS] and [prepS] are the prep seconds `beginSet`
 * resolves before the journal opens, null where no prep runs. All of these
 * are defaulted to what an ad-hoc set with no slot writes.
 *
 * NOT JOURNALED, AND NARROWED HERE ON PURPOSE: the plan's rep, load, velocity
 * and rest targets and the slot's live `side`. Each is available at START;
 * none is needed to analyse or count the set, and the row says they are
 * absent rather than carrying them. The confirmation says so.
 */
@Serializable
data class JournalSetFacts(
    val exercise: ExerciseDef,
    val geometry: ResolvedGeometry,
    val stackRuleApplied: Boolean = false,
    val loadKg: Double,
    val bodyWeightKg: Double? = null,
    val timed: Boolean,
    val prepCase: PrepCase = PrepCase.NONE,
    val armedSensors: RecordedSensors? = null,
    val counter: RepCounter,
    val tempo: String? = null,
    val warmup: Boolean = false,
    val added: Boolean = false,
    val plannedSide: String? = null,
    val plannedPrepS: Int? = null,
    val prepS: Int? = null,
)

/** Why a recovered capture cannot be written back into History as a row (#100). */
enum class RecoveryRefusal {
    /** The header predates [JournalSetFacts]: the row's load and definition were never journaled. */
    NOT_JOURNALED,

    /** A timed set's figure is its seconds, and a killed process ended no clock. */
    TIMED,

    /**
     * A hold or a carry recorded untimed: not a rep-based lift, so there is no
     * rep list for the analyzer to rebuild. Dynamic and explosive lifts are
     * both rep-based and both admitted.
     */
    NOT_REP_BASED,

    /** A bar-sensor stream the analysis would read is oversize or malformed, so it is not decoded. */
    STREAM_UNREADABLE,

    /** A prep ran and the instant the work began was never journaled, so the reps cannot be bounded. */
    WORK_START_UNKNOWN,

    /**
     * The counter the set ran under made no count the capture holds: a tally
     * counter with no mark, whatever the stream beside it could count, or a
     * sensor-counted set whose stream is not analysable or whose analysis
     * found no rep -- a withheld analysis among them. The row would have to
     * state a count nobody made, and on a set counted in reps 0 is a count.
     * The listing cannot run the analysis, so the last case is answered at
     * recover, not on the card.
     */
    NOTHING_TO_COUNT,
}

/** Where a recovered set goes, as [JournalRecoveryPolicy.target] decides it (#100). */
sealed interface RecoveryTarget {
    /** Into [sessionId], still open and still current, at [orderIdx]. */
    data class Attach(val sessionId: Long, val orderIdx: Int) : RecoveryTarget

    /** Into a new session row of its own, closed at the set's end. */
    data object NewSession : RecoveryTarget

    /** A row for this very set is already in History; writing again would duplicate it. */
    data object AlreadyStored : RecoveryTarget

    /**
     * A recording hold is held: a set write or a session close still
     * running, or the record screen's own hold, which under Home has leaked
     * -- every way out of Record pops it and its ViewModel releases the
     * hold. Nothing is decided until it is released;
     * [JournalRecoveryPolicy.waitNote] says which.
     */
    data object Wait : RecoveryTarget
}

/** The rep count a recovered row is written with, and whether a person or the guide stated it. */
data class RecoveredCount(val reps: Int, val stated: Boolean)

/**
 * Whether, where and as what an interrupted set's capture re-enters History
 * (#100, the stage #42 split off).
 *
 * THE HARD PROBLEM IS OWNERSHIP, and the rule is the issue's: better one
 * visibly odd single-set session than a set silently absent from its own
 * session's numbers. `SessionRepository.endSession` writes a session's
 * heart-rate summary once and refuses a second close, so a set attached to a
 * CLOSED session would sit beside a summary computed over a set list it is not
 * in, with nothing able to correct it. A closed session is therefore never
 * re-opened and never rewritten: the set goes into a new row of its own.
 *
 * Here and not in `:app`, for the reason #33, #25 and #59 each lifted a rule:
 * nothing on the CI path can execute a decision written beside a ViewModel.
 */
object JournalRecoveryPolicy {
    /** The `orderIdx` a set takes in the single-set session [RecoveryTarget.NewSession] opens. */
    const val NEW_SESSION_ORDER_IDX = 0

    /**
     * The session a recovered set is written into.
     *
     * [headerSessionId] is the session the journal's header named when the set
     * began, null on the first set of a session -- no row existed yet.
     * [latest] is `SessionRepository.latestSession` over
     * [SessionResumePolicy.LOOKBACK_MS] before [nowMs]. [writeOutstanding] is
     * [mustWait]'s answer: whether any recording hold but the recovery's own
     * is held. [alreadyStored] is
     * [alreadyStored]'s answer for this capture.
     *
     * In order: nothing is decided while a write is outstanding, because it
     * could be writing this very set or could close the session after the
     * answer -- which holds because the caller reads [writeOutstanding] when
     * the set is placed, after the decode, and holds
     * [RecordingHold.RECOVERY_WRITE] for the whole recovery so no session it
     * may attach to can be continued or closed under it (whole-branch review
     * of 0.2.0, round 1, CAP-B1; `JournalRecovery.recover` names the one
     * database read that remains between the answer and the write); a
     * capture whose set is already a row is never written again;
     * the set rejoins its own session only while that session is the one
     * [SessionResumePolicy.offer] would offer to continue -- open, holding
     * sets, newest, and left within [SessionResumePolicy.RESUME_WINDOW_MS] --
     * at the next free position rather than the header's `orderIdx`, which a
     * set recorded after the kill may hold. Everything else, a closed session
     * above all, gets a session of its own.
     *
     * WHERE IT JOINS, ORDER IS NOT TIME. An attached set takes the next
     * free `orderIdx`, after any set the lifter recorded in that session
     * after the interruption, so in that session `orderIdx` order is no
     * longer the order the sets were lifted in. The export's measured rest
     * pairs each set with the next by `orderIdx`, so the set before the
     * kill measures its rest to the first set recorded after it -- a span
     * with the recovered set inside it -- and the last set recorded before
     * the recovery pairs with the recovered set, which began before it, a
     * negative span `RestMeasurePolicy` refuses rather than publishes.
     * Rejoining at the header's own
     * position would collide with the set that took it, and renumbering
     * stored sets is a rewrite of rows this never makes.
     *
     * One boundary with the resume prompt, by construction: the offer is
     * asked here rather than restated, so the recovery cannot call a session
     * current that Record would not offer to continue, or the reverse.
     *
     * THE MATCH IS ON THE BARE SESSION ID, named and not closed (whole-branch
     * review of 0.2.0, round 1, CAP-N2, migrations N4, UI-N3). After a
     * database rescue (a fresh database, ids from 1) or a restore (an older
     * file), [headerSessionId] names a row in a database that is no longer
     * the one open; if the open database's current session happens to carry
     * the same id, the set joins a different workout. It needs a mid-set
     * kill, a rescue or restore, and an id collision on the one session that
     * is open and current inside the resume window. Comparing the current
     * row's start with the header's would close it; that is a behaviour
     * change left for the owner or the next release.
     */
    fun target(
        headerSessionId: Long?,
        latest: LatestSession?,
        nowMs: Long,
        writeOutstanding: Boolean,
        alreadyStored: Boolean,
    ): RecoveryTarget {
        if (writeOutstanding) return RecoveryTarget.Wait
        if (alreadyStored) return RecoveryTarget.AlreadyStored
        val current = SessionResumePolicy.offer(latest, nowMs, writeOutstanding = false)
        return if (headerSessionId != null && current != null && current.sessionId == headerSessionId) {
            RecoveryTarget.Attach(headerSessionId, current.nextOrderIdx)
        } else {
            RecoveryTarget.NewSession
        }
    }

    /**
     * Whether a stored set row is the set this capture recorded: the same
     * exercise, begun at the same instant.
     *
     * `startedAtMs` is one reading of the clock at `beginSet`, written into
     * both the journal's header and the row, so equality is exact. It is what
     * catches the window between a set's row landing and its journal being
     * removed -- a process killed there leaves a capture whose set is already
     * in History -- and a second recovery of a journal whose own removal
     * failed.
     */
    fun alreadyStored(exerciseId: String, startedAtMs: Long, rows: List<Pair<String, Long>>): Boolean =
        rows.any { (rowExerciseId, rowStartedAtMs) -> rowExerciseId == exerciseId && rowStartedAtMs == startedAtMs }

    /**
     * Why this capture cannot become a row, or null when it can.
     *
     * [kind] is the definition's kind, null where [facts] is. [imuReadable] is
     * false where any bar-sensor stream the analysis would decode was not
     * counted whole by the listing scan. [analysable] is whether the analysed
     * stream reaches `SensorCapturePolicy.MIN_ANALYSABLE_FRAMES`, [repMarks]
     * the rows in `reps.csv`, and [workStartJournaled] whether the instant the
     * work began after a prep reached the journal. Whether the set can be
     * counted is [count]'s question, asked with the journaled counter, so the
     * refusal and the count cannot disagree. The listing cannot run the
     * analysis, so it asks [count] as if the segmenter found [ANY_REP]: a
     * sensor-counted set whose analysis then finds none is refused at
     * recover, with the same [RecoveryRefusal.NOTHING_TO_COUNT].
     *
     * The order is the order the facts are needed in, so a capture refused
     * for two reasons names the one that stops everything after it.
     */
    fun refusal(
        facts: JournalSetFacts?,
        imuReadable: Boolean,
        analysable: Boolean,
        repMarks: Long,
        workStartJournaled: Boolean,
    ): RecoveryRefusal? = when {
        facts == null -> RecoveryRefusal.NOT_JOURNALED
        facts.timed -> RecoveryRefusal.TIMED
        facts.exercise.kind !in repBased -> RecoveryRefusal.NOT_REP_BASED
        !imuReadable -> RecoveryRefusal.STREAM_UNREADABLE
        facts.prepCase != PrepCase.NONE && !workStartJournaled -> RecoveryRefusal.WORK_START_UNKNOWN
        count(facts.counter, analysable, ANY_REP, repMarks.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) == null ->
            RecoveryRefusal.NOTHING_TO_COUNT
        else -> null
    }

    /**
     * The count the row is written with, or null where there is none to write.
     *
     * `RepCountPolicy.recorded`'s precedence, which the set's own write
     * applies, read with the counter the set ran under (#100 review round 1,
     * Lens B B1). On a set the lifter or the guide counted
     * ([RepCounter.MANUAL], [RepCounter.METRONOME]) the journaled marks ARE
     * the tally -- the `+1 REP` taps or the guide's calls -- and the row
     * states them, an analysable stream beside them or not; a tally of none
     * is no count. On a [RepCounter.SENSOR] set the live count the lifter
     * heard died with the process and no mark was journaled, so the count is
     * the batch segmenter's over an analysable stream, unstated, or none --
     * and none where it found no rep (#100 review round 2, Lens A B2). The
     * segmenter answers an empty list over a full stream whenever it
     * withholds (a mount it may not read, a stream that did not move), and
     * that is no count of zero; the set's own write would have stored the
     * live count beside it. [RepCounter.NOBODY] counts nothing.
     *
     * The previous rule, "the sensor's count wins and the marks count only
     * where no stream can be analysed", threw a lifter's taps away beside
     * an analysable stream and wrote a count the lifter never gave.
     */
    fun count(counter: RepCounter, analysable: Boolean, analysedReps: Int, repMarks: Int): RecoveredCount? =
        when (counter) {
            RepCounter.MANUAL, RepCounter.METRONOME -> RecoveredCount(repMarks, stated = true).takeIf { repMarks > 0 }
            RepCounter.SENSOR -> RecoveredCount(analysedReps, stated = false).takeIf { analysable && analysedReps > 0 }
            RepCounter.NOBODY -> null
        }

    /**
     * When the recovered set is recorded as having ended: the last instant any
     * of its streams reached the disk, and never earlier than it began.
     *
     * The app did not end this set, so no end was ever read off the clock; the
     * last journaled instant is the latest moment the capture can vouch for.
     */
    fun endedAtMs(startedAtMs: Long, journaledInstants: List<Long>): Long =
        maxOf(startedAtMs, journaledInstants.maxOrNull() ?: startedAtMs)

    /**
     * What [refusal] hands [count] for the segmenter's count, which the
     * listing cannot know: the least count that is a count. See [refusal].
     */
    private const val ANY_REP = 1

    /** The kinds a set row can be rebuilt for: the rep-based ones. */
    private val repBased = setOf(ExerciseKind.DYNAMIC, ExerciseKind.EXPLOSIVE)

    /** The third action on the interrupted-set card. */
    const val ACTION_LABEL = "PUT IT BACK"

    /** The confirmation's title. */
    const val CONFIRM_TITLE = "Put this set back into History?"

    /** The confirmation's continue control. */
    const val CONFIRM_LABEL = "Put it back"

    /**
     * The confirmation's body: what will be written and what will not.
     *
     * It says the set is re-analysed and marked recovered, that the targets,
     * the side and the effort tap are not recovered, and where the set goes.
     */
    fun confirmBody(exerciseName: String): String =
        "$exerciseName is analysed again from what reached the disk and saved to History marked recovered. " +
            "Its plan targets, side and effort rating were never saved and stay empty. It joins its own " +
            "session if that session is still open and current; otherwise it is saved as a session of its own. " +
            "The capture on this phone is removed once the set is saved."

    /**
     * What the lifter is told when [target] answered [RecoveryTarget.Wait],
     * given the recording holds [held] at the moment of asking.
     *
     * WHAT BRINGS IT BACK DEPENDS ON THE HOLD, `rescuedRestoreNote`'s
     * reading (#100 review round 1, Lens A N5, Lens B N4). A set-end write
     * or a session close finishes on its own: wait. The record screen's own
     * hold, [RecordingHold.SESSION], is released when that screen goes away,
     * and every way out of Record pops it, so held under Home it has leaked
     * -- its KDoc names a task swipe -- and no set is recording anywhere
     * (#100 review round 2, Lens B B1: the round-1 note said one was).
     * Nothing will finish it, so the note says what the app still holds and
     * names what clears it, the process ending, since the holds live in
     * process memory. That a force-stop clears it on a device is reasoned,
     * not observed.
     */
    fun waitNote(held: Set<RecordingHold>): String =
        if (RecordingServicePolicy.writing(held)) SAVING_NOTE else RECORDING_NOTE

    /**
     * The holds in [held] a recovery must wait for: every hold but
     * [RecordingHold.RECOVERY_WRITE], which the recovery itself holds while
     * it runs (whole-branch review of 0.2.0, round 1, CAP-B1). Recoveries
     * run one at a time (`HomeViewModel.recoverInterrupted` refuses a second
     * while that hold is held), so a held RECOVERY_WRITE is the asker's own.
     */
    fun outstanding(held: Set<RecordingHold>): Set<RecordingHold> = held - RecordingHold.RECOVERY_WRITE

    /** Whether [outstanding] leaves anything to wait for. */
    fun mustWait(held: Set<RecordingHold>): Boolean = outstanding(held).isNotEmpty()

    /**
     * [waitNote] while a write that finishes on its own is held: a set-end
     * write, a session close, or another recovery. "A set or session", as
     * `rescuedRestoreNote` says it for the same holds; "A set is still being
     * saved" was false on a session close alone (whole-branch review of
     * 0.2.0, round 1, UI-B2).
     */
    const val SAVING_NOTE = "A set or session is still saving -- try again in a moment"

    /** [waitNote] while only the record screen's own hold is held. */
    const val RECORDING_NOTE =
        "BarSpeed still counts a recording as running. If you are not recording, force-stop BarSpeed in " +
            "Android settings or restart the phone, and try again."

    /** What the card says in place of [ACTION_LABEL] when [refusal] refused. */
    fun refusalNote(refusal: RecoveryRefusal): String = when (refusal) {
        RecoveryRefusal.NOT_JOURNALED -> "Recorded before this version, so it cannot be put back into History"
        RecoveryRefusal.TIMED -> "A timed set cannot be put back: its clock never stopped"
        RecoveryRefusal.NOT_REP_BASED -> "Only a rep-based lift can be put back into History"
        RecoveryRefusal.STREAM_UNREADABLE -> "A sensor stream is damaged, so it cannot be put back"
        RecoveryRefusal.WORK_START_UNKNOWN -> "Its prep never finished on record, so it cannot be put back"
        RecoveryRefusal.NOTHING_TO_COUNT -> "No count of its reps survived, so it cannot be put back"
    }
}
