package com.macrophage.barspeed.record

import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.model.SensorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How `:app` pairs a ROLE with the LINK that holds it, characterized before
 * #225 moves the pairing into `:core:model`.
 *
 * [armedDeliveryOf] is the one place the screen state's four link fields
 * become a per-role reading, and it is the only part of #213's decision that
 * still lives in a module nothing on the CI path can reach for anything else.
 * These pins exist so the move can be shown to have changed nothing: they are
 * written against the shape as it stands at `73f48b92`, and the same
 * assertions travel with the function.
 *
 * ONE OF THEM PINS A DEFECT ON PURPOSE. `a single grace floor is applied to
 * both links` records that today one instant is handed to both readings, which
 * is what makes a set-end reading say `tooSoon` about a link the app has known
 * was silent all session (#225 item 8). It is here so the differential that
 * replaces it is a visible change of a pinned fact rather than a quiet edit.
 *
 * REACHABLE ONLY BECAUSE `app/build.gradle.kts` PINS THE TEST JVM TO 21 --
 * `AppendedSlotTest`'s KDoc has the reason. Nothing here composes anything,
 * executes a GATT client, or runs Room.
 */
class ArmedDeliveryOfTest {
    private val armedAt = 1_000_000L
    private val connected = ConnectionState.Connected("WT901BLE")

    private fun links(
        analysedState: ConnectionState = connected,
        analysedFrameAtMs: Long? = null,
        secondaryState: ConnectionState = connected,
        secondaryFrameAtMs: Long? = null,
    ) = ArmedLinks(analysedState, analysedFrameAtMs, secondaryState, secondaryFrameAtMs)

    /**
     * Each role is judged by ITS OWN link, and the wrong-pair mistake is what
     * the [ArmedLinks] parameter exists to make impossible.
     *
     * The analysed link is delivering and the second link is silent; the two
     * readings must not be swapped and must not be merged.
     */
    @Test
    fun `each role is read from the link that holds it`() {
        val now = armedAt + 10_000L

        val byRole =
            armedDeliveryOf(
                analysed = SensorRole.A,
                secondary = SensorRole.B,
                links = links(analysedFrameAtMs = now - 100L, secondaryFrameAtMs = null),
                sinceMs = armedAt,
                nowMs = now,
            )

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.DELIVERING, SensorRole.B to ArmedDelivery.LINKED_SILENT),
            byRole,
            "a role was judged against the other link's frames",
        )
    }

    /**
     * A set that armed no role reports nothing, rather than inventing a key.
     *
     * The ordinary one-sensor set and the set that met two paired units it
     * could not tell apart both arrive here with a null analysed role; #224's
     * roleless answer is what covers them, and this map must stay empty so the
     * two cannot both be published for one set.
     */
    @Test
    fun `no armed role produces no reading at all`() {
        assertTrue(
            armedDeliveryOf(
                analysed = null,
                secondary = null,
                links = links(),
                sinceMs = armedAt,
                nowMs = armedAt + 10_000L,
            ).isEmpty(),
            "a set that armed no role was given a role-keyed word",
        )
    }

    /**
     * Frames beat the link state through the pairing, not only inside
     * `ArmedSilencePolicy.deliveryOf`.
     *
     * The state flag is a report and the frames are the fact; a link reading
     * `Disconnected` while samples arrive is delivering.
     */
    @Test
    fun `arriving frames beat the state flag through the pairing`() {
        val now = armedAt + 10_000L

        val byRole =
            armedDeliveryOf(
                analysed = SensorRole.A,
                secondary = null,
                links = links(analysedState = ConnectionState.Disconnected, analysedFrameAtMs = now - 10L),
                sinceMs = armedAt,
                nowMs = now,
            )

        assertEquals(mapOf(SensorRole.A to ArmedDelivery.DELIVERING), byRole)
    }

    /**
     * TODAY'S FLOOR, PINNED AS THE DEFECT IT IS (#225 item 8).
     *
     * One `sinceMs` reaches both links, so the caller decides the grace for a
     * link it may know nothing about. At the set-end reading that caller
     * passes the set's own start, so a two-second set on a link armed an hour
     * earlier and silent throughout stores `tooSoon` -- "the app does not know
     * yet" -- about a link it has known was silent all session.
     */
    @Test
    fun `a single grace floor is applied to both links`() {
        val setStart = armedAt + 3_600_000L
        val setEnd = setStart + 2_000L

        val byRole =
            armedDeliveryOf(
                analysed = SensorRole.A,
                secondary = SensorRole.B,
                links = links(),
                sinceMs = setStart,
                nowMs = setEnd,
            )

        assertEquals(
            mapOf(SensorRole.A to ArmedDelivery.TOO_SOON, SensorRole.B to ArmedDelivery.TOO_SOON),
            byRole,
            "the floor handed in stopped being the only thing deciding too soon",
        )
    }
}
