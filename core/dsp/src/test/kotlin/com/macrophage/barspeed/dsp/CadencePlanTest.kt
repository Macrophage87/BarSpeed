package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the guided metronome plays, against what the plan prescribed.
 *
 * The arithmetic these assert used to live in GuidedCadenceRunner in `:app`,
 * which has no test source set, so none of it could be checked. Issue 106 is
 * the consequence: on every set the app has ever paced the delivered cycle
 * exceeded the prescription by exactly one second.
 *
 * The four tempos below are the four the captured sessions actually used, and
 * the 2011 pair is the one that matters most -- the floor lands on the pause
 * following the SECOND stroke in performance order, so the same tempo string
 * gives a different answer on a bench press and on a face pull. A test keyed on
 * the notation would miss it.
 */
class CadencePlanTest {
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

    private fun schedule(t: String, d: LiftDirection) = TempoSchedule.of(Tempo.parse(t), d)

    private fun plan(tempo: String, direction: LiftDirection) = CadencePlan.of(schedule(tempo, direction))

    private fun shape(p: CadencePlan) = p.beats.map { it.label to it.seconds }

    @Test
    fun `the beats of the four tempos the sessions used (pre-fix)`() {
        assertEquals(
            listOf("DOWN" to 3, "UP" to 1, CadencePlan.BREATHE to 1),
            shape(plan("3010", benchPress)),
            "bench press 3010",
        )
        assertEquals(
            listOf("DRIVE" to 1, CadencePlan.HOLD to 1, "RETURN" to 2, CadencePlan.BREATHE to 1),
            shape(plan("2011", facePull)),
            "face pull 2011",
        )
        assertEquals(
            listOf("DOWN" to 1, "UP" to 3, CadencePlan.BREATHE to 1),
            shape(plan("1030", legCurl)),
            "leg curl 1030",
        )
        assertEquals(
            listOf("UP" to 1, "DOWN" to 2, CadencePlan.BREATHE to 1),
            shape(plan("2010", legPress)),
            "leg press 2010",
        )
    }

    @Test
    fun `the delivered cycle exceeds the prescription by a second (pre-fix)`() {
        // Measured on 31 of 31 captured sets and reproduced here from the plan
        // alone. These four numbers are what issue 106 is.
        val cases = listOf(
            Triple("3010", benchPress, 4.0),
            Triple("2011", facePull, 4.0),
            Triple("1030", legCurl, 4.0),
            Triple("2010", legPress, 3.0),
        )
        cases.forEach { (tempo, direction, prescribed) ->
            assertEquals(prescribed, schedule(tempo, direction).prescribedCycleS, "$tempo prescribed")
            assertEquals(
                prescribed.toInt() + 1,
                plan(tempo, direction).deliveredCycleS,
                "$tempo delivered",
            )
        }
    }

    @Test
    fun `the same tempo string closes on a different digit for a different lift`() {
        // 2011 ends in a 1, so a vertical eccentric-first lift already has a
        // full second of closing pause and the floor changes nothing. The face
        // pull is horizontal and concentric-first, so the BOTTOM pause -- zero --
        // lands last instead.
        assertEquals(1.0, schedule("2011", benchPress).pauseAfterSecondS)
        assertEquals(0.0, schedule("2011", facePull).pauseAfterSecondS)
        assertEquals(4, plan("2011", benchPress).deliveredCycleS, "bench 2011 needs no floor")
        assertEquals(5, plan("2011", facePull).deliveredCycleS, "face pull 2011 takes the floor")
    }

    @Test
    fun `the announcement rides the closing beat and no stroke gives up a count (pre-fix)`() {
        listOf(
            "3010" to benchPress,
            "2011" to facePull,
            "1030" to legCurl,
            "2010" to legPress,
        ).forEach { (tempo, direction) ->
            val p = plan(tempo, direction)
            assertEquals(p.beats.lastIndex, p.announceOnBeat, "$tempo announces on the closing beat")
            assertEquals(false, p.announceMerged, "$tempo does not merge")
            assertTrue(p.beats.none { it.suppressFirstCount }, "$tempo suppresses no count")
        }
    }

    @Test
    fun `a rep completes after the second stroke, before any closing beat`() {
        val p = plan("2011", facePull)
        assertEquals(2, p.repCompleteAfterBeat, "RETURN is the third beat, index 2")
        assertEquals("RETURN", p.beats[p.repCompleteAfterBeat].label)
    }

    @Test
    fun `a fractional prescription cannot be delivered in whole seconds`() {
        // Representable and never yet used. Pinned so the truncation is visible
        // rather than discovered: 1.5 s of drive is played as 1 s.
        val s = schedule("3-0-1.5-0", benchPress)
        assertEquals(4.5, s.prescribedCycleS, "prescribed, fractional")
        assertEquals(listOf("DOWN" to 3, "UP" to 1, CadencePlan.BREATHE to 1), shape(CadencePlan.of(s)))
    }

    @Test
    fun `a zero-second stroke is played as one second, which no prescription asks for`() {
        // The other half of the truncation family in issue 106: a stroke digit
        // of 0 is coerced up to a second, so 3000 is played as a 5-second cycle
        // against a prescribed 3. Degenerate but representable, and unpinned
        // until now -- removing the coercion passed the whole suite.
        val s = schedule("3000", benchPress)
        assertEquals(3.0, s.prescribedCycleS)
        assertEquals(listOf("DOWN" to 3, "UP" to 1, CadencePlan.BREATHE to 1), shape(CadencePlan.of(s)))
        assertEquals(5, CadencePlan.of(s).deliveredCycleS)
    }

    @Test
    fun `an explosive stroke is played as one second`() {
        val s = schedule("30X0", benchPress)
        assertEquals(null, s.second.seconds, "X has no prescribed seconds")
        assertEquals(listOf("DOWN" to 3, "UP" to 1, CadencePlan.BREATHE to 1), shape(CadencePlan.of(s)))
    }
}
