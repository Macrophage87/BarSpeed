package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which physical unit is behind each sensor role, issue #260.
 *
 * Two decisions, both pure and both here rather than in `:app`: what the export
 * may state about a role's unit ([SensorCapturePolicy.unitAddresses]) and what
 * the two screens say about it ([DualSensorSetup.rolesLine]). The screens
 * themselves are Compose and nothing in this repository can run them, so
 * lifting the phrase out is what makes the decision testable at all.
 *
 * The addresses are WT901-shaped but invented. Nothing here has met a sensor.
 */
class SensorUnitIdentityPolicyTest {
    private val unitA = "C0:82:2D:8A:1D:3F"
    private val unitB = "C0:82:2D:8A:0C:7A"
    private val bothLabelled = mapOf(unitA to SensorRole.A, unitB to SensorRole.B)

    // ---- what the export may state -----------------------------------------

    /** A dual set names both units, keyed by the role each one carried. */
    @Test
    fun `a dual set names the unit behind each role`() {
        assertEquals(
            mapOf(SensorRole.A to unitA, SensorRole.B to unitB),
            SensorCapturePolicy.unitAddresses(listOf(SensorRole.A, SensorRole.B), bothLabelled),
        )
    }

    /**
     * The address is published exactly as the pairing store holds it.
     *
     * It is an identity, not a display string: uppercasing, trimming or
     * re-punctuating it here would publish a token no other document in the app
     * uses. `DevicePairingPolicy.unitTag` is where a form fit for a screen is
     * decided.
     */
    @Test
    fun `the address is published verbatim`() {
        val odd = "c0:82:2d:8a:1d:3f"
        assertEquals(
            mapOf(SensorRole.A to odd),
            SensorCapturePolicy.unitAddresses(listOf(SensorRole.A), mapOf(odd to SensorRole.A)),
        )
    }

    /**
     * A label for a unit this set did not arm is not published.
     *
     * The reachable case, and it is the owner's: a set armed one sensor and
     * carries no role, while the pairing store still holds both labels. An
     * entry here would attach an address to a capture that carries no role.
     */
    @Test
    fun `a role the set did not arm is not named`() {
        assertEquals(
            mapOf(SensorRole.A to unitA),
            SensorCapturePolicy.unitAddresses(listOf(SensorRole.A), bothLabelled),
        )
        assertEquals(
            emptyMap(),
            SensorCapturePolicy.unitAddresses(emptyList(), bothLabelled),
        )
    }

    /**
     * A role two addresses claim names no unit at all.
     *
     * Reachable and permanent: forgetting the unit labelled A does not clear
     * its label, so pairing a replacement and labelling it A leaves two A
     * addresses in the store for good. Picking either would be a coin flip
     * published as an identity; role B is still named, because nothing about it
     * is ambiguous.
     */
    @Test
    fun `a role claimed by two units is omitted and the other role survives`() {
        val stale = "C0:82:2D:8A:FF:01"
        assertEquals(
            mapOf(SensorRole.B to unitB),
            SensorCapturePolicy.unitAddresses(
                listOf(SensorRole.A, SensorRole.B),
                bothLabelled + mapOf(stale to SensorRole.A),
            ),
        )
    }

    /** An unlabelled pairing store names nothing, and says so as an empty map. */
    @Test
    fun `nothing labelled names nothing`() {
        assertEquals(
            emptyMap(),
            SensorCapturePolicy.unitAddresses(listOf(SensorRole.A, SensorRole.B), emptyMap()),
        )
    }

    // ---- what the screens say ----------------------------------------------

    /** Both labels, each with the four characters that go on the sticker. */
    @Test
    fun `the screens name both units by their address tail`() {
        assertEquals(
            "A is 1D:3F, B is 0C:7A",
            DualSensorSetup.rolesLine(listOf(unitA, unitB), bothLabelled),
        )
    }

    /**
     * Two units whose addresses end the same way are drawn in full.
     *
     * A tag that is identical on both units is a discriminator that does not
     * discriminate, which is the one thing this line must not be.
     */
    @Test
    fun `colliding tails are drawn as whole addresses`() {
        val twin = "D4:22:2D:8A:1D:3F"
        assertEquals(
            "A is C0:82:2D:8A:1D:3F, B is D4:22:2D:8A:1D:3F",
            DualSensorSetup.rolesLine(listOf(unitA, twin), mapOf(unitA to SensorRole.A, twin to SensorRole.B)),
        )
    }

    /**
     * Nothing is said unless the setup is READY, which is [DualSensorSetup.step]'s
     * answer and not a second reading of it.
     *
     * One sensor is the ordinary setup and has no pair to name; a missing label
     * and a collision are what the card's own sentence is for; and a third
     * paired unit is a collision over a two-valued enum, which is the owner's
     * state whenever an old unit is still paired.
     */
    @Test
    fun `no line unless both labels name one unit each`() {
        assertNull(DualSensorSetup.rolesLine(emptyList(), emptyMap()), "no sensor")
        assertNull(DualSensorSetup.rolesLine(listOf(unitA), bothLabelled), "one sensor")
        assertNull(
            DualSensorSetup.rolesLine(listOf(unitA, unitB), mapOf(unitA to SensorRole.A)),
            "one of the two is unlabelled",
        )
        assertNull(
            DualSensorSetup.rolesLine(listOf(unitA, unitB), mapOf(unitA to SensorRole.A, unitB to SensorRole.A)),
            "both carry the same label",
        )
        assertNull(
            DualSensorSetup.rolesLine(
                listOf(unitA, unitB, "C0:82:2D:8A:FF:01"),
                bothLabelled + mapOf("C0:82:2D:8A:FF:01" to SensorRole.B),
            ),
            "a third paired unit collides",
        )
    }

    /**
     * A label left behind by a unit that is no longer paired does not appear.
     *
     * The same store-versus-store gap the export's omission rule turns on, seen
     * from the screen: the line reads the paired list, so a forgotten unit's
     * label cannot name a sensor that is not there.
     */
    @Test
    fun `a stale label for a forgotten unit is ignored`() {
        val forgotten = "C0:82:2D:8A:FF:01"
        assertEquals(
            "A is 1D:3F, B is 0C:7A",
            DualSensorSetup.rolesLine(
                listOf(unitA, unitB),
                bothLabelled + mapOf(forgotten to SensorRole.B),
            ),
        )
    }
}
