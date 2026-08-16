package com.macrophage.barspeed.model

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runtime permissions this app asks for, as `app/src/main/AndroidManifest.xml`
 * declares them.
 *
 * A runtime permission that is requested but not declared is denied forever, with
 * no dialog and no error anywhere: exactly the dead end issue #41 is about,
 * arriving by a different road. Nothing in this build detects it. Android lint
 * reports the reverse pairing (a declared `foregroundServiceType` whose
 * `FOREGROUND_SERVICE_*` permission is missing) but not this one — review deleted
 * `BLUETOOTH_SCAN` from the manifest and `:app:lintDebug` stayed BUILD SUCCESSFUL
 * with zero issues naming it.
 *
 * This commit pins only what the manifest says today, in literals. The policy
 * functions that will be checked against these facts do not exist yet; they arrive
 * in the next commit and are cross-checked here without these assertions changing,
 * so the fixed point is set before there is anything to bend it towards.
 *
 * What this can prove is narrow and worth stating: it compares the manifest against
 * Kotlin, so it catches the two files diverging and nothing else. An identical typo
 * in both passes every gate in this repository. Policy == manifest, never
 * policy == platform.
 *
 * The manifest is read off the test classpath rather than copied, for the reason
 * [ForegroundServiceContractTest] reads it — a copy drifts. It is wired in by
 * `core/model/build.gradle.kts`, whose include filter already admits
 * `AndroidManifest.xml`.
 */
class BlePermissionManifestTest {
    private val manifest: Element by lazy {
        val stream =
            javaClass.getResourceAsStream("/AndroidManifest.xml")
                ?: error("AndroidManifest.xml is not on the test classpath; see core/model/build.gradle.kts")
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private fun usesPermissions(): List<Element> {
        val nodes = manifest.getElementsByTagName("uses-permission")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun declaration(name: String): Element =
        usesPermissions().firstOrNull { it.getAttribute("android:name") == name }
            ?: error("the manifest has no <uses-permission android:name=\"$name\">")

    /**
     * The `android:maxSdkVersion` cap on [name], or null where it is uncapped.
     *
     * `getAttribute` returns "" for an absent attribute rather than null, and ""
     * is not the same fact as a cap: an uncapped permission is requestable at
     * every level, a capped one is silently ignored above the cap.
     */
    private fun maxSdkVersion(name: String): Int? =
        declaration(name).getAttribute("android:maxSdkVersion").takeIf { it.isNotEmpty() }?.toInt()

    @Test
    fun `the manifest declares every permission this app requests at runtime`() {
        val declared = usesPermissions().map { it.getAttribute("android:name") }.toSet()
        for (permission in REQUESTED) {
            assertTrue(
                permission in declared,
                "MainActivity requests $permission at runtime and the manifest does not declare it, " +
                    "so the platform denies it with no dialog",
            )
        }
    }

    @Test
    fun `the bluetooth runtime permissions are declared with no maxSdkVersion`() {
        // Both are requested from API 31 up with no upper bound. A cap here
        // would silently stop the request working above it.
        assertEquals(null, maxSdkVersion("android.permission.BLUETOOTH_SCAN"))
        assertEquals(null, maxSdkVersion("android.permission.BLUETOOTH_CONNECT"))
    }

    @Test
    fun `location is declared only through API 30`() {
        // The cap and the SDK band the app asks on are one contract in two
        // files. Requesting fine location above 30 is a no-op against this
        // manifest; the next commit pins the policy to the same boundary.
        assertEquals(30, maxSdkVersion("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `the notification permission is declared with no maxSdkVersion`() {
        assertEquals(null, maxSdkVersion("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `the manifest declares every permission the policy asks for and decides on`() {
        val declared = usesPermissions().map { it.getAttribute("android:name") }.toSet()
        for (sdk in SDK_BAND) {
            for (permission in BlePermissionPolicy.runtimePermissions(sdk)) {
                assertTrue(
                    permission in declared,
                    "runtimePermissions($sdk) asks for $permission, which the manifest does not declare, " +
                        "so the platform denies it with no dialog",
                )
            }
            for (permission in BlePermissionPolicy.blePermissions(sdk)) {
                assertTrue(
                    permission in declared,
                    "blePermissions($sdk) reads the grant state of $permission, which the manifest " +
                        "does not declare, so it reads denied forever",
                )
            }
        }
    }

    @Test
    fun `the policy asks on no SDK level above a permission's maxSdkVersion cap`() {
        // The direction that matters. A request above the cap is silently
        // ignored -- no dialog, no error, no grant -- and the grant check
        // beside it then reads denied forever. The opposite direction, a
        // declaration wider than the band asked on, costs nothing and is not
        // asserted.
        for (sdk in SDK_BAND) {
            for (permission in BlePermissionPolicy.runtimePermissions(sdk)) {
                val cap = maxSdkVersion(permission) ?: continue
                assertTrue(
                    sdk <= cap,
                    "the policy asks for $permission at API $sdk, above its android:maxSdkVersion=$cap",
                )
            }
        }
    }

    private companion object {
        /**
         * What `MainActivity.requestBlePermissions` passes to the launcher today,
         * across API 26-35. Literals, deliberately: this is the characterization,
         * and it is what the policy added next has to keep producing.
         */
        val REQUESTED =
            listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACCESS_FINE_LOCATION",
            )

        /**
         * `minSdk = 26` and `compileSdk = 35` (`app/build.gradle.kts:17,21` at
         * 5606250eb22039a6625b747a6d0c1d5b7364ea9a). Written out so that raising
         * either is a visible decision here rather than a silent widening of
         * what these two assertions cover.
         */
        val SDK_BAND = 26..35
    }
}
