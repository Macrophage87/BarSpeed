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
enum class Stage { SETUP, READY, IN_SET, RESTING, FINISHED }
