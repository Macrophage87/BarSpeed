package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which throws out of the foreground-service start the app reports, and which
 * it lets crash (#53).
 *
 * The cases here are what the service must NOT swallow: a throw outside the
 * three families `RecordingService` has always caught. Answering null for them
 * is what keeps an unknown bug visible as a crash rather than turning it into a
 * banner.
 */
class ForegroundStartPolicyTest {
    @Test
    fun `a throw outside the three families is not a start failure`() {
        // NullPointerException and UnsupportedOperationException are
        // RuntimeExceptions the service's catch could see; neither is a
        // documented startForeground refusal.
        assertNull(ForegroundStartPolicy.classify(NullPointerException("boom")))
        assertNull(ForegroundStartPolicy.classify(UnsupportedOperationException("boom")))
        assertNull(ForegroundStartPolicy.classify(RuntimeException("boom")))
    }

    @Test
    fun `an Error is not a start failure`() {
        assertNull(ForegroundStartPolicy.classify(OutOfMemoryError("boom")))
    }

    @Test
    fun `a permission refusal is reported as one`() {
        // From API 34, startForeground with a type whose permission is not
        // held. Deleting this arm is #52's third hole: the service would
        // rethrow and the lifter's tap on START SET would kill the app.
        assertEquals(
            ForegroundStartFailure.PERMISSION_REFUSED,
            ForegroundStartPolicy.classify(SecurityException("type requires a permission")),
        )
    }

    @Test
    fun `a refused start is reported, subclasses included`() {
        // ForegroundServiceStartNotAllowedException extends
        // ServiceStartNotAllowedException extends IllegalStateException; this
        // module cannot see either, so a local subclass stands in for the
        // depth. A classify keyed on the exact class would pass the first
        // call and red here.
        assertEquals(
            ForegroundStartFailure.START_REFUSED,
            ForegroundStartPolicy.classify(IllegalStateException("not allowed")),
        )
        assertEquals(ForegroundStartFailure.START_REFUSED, ForegroundStartPolicy.classify(StartNotAllowed()))
    }

    @Test
    fun `a rejected type is reported as one`() {
        assertEquals(
            ForegroundStartFailure.TYPE_REJECTED,
            ForegroundStartPolicy.classify(IllegalArgumentException("type not in the manifest")),
        )
    }

    @Test
    fun `the three families are told apart`() {
        val answers =
            listOf(SecurityException(), IllegalStateException(), IllegalArgumentException())
                .map { ForegroundStartPolicy.classify(it) }
        assertEquals(ForegroundStartFailure.entries.toList(), answers)
    }

    /** Stands in for `android.app.ForegroundServiceStartNotAllowedException`'s depth. */
    private class StartNotAllowed : IllegalStateException("background start not allowed")
}
