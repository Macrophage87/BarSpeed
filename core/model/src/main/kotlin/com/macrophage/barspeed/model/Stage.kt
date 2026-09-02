package com.macrophage.barspeed.model

/**
 * Where the record flow is. `RecordViewModel` advances it; `RecordScreen` draws
 * one branch per value.
 *
 * This enum lives here, rather than beside the ViewModel that owns it, so that
 * [RecordExitPolicy] can be keyed on it from a module where a test can run on
 * it. The policy's two callers sit inside an `AndroidViewModel` and a
 * `@Composable`, which no test on the CI path can construct, so the decision
 * could not be tested where it was written. Nothing in this file touches Android.
 */
/**
 * PREVIEW sits between SETUP and READY and nothing about it is started: the
 * queue has been built and is being read, no session row exists, no clock is
 * running, no service has been asked for and no buffer has been cleared. It is
 * a stage of the record screen rather than a separate route so that what the
 * lifter reads IS the queue the flow will run, rather than a second rendering
 * of the plan that could disagree with it (#202).
 */
enum class Stage { SETUP, PREVIEW, READY, IN_SET, RESTING, FINISHED }
