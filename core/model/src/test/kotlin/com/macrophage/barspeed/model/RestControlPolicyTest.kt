package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which controls the rest screen may draw while a session close is outstanding.
 *
 * A separate decision from [RecordExitPolicy], and separate for the reason the
 * defect exists: guarding the way out and leaving the controls on the screen
 * live is not a guard. START NEXT SET during an in-flight close begins a real
 * set — service started, collectors attached, IMU buffer filling — and the close
 * then writes FINISHED over it, onto a screen with no way to end a set.
 */
class RestControlPolicyTest {
    @Test
    fun `nothing closing draws the controls that start and end work`() {
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.FINISH_SESSION),
            RestControlPolicy.controls(SessionCloseState.NONE),
        )
    }

    /**
     * Nothing that starts or ends work may be operated while a close is in
     * flight.
     *
     * START_NEXT_SET is the one that destroys a set. It runs `startNextSet`,
     * which writes READY and calls `beginSet` in the same frame: service
     * started, collectors attached, stage IN_SET. The close then resumes, writes
     * FINISHED over that stage and stops the service, leaving the lifter under a
     * loaded bar with the buffers filling on a screen that has no way to end a
     * set and two buttons that both navigate away.
     */
    @Test
    fun `nothing may be started or ended while the session is closing`() {
        assertEquals(emptySet(), RestControlPolicy.controls(SessionCloseState.IN_FLIGHT))
    }

    /**
     * A failed close leaves the session open, so continuing is legitimate again
     * -- and the retry has to be there, because the R-R intervals behind the
     * session HRV are still in memory and are not held anywhere else.
     */
    @Test
    fun `a failed close offers the retry and the next set`() {
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.RETRY_FINISH),
            RestControlPolicy.controls(SessionCloseState.FAILED),
        )
    }

    @Test
    fun `a failed close does not offer the finish that just failed`() {
        // The retry replays a close frozen at the first tap. Offering the plain
        // finish beside it would be a second control launching the same work
        // from a different input.
        assertFalse(RestControl.FINISH_SESSION in RestControlPolicy.controls(SessionCloseState.FAILED))
    }

    @Test
    fun `retrying a finish is never offered while nothing has failed`() {
        // True now and after: the retry replays a frozen close, and there is
        // nothing frozen until one has come back failed.
        assertFalse(RestControl.RETRY_FINISH in RestControlPolicy.controls(SessionCloseState.NONE))
        assertFalse(RestControl.RETRY_FINISH in RestControlPolicy.controls(SessionCloseState.IN_FLIGHT))
    }

    @Test
    fun `finishing and retrying a finish are never offered together`() {
        // Two controls that both close the session, side by side, would be two
        // ways to launch the same work and one of them would be the wrong one.
        SessionCloseState.entries.forEach { close ->
            val controls = RestControlPolicy.controls(close)
            assertFalse(
                RestControl.FINISH_SESSION in controls && RestControl.RETRY_FINISH in controls,
                "$close offers two ways to close the session",
            )
        }
    }

    // ---- the session rating step, issue #159 --------------------------------

    /**
     * Asking for the session rating REPLACES the finish control rather than
     * joining it.
     *
     * Green on arrival: the two-argument form is a new function nothing called
     * when this was written. What it guards is the same rule the retry already
     * obeys -- two controls that both close the session are two ways to launch
     * the same work from different inputs -- and here the wrong one would be
     * the one that closes with no rating while the lifter is looking at the
     * panel asking for it.
     */
    @Test
    fun `asking for the session rating replaces the finish control`() {
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.RATE_SESSION),
            RestControlPolicy.controls(SessionCloseState.NONE, askedToFinish = true),
        )
    }

    @Test
    fun `not having asked leaves the two forms saying the same thing`() {
        SessionCloseState.entries.forEach { close ->
            assertEquals(
                RestControlPolicy.controls(close),
                RestControlPolicy.controls(close, askedToFinish = false),
                "$close disagrees with itself between the one- and two-argument forms",
            )
        }
    }

    /**
     * The next set stays reachable while the panel is up.
     *
     * The close has not begun -- nothing is in flight and no row has been
     * written -- so the hazard that empties the in-flight state does not exist
     * here, and a lifter who reached the panel by mistapping Finish needs a way
     * out that is not answering a question about a workout they have not
     * finished.
     */
    @Test
    fun `the next set is still reachable while the rating is being asked for`() {
        assertTrue(
            RestControl.START_NEXT_SET in RestControlPolicy.controls(SessionCloseState.NONE, askedToFinish = true),
        )
    }

    /**
     * Asking cannot resurrect a control the close state already withheld.
     *
     * The flag is the lifter's intent, not an override. An in-flight close
     * draws nothing whatever the lifter tapped a moment ago, and a failed close
     * still draws its retry rather than a fresh rating panel -- the retry
     * replays a close frozen at the first tap, and the rating that was frozen
     * with it is the one that gets written.
     */
    @Test
    fun `asking to finish adds nothing to a state that already withholds the finish`() {
        assertEquals(emptySet(), RestControlPolicy.controls(SessionCloseState.IN_FLIGHT, askedToFinish = true))
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.RETRY_FINISH),
            RestControlPolicy.controls(SessionCloseState.FAILED, askedToFinish = true),
        )
    }

    @Test
    fun `no state offers the rating panel and a way to close beside it`() {
        SessionCloseState.entries.forEach { close ->
            listOf(true, false).forEach { asked ->
                val controls = RestControlPolicy.controls(close, asked)
                assertFalse(
                    RestControl.RATE_SESSION in controls &&
                        (RestControl.FINISH_SESSION in controls || RestControl.RETRY_FINISH in controls),
                    "$close/$asked offers the rating panel beside another way to close",
                )
            }
        }
    }

    @Test
    fun `every close state names its controls explicitly`() {
        // A set per state rather than a boolean per control, so a control added
        // later has to be placed in every state rather than defaulting into all
        // of them. This asserts the enum is total, not that any state is
        // non-empty -- an in-flight close draws nothing, deliberately.
        SessionCloseState.entries.forEach { close ->
            assertTrue(RestControlPolicy.controls(close).all { it in RestControl.entries })
        }
    }
}
