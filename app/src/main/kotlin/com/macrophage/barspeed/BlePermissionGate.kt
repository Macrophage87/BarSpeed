package com.macrophage.barspeed

import com.macrophage.barspeed.model.BlePermissionPolicy
import com.macrophage.barspeed.model.BlePermissionStep
import com.macrophage.barspeed.model.PermissionAsk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the Nearby-devices permission has got to, for the whole process.
 *
 * Held by [AppContainer] rather than by the Activity, and holding nothing but
 * the enums: no launcher, no Activity, no Context. A launcher holds the result
 * registry, which holds the Activity, so parking one here would leak an
 * Activity on every rotation -- the rotation this class exists to survive.
 * MainActivity owns the launcher, reads the two Android facts, and hands them
 * in.
 *
 * Rotation is why it is here at all. An Activity FIELD is rebuilt on every
 * configuration change, so [PermissionAsk.NEVER_ASKED] would come back and
 * re-launch a request on each rotation; an Activity-scoped ViewModel would
 * survive that, but not the case that matters more -- `ActivityResultRegistry`
 * re-dispatches a pending result to the rebuilt Activity, so the state that
 * receives the answer has to outlive the Activity that asked. Process scope
 * does both, and dies when the process dies, which is what keeps it honest
 * across Android's unused-app auto-reset. See [PermissionAsk] for why that
 * must not be persisted.
 *
 * Not durable, and one consequence is worth naming rather than leaving to be
 * discovered: if the process dies while the dialog is up, the next launch asks
 * once more. Bounded at once per process, self-clearing, and much cheaper than
 * the alternative it is chosen over.
 */
class BlePermissionGate {
    private var ask: PermissionAsk = PermissionAsk.NEVER_ASKED
    private var granted: Boolean = false
    private var shouldShowRationale: Boolean = false

    private val stepFlow = MutableStateFlow(BlePermissionStep.AWAITING_ANSWER)

    /** What the screens draw. */
    val step: StateFlow<BlePermissionStep> = stepFlow

    /** True while no request has been issued in this process. */
    fun needsAsk(): Boolean = ask == PermissionAsk.NEVER_ASKED

    /**
     * Record that a request is out.
     *
     * Called only after `launch` has returned normally.
     * `ActivityResultLauncher.launch` throws `IllegalStateException` on an
     * unregistered or destroyed launcher, and [PermissionAsk.IN_FLIGHT] draws
     * the one state with no button on it -- so marking it before the call would
     * latch a dead banner for the life of the process, rebuilding the dead end
     * this whole change closes.
     */
    fun markInFlight() {
        ask = PermissionAsk.IN_FLIGHT
        recompute()
    }

    /**
     * Take the launcher's answer.
     *
     * An empty map is the platform saying the request was closed without an
     * answer, not that everything was refused; [PermissionAsk] carries the
     * evidence for that. [granted] is passed in separately and read from
     * `checkSelfPermission`, never derived from [result], because the map is
     * empty on exactly that path and permissions also change while the app is
     * backgrounded.
     */
    fun onResult(result: Map<String, Boolean>, granted: Boolean, shouldShowRationale: Boolean) {
        ask = if (result.isEmpty()) PermissionAsk.ABANDONED else PermissionAsk.ANSWERED
        refresh(granted, shouldShowRationale)
    }

    /** Re-read the two Android facts. Called on every resume; leaves [ask] alone. */
    fun refresh(granted: Boolean, shouldShowRationale: Boolean) {
        this.granted = granted
        this.shouldShowRationale = shouldShowRationale
        recompute()
    }

    private fun recompute() {
        stepFlow.value = BlePermissionPolicy.permissionStep(granted, shouldShowRationale, ask)
    }
}
