package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue 96, closed on a DRIVE-UP stack lift, and the limit of issue 223's seed.
 *
 * [GeometryFallbackTest] measured the cancellation on three drive-DOWN leg
 * curls: `concentricUp = false` and `sensorInverted = true` are two flips that
 * cancel, so leaving both at their defaults is the same analysis as declaring
 * both. What that could not say is whether the cancellation is a property of
 * leg curls or of the pair. Field-37 (v0.1.48, 2026-09-02) supplies the other
 * case: three assisted pull-up sets whose drive goes UP, performed with the
 * sensor on the assist stack and, owner-confirmed, declared in the plan as
 * bar-mounted. The captures are committed here as
 * `field-assistedpullup-3010-s37-set{08,09,10}.csv`, byte copies of
 * `set{08,09,10}_assisted_pull_up_imu-a.csv` from that session's export.
 *
 * ## What the numbers say
 *
 * - The pair cancels here too. Declaring the drive DOWN together with the
 *   stack inversion reproduces the all-defaults analysis bit for bit on a lift
 *   whose drive actually goes up. So the cancellation is a property of the two
 *   flags, not of the leg curl.
 * - Under a declared stack mount, leaving `concentricUp` at its default is the
 *   same analysis as writing `up` out. That is the invariant issue 96 asked
 *   for, and it holds. Nothing is structurally dropped from pairing.
 * - **Issue 223's seed default changes no number on these sets.**
 *   `sensorOnStack` only picks [LiftDirection.measuredPlane], which was
 *   already VERTICAL, so seeding it for `assisted_pull_up` leaves the analysis
 *   bit-identical to the bar declaration that shipped. The flag that would
 *   move these sets is `sensorInverted`, which nothing seeds and the plan did
 *   not declare.
 *
 * ## What these pins deliberately do NOT claim
 *
 * They do not say which configuration is RIGHT. Which way an assist carriage
 * travels relative to the lifter is a fact about the machine, and no committed
 * byte records it. The lifter's own counts were 5, 8 and 6; neither
 * configuration returns that triple, so the difference between them is not
 * settled by rep count either. That is a `[Field]` question, not a defect
 * these numbers establish.
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

    /** The same, plus issue 223's seeded stack mount and nothing else. */
    private val seededStack = LiftDirection(startsWith = StartPhase.CONCENTRIC, sensorOnStack = true)

    /** Stack mount with the inversion a counterweight carriage implies. */
    private val stackInverted = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** [stackInverted] with `concentricUp` written out instead of defaulted. */
    private val stackInvertedExplicitUp = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        sensorInverted = true,
        sensorOnStack = true,
    )

    /** Both flips of the cancelling pair, on a lift whose drive goes up. */
    private val downAndInverted = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    private fun batch(d: LiftDirection) = captures.map { SetAnalyzer.analyze(load(it), d, loadKg = 23.443564147942737) }

    private fun liveCount(capture: String, d: LiftDirection): Int {
        val tracker = StreamingSetTracker.forLift(d)
        var last = LiveSetState()
        load(capture).forEach { last = tracker.feed(it) }
        return last.repCount
    }

    private fun assertSameAnalysis(a: LiftDirection, b: LiftDirection, why: String) {
        batch(a).zip(batch(b)).forEachIndexed { i, (x, y) ->
            val name = captures[i]
            assertEquals(x.reps.size, y.reps.size, "$name: rep count ($why)")
            assertEquals(x.reps.map { it.romM }, y.reps.map { it.romM }, "$name: ROM per rep ($why)")
            assertEquals(
                x.reps.map { it.meanConVelMps },
                y.reps.map { it.meanConVelMps },
                "$name: mean concentric velocity per rep ($why)",
            )
            assertEquals(
                x.reps.count { it.eccS != null },
                y.reps.count { it.eccS != null },
                "$name: reps that resolved an eccentric ($why)",
            )
        }
        captures.forEach { assertEquals(liveCount(it, a), liveCount(it, b), "$it: live rep count ($why)") }
    }

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

    @Test
    fun `declaring the drive down with the stack inversion cancels back to the defaults`() {
        // The leg-curl finding, on a lift whose drive really does go up.
        assertSameAnalysis(downAndInverted, asShipped, "the two flips cancel")
    }

    @Test
    fun `concentricUp defaulted and written out are the same stack-mounted analysis`() {
        // Issue 96's invariant. It guards the DEFAULT VALUE of concentricUp:
        // flip that default to false and this pin is the one that reds.
        assertSameAnalysis(stackInverted, stackInvertedExplicitUp, "default against explicit up")
        assertEquals(listOf(7, 11, 4), batch(stackInverted).map { it.reps.size }, "stack-mounted and inverted")
    }

    @Test
    fun `seeding the stack mount alone changes no number on a vertical lift`() {
        // Issue 223 seeds sensorOnStack for assisted_pull_up. On a vertical
        // exercise that only re-picks an axis already vertical, so field-37's
        // six mis-declared sets would analyse identically under the seed.
        assertSameAnalysis(seededStack, asShipped, "sensorOnStack alone on a vertical lift")
        assertEquals(MovementPlane.VERTICAL, asShipped.measuredPlane, "already vertical without the seed")
        // sensorInverted is what would actually move them, and it is not seeded.
        assertNotEquals(
            batch(asShipped).map { it.reps.size },
            batch(stackInverted).map { it.reps.size },
            "the inversion is the load-bearing flag",
        )
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
