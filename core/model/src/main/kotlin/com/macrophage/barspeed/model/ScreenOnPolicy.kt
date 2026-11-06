package com.macrophage.barspeed.model

/**
 * Which Record stages hold the display awake (UX review 2026-11-06, record-live
 * F1, accessibility F1, settings-onboarding F3).
 *
 * The phone sits on a bench 1-2 m away. READY is the last screen before START,
 * IN_SET carries the live readout and every control that ends a set (a guided
 * or timed set's lead-in is part of IN_SET, not a stage of its own), and
 * RESTING carries the rest countdown and START NEXT SET. Under the system
 * display timeout each of those goes black mid-glance and costs a wake and an
 * unlock with chalked hands.
 *
 * SETUP, PREVIEW and FINISHED are not held: nothing is running there, and a
 * picker or a summary left on a bench should time out as any other screen.
 *
 * Here rather than inline in `RecordScreen` so the set of stages is pinned by
 * a test on the CI path. What the platform does with the flag is `[Field]`.
 */
object ScreenOnPolicy {
    fun keepsScreenOn(stage: Stage): Boolean = when (stage) {
        Stage.READY, Stage.IN_SET, Stage.RESTING -> true
        Stage.SETUP, Stage.PREVIEW, Stage.FINISHED -> false
    }
}
