package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether, where and as what an interrupted set's capture re-enters History
 * (#100).
 *
 * Nothing here reaches a journal directory, a DAO or the Home card. What is
 * pinned is the decision `JournalRecovery` in `:core:data` asks before it
 * writes, and the words the card draws; that they do is
 * `JournalRecoveryTest`'s, against a fake DAO, and the card's by reading it.
 */
class JournalRecoveryPolicyTest {
    /** The header's own session, open, its last set ten minutes before [now]. */
    private val own =
        LatestSession(
            sessionId = 7,
            startedAtMs = 0L,
            endedAtMs = null,
            setCount = 2,
            lastSetEndedAtMs = 600_000L,
            nextOrderIdx = 2,
            planSessionName = "Lower A",
        )

    private val now = 600_000L + 10 * 60_000L

    private fun target(
        headerSessionId: Long? = 7L,
        latest: LatestSession? = own,
        nowMs: Long = now,
        writeOutstanding: Boolean = false,
        alreadyStored: Boolean = false,
    ) = JournalRecoveryPolicy.target(headerSessionId, latest, nowMs, writeOutstanding, alreadyStored)

    // ---- which session ------------------------------------------------------

    /**
     * The first set of a session: the header names no session because none
     * existed until that set landed, and it never did. Whatever is open now is
     * a later session's, and the set is not one of its sets.
     */
    @Test
    fun `a first set's capture goes into a session of its own`() {
        assertEquals(RecoveryTarget.NewSession, target(headerSessionId = null))
    }

    /**
     * THE RULE THE ISSUE RECORDS. A closed session's heart-rate summary was
     * written once, over the sets it held, and `endSession` refuses a second
     * close: attaching would leave the summary silently computed over a set
     * list the recovered set is missing from.
     */
    @Test
    fun `a closed session is never re-opened`() {
        assertEquals(RecoveryTarget.NewSession, target(latest = own.copy(endedAtMs = 900_000L)))
    }

    /** The header's session is open but a later session is the newest: it is not the current one. */
    @Test
    fun `an open session that is not the newest is not attached to`() {
        assertEquals(RecoveryTarget.NewSession, target(latest = own.copy(sessionId = 8L)))
    }

    /** Gone from the database, or older than the lookback: nothing to attach to. */
    @Test
    fun `a session that cannot be found is not attached to`() {
        assertEquals(RecoveryTarget.NewSession, target(latest = null))
    }

    /**
     * Open, newest, and left longer ago than Record would offer to continue:
     * the same boundary [SessionResumePolicy.offer] draws, so the recovery and
     * the resume prompt cannot disagree about which session is current.
     */
    @Test
    fun `an open session left past the resume window is not current`() {
        val late = 600_000L + SessionResumePolicy.RESUME_WINDOW_MS + 1L
        assertEquals(RecoveryTarget.NewSession, target(nowMs = late))
    }

    /**
     * THE ATTACH (#100). The header's own session is still open and is the one
     * Record would offer to continue: the set is one of its sets, and it takes
     * the next free position, never the header's `orderIdx`, which a set
     * recorded after the kill may already hold.
     */
    @Test
    fun `the set rejoins its own session while that session is open and current`() {
        assertEquals(RecoveryTarget.Attach(sessionId = 7L, orderIdx = 2), target())
    }

    /**
     * A set write or a session close still running could be writing THIS set,
     * or could give the session an end time after the answer: nothing is
     * decided while one is outstanding.
     */
    @Test
    fun `nothing is decided while a write is outstanding`() {
        assertEquals(RecoveryTarget.Wait, target(writeOutstanding = true))
        assertEquals(RecoveryTarget.Wait, target(headerSessionId = null, writeOutstanding = true))
    }

    /** A capture whose set is already a row is never written a second time, current session or not. */
    @Test
    fun `a capture whose set is already stored is not written again`() {
        assertEquals(RecoveryTarget.AlreadyStored, target(alreadyStored = true))
        assertEquals(RecoveryTarget.AlreadyStored, target(headerSessionId = null, alreadyStored = true))
    }

    // ---- the duplicate ------------------------------------------------------

    @Test
    fun `a row of the same exercise begun at the same instant is this set`() {
        assertTrue(JournalRecoveryPolicy.alreadyStored("squat", 1_000L, listOf("bench" to 1_000L, "squat" to 1_000L)))
    }

    @Test
    fun `another exercise at the same instant, or the same exercise at another, is not`() {
        assertFalse(JournalRecoveryPolicy.alreadyStored("squat", 1_000L, listOf("bench" to 1_000L, "squat" to 999L)))
        assertFalse(JournalRecoveryPolicy.alreadyStored("squat", 1_000L, emptyList()))
    }

    // ---- whether it can become a row ----------------------------------------

    private val squat = ExerciseDef.seedById("back_squat") ?: error("no back_squat seed")

    private val facts =
        JournalSetFacts(
            exercise = squat,
            geometry = SetGeometryPolicy.describe(squat, null),
            loadKg = 100.0,
            timed = false,
            counter = RepCounter.SENSOR,
        )

    private fun refusal(
        facts: JournalSetFacts? = this.facts,
        imuReadable: Boolean = true,
        analysable: Boolean = true,
        repMarks: Long = 0L,
        workStartJournaled: Boolean = false,
    ) = JournalRecoveryPolicy.refusal(facts, imuReadable, analysable, repMarks, workStartJournaled)

    @Test
    fun `a journaled rep-based set with an analysable stream can be put back`() {
        assertNull(refusal())
    }

    /** A header an older build wrote: no load, no definition, and none is invented. */
    @Test
    fun `a capture recorded before the facts were journaled is refused`() {
        assertEquals(RecoveryRefusal.NOT_JOURNALED, refusal(facts = null))
    }

    @Test
    fun `a timed set is refused`() {
        assertEquals(RecoveryRefusal.TIMED, refusal(facts = facts.copy(timed = true)))
    }

    @Test
    fun `a lift that is not rep-based is refused`() {
        val hold = facts.copy(exercise = facts.exercise.copy(kind = ExerciseKind.HOLD))
        assertEquals(RecoveryRefusal.NOT_REP_BASED, refusal(facts = hold))
    }

    @Test
    fun `a damaged sensor stream is refused rather than decoded`() {
        assertEquals(RecoveryRefusal.STREAM_UNREADABLE, refusal(imuReadable = false))
    }

    /**
     * A prepped set whose work start never reached the journal: movement made
     * during the countdown would be counted as reps of the set, by an amount
     * nothing can bound.
     */
    @Test
    fun `a prepped set whose work start was not journaled is refused`() {
        assertEquals(RecoveryRefusal.WORK_START_UNKNOWN, refusal(facts = facts.copy(prepCase = PrepCase.CUED)))
        assertNull(refusal(facts = facts.copy(prepCase = PrepCase.CUED), workStartJournaled = true))
    }

    @Test
    fun `a capture with no analysable stream and no counted rep is refused`() {
        val tallied = facts.copy(counter = RepCounter.MANUAL)
        assertEquals(RecoveryRefusal.NOTHING_TO_COUNT, refusal(facts = tallied, analysable = false))
        assertNull(refusal(facts = tallied, analysable = false, repMarks = 5L))
    }

    /**
     * An explosive lift is a rep set the analyzer reads on peak velocity
     * (#100 review round 1, Lens A B4): nothing about it stops a capture
     * becoming a row, and refusing it narrowed the issue silently.
     */
    @Test
    fun `an explosive lift can be put back`() {
        val clean = facts.copy(exercise = facts.exercise.copy(kind = ExerciseKind.EXPLOSIVE))
        assertNull(refusal(facts = clean))
    }

    /**
     * The counter the set ran under decides what can count it (#100 review
     * round 1, Lens B B1). A tally counter that tallied nothing made no
     * count, whatever the stream says; a sensor-counted set journals no
     * marks, so only its stream can count it.
     */
    @Test
    fun `only the counter the set ran under can count it`() {
        assertEquals(
            RecoveryRefusal.NOTHING_TO_COUNT,
            refusal(facts = facts.copy(counter = RepCounter.MANUAL), analysable = true, repMarks = 0L),
        )
        assertEquals(
            RecoveryRefusal.NOTHING_TO_COUNT,
            refusal(facts = facts.copy(counter = RepCounter.SENSOR), analysable = false, repMarks = 5L),
        )
    }

    // ---- the count ----------------------------------------------------------

    @Test
    fun `the sensor's count wins and is not stated`() {
        assertEquals(RecoveredCount(6, stated = false), JournalRecoveryPolicy.count(RepCounter.SENSOR, true, 6, 8))
    }

    /**
     * RepCountPolicy.recorded's precedence, which the set's own write applies
     * (#100 review round 1, Lens B B1): on a set the lifter or the guide
     * counted, the tally is the stated count, an analysable stream beside it
     * or not. Only a sensor-counted set takes the segmenter's count.
     */
    @Test
    fun `a tallied set's marks are its stated count beside an analysable stream`() {
        assertEquals(RecoveredCount(5, stated = true), JournalRecoveryPolicy.count(RepCounter.MANUAL, true, 6, 5))
        assertEquals(RecoveredCount(6, stated = true), JournalRecoveryPolicy.count(RepCounter.METRONOME, true, 7, 6))
        assertNull(JournalRecoveryPolicy.count(RepCounter.MANUAL, true, 6, 0))
    }

    @Test
    fun `a sensor-counted set is counted by its stream or not at all`() {
        assertEquals(RecoveredCount(6, stated = false), JournalRecoveryPolicy.count(RepCounter.SENSOR, true, 6, 3))
        assertNull(JournalRecoveryPolicy.count(RepCounter.SENSOR, false, 0, 3))
        assertNull(JournalRecoveryPolicy.count(RepCounter.NOBODY, true, 6, 3))
    }

    /**
     * A sensor-counted set whose analysis found no rep has no count (#100
     * review round 2, Lens A B2). Its live count died with the process, and
     * a segmenter that withheld -- a mount it may not read, a stream that
     * did not move -- answers an empty rep list over plenty of frames. On a
     * set counted in reps, 0 is a count, and nobody made it. The listing,
     * which cannot run the analysis, still offers such a set: the default
     * [refusal] below is a SENSOR set with an analysable stream and no mark.
     */
    @Test
    fun `a sensor-counted set whose analysis found no rep is not counted`() {
        assertNull(JournalRecoveryPolicy.count(RepCounter.SENSOR, true, 0, 0))
        assertNull(refusal(), "the listing refused a set only the analysis can judge")
    }

    @Test
    fun `with no analysable stream the journaled marks are the stated count`() {
        assertEquals(RecoveredCount(8, stated = true), JournalRecoveryPolicy.count(RepCounter.MANUAL, false, 0, 8))
        assertNull(JournalRecoveryPolicy.count(RepCounter.MANUAL, false, 0, 0))
    }

    // ---- the end ------------------------------------------------------------

    @Test
    fun `the set ends at the last journaled instant and never before it began`() {
        assertEquals(5_000L, JournalRecoveryPolicy.endedAtMs(1_000L, listOf(2_000L, 5_000L, 3_000L)))
        assertEquals(1_000L, JournalRecoveryPolicy.endedAtMs(1_000L, emptyList()))
        assertEquals(1_000L, JournalRecoveryPolicy.endedAtMs(1_000L, listOf(500L)))
    }

    // ---- the words ----------------------------------------------------------

    @Test
    fun `the confirmation says what is written and what is not`() {
        val body = JournalRecoveryPolicy.confirmBody("Back Squat")
        assertTrue("Back Squat" in body, body)
        assertTrue("marked recovered" in body, body)
        assertTrue("stay empty" in body, body)
        assertTrue("session of its own" in body, body)
        assertTrue("removed once the set is saved" in body, body)
    }

    /**
     * A wait names what it waits for (#100 review round 1, Lens A N5, Lens B
     * N4). A set-end write or a session close finishes on its own, so the
     * lifter is told to wait. The record screen's own hold, SESSION, is
     * released when that screen goes away, and every way out of Record pops
     * it, so held under Home with Record gone it has leaked: "try again in a
     * moment" would never come true, and no set is recording anywhere (#100
     * review round 2, Lens B B1 -- the round-1 note said one was). The note
     * says what the app holds and what clears it, `rescuedRestoreNote`'s
     * reading. The holds are read after the decode, so the same SESSION may
     * instead be a Record screen opened on top of Home with a set recording
     * now (whole-branch review of 0.2.0, round 2, CAP-R2-B1); the note's
     * "If you are not recording" hedge keeps it true there, and is pinned.
     */
    @Test
    fun `a wait names what it waits for`() {
        val saving = JournalRecoveryPolicy.waitNote(setOf(RecordingHold.SESSION, RecordingHold.SET_WRITE))
        assertEquals(SAVING, saving)
        val recording = JournalRecoveryPolicy.waitNote(setOf(RecordingHold.SESSION))
        assertFalse("in a moment" in recording, recording)
        assertFalse("still recording" in recording, "a held SESSION may be a leak, with no set recording: $recording")
        assertFalse("Record screen" in recording, "a leaked hold has no Record screen to finish a set on: $recording")
        assertTrue("still counts a recording as running" in recording, recording)
        assertTrue("force-stop" in recording, recording)
        // CAP-R2-B1: a SESSION seen after the decode may be a Record screen
        // opened on top of Home with a set started there (recording now, or
        // resting after it); the hedge keeps the force-stop advice from
        // reaching a lifter mid-set.
        assertTrue("If you are not recording, force-stop" in recording, recording)
    }

    /**
     * Whole-branch review of 0.2.0, round 1, UI-B2. The note covers a
     * session close as much as a set-end write, so "A set is still being
     * saved" was false on a close alone -- reachable by finishing a session,
     * going Home while the close runs and tapping PUT IT BACK -- and L9's
     * restore note said "a set or session" for the same hold. One reading,
     * one wording; a second recovery waits on the first the same way.
     */
    @Test
    fun `a wait on a session close or another recovery says a set or session is saving`() {
        assertEquals(SAVING, JournalRecoveryPolicy.waitNote(setOf(RecordingHold.SESSION_CLOSE)))
        assertEquals(SAVING, JournalRecoveryPolicy.waitNote(setOf(RecordingHold.RECOVERY_WRITE)))
        assertEquals(SAVING, JournalRecoveryPolicy.SAVING_NOTE)
    }

    /**
     * Whole-branch review of 0.2.0, round 1, CAP-B1. A recovery holds
     * RECOVERY_WRITE for as long as it runs, so the resume offer, a session
     * close and RESTORE can see it; it must not then wait for its own hold.
     * Everything else held is still waited for, the record screen's own
     * hold included.
     */
    @Test
    fun `a recovery does not wait for its own hold, and waits for every other`() {
        assertFalse(JournalRecoveryPolicy.mustWait(setOf(RecordingHold.RECOVERY_WRITE)), "it waited on itself")
        assertEquals(emptySet(), JournalRecoveryPolicy.outstanding(setOf(RecordingHold.RECOVERY_WRITE)))
        for (other in RecordingHold.entries - RecordingHold.RECOVERY_WRITE) {
            val held = setOf(RecordingHold.RECOVERY_WRITE, other)
            assertTrue(JournalRecoveryPolicy.mustWait(held), "a recovery decided while $other was held")
            assertEquals(setOf(other), JournalRecoveryPolicy.outstanding(held), "the note would not name $other")
        }
        assertFalse(JournalRecoveryPolicy.mustWait(emptySet()))
    }

    @Test
    fun `every refusal has its own note`() {
        val notes = RecoveryRefusal.entries.map(JournalRecoveryPolicy::refusalNote)
        assertEquals(notes.size, notes.toSet().size)
        assertTrue(notes.all { it.isNotBlank() })
    }

    private companion object {
        /** L9's restore note's reading of the same holds (`rescuedRestoreNote`). */
        const val SAVING = "A set or session is still saving -- try again in a moment"
    }
}
