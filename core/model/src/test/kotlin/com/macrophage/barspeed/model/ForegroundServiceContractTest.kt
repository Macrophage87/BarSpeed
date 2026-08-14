package com.macrophage.barspeed.model

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FgsTypeChoice] and `app/src/main/AndroidManifest.xml` are one contract in
 * two files, and only one half of it is checked by anything else. Android lint
 * reports a declared `foregroundServiceType` whose `FOREGROUND_SERVICE_*`
 * permission is missing, but nothing checks that a type the policy can *choose*
 * was ever declared. Narrow the manifest back to `connectedDevice` and lint
 * stays green while the platform is handed a type the service never declared —
 * which throws at the moment the lifter taps START SET, on the crash path this
 * whole change exists to close.
 *
 * An earlier version of this comment named that throw
 * `MissingForegroundServiceTypeException`. It is not: AOSP scopes that one to
 * "manifest attribute not set and the param is FOREGROUND_SERVICE_TYPE_MANIFEST".
 * The type-not-a-subset-of-the-manifest case is `IllegalArgumentException`,
 * which is a sibling of `IllegalStateException`, not a subclass, so it needs a
 * catch clause of its own; `RecordingService` grows one in the third commit on
 * this branch. Caught, the narrowing stops being a crash and becomes a
 * foreground service that silently never starts — still worth pinning, and this
 * test is still the only thing that detects the narrowing at build time.
 *
 * The manifest is read here, off the test classpath, rather than copied — the
 * same reason `SchemaContractTest` reads the published schemas. A copy drifts.
 * This is `app/src/main/AndroidManifest.xml`, not AGP's merged manifest, which
 * is what the APK is actually built from; what makes the two equivalent for
 * this assertion is that `app/src` holds exactly one manifest, there are no
 * flavour or buildType manifests and no `tools:node`, and merging can only add
 * a `<uses-permission>`, never remove one.
 */
class ForegroundServiceContractTest {
    /**
     * The `FOREGROUND_SERVICE_*` permission each type token requires from
     * API 34. Keyed by token so that adding an [FgsTypeChoice] member without
     * extending this map fails the exhaustiveness assertion below rather than
     * silently skipping the new type.
     */
    private val permissionForToken =
        mapOf(
            "connectedDevice" to "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "specialUse" to "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        )

    private val manifest: Element by lazy {
        val stream =
            javaClass.getResourceAsStream("/AndroidManifest.xml")
                ?: error("AndroidManifest.xml is not on the test classpath; see core/model/build.gradle.kts")
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun recordingService(): Element =
        elements("service").firstOrNull { it.getAttribute("android:name") == ".RecordingService" }
            ?: error("no <service android:name=\".RecordingService\"> in the manifest")

    private fun declaredTypes(): Set<String> = recordingService()
        .getAttribute("android:foregroundServiceType")
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun declaredPermissions(): Set<String> =
        elements("uses-permission").map { it.getAttribute("android:name") }.toSet()

    @Test
    fun `the manifest declares every foreground service type the policy can choose`() {
        val declared = declaredTypes()
        val chooseable = FgsTypeChoice.entries.map { it.manifestToken }.toSet()
        assertTrue(
            declared.containsAll(chooseable),
            "BlePermissionPolicy can return ${chooseable - declared}, which " +
                "RecordingService may not claim: android:foregroundServiceType declares $declared",
        )
    }

    @Test
    fun `the manifest holds the permission every declared foreground service type requires`() {
        val permissions = declaredPermissions()
        for (token in declaredTypes()) {
            val required =
                permissionForToken[token]
                    ?: error("foregroundServiceType token '$token' is undocumented in this test")
            assertTrue(
                required in permissions,
                "android:foregroundServiceType declares '$token' but the manifest does not <uses-permission> $required",
            )
        }
    }

    @Test
    fun `every FgsTypeChoice names a permission this test knows how to check`() {
        assertEquals(
            FgsTypeChoice.entries.map { it.manifestToken }.toSet(),
            permissionForToken.keys,
            "a new FgsTypeChoice member needs its FOREGROUND_SERVICE_* permission recorded here",
        )
    }
}
