package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What field-37's three assisted pull-up sets resolve to as they were declared.
 *
 * Field-37 (v0.1.48, 2026-09-02) recorded sets 8, 9 and 10 with the sensor on
 * an assist machine's counterweight stack, while the plan declared
 * `sensorOnStack: false` -- owner-confirmed, and the first field case where the
 * geometry declaration is KNOWN wrong rather than inferred. The captures are
 * committed here as `field-assistedpullup-3010-s37-set{08,09,10}.csv`, byte
 * copies of `set{08,09,10}_assisted_pull_up_imu-a.csv` from that export.
 *
 * This class pins what the SHIPPED declaration produces, so a later geometry
 * change is a visible diff against the numbers the lifter was actually shown.
 * It makes no claim about which declaration is correct.
 */
class StackMountGeometryTest {
    private fun load(n: String): List<ImuSample> = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n.csv")!!.readBytes().decodeToString(),
    )

    private val captures = listOf(
        "field-assistedpullup-3010-s37-set08",
        "field-assistedpullup-3010-s37-set09",
        "field-assistedpullup-3010-s37-set10",
    )

    /** Exactly what field-37's plan declared on these three sets. */
    private val asShipped = LiftDirection(startsWith = StartPhase.CONCENTRIC)

    private fun batch(d: LiftDirection) = captures.map { SetAnalyzer.analyze(load(it), d, loadKg = 23.443564147942737) }

    /**
     * Provenance: these are field-37's own bytes, not a re-encode.
     *
     * Sample counts come from that session's `meta.json` `sensors[0].samples`
     * for sets 8, 9 and 10; the first arrival timestamps are read from the
     * committed captures themselves and pinned so a re-encode or a truncation
     * cannot pass unnoticed.
     */
    @Test
    fun `the committed captures are field-37 sets 8 to 10`() {
        assertEquals(listOf(4552, 6196, 4060), captures.map { load(it).size }, "meta.json sensors[0].samples")
        assertEquals(
            listOf(1788342174835L, 1788342368576L, 1788342567324L),
            captures.map { load(it).first().timestampMs },
            "first arrival timestamp per capture",
        )
    }

    /**
     * The shipped bar declaration, reproduced to the published digit.
     *
     * `session.json` publishes `repMetrics` for set 8 in full (7 entries) and
     * for set 10 in full (5), and those are the values below. Set 9 is not
     * pinned per rep here because its export carries only four entries, fewer
     * than the analyzer returns at this tree.
     */
    @Test
    fun `the bar declaration reproduces field-37's published rep metrics`() {
        val a = batch(asShipped)
        assertEquals(listOf(7, 6, 5), a.map { it.reps.size }, "reps resolved, against 5, 8 and 6 counted by hand")
        assertEquals(
            listOf(0.259, 0.265, 0.197, 0.192, 0.107, 0.200, 0.878),
            a[0].reps.map { round3(it.romM) },
            "set 8 rom_m",
        )
        assertEquals(
            listOf(0.137, 0.119, 0.126, 0.144, 0.120, 0.153, 0.342),
            a[0].reps.map { round3(it.meanConVelMps) },
            "set 8 meanConVel_mps",
        )
        assertEquals(listOf(0.471, 0.330, 0.481, 0.334, 1.746), a[2].reps.map { round3(it.romM) }, "set 10 rom_m")
        assertEquals(
            listOf(0.243, 0.166, 0.238, 0.149, 0.522),
            a[2].reps.map { round3(it.meanConVelMps) },
            "set 10 meanConVel_mps",
        )
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
