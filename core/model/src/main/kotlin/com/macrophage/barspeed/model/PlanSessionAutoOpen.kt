package com.macrophage.barspeed.model

/**
 * Whether Record may open a plan session's preview that it was navigated to
 * open -- Home's next session, or the session whose START was tapped on Plan
 * detail (UX review 2026-11-06, home-nav F2, plans F7). `homeEntryStep` in
 * `:app` asks this for every PREVIEW entry.
 *
 * It WAITS FOR THE RESUME CHECK. Record asks once, on entry, whether an
 * earlier visit left a session open (#48), and that prompt is only offered
 * while Record is still on SETUP. Opening the preview before the check
 * answered moved Record off SETUP, so a lifter whose phone died mid "Day A"
 * who tapped Day A's START got set 1 in a second row and was never offered
 * "Continue it" -- the split the owner ruled against (focused review of
 * batch1-plan-warnings). So:
 *
 * - nothing opens until [resumeChecked] is true, whatever the check found;
 * - nothing opens while an offer is pending: the prompt is answered first;
 * - "Start a new one" clears the offer, and the requested preview opens;
 * - "Continue it" ([resumeContinued]) takes the lifter to the continued
 *   session instead, and the request is never acted on afterwards.
 */
object PlanSessionAutoOpen {
    fun mayOpen(resumeChecked: Boolean, resumePending: Boolean, resumeContinued: Boolean): Boolean =
        resumeChecked && !resumePending && !resumeContinued
}
