package com.macrophage.barspeed.dsp

/**
 * [reps] with every input to `AccelArtefact.isPeakEligible` set to
 * "eligible": no artefact sample in the span or in the guard band before it,
 * and a displacement the analysis can bound. Issue #306.
 *
 * FOR THE PINS THAT USED A SET-LEVEL FIGURE AS A WITNESS. Before #306 a set's
 * `velocityLoss_pct` was taken over every rep, and a dozen field tests pinned
 * it to say WHICH reps a bound, a refusal or a geometry kept -- a cue bound
 * moving 64.1 to 40.9 is evidence the tail left the list. From #306 the
 * figure is taken over the eligible reps only and needs two of them, and
 * almost no committed capture has two, so the published figure is absent on
 * nearly every one and would witness nothing.
 *
 * Asking the same question of this list keeps each such pin a witness of the
 * rep list, and each one now sits beside an assertion of what the set
 * publishes. It is a test aid, not a second rule: nothing in production
 * masks eligibility.
 */
internal fun everyRepEligible(reps: List<RepAnalysis>): List<RepAnalysis> =
    reps.map { it.copy(artefactSamples = 0, guardArtefactSamples = 0, romBounded = true) }

/** Best-to-last over [everyRepEligible]: `velocityLoss_pct` as it was taken before #306. */
internal fun everyRepLossPct(reps: List<RepAnalysis>): Double? = VelocityLoss.of(everyRepEligible(reps)).pctOrNull
