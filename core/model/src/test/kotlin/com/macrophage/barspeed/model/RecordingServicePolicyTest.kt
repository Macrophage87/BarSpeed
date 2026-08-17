package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When the recording foreground service runs.
 *
 * The two pins named `(pre-fix)` describe the behaviour shipping at
 * b53e2bd743e50fbbb0518be20f19d844a9eafaa5, which is the defect: the screen
 * going away stops the service whatever else is still running, and a write
 * landing never stops it. They are replaced by their inversions in the next
 * commit, which is a test-file-only commit and names the retirement in its
 * body. The naming follows `SetLoadPolicyTest` and `RecordExitPolicyTest`.
 */
class RecordingServicePolicyTest {
    @Test
    fun `the first set starts the service`() {
        val t = RecordingServicePolicy.acquire(emptySet(), RecordingHold.SESSION)
        assertEquals(FgsCommand.START, t.command)
        assertEquals(setOf(RecordingHold.SESSION), t.held)
    }

    @Test
    fun `every set re-arms the service`() {
        // Not an optimisation left on the table. Every catch clause in
        // RecordingService.onStartCommand ends in stopSelf(startId), so a start
        // that was refused leaves nothing running, and the next beginSet is the
        // only retry there is.
        val t = RecordingServicePolicy.acquire(setOf(RecordingHold.SESSION), RecordingHold.SESSION)
        assertEquals(FgsCommand.START, t.command)
        assertEquals(setOf(RecordingHold.SESSION), t.held)
    }

    @Test
    fun `a set write does not re-arm the service`() {
        val t = RecordingServicePolicy.acquire(setOf(RecordingHold.SESSION), RecordingHold.SET_WRITE)
        assertEquals(FgsCommand.NONE, t.command)
        assertEquals(setOf(RecordingHold.SESSION, RecordingHold.SET_WRITE), t.held)
    }

    @Test
    fun `a session close does not re-arm the service`() {
        val t = RecordingServicePolicy.acquire(setOf(RecordingHold.SESSION), RecordingHold.SESSION_CLOSE)
        assertEquals(FgsCommand.NONE, t.command)
        assertEquals(setOf(RecordingHold.SESSION, RecordingHold.SESSION_CLOSE), t.held)
    }

    @Test
    fun `the screen going away stops the service`() {
        val t = RecordingServicePolicy.release(setOf(RecordingHold.SESSION), RecordingHold.SESSION)
        assertEquals(FgsCommand.STOP, t.command)
        assertEquals(emptySet(), t.held)
    }

    @Test
    fun `the screen going away stops the service even mid-write (pre-fix)`() {
        // The defect. The set's samples are in memory and nowhere else at this
        // moment, and the process loses the priority that was protecting them.
        val t = RecordingServicePolicy.release(
            setOf(RecordingHold.SESSION, RecordingHold.SET_WRITE),
            RecordingHold.SESSION,
        )
        assertEquals(FgsCommand.STOP, t.command)
        assertEquals(setOf(RecordingHold.SET_WRITE), t.held)
    }

    @Test
    fun `a completed write does not stop the service (pre-fix)`() {
        // The other half of the defect: nothing stops the service when the work
        // that outlived the screen lands, because today only the screen stops it.
        val t = RecordingServicePolicy.release(setOf(RecordingHold.SET_WRITE), RecordingHold.SET_WRITE)
        assertEquals(FgsCommand.NONE, t.command)
        assertEquals(emptySet(), t.held)
    }

    @Test
    fun `a write landing while the lifter is still here does not stop the service`() {
        // Between sets the screen still needs the service, so a write finishing
        // must not take it down. True before the fix and after it.
        val t = RecordingServicePolicy.release(
            setOf(RecordingHold.SESSION, RecordingHold.SET_WRITE),
            RecordingHold.SET_WRITE,
        )
        assertEquals(FgsCommand.NONE, t.command)
        assertEquals(setOf(RecordingHold.SESSION), t.held)
    }

    @Test
    fun `releasing a hold that was never held changes what is held`() {
        val t = RecordingServicePolicy.release(setOf(RecordingHold.SESSION), RecordingHold.SET_WRITE)
        assertEquals(setOf(RecordingHold.SESSION), t.held)
    }

    @Test
    fun `a hold acquired twice is held once`() {
        // Membership, not a count. beginSet acquires SESSION on every set, so a
        // counted hold would need one release per set and would never reach
        // zero from the one place that releases it.
        val once = RecordingServicePolicy.acquire(emptySet(), RecordingHold.SESSION).held
        val twice = RecordingServicePolicy.acquire(once, RecordingHold.SESSION).held
        assertEquals(once, twice)
        assertEquals(emptySet(), RecordingServicePolicy.release(twice, RecordingHold.SESSION).held)
    }

    @Test
    fun `a set write and a session close can be held at the same time`() {
        // Representable rather than assumed away. The same reasoning keeps
        // SetWriteState and SessionCloseState as two facts.
        val write = RecordingServicePolicy.acquire(emptySet(), RecordingHold.SET_WRITE).held
        val both = RecordingServicePolicy.acquire(write, RecordingHold.SESSION_CLOSE).held
        assertEquals(setOf(RecordingHold.SET_WRITE, RecordingHold.SESSION_CLOSE), both)
    }

    @Test
    fun `a finished session ends with nothing held`() {
        // RecordExitPolicy.promptFor says FINISHED "has already written
        // everything and stopped the service", and RecordExitPolicyTest repeats
        // it in a comment; neither has an assertion behind it. FinishedStage
        // navigates on a tap and never on its own, so a hold left over here is a
        // permanent notification on a screen the lifter can sit on.
        val held = setOf(RecordingHold.SESSION, RecordingHold.SESSION_CLOSE)
        val afterScreen = RecordingServicePolicy.release(held, RecordingHold.SESSION).held
        val afterClose = RecordingServicePolicy.release(afterScreen, RecordingHold.SESSION_CLOSE).held
        assertEquals(emptySet(), afterClose)
    }
}
