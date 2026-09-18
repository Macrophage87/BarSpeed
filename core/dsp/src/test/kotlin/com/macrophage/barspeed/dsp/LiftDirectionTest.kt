package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan declares a lift's geometry and the DSP acts on it, so the hand-off
 * between them is load-bearing: every field dropped here is a declaration the
 * lifter made that the analysis silently ignores. An earlier version of
 * [liftDirection] passed only four of the six, which left the whole
 * horizontal-plane and stack-mounted path unreachable no matter what the plan
 * said.
 */
class LiftDirectionTest {
    @Test
    fun `carries every declared geometry field into the DSP`() {
        val seatedRowOffTheStack =
            ExerciseDef(
                id = "seated_row",
                displayName = "Seated row",
                startsWith = StartPhase.CONCENTRIC,
                concentricUp = false,
                sensorInverted = true,
                travelRatio = 2.0,
                horizontal = true,
                sensorOnStack = true,
            )

        val direction = seatedRowOffTheStack.liftDirection()

        assertEquals(StartPhase.CONCENTRIC, direction.startsWith)
        assertEquals(false, direction.concentricUp)
        assertEquals(true, direction.sensorInverted)
        assertEquals(2.0, direction.travelRatio)
        assertEquals(MovementPlane.HORIZONTAL, direction.plane)
        assertEquals(true, direction.sensorOnStack)
    }

    @Test
    fun `a horizontal lift is declared horizontal`() {
        val chestPress = ExerciseDef(id = "chest_press", displayName = "Chest press", horizontal = true)
        assertEquals(MovementPlane.HORIZONTAL, chestPress.liftDirection().plane)
    }

    @Test
    fun `a vertical lift stays vertical`() {
        val squat = ExerciseDef(id = "back_squat", displayName = "Back squat")
        assertEquals(MovementPlane.VERTICAL, squat.liftDirection().plane)
        assertEquals(false, squat.liftDirection().sensorOnStack)
    }

    /**
     * The stack travels up and down however the lifter moves, so a seated row
     * measured off the stack is a VERTICAL measurement of a horizontal lift.
     */
    @Test
    fun `a stack-mounted sensor on a horizontal lift is measured vertically`() {
        val rowOffStack =
            ExerciseDef(id = "cable_row", displayName = "Cable row", horizontal = true, sensorOnStack = true)
        val direction = rowOffStack.liftDirection()
        assertEquals(MovementPlane.HORIZONTAL, direction.plane)
        assertEquals(MovementPlane.VERTICAL, direction.measuredPlane)
    }

    /** A handle-mounted sensor on a horizontal lift travels with the load. */
    @Test
    fun `a handle-mounted sensor on a horizontal lift is measured horizontally`() {
        val rowOffHandle =
            ExerciseDef(id = "cable_row", displayName = "Cable row", horizontal = true, sensorOnStack = false)
        assertEquals(MovementPlane.HORIZONTAL, rowOffHandle.liftDirection().measuredPlane)
    }

    /** Horizontal work has no "up", so the drive is always the positive direction. */
    @Test
    fun `horizontal work always drives positive`() {
        val row = ExerciseDef(id = "seated_row", displayName = "Seated row", horizontal = true, concentricUp = false)
        assertTrue(row.liftDirection().driveIsPositive)
    }

    /**
     * Which of the six declared fields describe a MOUNT rather than the lift
     * (#247). Each of the three is asserted alone, because a rule that
     * happened to read only one of them would pass a test that set all three.
     */
    @Test
    fun `only the three mount terms make a direction mount-specific`() {
        assertTrue(LiftDirection(sensorOnStack = true).mountSpecific, "a stack mount")
        assertTrue(LiftDirection(sensorInverted = true).mountSpecific, "the other end of a cable")
        assertTrue(LiftDirection(travelRatio = 2.0).mountSpecific, "a 2:1 pulley")
        assertTrue(LiftDirection(travelRatio = 0.5).mountSpecific, "a ratio under one is a mount too")
    }

    /**
     * The lift's own three fields do NOT, whatever they are set to. Which
     * phase opens a rep, which way the lifter drives and which plane the
     * lifter works in are unchanged by clipping a unit somewhere else.
     */
    @Test
    fun `the lift's own fields leave a direction mount-free`() {
        assertFalse(LiftDirection().mountSpecific, "the default declares no mount")
        assertFalse(LiftDirection(startsWith = StartPhase.CONCENTRIC).mountSpecific, "a concentric-first lift")
        assertFalse(LiftDirection(concentricUp = false).mountSpecific, "a lift whose drive goes down")
        assertFalse(LiftDirection(plane = MovementPlane.HORIZONTAL).mountSpecific, "horizontal work")
        assertFalse(LiftDirection(travelRatio = 1.0).mountSpecific, "the type default ratio")
    }

    /** A dumbbell press: nothing about it names a mount, so a partner unit reads it unchanged. */
    @Test
    fun `an ordinary free-weight declaration is mount-free`() {
        val press = ExerciseDef(id = "dumbbell_incline_press", displayName = "DB incline press")
        assertFalse(press.liftDirection().mountSpecific)
    }

