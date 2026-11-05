package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which declarations `SetAnalyzer.repMetrics` publishes power under. Issue #116.
 *
 * The gate is `loadKg > 0 && concentricUp && !sensorInverted`, and the owner's
 * ruling on #116 is to KEEP it. Its KDoc was rewritten to say what each clause
 * excludes and why; these pins hold the behaviour that KDoc describes, clause
 * by clause, so a reader can check the prose against something that runs.
 * Before this file nothing asserted that any declaration published NO power --
 * `PowerTravelRatioTest` only asserts that a direct-drive lift and a pulley
 * ratio publish some.
 *
 * Characterization, not targets: one synthetic stream, read under each
 * declaration, so the declaration is the only thing that differs. Nothing
 * here was measured on a machine.
 */
class PowerGateTest {
    private val fourReps = SyntheticSets.generate(
        List(4) { SyntheticSets.RepSpec(eccS = 3.0, bottomPauseS = 1.0, conS = 1.0, topPauseS = 1.5, romM = 0.6) },
    )

    private val driveUp = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private fun analyse(direction: LiftDirection, loadKg: Double? = 50.0) =
        SetAnalyzer.analyze(fourReps, direction, loadKg = loadKg)

    private fun assertPower(direction: LiftDirection, message: String) {
        val reps = analyse(direction).reps
        assertTrue(reps.isNotEmpty(), "$message: no rep resolved, so the pin proves nothing")
        reps.forEach {
            assertTrue(it.peakPowerW != null && it.meanConPowerW != null, "$message: rep ${it.index} has no power")
        }
    }

    private fun assertNoPower(direction: LiftDirection, message: String, loadKg: Double? = 50.0) {
        val reps = analyse(direction, loadKg).reps
        assertTrue(reps.isNotEmpty(), "$message: no rep resolved, so the pin proves nothing")
        assertEquals(List(reps.size) { null }, reps.map { it.peakPowerW }, "$message: peak power published")
        assertEquals(List(reps.size) { null }, reps.map { it.meanConPowerW }, "$message: mean power published")
    }

    @Test
    fun `a drive-up lift with a load publishes power`() {
        assertPower(driveUp, "drive up, no mount")
    }

    @Test
    fun `no load or a zero load publishes no power`() {
        assertNoPower(driveUp, "load absent", loadKg = null)
        assertNoPower(driveUp, "load zero", loadKg = 0.0)
    }

    @Test
    fun `a drive that moves down publishes no power`() {
        assertNoPower(driveUp.copy(concentricUp = false), "drive down, no mount")
        // The stack-mounted drive-down shape -- a pushdown, a pulldown or a
        // leg curl read off the stack -- is excluded by this clause as well.
        assertNoPower(
            driveUp.copy(concentricUp = false, sensorOnStack = true, sensorInverted = true),
            "drive down, on the stack, inverted",
        )
    }

    @Test
    fun `an inverted mount under a drive that moves up publishes no power`() {
        assertNoPower(driveUp.copy(sensorInverted = true), "drive up, inverted")
    }

    @Test
    fun `a stack mount that moves with the drive and a pulley ratio both publish power`() {
        // Neither is a gate term: on the conservation argument the KDoc gives,
        // both are the lifter's output measured at the stack (#26).
        assertPower(driveUp.copy(sensorOnStack = true), "drive up, on the stack, not inverted")
        assertPower(driveUp.copy(travelRatio = 2.0), "drive up, 2:1")
    }
}
