package com.macrophage.barspeed.ui

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.macrophage.barspeed.record.RecordEntry

/**
 * Everything about the Record screen's route in one place: the pattern the
 * NavHost registers, its arguments, how a caller builds a path to it, and how
 * the screen reads its arguments back. A change to the route's arguments is a
 * change to this object alone.
 *
 * Both arguments are optional. A path with neither opens Record's session
 * picker. `entry` says what the visit is for ([RecordEntry]: Home's hero
 * continuing, starting new, or previewing; UX review 2026-11-06, home-nav F2,
 * F3) and `planSession` names the plan session a PREVIEW opens -- Home's next
 * session, or the session whose START was tapped on Plan detail (plans F7).
 */
object RecordRoute {
    private const val ENTRY = "entry"
    private const val PLAN_SESSION = "planSession"

    /**
     * The registered pattern. Also what to pass to `getBackStackEntry` when
     * asking whether Record is on the stack: the pattern string matches by
     * equality, where a concrete path such as "record" would have to match the
     * optional arguments of an entry opened with them.
     */
    const val PATTERN = "record?$ENTRY={$ENTRY}&$PLAN_SESSION={$PLAN_SESSION}"

    val arguments: List<NamedNavArgument> = listOf(
        navArgument(ENTRY) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
        navArgument(PLAN_SESSION) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
    )

    /** The path to navigate to; no arguments opens the session picker. */
    fun path(entry: RecordEntry? = null, planSession: String? = null): String {
        val query = listOfNotNull(
            entry?.let { "$ENTRY=${it.name.lowercase()}" },
            planSession?.let { "$PLAN_SESSION=${Uri.encode(it)}" },
        )
        return if (query.isEmpty()) "record" else "record?" + query.joinToString("&")
    }

    /** A PREVIEW of the named plan session. */
    fun preview(planSession: String): String = path(RecordEntry.PREVIEW, planSession)

    fun entry(arguments: Bundle?): RecordEntry? = RecordEntry.parse(arguments?.getString(ENTRY))

    fun planSession(arguments: Bundle?): String? = arguments?.getString(PLAN_SESSION)
}
