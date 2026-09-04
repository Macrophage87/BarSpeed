package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ImuSample
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What [SetAnalyzer] publishes once [RepRefusal] is applied to the rep list,
 * and what it must go on publishing for the sets the rule does not touch.
 *
 * These are issue #125's differentials. Where [RepRefusal] exists and nothing
 * calls it, the four set-10 tests below fail --
 * `field-assistedpullup-3010-s37-set10` still publishes five detections and
 * still publishes 552.4 W from the one that is not a rep -- while the two
 * "is untouched" tests pass, as their names say they must.
 *
 * The set-10 figures are reached by a SECOND, independent route as well,
 * which is why they are quoted rather than merely expected: substituting the
 * last in-range reading for that capture's two out-of-range accelerometer
 * samples and re-running the unmodified analyzer produces four detections
 * carrying these same four ranges. `ArtefactRepTest` runs that substitution
 * and pins its result; [RepRefusal]'s KDoc argues why only the rule over the
 * REP LIST is what ships.
 */
class ArtefactRefusalWiringTest {
    private fun load(f: String): List<ImuSample> =
        ImuCsv.decode(javaClass.getResourceAsStream("/$f.csv")!!.readBytes().decodeToString())

    private val conFirst = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private fun analyse(f: String, loadKg: Double) =
        SetAnalyzer.analyze(load(f), conFirst, loadKg, SetTargets(), DspConfig(), emptyList())

    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    private val set01 = "field-ohp-3010-8rep-s37-set01"
    private val set03 = "field-ohp-prepinflated-s37-set03"
    private val set10 = "field-assistedpullup-3010-s37-set10"

    @Test
    fun `set 10 no longer publishes a detection with no eccentric partner and 4x the range`() {
        val reps = analyse(set10, 23.443564147942737).reps
        assertEquals(4, reps.size, "detections published")
        assertEquals(listOf(0.471, 0.33, 0.481, 0.334), reps.map { it.romM }, "rom_m per rep")
        assertEquals(listOf(0, 1, 2, 3), reps.map { it.index }, "and they are numbered from zero")
    }

    @Test
    fun `set 10's peak power falls from 552_4 W to the best of the reps it actually did`() {
        val reps = analyse(set10, 23.443564147942737).reps
        assertEquals(0.435, reps.maxOf { it.peakConVelMps }, "summary peakConVel_mps")
        assertEquals(101.6, reps.mapNotNull { it.peakPowerW }.maxOrNull(), "summary peakPower_w")
        assertEquals(0.404, round3(reps.map { it.romM }.average()), "summary meanRom_m")
        assertEquals(17.8, SetAnalyzer.romSpreadPct(reps), "summary romSpread_pct")
    }

    /**
     * Issue #126's withholding fired on this set because the phantom was the
     * fastest thing in it. With the phantom refused, the basis is re-derived
     * from the surviving reps and the figure publishes -- 38.7% best-to-last
     * over four reps, on a set the lifter ended for pace at 6 of 8.
     */
    @Test
    fun `set 10 publishes a measured velocity loss once the phantom is refused`() {
        val a = analyse(set10, 23.443564147942737)
        assertEquals(38.7, a.velocityLossPct, "velocityLoss_pct")
        assertEquals(VelocityLoss.MEASURED, VelocityLoss.of(a.reps).basis, "velocityLossBasis")
    }

    @Test
    fun `the analyzer's rep list is what the refusal rule keeps`() {
        val raw = analyse(set10, 23.443564147942737).reps
        assertSame(raw, RepRefusal.kept(raw), "nothing left for the rule to refuse")
    }

    /**
     * Sets 1 and 3 have nothing above the bound, so not one of their figures
     * moves. Both are analysed UNCUED -- so set 1 carries the detection its
     * archived `Done` would exclude, and the rule leaves that one alone too.
     * Its range ratio is 2.00x.
     */
    @Test
    fun `set 1 is untouched by the refusal rule`() {
        val a = analyse(set01, 20.411656650451594)
        assertEquals(11, a.reps.size, "detections")
        assertEquals(1.363, a.reps.maxOf { it.peakConVelMps }, "summary peakConVel_mps")
        assertEquals(332.2, a.reps.mapNotNull { it.peakPowerW }.maxOrNull(), "summary peakPower_w")
        assertEquals(0.619, round3(a.reps.map { it.romM }.average()), "summary meanRom_m")
        assertEquals(59.2, SetAnalyzer.romSpreadPct(a.reps), "summary romSpread_pct")
        assertEquals(48.7, a.velocityLossPct, "velocityLoss_pct")
        assertEquals(0, RepRefusal.refusedCount(a.reps), "and the rule ran and refused nothing")
    }

    @Test
    fun `set 3 is untouched by the refusal rule, 783_2 W and all`() {
        val a = analyse(set03, 22.67961850050177)
        assertEquals(11, a.reps.size, "detections")
        assertEquals(1.396, a.reps.maxOf { it.peakConVelMps }, "summary peakConVel_mps")
        assertEquals(783.2, a.reps.mapNotNull { it.peakPowerW }.maxOrNull(), "summary peakPower_w")
        assertEquals(0.698, round3(a.reps.map { it.romM }.average()), "summary meanRom_m")
        assertEquals(63.5, SetAnalyzer.romSpreadPct(a.reps), "summary romSpread_pct")
        assertEquals(67.0, a.velocityLossPct, "velocityLoss_pct")
        assertEquals(0, RepRefusal.refusedCount(a.reps), "and the rule ran and refused nothing")
    }
}
