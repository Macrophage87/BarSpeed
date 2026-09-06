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
