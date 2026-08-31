package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which instant a set's measured seconds are counted from.
 *
 * The rule is here because no test on the CI path can construct the view model
 * that produces it, and the figure it produces is the whole of what a hold
 * reports. What this file cannot check is
 * that `RecordViewModel` hands in the right instants; that half is compile- and
 * lint-gated only.
 */
class SetClockPolicyTest {
    private val tap = 1_700_000_000_000L

    /**
     * A set with no prep in front of it is measured from the tap, exactly as
     * every timed set was before preps reached them.
     */
    @Test
    fun `a set with no prep is measured from the tap`() {
        assertEquals(45, SetClockPolicy.heldSeconds(PrepCase.NONE, tap, clockStartedAtMs = null, tap + 45_000))
    }

    /**
     * A tempo'd set's prep runs into the cadence rather than into a clock, and
     * the seconds it reports are still counted from the tap. Handing in a clock
     * instant does not move it -- reds if the CUED branch starts reading
     * `clockStartedAtMs`.
     */
    @Test
    fun `a cued set is measured from the tap even when a clock instant is supplied`() {
        assertEquals(
            45,
            SetClockPolicy.heldSeconds(PrepCase.CUED, tap, clockStartedAtMs = tap + 5_000, tap + 45_000),
        )
    }

    /**
     * The whole point: on a timed set the prep is not part of the hold.
     *
     * Reds if the TIMED branch is changed to measure from `tappedAtMs` -- the
     * same forty-five second hang then reports 55.
     */
    @Test
    fun `a timed set is measured from its clock start, so the prep is not in the figure`() {
        val clockStart = tap + 10_000
        assertEquals(45, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, clockStart, clockStart + 45_000))
        assertEquals(55, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, tap, clockStart + 45_000))
    }

    /**
     * A hold ended while its prep was still running never began, so it lasted no
     * time at all. Zero here is measured rather than absent: the lifter stopped
     * before the clock started.
     */
    @Test
    fun `a timed set ended during its prep held for no time at all`() {
        assertEquals(0, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, clockStartedAtMs = null, tap + 3_000))
    }

    /**
     * Part seconds are dropped rather than rounded, which is what the app did
     * before this function existed.
     */
    @Test
    fun `a part second is dropped rather than rounded up`() {
        assertEquals(45, SetClockPolicy.heldSeconds(PrepCase.NONE, tap, null, tap + 45_999))
    }

    /**
     * A hold the lifter puts down before its target reports what it actually
     * lasted, and nothing rounds it up towards the prescription.
     *
     * Pinned here ahead of #168, which makes a hold END at its target. The
     * short set is the case that must not move: auto-end fires at the planned
     * boundary and never before it, so a 60 s plank abandoned at 30 s is 30 s
     * on every path -- and 30 is what the shortfall rule then judges against
     * the prescription. Asserting it before the auto-end exists is what makes
     * the assertion evidence rather than a description of the new code.
     *
     * Reds if the TIMED branch ever clamps, floors or substitutes the
     * prescription for the measured figure.
     */
    @Test
    fun `a timed set stopped short of its target reports what it actually lasted`() {
        val clockStart = tap + 10_000
        assertEquals(30, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, clockStart, clockStart + 30_000))
        assertEquals(1, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, clockStart, clockStart + 1_000))
    }

    /**
     * The function knows nothing about the prescription and takes no argument
     * carrying one, which is the property #168 relies on: the recorded figure
     * for an auto-ended set is decided one layer up, by the policy that owns
     * the planned-end instant, and this one keeps reporting the measurement.
     *
     * Stated as a measurement that runs PAST a plausible target rather than as
     * a comment, so it reds if a cap is ever added here instead of there.
     */
    @Test
    fun `a hold measured past any target is still reported as measured`() {
        val clockStart = tap + 10_000
        assertEquals(67, SetClockPolicy.heldSeconds(PrepCase.TIMED, tap, clockStart, clockStart + 67_000))
    }
}
