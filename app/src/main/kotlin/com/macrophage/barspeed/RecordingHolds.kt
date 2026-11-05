package com.macrophage.barspeed

import android.app.Application
import com.macrophage.barspeed.model.FgsCommand
import com.macrophage.barspeed.model.ForegroundStartFailure
import com.macrophage.barspeed.model.HoldTransition
import com.macrophage.barspeed.model.RecordingHold
import com.macrophage.barspeed.model.RecordingServicePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val heldFlow = MutableStateFlow<Set<RecordingHold>>(emptySet())

    /**
     * What is held now, read-only. Issue #112, round 1 review (A2, B4): the
     * rescued-database card's RESTORE ends the process, and asks
     * `RecordingServicePolicy.mayEndProcess` of this first, so a restart
     * never cuts a set-end write or a session close short. A flow rather
     * than a plain value so the card's button follows it.
     */
    val held: StateFlow<Set<RecordingHold>> = heldFlow

    private val foregroundRefusal = MutableStateFlow<ForegroundStartFailure?>(null)

    /**
     * Why the foreground service is NOT protecting the recording, or null
     * while nothing has said it is refused (#53).
     *
     * Process scope for the reason this class is: the refusal is reported by
     * [RecordingService], which no ViewModel owns. Written from two places --
     * the synchronous refusal [RecordingService.start] returns, and whatever
     * `onStartCommand` reports when it tries to go foreground -- and cleared
     * by a start that succeeds and by the stop, after which nothing is
     * recording for the service to protect. `RecordViewModel` mirrors it into
     * `RecordState.foregroundFailure`, which `RecordScreen` draws.
     *
     * Null is "no refusal reported", not "protected": between the start
     * request and `onStartCommand` running there is a window in which neither
     * has said anything. Non-null can also outlive the session it describes:
     * [apply] clears it on STOP, but an `onStartCommand` that runs after that
     * STOP -- the start accepted, the stop issued before the service got to
     * try -- reports its refusal with nothing recording, and the next Record
     * screen mirrors it before any set until the next set's `onStartCommand`
     * reports. That errs toward a warning, never toward a false all-clear,
     * and it is reasoned from the call order, not watched on a device.
     * A `MutableStateFlow` because the service's callback
     * and the main-thread [apply] both write it; its `value` setter is
     * thread-safe.
     */
    val foregroundFailure: StateFlow<ForegroundStartFailure?> = foregroundRefusal

    /** [RecordingService.onStartCommand]'s outcome: null went foreground, non-null did not. */
    fun reportForeground(failure: ForegroundStartFailure?) {
        foregroundRefusal.value = failure
    }

    /** Take [hold], starting or re-arming the service if that hold arms it. */
    fun acquire(hold: RecordingHold) {
        apply(RecordingServicePolicy.acquire(heldFlow.value, hold))
    }

    /**
     * Whether [hold] is held now. Read by the resume question (#48), which
     * must not offer to continue a session an earlier visit is still writing.
     */
    fun isHeld(hold: RecordingHold): Boolean = hold in held

    /** Give up [hold], stopping the service if it was the last reason to run. */
    fun release(hold: RecordingHold) {
        apply(RecordingServicePolicy.release(heldFlow.value, hold))
    }

    private fun apply(transition: HoldTransition) {
        heldFlow.value = transition.held
        when (transition.command) {
            // A refused request is recorded; an accepted one leaves the
            // standing value for onStartCommand to settle, so a banner for
            // the previous set's refusal is not wiped before this start has
            // actually gone foreground.
            FgsCommand.START -> RecordingService.start(app)?.let { foregroundRefusal.value = it }
            FgsCommand.STOP -> {
                RecordingService.stop(app)
                foregroundRefusal.value = null
            }
            FgsCommand.NONE -> Unit
        }
    }
}
