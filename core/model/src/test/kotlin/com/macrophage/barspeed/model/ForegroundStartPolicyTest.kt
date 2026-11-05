package com.macrophage.barspeed.model

import kotlin.test.Test
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
}
