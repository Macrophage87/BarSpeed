package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which GATT links ask for a high connection priority, #322.
 *
 * DIFFERENTIALS. At the commit that adds this file, every link still answers
 * [ConnectionPriorityPolicy.Priority.STACK_DEFAULT], so the IMU case fails and
 * the HRM case passes. The fix is the commit after it.
 *
 * WHY THE IMU LINK ASKS. On field-42 one unit's link delivered about 44 frames
 * a second in 90 ms bursts against its partner's 99 in 31 ms bursts, and the
 * app had never asked the stack for anything but its default. That the default
 * priority caused it is a hypothesis for the field, not a finding. Both
 * WitMotion clients share one [ConnectionPriorityPolicy.Link.IMU] answer, so
 * role a and role b ask alike.
 *
 * WHY THE STRAP DOES NOT. A heart-rate strap notifies about once a second, so
 * a faster connection interval buys it nothing the export can use, and it
 * would add radio time on a phone already holding two IMU links. That is
 * reasoning, not a measurement.
 *
 * What is pinned is the RULE. Whether `GattClient` reads it, and what the stack
 * does with the request, is compile- and lint-gated only: `:core:ble` has no
 * test source set.
 */
class ConnectionPriorityPolicyTest {
    @Test
    fun `a WitMotion link asks for high priority`() {
        assertEquals(
            ConnectionPriorityPolicy.Priority.HIGH,
            ConnectionPriorityPolicy.priorityFor(ConnectionPriorityPolicy.Link.IMU),
            "an IMU link still leaves its connection priority to the stack",
        )
    }

    @Test
    fun `a heart-rate strap makes no request`() {
        assertEquals(
            ConnectionPriorityPolicy.Priority.STACK_DEFAULT,
            ConnectionPriorityPolicy.priorityFor(ConnectionPriorityPolicy.Link.HRM),
            "the strap asks for a priority it has no use for",
        )
    }
}
