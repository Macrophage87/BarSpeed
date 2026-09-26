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
 * different calls, and only the first is what the app has always done.
 *
 * AS IT STANDS every link answers [Priority.STACK_DEFAULT], which is the
 * behaviour the app has always had: no `requestConnectionPriority` call exists
 * anywhere in `:core:ble` or `:app`. This declares the seam before anything
 * reads a different answer from it.
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
        Link.IMU -> Priority.STACK_DEFAULT
        Link.HRM -> Priority.STACK_DEFAULT
    }
}
