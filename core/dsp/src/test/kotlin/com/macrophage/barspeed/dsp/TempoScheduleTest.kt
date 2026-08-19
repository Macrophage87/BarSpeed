package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [TempoSchedule.of] does with a tempo string, pinned.
 *
 * It had no direct test of any kind before this. It nevertheless decides which
 * digit is performed when, which is the whole of issue 106: the guided
 * metronome puts its floor on the pause following the SECOND stroke in
 * performance order, and which digit that is comes from here.
 *
 * The face pull is the case worth understanding. Tempo 2011 ends in a 1, so
 * paired with an ordinary vertical eccentric-first lift the closing pause is a
 * full second and nothing is added. Paired with the face pull -- horizontal,
 * concentric-first -- the reordering puts the BOTTOM pause last, which is 0,
 * and the floor applies. The outcome is a property of the (tempo, lift) PAIR,
 * never of the tempo string alone, and a test keyed on the notation would miss
 * it entirely.
 *
 * Six configurations reach different branches: vertical crossed with
 * concentricUp and with the starting phase, plus horizontal crossed with the
 * starting phase, since horizontal reads by phase and ignores concentricUp.
 * All six are below.
 */
class TempoScheduleTest {
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private val legCurl = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = false,
        sensorInverted = true,
        sensorOnStack = true,
    )

    private val facePull = LiftDirection(
        startsWith = StartPhase.CONCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    private val legPress = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    @Test
    fun `a vertical eccentric-first lift performs the digits in written order`() {
        val s = TempoSchedule.of(Tempo.parse("3010"), benchPress)
        assertEquals("DOWN", s.first.label)
        assertEquals(3.0, s.first.seconds)
        assertEquals(false, s.first.isConcentric)
        assertEquals(0.0, s.pauseAfterFirstS)
        assertEquals("UP", s.second.label)
        assertEquals(1.0, s.second.seconds)
        assertEquals(true, s.second.isConcentric)
        assertEquals(0.0, s.pauseAfterSecondS)
        assertEquals(3.0, s.eccentricS)
        assertEquals(1.0, s.concentricS)
    }

    @Test
    fun `a drive-down lift reads the digits positionally, so digit 1 is its concentric`() {
        // Leg curl 1030: the pull DOWN is the drive and takes 1 s; the return
        // up is the eccentric and takes 3 s.
        val s = TempoSchedule.of(Tempo.parse("1030"), legCurl)
        assertEquals("DOWN", s.first.label)
        assertEquals(1.0, s.first.seconds)
        assertEquals(true, s.first.isConcentric)
        assertEquals("UP", s.second.label)
        assertEquals(3.0, s.second.seconds)
        assertEquals(3.0, s.eccentricS)
        assertEquals(1.0, s.concentricS)
        assertEquals(0.0, s.pauseAfterSecondS)
    }

    @Test
    fun `horizontal work is read by phase and called DRIVE and RETURN`() {
        // Face pull 2011. Horizontal, so digit 1 is the eccentric by phase
        // rather than the down stroke by position; concentric-first, so the
        // drive is performed first and the pause that lands LAST is the bottom
        // pause -- digit 2, which is zero.
        val s = TempoSchedule.of(Tempo.parse("2011"), facePull)
        assertEquals("DRIVE", s.first.label)
        assertEquals(1.0, s.first.seconds)
        assertEquals(true, s.first.isConcentric)
        assertEquals(1.0, s.pauseAfterFirstS, "the TOP pause is performed first here")
        assertEquals("RETURN", s.second.label)
        assertEquals(2.0, s.second.seconds)
        assertEquals(0.0, s.pauseAfterSecondS, "the BOTTOM pause lands last, and it is zero")
    }

    @Test
    fun `the same tempo string closes on a different digit for a different lift`() {
        // The pairing that makes issue 106 impossible to reason about from the
        // notation alone.
        assertEquals(1.0, TempoSchedule.of(Tempo.parse("2011"), benchPress).pauseAfterSecondS)
        assertEquals(0.0, TempoSchedule.of(Tempo.parse("2011"), facePull).pauseAfterSecondS)
    }

    @Test
    fun `a concentric-first vertical press performs the up stroke first`() {
        val s = TempoSchedule.of(Tempo.parse("2010"), legPress)
        assertEquals("UP", s.first.label)
        assertEquals(1.0, s.first.seconds)
        assertEquals(0.0, s.pauseAfterFirstS)
        assertEquals("DOWN", s.second.label)
        assertEquals(2.0, s.second.seconds)
        assertEquals(0.0, s.pauseAfterSecondS)
    }

    private val latPulldownEccFirst = LiftDirection(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = false,
        sensorOnStack = true,
    )

    private val chestPressEccFirst = LiftDirection(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
        sensorOnStack = true,
    )

    @Test
    fun `a drive-down lift started on its eccentric performs the up stroke first`() {
        // Vertical, concentricUp false, ECCENTRIC-first: the lifter lets the bar
        // rise first, and the digits swap so the UP stroke takes digit 3.
        val s = TempoSchedule.of(Tempo.parse("3010"), latPulldownEccFirst)
        assertEquals("UP", s.first.label)
        assertEquals(1.0, s.first.seconds, "digit 3 leads")
        assertEquals(false, s.first.isConcentric, "and it is the eccentric")
        assertEquals(0.0, s.pauseAfterFirstS)
        assertEquals("DOWN", s.second.label)
        assertEquals(3.0, s.second.seconds)
        assertEquals(true, s.second.isConcentric)
        assertEquals(0.0, s.pauseAfterSecondS)
    }

    @Test
    fun `horizontal work started on its eccentric returns before it drives`() {
        // Horizontal, ECCENTRIC-first: digits read by phase, digit 1 performed
        // first, so the BOTTOM pause is the one performed in the middle.
        val s = TempoSchedule.of(Tempo.parse("3010"), chestPressEccFirst)
        assertEquals("RETURN", s.first.label)
        assertEquals(3.0, s.first.seconds)
        assertEquals(false, s.first.isConcentric)
        assertEquals(0.0, s.pauseAfterFirstS)
        assertEquals("DRIVE", s.second.label)
        assertEquals(1.0, s.second.seconds)
        assertEquals(true, s.second.isConcentric)
        assertEquals(0.0, s.pauseAfterSecondS)
    }

    @Test
    fun `an explosive up stroke has no prescribed seconds`() {
        val s = TempoSchedule.of(Tempo.parse("30X0"), benchPress)
        assertEquals(null, s.second.seconds)
        assertEquals(null, s.concentricS)
        assertEquals(3.0, s.eccentricS)
    }
}
