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
 * where no test on the CI path reaches it, so none of it could be checked. Issue 106 is
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

    /**
     * The (tempo, lift) pairs this file reasons about, each with a name that
     * reads in a failure message.
     *
     * One list rather than a list per test, because the tests below PARTITION
     * it: every pair either carries the spoken rep call or does not. A pair
     * cannot quietly stop being covered by one assertion without the other
     * gaining it, which is the only mechanical protection this repo has
     * against a pin being narrowed instead of a defect being fixed.
     */
    private val corpus: List<Triple<String, String, LiftDirection>> = listOf(
        Triple("bench 3010", "3010", benchPress),
        Triple("bench 2011", "2011", benchPress),
        Triple("bench 2010", "2010", benchPress),
        Triple("bench 1010", "1010", benchPress),
        Triple("bench 1110", "1110", benchPress),
        Triple("leg curl 1030", "1030", legCurl),
        Triple("leg curl 1020", "1020", legCurl),
        Triple("leg press 2010", "2010", legPress),
        Triple("leg press 3010", "3010", legPress),
        Triple("leg press 2011", "2011", legPress),
        Triple("leg press 1010", "1010", legPress),
        Triple("face pull 2011", "2011", facePull),
        Triple("lat pulldown ecc-first 3010", "3010", latPulldownEccFirst),
        Triple("chest press ecc-first 3010", "3010", chestPressEccFirst),
    )

    private fun schedule(t: String, d: LiftDirection) = TempoSchedule.of(Tempo.parse(t), d)

    private fun plan(tempo: String, direction: LiftDirection) = CadencePlan.of(schedule(tempo, direction))

    private fun shape(p: CadencePlan) = p.beats.map { it.label to it.seconds }

    /** Corpus names, split by whether the plan speaks a rep call at all. */
    private fun named(carries: Boolean) =
        corpus.filter { (_, t, d) -> (plan(t, d).announceOnBeat != null) == carries }.map { it.first }

    /**
     * The beats a prescription asks for, built from [TempoSchedule] alone:
     * first stroke, the pause after it when the prescription has one, second
     * stroke, the closing pause when the prescription has one.
     *
     * Restated here rather than read back off [CadencePlan] -- an oracle that
     * asks the thing it is checking checks nothing. What it pins is the SHAPE
     * of the schedule against the prescription. It repeats CadencePlan's
     * whole-second rules, so it does NOT pin those; `a zero-second stroke is
     * played as one second` and `a fractional prescription cannot be delivered
     * in whole seconds` pin them separately.
     */
    private fun prescribedBeats(s: TempoSchedule): List<Pair<String, Int>> = buildList {
        add(s.first.label to (s.first.seconds ?: 1.0).toInt().coerceAtLeast(1))
        if (s.pauseAfterFirstS.toInt() > 0) add(CadencePlan.HOLD to s.pauseAfterFirstS.toInt())
        add(s.second.label to (s.second.seconds ?: 1.0).toInt().coerceAtLeast(1))
        if (s.pauseAfterSecondS.toInt() > 0) add(CadencePlan.BREATHE to s.pauseAfterSecondS.toInt())
    }

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
    fun `which pairs run a whole set with no spoken count`() {
        // Issue 147, stated as a partition. A pair keeps its silence only when
        // NEITHER stroke has a count to give up and the prescription leaves no
        // closing pause -- both strokes one second, so every second of the
        // cycle already has a word in it.
        //
        // The seven pairs that move out of the silent list are the complaint.
        // Two of them are recorded rather than reasoned about, both captured
        // after issue 106 and neither carrying a rep call:
        // `field-legcurl-1030-10rep-cues.csv` is leg curl 1030, and
        // `field-reardeltfly-s32-set06-cues.csv` is 2011 on a vertical
        // concentric-first drive-up machine, which resolves exactly as
        // [legPress] does -- its track reads Up, Hold, Down, 1 and repeats.
        // Those files are recordings and do not change; a set paced on this
        // version of the plan will not look like them.
        //
        // Which pairs these are belongs to (TEMPO, LIFT) and never to a tempo
        // string: 2010 was silent on a leg press and never on a bench press,
        // from the same four digits.
        //
        // What stays true of the three that remain: the rep NUMBER is on
        // screen, driven by onRepCounted, so it is the metronome's own count
        // and not the sensor's, and the "Last rep" warning has no on-screen
        // equivalent at all and is simply lost.
        assertEquals(
            listOf("bench 1010", "bench 1110", "leg press 1010"),
            named(carries = false),
            "pairs that run a whole set with no spoken rep count",
        )
        assertEquals(
            listOf(
                "bench 3010",
                "bench 2011",
                "bench 2010",
                "leg curl 1030",
                "leg curl 1020",
                "leg press 2010",
                "leg press 3010",
                "leg press 2011",
                "face pull 2011",
                "lat pulldown ecc-first 3010",
                "chest press ecc-first 3010",
            ),
            named(carries = true),
            "pairs that speak it",
        )
        // Silence is total, not partial: nothing is half-said, and no stroke
        // gives up a count for a call that never comes.
        val silent = named(carries = false)
        corpus.filter { it.first in silent }.forEach { (name, tempo, direction) ->
            val p = plan(tempo, direction)
            assertEquals(false, p.announceMerged, name)
            assertTrue(p.beats.none { it.suppressFirstCount }, "$name gives up no count")
        }
    }

    @Test
    fun `a one-second opener sends the rep call to the second stroke`() {
        // Issue 147, and the fix for the list above. No closing pause to say
        // the call in and a one-second opening stroke with no count to give up,
        // so it goes to the OTHER stroke on the same terms the first would have
        // had: merged into that stroke's own word, that stroke's first count
        // given up to widen the window, and not one second added anywhere.
        //
        // The beat index is the second stroke's, which is also the beat the rep
        // completes after. It is 2 rather than 1 whenever the prescription puts
        // an isometric pause between the strokes, so it is asserted per pair
        // rather than as a constant.
        listOf(
            Triple("leg curl 1030", 1, 3),
            Triple("leg curl 1020", 1, 2),
            Triple("leg press 2010", 1, 2),
            Triple("leg press 3010", 1, 3),
            Triple("leg press 2011", 2, 2),
            Triple("face pull 2011", 2, 2),
            Triple("lat pulldown ecc-first 3010", 1, 3),
        ).forEach { (name, beat, seconds) ->
            val (_, tempo, direction) = corpus.first { it.first == name }
            val p = plan(tempo, direction)
            assertEquals(beat, p.announceOnBeat, "$name: the second stroke carries the call")
            assertEquals(p.repCompleteAfterBeat, p.announceOnBeat, "$name: which is the rep-completion beat")
            assertEquals(true, p.announceMerged, "$name: merged into that stroke's own word")
            assertEquals(true, p.beats[beat].isStroke, "$name: and it is a stroke, not a pause")
            assertEquals(seconds, p.beats[beat].seconds, "$name: seconds of the stroke that carries it")
            assertEquals(
                listOf(beat),
                p.beats.indices.filter { p.beats[it].suppressFirstCount },
                "$name: exactly that stroke gives up its first count, and only it",
            )
        }
    }

    @Test
    fun `deciding where the rep call goes moves no beat, on any tempo any lift prescribes`() {
        // THE INVARIANT THE REP CALL MAY NOT BREAK, and the one this suite
        // could not previously state. Two shipped releases moved these beats: a
        // flat allowance for everything after the first stroke (+3.0 s per
        // rep), then a one-second floor under the closing pause (issue 106,
        // +1.00 s per rep, measured on 31 of 31 captured sets). Both were found
        // by outside audit rather than here.
        //
        // deliveredCycleS checks only the TOTAL, so a second moved out of one
        // beat and into another passes it. This compares the whole beat list
        // against the prescription for every whole-second tempo with strokes in
        // 0..4 and pauses in 0..2, plus the explosive and fractional forms, on
        // all six lifts -- so a beat added, dropped, lengthened or shortened
        // anywhere reds, whatever the announcement decides to ride.
        val whole = (0..4).flatMap { d1 ->
            (0..2).flatMap { d2 ->
                (0..4).flatMap { d3 ->
                    (0..2).map { d4 -> "$d1$d2$d3$d4" }
                }
            }
        }
        val notations = whole + listOf("30X0", "20X1", "10X0", "3-0-1.5-0", "1-0-2.5-1")
        val lifts = listOf(benchPress, legCurl, facePull, legPress, latPulldownEccFirst, chestPressEccFirst)
        var checked = 0
        notations.forEach { n ->
            lifts.forEach { lift ->
                val s = schedule(n, lift)
                val drive = if (lift.concentricUp) "up" else "down"
                assertEquals(
                    prescribedBeats(s),
                    CadencePlan.of(s).beats.map { it.label to it.seconds },
                    "$n on ${lift.plane}/${lift.startsWith}/drive-$drive",
                )
                checked++
            }
        }
        assertEquals(230 * 6, checked, "(tempo, lift) pairs checked")
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
        //
        // The pulldown is one of the seven pairs issue 147 moves, and this
        // assertion is the near neighbour of the two the differential commit
        // before this one changed: same defect, same fix, a different test.
        // Its opener is one second and its second stroke is three, so the call
        // goes there instead of nowhere. `the merge threshold is two seconds`
        // and `a one-second opener sends the rep call to the second stroke`
        // both pin the rule; this one only stops asserting the old outcome.
        val pulldown = plan("3010", latPulldownEccFirst)
        assertEquals(listOf("UP" to 1, "DOWN" to 3), shape(pulldown), "drive-down, ecc-first")
        assertEquals(4, pulldown.deliveredCycleS)
        assertEquals(1, pulldown.announceOnBeat, "one-second opener, so the second stroke takes the call")
        assertEquals(true, pulldown.announceMerged)
        assertEquals(true, pulldown.beats[1].suppressFirstCount, "and gives up its first count for it")

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
        // The same threshold on the same terms for the second stroke. Leg press
        // 2010 is the same four digits resolved the other way round, so the
        // two-second stroke is the one the rep ends on rather than opens with.
        val q = plan("2010", legPress)
        assertEquals(listOf("UP" to 1, "DOWN" to 2), shape(q), "closes on exactly two seconds")
        assertEquals(1, q.announceOnBeat, "which is equally enough")
        assertEquals(true, q.beats[1].suppressFirstCount)
        // And one second is not enough at either end of the rep. Raise the
        // threshold to 3 and both pins above red; lower it to 1 and this reds.
        assertEquals(null, plan("1010", legPress).announceOnBeat, "neither stroke has a count to give up")
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

    /**
     * Both stroke digits at zero, which is the degenerate end of the coercion
     * the two pins above cover one digit at a time.
     *
     * Premise pin for #148, which needs a floor for a control that BUILDS a
     * tempo out of digits rather than reading one a plan wrote. The floor is
     * measured here rather than chosen: `0000` is played as a two-second
     * cycle against a prescription of none, it carries no spoken rep count
     * because neither stroke has a second to give up, and the compliance
     * scorer still grades the lifter against the zeros.
     */
    @Test
    fun `both stroke digits at zero are played as a second each, and the rep call has nowhere to go`() {
        val s = schedule("0000", benchPress)
        assertEquals(0.0, s.prescribedCycleS, "a prescription in which nothing moves for any time")
        assertEquals(listOf("DOWN" to 1, "UP" to 1), shape(CadencePlan.of(s)))
        assertEquals(2, CadencePlan.of(s).deliveredCycleS, "two seconds over, from the coercion alone")
        assertEquals(null, CadencePlan.of(s).announceOnBeat, "and neither stroke can carry the rep call")
    }
}
