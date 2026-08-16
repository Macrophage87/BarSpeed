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

    @Test
    fun `the close state is not read yet, in flight (pre-fix)`() {
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.FINISH_SESSION),
            RestControlPolicy.controls(SessionCloseState.IN_FLIGHT),
        )
    }

    @Test
    fun `the close state is not read yet, failed (pre-fix)`() {
        assertEquals(
            setOf(RestControl.START_NEXT_SET, RestControl.FINISH_SESSION),
            RestControlPolicy.controls(SessionCloseState.FAILED),
        )
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
