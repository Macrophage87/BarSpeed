package com.macrophage.barspeed

import android.app.Application
import com.macrophage.barspeed.model.FgsCommand
import com.macrophage.barspeed.model.HoldTransition
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RecordingServicePolicy

/**
 * Why the recording foreground service is running, for the whole process.
 *
 * Held by [AppContainer] rather than by `RecordViewModel`, in the shape
 * [BlePermissionGate] already uses: the decision is a pure function in
 * `:core:model` where it can be tested, and this keeps the one piece of state
 * that function needs. Process scope is not a preference here — the ViewModel
 * is being destroyed at the exact moment the interesting question is asked, and
 * a field on it would be destroyed with it while the work it describes carries
 * on running.
 *
 * It holds an [Application], which is the process's own context and so cannot
 * leak an Activity. That is the difference from [BlePermissionGate], which
 * holds no context at all because a result launcher would have dragged an
 * Activity in behind it.
 *
 * Not synchronised, and the reason is a claim about `:app` code paths read
 * rather than anything a test here can show. Every acquire and release is
 * issued from the main thread: `beginSet` from a tap, the two durable writers
 * from `Dispatchers.Main.immediate` coroutines, and `onCleared` from
 * `ViewModelStore.clear`. Nothing in this repository can verify which thread an
 * Android lifecycle callback runs on.
 */
class RecordingHolds(private val app: Application) {
    private var held: Set<RecordingHold> = emptySet()

    /** Take [hold], starting or re-arming the service if that hold arms it. */
    fun acquire(hold: RecordingHold) {
        apply(RecordingServicePolicy.acquire(held, hold))
    }

    /** Give up [hold], stopping the service if it was the last reason to run. */
    fun release(hold: RecordingHold) {
        apply(RecordingServicePolicy.release(held, hold))
    }

    private fun apply(transition: HoldTransition) {
        held = transition.held
        when (transition.command) {
            FgsCommand.START -> RecordingService.start(app)
            FgsCommand.STOP -> RecordingService.stop(app)
            FgsCommand.NONE -> Unit
        }
    }
}
