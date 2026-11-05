package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.Phase
import com.macrophage.barspeed.model.StartPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The word the in-set tempo ring draws over the live phase ([LivePhaseCaption]),
 * and the tracker fact it reads beside the phase.
 *
 * The tracker names a MOVEMENT for what the lifter is doing and a PAUSE for
 * where the bar is (#134, `LivePhaseLabelTest`). The caption has to say the
 * same thing in words, and the words depend on the lift: on a squat the drive
 * goes up and the pause at the top is the lockout; on a pushdown the drive goes
 * down and the pause at the top is the stretch; on a horizontal lift there is
 * no up at all.
 *
 * What the ring looks like, and whether a lifter can read it mid-set, is
 * `[Field]`. This pins the words and nothing drawn.
 */
class LivePhaseCaptionTest {
    private val squat = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString(),
    )

    @Test
    fun `a lift that drives up reads its strokes and pauses by their positions`() {
        val words = Phase.entries.associateWith { LivePhaseCaption.of(it, squat, labelWithdrawn = false) }
        assertEquals(
            mapOf(
                Phase.IDLE to "Ready",
                Phase.ECCENTRIC to "Lowering",
                Phase.BOTTOM_PAUSE to "Bottom pause",
                Phase.CONCENTRIC to "Driving up",
                Phase.TOP_PAUSE to "Lockout",
            ),
            words,
            "squat",
        )
    }

    @Test
    fun `the tracker flags a withdrawn label apart from the idle before the first stroke`() {
        // field-bench-rotating-6rep holds one live run for 24.3 s; #95 withdraws
        // its label past MAX_LABELLED_RUN_S. The phase reads IDLE there AND
        // before the first stroke qualifies, so the flag is the only thing that
        // tells the two apart.
        val tracker = StreamingSetTracker.forLift(squat)
        var withdrawnFrames = 0
        var idleNotWithdrawn = 0
        load("field-bench-rotating-6rep.csv").forEach { sample ->
            val s = tracker.feed(sample)
            if (s.phaseLabelWithdrawn) {
                withdrawnFrames++
                assertEquals(Phase.IDLE, s.phase, "a withdrawn label reads IDLE")
                assertEquals(0.0, s.currentPhaseElapsedS, "and is not timed")
            } else if (s.phase == Phase.IDLE) {
                idleNotWithdrawn++
            }
        }
        assertTrue(withdrawnFrames > 0, "the drift run's label is withdrawn on some frame")
        assertTrue(idleNotWithdrawn > 0, "and the opening IDLE is not flagged")
        assertEquals(false, LiveSetState().phaseLabelWithdrawn, "the default state")
    }
}
