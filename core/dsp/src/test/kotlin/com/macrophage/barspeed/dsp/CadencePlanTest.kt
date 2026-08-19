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
 * Every outcome here belongs to a (TEMPO, LIFT) PAIR and never to a tempo
 * string on its own, because TempoSchedule reorders the digits by plane and
 * start phase before the metronome sees them. Two pairs make that concrete:
 * 2011 takes the floor with a face pull and not with a bench press, and 3010
 * keeps its spoken rep call with a bench press but loses it with a drive-down
 * lift started on its eccentric, whose opening stroke is one second instead of
 * three. A test keyed on the notation would miss both.
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

    private fun schedule(t: String, d: LiftDirection) = TempoSchedule.of(Tempo.parse(t), d)

    private fun plan(tempo: String, direction: LiftDirection) = CadencePlan.of(schedule(tempo, direction))

    private fun shape(p: CadencePlan) = p.beats.map { it.label to it.seconds }

    @Test
    fun `the beats of the four tempo-and-lift pairs the sessions used`() {
        assertEquals(
            listOf("DOWN" to 3, "UP" to 1),
            shape(plan("3010", benchPress)),
            "bench press 3010",
        )
        assertEquals(
            listOf("DRIVE" to 1, CadencePlan.HOLD to 1, "RETURN" to 2),
            shape(plan("2011", facePull)),
            "face pull 2011",
        )
        assertEquals(
            listOf("DOWN" to 1, "UP" to 3),
            shape(plan("1030", legCurl)),
            "leg curl 1030",
        )
        assertEquals(
            listOf("UP" to 1, "DOWN" to 2),
            shape(plan("2010", legPress)),
            "leg press 2010",
        )
    }

    @Test
    fun `the delivered cycle is the prescribed cycle`() {
        // The whole of issue 106. Before the fix each of these delivered one
        // second more than it prescribed, measured on 31 of 31 captured sets.
        val cases = listOf(
            Triple("3010", benchPress, 4.0),
            Triple("2011", facePull, 4.0),
            Triple("1030", legCurl, 4.0),
            Triple("2010", legPress, 3.0),
        )
        cases.forEach { (tempo, direction, prescribed) ->
            assertEquals(prescribed, schedule(tempo, direction).prescribedCycleS, "$tempo prescribed")
            assertEquals(
                prescribed.toInt(),
                plan(tempo, direction).deliveredCycleS,
                "$tempo delivered",
            )
        }
    }

    @Test
    fun `one tempo string closes on a different digit depending on the lift`() {
        // 2011 ends in a 1, so a vertical eccentric-first lift already has a
        // full second of closing pause and the floor changes nothing. The face
        // pull is horizontal and concentric-first, so the BOTTOM pause -- zero --
        // lands last instead.
        assertEquals(1.0, schedule("2011", benchPress).pauseAfterSecondS)
        assertEquals(0.0, schedule("2011", facePull).pauseAfterSecondS)
        // Both now deliver what they prescribe; before the fix the face pull
        // took a floor the bench press did not, from the same tempo string.
        assertEquals(4, plan("2011", benchPress).deliveredCycleS, "bench 2011")
        assertEquals(4, plan("2011", facePull).deliveredCycleS, "face pull 2011")
    }

    @Test
    fun `the announcement rides a closing pause the prescription already provides`() {
        // Free: the pause exists either way, so nothing is added and no count
        // is given up. Bench 2011 has a full second at the top.
        val p = plan("2011", benchPress)
        // BREATHE is the closing pause; HOLD is the one between the strokes.
        assertEquals(listOf("DOWN" to 2, "UP" to 1, CadencePlan.BREATHE to 1), shape(p))
        assertEquals(2, p.announceOnBeat, "announces on the closing pause")
        assertEquals(false, p.announceMerged)
        assertTrue(p.beats.none { it.suppressFirstCount })
    }

    @Test
    fun `with no closing pause the announcement merges into a stroke long enough to spare a count`() {
        // TTS speaks with QUEUE_FLUSH, so an utterance needs silence after it or
        // the next one cuts it off -- and the runner says something every
        // second. The window is one second whatever the stroke length, so the
        // only way to widen it is to give up that stroke's first count, which
        // requires the stroke to have one. 3010 opens on a 3 s eccentric, so
        // the lifter keeps the spoken rep number.
        val p = plan("3010", benchPress)
        assertEquals(0, p.announceOnBeat, "merged into the opening stroke of the next rep")
        assertEquals(true, p.announceMerged)
        assertEquals(true, p.beats[0].suppressFirstCount, "that stroke gives up its first count")
        assertEquals(false, p.beats[1].suppressFirstCount, "and only that stroke")
        assertEquals(4, p.deliveredCycleS, "and it still costs no time")
    }

    @Test
    fun `when no stroke can spare a count the announcement is not spoken`() {
        // These pairs all open on a one-second stroke, so nothing can carry the
        // call. The rep NUMBER stays on screen, driven by onRepCounted, so it
        // is the metronome's own count and not the sensor's. The "Last rep"
        // warning has no on-screen equivalent at all and is simply lost on
        // these pairs -- the screen shows "rep 7 of 8" and nothing marks the
        // last one.
        listOf("2010" to legPress, "1030" to legCurl, "2011" to facePull).forEach { (tempo, direction) ->
            val p = plan(tempo, direction)
            assertEquals(null, p.announceOnBeat, "$tempo cannot carry the announcement")
            assertEquals(false, p.announceMerged, "$tempo")
            assertTrue(p.beats.none { it.suppressFirstCount }, "$tempo gives up no count")
        }
    }

    @Test
    fun `no plan ever contains a beat the prescription did not ask for`() {
        // The regression guard. BREATHE was the beat issue 106 inserted; it may
        // now appear only when the prescription actually prescribes a closing
        // pause, and its seconds must be exactly that pause.
        listOf(
            "3010" to benchPress,
            "2011" to facePull,
            "1030" to legCurl,
            "2010" to legPress,
            "2011" to benchPress,
            "3011" to benchPress,
        ).forEach { (tempo, direction) ->
            val sch = schedule(tempo, direction)
            val p = CadencePlan.of(sch)
            assertEquals(
                sch.prescribedCycleS.toInt(),
                p.deliveredCycleS,
                "$tempo on ${direction.plane}/${direction.startsWith}",
            )
        }
    }

    @Test
    fun `a rep completes after the second stroke, before any closing beat`() {
        val p = plan("2011", facePull)
        assertEquals(2, p.repCompleteAfterBeat, "RETURN is the third beat, index 2")
        assertEquals("RETURN", p.beats[p.repCompleteAfterBeat].label)
    }

    @Test
    fun `the two configurations no captured session contains behave the same way`() {
        // Vertical drive-down started on its eccentric, and horizontal started
        // on its eccentric. Both are representable -- start phase and plane are
        // independent fields -- and both take a branch of TempoSchedule that
        // none of the captured sessions reaches. Both lose a second here.
        val pulldown = plan("3010", latPulldownEccFirst)
        assertEquals(listOf("UP" to 1, "DOWN" to 3), shape(pulldown), "drive-down, ecc-first")
        assertEquals(4, pulldown.deliveredCycleS)
        assertEquals(null, pulldown.announceOnBeat, "opens on a one-second stroke")

        val chestPress = plan("3010", chestPressEccFirst)
        assertEquals(listOf("RETURN" to 3, "DRIVE" to 1), shape(chestPress), "horizontal, ecc-first")
        assertEquals(4, chestPress.deliveredCycleS)
        assertEquals(0, chestPress.announceOnBeat, "opens on a three-second stroke")
        assertEquals(true, chestPress.announceMerged)
    }

    @Test
    fun `the merge threshold is two seconds, and two seconds is enough`() {
        // Pins the value itself, not just an interval around it. A 2 s opening
        // stroke merges; the one-second cases below do not. Raise the threshold
        // to 3 and this reds; lower it to 1 and the one-second pin reds.
        assertEquals(2, CadencePlan.MERGE_MIN_STROKE_S)
        val p = plan("2010", benchPress)
        assertEquals(listOf("DOWN" to 2, "UP" to 1), shape(p), "opens on exactly two seconds")
        assertEquals(0, p.announceOnBeat, "which is enough to carry the call")
        assertEquals(true, p.announceMerged)
        assertEquals(true, p.beats[0].suppressFirstCount)
    }

    @Test
    fun `a zero first digit overruns exactly as a zero third digit does`() {
        // The other end of the coercion. 0010 prescribes no down stroke at all
        // and is played as one second of it, the same +1 s as 3000 in digit 3.
        val s = schedule("0010", benchPress)
        assertEquals(1.0, s.prescribedCycleS)
        assertEquals(listOf("DOWN" to 1, "UP" to 1), shape(CadencePlan.of(s)))
        assertEquals(2, CadencePlan.of(s).deliveredCycleS, "one second over, in digit 1")
    }

    @Test
    fun `the cue vocabulary a plan can emit is the one the committed tracks use`() {
        // The persisted format. A plan's spokenLabel becomes a row in the set's
        // cue track, and every fixture and parser matches these strings exactly
        // -- CueTrack.calledReps counts rows equal to "Down". Anything that
        // widens this set renames a column of history.
        val emitted = listOf(
            "3010" to benchPress,
            "2011" to facePull,
            "1030" to legCurl,
            "2010" to legPress,
            "3010" to latPulldownEccFirst,
            "3010" to chestPressEccFirst,
        ).flatMap { (tempo, direction) -> plan(tempo, direction).beats.mapNotNull { it.spokenLabel } }
            .toSortedSet()
        assertEquals(setOf("Down", "Drive", "Hold", "Return", "Up"), emitted.toSet())
    }

    @Test
    fun `a fractional prescription cannot be delivered in whole seconds`() {
        // Representable and never yet used. Pinned so the truncation is visible
        // rather than discovered: 1.5 s of drive is played as 1 s.
        val s = schedule("3-0-1.5-0", benchPress)
        assertEquals(4.5, s.prescribedCycleS, "prescribed, fractional")
        assertEquals(listOf("DOWN" to 3, "UP" to 1), shape(CadencePlan.of(s)))
        assertEquals(4, CadencePlan.of(s).deliveredCycleS, "0.5 s of drive is lost to whole seconds")
    }

    @Test
    fun `a zero-second stroke is played as one second, which no prescription asks for`() {
        // The other half of the truncation family in issue 106: a stroke digit
        // of 0 is coerced up to a second, so 3000 is played as a 5-second cycle
        // against a prescribed 3. Degenerate but representable, and unpinned
        // until now -- removing the coercion passed the whole suite.
        val s = schedule("3000", benchPress)
        assertEquals(3.0, s.prescribedCycleS)
        assertEquals(listOf("DOWN" to 3, "UP" to 1), shape(CadencePlan.of(s)))
        assertEquals(4, CadencePlan.of(s).deliveredCycleS, "one second over, from the coercion alone")
    }

    @Test
    fun `an explosive stroke is played as one second`() {
        val s = schedule("30X0", benchPress)
        assertEquals(null, s.second.seconds, "X has no prescribed seconds")
        assertEquals(listOf("DOWN" to 3, "UP" to 1), shape(CadencePlan.of(s)))
    }
}
