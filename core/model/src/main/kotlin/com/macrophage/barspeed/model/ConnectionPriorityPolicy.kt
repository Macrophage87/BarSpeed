package com.macrophage.barspeed.model

/**
 * Which GATT links ask Android for a connection priority once they are up, and
 * which priority they ask for (#322).
 *
 * The decision lives here, in a module with tests, because `:core:ble` has no
 * test source set: the call `GattClient` makes from it is compile- and
 * lint-gated only, and the rule it reads is the part a test can hold.
 *
 * [Priority.STACK_DEFAULT] means the app makes NO request and the link stays
 * on whatever the phone's Bluetooth stack chose. It is not a request for
 * balanced priority. Leaving a link alone and asking for the default are two
 * different calls, and only the first is what the app did before #322.
 *
 * THE IMU LINK ASKS FOR HIGH. On field-42 one WitMotion unit's link delivered
 * about 44 frames a second, every arrival stamp 90 ms from the next, while its
 * partner delivered about 99 on a 31 ms rhythm, and the app had never asked
 * the stack for anything. That a default-priority link caused it is a
 * hypothesis, not a finding. Both WitMotion clients declare
 * [Link.IMU], so role a and role b ask alike.
 *
 * THE STRAP DOES NOT ASK. A heart-rate strap notifies about once a second, so
 * a faster connection interval buys the export nothing, and it would add
 * radio time beside two IMU links. That is reasoning, not a measurement.
 *
 * WHAT A REQUEST OBTAINS IS NOT KNOWN HERE. The stack or the unit may refuse
 * it or grant something else, and nothing in the app reads the result back.
 * The export's per-unit `burstSpacing_ms` and `deliveredRate_hz` are what a
 * reader can compare across sessions recorded before and after this rule.
 */
object ConnectionPriorityPolicy {
    /** The two kinds of GATT link the app holds. */
    enum class Link {
        /** A WitMotion WT901 unit, whichever sensor role it carries. */
        IMU,

        /** A heart-rate strap. */
        HRM,
    }

    /** What a link asks for once its services are discovered. */
    enum class Priority {
        /** `BluetoothGatt.CONNECTION_PRIORITY_HIGH`. */
        HIGH,

        /** No request at all; the stack's own choice stands. */
        STACK_DEFAULT,
    }

    /** The priority [link] asks for once its services are discovered. */
    fun priorityFor(link: Link): Priority = when (link) {
        Link.IMU -> Priority.HIGH
        Link.HRM -> Priority.STACK_DEFAULT
    }
}