    /**
     * Every COMBINATION of the three, not just each alone (#280 c0).
     *
     * The test above asserts each term on its own, which a rule reading one
     * term would also pass; this asserts the eight-row truth table, which one
     * reading the wrong pair of them would not. It is a characterization pin:
     * the expression is about to be split so a caller can ask about the stack
     * term separately from the other two, and the table is what that split
     * must leave alone.
     */
    @Test
    fun `the three mount terms combine as an or`() {
        val rows =
            listOf(
                Triple(false, false, 1.0) to false,
                Triple(true, false, 1.0) to true,
                Triple(false, true, 1.0) to true,
                Triple(false, false, 2.0) to true,
                Triple(true, true, 1.0) to true,
                Triple(true, false, 2.0) to true,
                Triple(false, true, 2.0) to true,
                Triple(true, true, 2.0) to true,
            )
        for ((terms, expected) in rows) {
            val (onStack, inverted, ratio) = terms
            val direction =
                LiftDirection(sensorOnStack = onStack, sensorInverted = inverted, travelRatio = ratio)
            assertEquals(expected, direction.mountSpecific, "onStack=$onStack inverted=$inverted ratio=$ratio")
        }
    }

    /**
     * What is left of a BUILT-IN stack declaration once the stack term goes
     * (#280 c0).
     *
     * The live readout's repair depends on this and on nothing else: where a
     * unit measurably did NOT ride the stack, the remaining declaration has to
     * be a statement about the LIFT before it can be applied to that unit's
     * stream. It is here for the twelve seed ids `ExerciseDef.ridesStack`
     * carries, none of which declares `sensorInverted` or a travel ratio, so
     * dropping the stack term leaves them mount-free. A PLAN that declares
     * either of the other two does not, which is the second row.
     */
    @Test
    fun `dropping the stack term leaves a built-in cable declaration mount-free`() {
        val pulldown = ExerciseDef(id = "lat_pulldown", displayName = "Lat pulldown", sensorOnStack = true)
        assertTrue(pulldown.liftDirection().mountSpecific, "as declared")
        assertFalse(
            pulldown.liftDirection().copy(sensorOnStack = false).mountSpecific,
            "the stack term was the only mount term it had",
        )
        val inverted =
            ExerciseDef(
                id = "lat_pulldown",
                displayName = "Lat pulldown",
                sensorOnStack = true,
                sensorInverted = true,
            )
        assertTrue(
            inverted.liftDirection().copy(sensorOnStack = false).mountSpecific,
            "a declared inversion is still a mount nothing measured",
        )
    }

    /**
     * The two halves of [LiftDirection.mountSpecific], split so a caller
     * holding a MEASUREMENT for one of them can ask about the other (#280).
     *
     * `StackRollSignature` reads a stream's roll and answers for the stack term
     * alone; nothing anywhere reads whether a unit moved opposite to the load
     * or through a pulley. So the split is not cosmetic -- it is the line
     * between what can be measured and what can only be declared -- and this
     * asserts the identity the refactor must hold to.
     */
    @Test
    fun `the stack term and the other two make up mount-specific between them`() {
        for (onStack in listOf(false, true)) {
            for (inverted in listOf(false, true)) {
                for (ratio in listOf(1.0, 2.0)) {
                    val d = LiftDirection(sensorOnStack = onStack, sensorInverted = inverted, travelRatio = ratio)
                    assertEquals(inverted || ratio != 1.0, d.mountSpecificBesidesStack, "$onStack/$inverted/$ratio")
                    assertEquals(onStack || d.mountSpecificBesidesStack, d.mountSpecific, "$onStack/$inverted/$ratio")
                }
            }
        }
    }

    /**
     * Replacing the stack term with what a stream MEASURED, and nothing else
     * (#280).
     *
     * `true` on a stack-declared lift returns the declaration itself -- the
     * measurement confirmed it -- and `false` returns it with that one term
     * dropped, which on a built-in cable id leaves a mount-free geometry. The
     * five fields that describe the LIFT are untouched in both directions,
     * which is asserted rather than assumed: a repair that quietly reset the
     * start phase or the drive direction would swap a pulldown's concentric and
     * eccentric while looking like it had only touched a mount.
     */
    @Test
    fun `a measured mount replaces the stack term and nothing else`() {
        val declared =
            LiftDirection(
                startsWith = StartPhase.CONCENTRIC,
                concentricUp = false,
                plane = MovementPlane.VERTICAL,
                sensorOnStack = true,
            )
        assertEquals(declared, declared.forMeasuredMount(onStack = true), "the measurement confirmed it")
        val measuredOff = declared.forMeasuredMount(onStack = false)
        assertFalse(measuredOff.sensorOnStack)
        assertFalse(measuredOff.mountSpecific, "the stack term was the only mount term")
        assertEquals(declared.startsWith, measuredOff.startsWith)
        assertEquals(declared.concentricUp, measuredOff.concentricUp)
        assertEquals(declared.plane, measuredOff.plane)
        assertEquals(declared.sensorInverted, measuredOff.sensorInverted)
        assertEquals(declared.travelRatio, measuredOff.travelRatio)
        val free = LiftDirection()
        assertEquals(free, free.forMeasuredMount(onStack = false), "nothing to replace")
        assertTrue(free.forMeasuredMount(onStack = true).sensorOnStack, "and it works the other way")
    }

    /** A lat pulldown as field-38 declared it: stack-mounted and inverted, so both terms fire. */
    @Test
    fun `a stack-declared pulldown is mount-specific`() {
        val pulldown =
            ExerciseDef(
                id = "lat_pulldown",
                displayName = "Lat pulldown",
                startsWith = StartPhase.CONCENTRIC,
                concentricUp = false,
                sensorInverted = true,
                sensorOnStack = true,
            )
        assertTrue(pulldown.liftDirection().mountSpecific)
    }
}
