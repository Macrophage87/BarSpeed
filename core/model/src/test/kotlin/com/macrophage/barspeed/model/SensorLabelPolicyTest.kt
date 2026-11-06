package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** UX review 2026-11-06, ble-devices F3: A/B labels without the two manual taps. */
class SensorLabelPolicyTest {
    private val x = "AA:BB:CC:DD:00:01"
    private val y = "AA:BB:CC:DD:00:02"
    private val z = "AA:BB:CC:DD:00:03"

    /** What [DualSensorSetup.step] reads after the writes land. */
    private fun after(roles: Map<String, SensorRole>, writes: Map<String, SensorRole>) = roles + writes

    @Test
    fun `two unlabelled units get A for the first paired and B for the second`() {
        val writes = SensorLabelPolicy.autoAssign(listOf(x, y), emptyMap())
        assertEquals(mapOf(x to SensorRole.A, y to SensorRole.B), writes)
        assertEquals(DualSetupStep.READY, DualSensorSetup.step(listOf(x, y), writes))
    }

    @Test
    fun `a second unit takes whichever label the first one left`() {
        assertEquals(mapOf(y to SensorRole.A), SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(x to SensorRole.B)))
        assertEquals(mapOf(x to SensorRole.B), SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(y to SensorRole.A)))
    }

    @Test
    fun `nothing is written for one unit, three units or a labelled pair`() {
        assertEquals(emptyMap(), SensorLabelPolicy.autoAssign(listOf(x), emptyMap()))
        assertEquals(emptyMap(), SensorLabelPolicy.autoAssign(listOf(x, y, z), emptyMap()))
        assertEquals(
            emptyMap(),
            SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(x to SensorRole.B, y to SensorRole.A)),
        )
    }

    @Test
    fun `a collision keeps the earlier-paired unit's label and gives the later one the other`() {
        assertEquals(
            mapOf(y to SensorRole.B),
            SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(x to SensorRole.A, y to SensorRole.A)),
        )
        assertEquals(
            mapOf(y to SensorRole.A),
            SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(x to SensorRole.B, y to SensorRole.B)),
        )
    }

    /**
     * Whole-release review: X=A, Y=B; forget Y; pair Z (gets B); forget X;
     * re-pair Y, which still carried its stale B. Both paired units read B,
     * `DualSensorSetup` reported the roles colliding and only one stream was
     * recorded. Re-pairing puts Y last in the registry, so Z keeps B and Y
     * takes A.
     */
    @Test
    fun `re-pairing a unit whose stale label collides relabels it`() {
        var roles = mapOf(x to SensorRole.A, y to SensorRole.B)
        roles = roles + SensorLabelPolicy.autoAssign(listOf(x, z), roles) // Y forgotten, Z paired
        assertEquals(SensorRole.B, roles[z])
        roles = roles + SensorLabelPolicy.autoAssign(listOf(z, y), roles) // X forgotten, Y re-paired
        assertEquals(mapOf(z to SensorRole.B, y to SensorRole.A), roles.filterKeys { it in setOf(z, y) })
        assertEquals(DualSetupStep.READY, DualSensorSetup.step(listOf(z, y), roles))
    }

    @Test
    fun `a stale label on a forgotten unit is ignored`() {
        // z was A and has been forgotten; its label survives in settings.
        val writes = SensorLabelPolicy.autoAssign(listOf(x, y), mapOf(z to SensorRole.A))
        assertEquals(mapOf(x to SensorRole.A, y to SensorRole.B), writes)
    }

    @Test
    fun `tapping the label a unit already has swaps the pair`() {
        val roles = mapOf(x to SensorRole.A, y to SensorRole.B)
        val writes = SensorLabelPolicy.onChipTap(x, SensorRole.A, listOf(x, y), roles)
        assertEquals(mapOf(x to SensorRole.B, y to SensorRole.A), writes)
    }

    @Test
    fun `tapping the other unit's label swaps the pair too`() {
        val roles = mapOf(x to SensorRole.A, y to SensorRole.B)
        val writes = SensorLabelPolicy.onChipTap(x, SensorRole.B, listOf(x, y), roles)
        assertEquals(mapOf(x to SensorRole.B, y to SensorRole.A), writes)
    }

    @Test
    fun `no tap on a pair can leave the two carrying one label`() {
        val starts =
            listOf(
                emptyMap(),
                mapOf(x to SensorRole.A),
                mapOf(y to SensorRole.A),
                mapOf(x to SensorRole.A, y to SensorRole.B),
                mapOf(x to SensorRole.A, y to SensorRole.A),
                mapOf(x to SensorRole.B, y to SensorRole.B),
            )
        for (roles in starts) {
            for (unit in listOf(x, y)) {
                for (tapped in SensorRole.entries) {
                    val next = after(roles, SensorLabelPolicy.onChipTap(unit, tapped, listOf(x, y), roles))
                    // Every tap on a pair either labels both apart, or leaves
                    // exactly one unlabelled for autoAssign to finish.
                    val finished = after(next, SensorLabelPolicy.autoAssign(listOf(x, y), next))
                    assertEquals(
                        DualSetupStep.READY,
                        DualSensorSetup.step(listOf(x, y), finished),
                        "start $roles, tap $tapped on $unit",
                    )
                }
            }
        }
    }

    @Test
    fun `taking a label from an unlabelled unit hands the partner the other one`() {
        val writes = SensorLabelPolicy.onChipTap(x, SensorRole.A, listOf(x, y), mapOf(y to SensorRole.A))
        assertEquals(mapOf(x to SensorRole.A, y to SensorRole.B), writes)
    }
}
