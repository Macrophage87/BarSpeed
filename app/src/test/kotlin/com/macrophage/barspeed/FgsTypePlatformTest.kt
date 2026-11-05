package com.macrophage.barspeed

import android.content.pm.ServiceInfo
import com.macrophage.barspeed.model.FgsTypeChoice
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FgsTypeChoice.platformType] against the platform's own constants (#52).
 *
 * `RecordingService` hands `ServiceCompat.startForeground` the choice's
 * `platformType` and nothing else, so this is the hop between the policy's
 * answer and what API 34 enforces. `ForegroundServiceContractTest` in
 * `:core:model` holds the same pair as literals, because that module cannot
 * see `android.content.pm`; this file is the half that reads `ServiceInfo`.
 *
 * Runs on the JVM against `android.jar`'s stubs, and that is enough here:
 * both `FOREGROUND_SERVICE_TYPE_*` fields are compile-time constants, which
 * the compiler inlines, so nothing Android is executed or loaded. What this
 * cannot show is what a device does with the int.
 */
class FgsTypePlatformTest {
    @Test
    fun `connected device carries the platform's connectedDevice type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            FgsTypeChoice.CONNECTED_DEVICE.platformType,
        )
    }

    @Test
    fun `special use carries the platform's specialUse type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            FgsTypeChoice.SPECIAL_USE.platformType,
        )
    }
}
