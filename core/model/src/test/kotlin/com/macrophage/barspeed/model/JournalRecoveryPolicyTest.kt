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
        assertEquals(RecoveryRefusal.NOTHING_TO_COUNT, refusal(analysable = false))
        assertNull(refusal(analysable = false, repMarks = 5L))
    }

    // ---- the count ----------------------------------------------------------

    @Test
    fun `the sensor's count wins and is not stated`() {
        assertEquals(RecoveredCount(6, stated = false), JournalRecoveryPolicy.count(true, 6, 8))
    }

    @Test
    fun `with no analysable stream the journaled marks are the stated count`() {
        assertEquals(RecoveredCount(8, stated = true), JournalRecoveryPolicy.count(false, 0, 8))
        assertNull(JournalRecoveryPolicy.count(false, 0, 0))
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

    @Test
    fun `every refusal has its own note`() {
        val notes = RecoveryRefusal.entries.map(JournalRecoveryPolicy::refusalNote)
        assertEquals(notes.size, notes.toSet().size)
        assertTrue(notes.all { it.isNotBlank() })
    }
}
