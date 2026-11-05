package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which phase [LiveSetState.phase] names, on every combination of the two
 * declarations that decide it (#134).
 *
 * `startsWith` says which phase opens a rep and `concentricUp` which way the
 * drive moves; they are independent, so a lift is one of four shapes:
 *
 * | startsWith | concentricUp | starts at | e.g.                       |
 * |------------|--------------|-----------|----------------------------|
 * | ECCENTRIC  | true         | top       | squat, bench press         |
 * | ECCENTRIC  | false        | bottom    | (drive-down, lowered first)|
 * | CONCENTRIC | true         | bottom    | deadlift, overhead press   |
 * | CONCENTRIC | false        | top       | seated leg curl, pushdown  |
 *
 * ## What the label has to say
 *
 * A PAUSE is named for WHERE the bar is: [Phase.BOTTOM_PAUSE] still at the
 * bottom of the stroke, [Phase.TOP_PAUSE] still at the top -- the same
 * positional meaning tempo digits 2 and 4 carry (`PhaseTempoTarget`). So the
 * still period after a stroke that went DOWN is a bottom pause and the one
 * after a stroke that went UP is a top pause, on every lift; and before the
 * first stroke the bar is wherever the lift starts.
 *
 * A MOVEMENT is named for what the lifter is DOING: [Phase.CONCENTRIC] is the
 * drive and [Phase.ECCENTRIC] the return, so on a lift whose drive goes down
 * the DOWN stroke is the concentric.
 *
 * The tracker chose the pause from `startsWith` alone -- every still period on
 * an eccentric-first lift a top pause, every one on a concentric-first lift a
 * bottom pause -- and the movement from the stroke's sign alone, so `UP` was
 * always [Phase.CONCENTRIC]. Each of the four shapes reaches at least one wrong
 * label that way, which is why all four are pinned.
 *
 * ## Kept as it was
 *
 * An eccentric-first lift reads [Phase.IDLE] until its first stroke has
 * qualified, the label `RecordScreen` captions "Ready" and the one
 * `FieldDataRegressionTest` pins after 45 s of stillness. A concentric-first
 * lift has never had that state and is not given it here.
 *
 * ## What this does not reach, stated
 *
 * `bottomPause_s` and `topPause_s` in the export are NOT this label. They are
 * `SetAnalyzer.analyzeRep`'s, computed from the batch segmenter's own
 * boundaries and placed by `LiftDirection.startsAtTop`, and nothing on the
 * batch path reads [LiveSetState.phase] (#93 was that path and is closed).
 * The live label reaches the in-set ring's caption and colour, and
 * `VoiceMilestonePolicy.phaseCount` on the one set shape that speaks phase
 * seconds. What any of that looks or sounds like is `[Field]`.
 *
 * Synthetic strokes, level sensor, no inversion: one second per stroke and a
 * second and a half of stillness after each, so every sample read below sits
 * in the middle of a stroke or a pause and nowhere near a boundary.
 */
class LivePhaseLabelTest {
    private val strokeS = 1.0
    private val pauseS = 1.5
    private val leadInS = 1.5
    private val reps = 3

    private data class Shape(val startsWith: StartPhase, val concentricUp: Boolean)

    private val shapes = listOf(
        Shape(StartPhase.ECCENTRIC, concentricUp = true),
        Shape(StartPhase.ECCENTRIC, concentricUp = false),
        Shape(StartPhase.CONCENTRIC, concentricUp = true),
        Shape(StartPhase.CONCENTRIC, concentricUp = false),
    )

    private fun directionOf(s: Shape) = LiftDirection(startsWith = s.startsWith, concentricUp = s.concentricUp)

    private fun startsAtTop(s: Shape) = (s.startsWith == StartPhase.ECCENTRIC) == s.concentricUp

    /** The label published at each of [timesS], seconds from the first sample. */
    private fun labelsAt(s: Shape, timesS: List<Double>): List<Phase> {
        val samples = SyntheticSets.generate(
            List(reps) { SyntheticSets.RepSpec(strokeS, pauseS, strokeS, pauseS, romM = 0.5) },
            leadInS = leadInS,
            leadOutS = pauseS,
            eccentricFirst = startsAtTop(s),
        )
        val tracker = StreamingSetTracker.forLift(directionOf(s))
        val wanted = timesS.map { (it * 1000).toLong() }.toSet()
        val seen = mutableMapOf<Long, Phase>()
        samples.forEach { sample ->
            val state = tracker.feed(sample)
            if (sample.timestampMs in wanted) seen[sample.timestampMs] = state.phase
        }
        return timesS.map { seen.getValue((it * 1000).toLong()) }
    }

    /** One rep's four read points: mid first stroke, mid pause, mid second stroke, mid pause. */
    private fun repPoints(rep: Int): List<Double> {
        val base = leadInS + rep * (2 * strokeS + 2 * pauseS)
        return listOf(
            base + strokeS / 2,
            base + strokeS + pauseS / 2,
            base + strokeS + pauseS + strokeS / 2,
            base + 2 * strokeS + pauseS + pauseS / 2,
        )
    }

    /** What each read point must say, for one rep of shape [s]. */
    private fun expectedRep(s: Shape): List<Phase> {
        val firstDown = startsAtTop(s)
        fun stroke(down: Boolean) = if (down == !s.concentricUp) Phase.CONCENTRIC else Phase.ECCENTRIC
        fun pauseAfter(down: Boolean) = if (down) Phase.BOTTOM_PAUSE else Phase.TOP_PAUSE
        return listOf(stroke(firstDown), pauseAfter(firstDown), stroke(!firstDown), pauseAfter(!firstDown))
    }

    @Test
    fun `every stroke and every pause is labelled for what it is, on all four shapes`() {
        shapes.forEach { s ->
            val times = (0 until reps).flatMap { repPoints(it) }
            assertEquals(
                List(reps) { expectedRep(s) }.flatten(),
                labelsAt(s, times),
                "$s: first stroke, pause, second stroke, pause, rep by rep",
            )
        }
    }

    @Test
    fun `before the first stroke the label is where the lift starts`() {
        val expected = mapOf(
            // Unchanged: an eccentric-first lift has not begun until it moves.
            Shape(StartPhase.ECCENTRIC, true) to Phase.IDLE,
            Shape(StartPhase.ECCENTRIC, false) to Phase.IDLE,
            // A deadlift waits at the floor; a leg curl waits with the pad up.
            Shape(StartPhase.CONCENTRIC, true) to Phase.BOTTOM_PAUSE,
            Shape(StartPhase.CONCENTRIC, false) to Phase.TOP_PAUSE,
        )
        shapes.forEach { s ->
            assertEquals(expected.getValue(s), labelsAt(s, listOf(leadInS / 2)).single(), "$s before any movement")
        }
    }

    @Test
    fun `the rep count is untouched by the label`() {
        // Labelling is display; the count reads the same pairing rule it did.
        // Asserted so a change to the label that reached the count would red.
        shapes.forEach { s ->
            val samples = SyntheticSets.generate(
                List(reps) { SyntheticSets.RepSpec(strokeS, pauseS, strokeS, pauseS, romM = 0.5) },
                leadInS = leadInS,
                leadOutS = pauseS,
                eccentricFirst = startsAtTop(s),
            )
            val tracker = StreamingSetTracker.forLift(directionOf(s))
            var last = LiveSetState()
            samples.forEach { last = tracker.feed(it) }
            assertEquals(reps, last.repCount, "$s reps")
        }
    }
}
