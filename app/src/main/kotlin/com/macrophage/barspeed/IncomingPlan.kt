package com.macrophage.barspeed

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.macrophage.barspeed.model.ArrivalClaim
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A plan handed to the app from outside it: "Open with BarSpeed" on a file,
 * or the share sheet with a file or with the plan's text (UX review
 * 2026-11-06, plans F1).
 */
sealed interface IncomingPlan {
    /** The plan's own text, shared as text. */
    data class Text(val text: String) : IncomingPlan

    /** A document to read through the content resolver. */
    data class Document(val uri: Uri) : IncomingPlan

    companion object {
        /**
         * What [intent] carries, or null when it is not a plan hand-off (the
         * launcher's MAIN, for one). A shared stream wins over shared text: a
         * file shared as text/plain often carries both, and the text is then
         * a caption or the file name, not the plan.
         */
        fun from(intent: Intent?): IncomingPlan? {
            intent ?: return null
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let { Document(it) }
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let { Document(it) }
                        ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Text(it) }
                else -> null
            }
        }
    }
}

/**
 * Holds one incoming plan from the moment the Activity receives it until the
 * Plans screen's ViewModel takes it into the import gate.
 *
 * Process-wide rather than in the Activity, because the Activity and the
 * ViewModel that stages the plan do not share a lifetime: the plan can arrive
 * before the Plans screen exists, and the hand-off has to survive the
 * navigation that creates it. A newer hand-off replaces an untaken one; the
 * lifter sent the second one last.
 */
object IncomingPlanInbox {
    private val held = MutableStateFlow<IncomingPlan?>(null)

    val pending: StateFlow<IncomingPlan?> = held

    private val count = MutableStateFlow(0)

    /**
     * How many hand-offs this process has received. The navigation reacts to
     * this rather than to [pending], because a Plans screen already open can
     * take a plan before the navigation sees it, and the lifter must still be
     * told it arrived.
     */
    val arrivals: StateFlow<Int> = count

    private val announcement = ArrivalClaim()

    fun offer(plan: IncomingPlan) {
        held.value = plan
        // Set before the count moves, so the effect the count wakes always
        // finds the arrival to claim.
        announcement.arrive()
        count.update { it + 1 }
    }

    /**
     * True once per arrival, for whoever announces it. In the process, with
     * the inbox, and not in saved UI state, which would outlive [arrivals]
     * across a process death and swallow every later share.
     */
    fun claimArrival(): Boolean = announcement.claim()

    /** Takes the held plan, once: a second caller gets null. */
    fun take(): IncomingPlan? = held.getAndUpdate { null }
}
