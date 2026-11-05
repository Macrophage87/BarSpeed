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

    /** Drive DOWN, started at the top: a pushdown or a seated leg curl. */
    private val pushdown = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = false)

    /** A lift with no up: the lifter's drive is horizontal. */
    private val chestPress = LiftDirection(
        startsWith = StartPhase.ECCENTRIC,
        concentricUp = true,
        plane = MovementPlane.HORIZONTAL,
    )

    private fun caption(
        phase: Phase,
        d: LiftDirection,
        labelWithdrawn: Boolean = false,
        countWithheld: Boolean = false,
    ) = LivePhaseCaption.of(phase, d, labelWithdrawn = labelWithdrawn, countWithheld = countWithheld)

    private fun load(n: String) = ImuCsv.decode(
        javaClass.getResourceAsStream("/$n")!!.readBytes().decodeToString(),
    )

    @Test
    fun `a lift that drives up reads its strokes and pauses by their positions`() {
        val words = Phase.entries.associateWith { caption(it, squat) }
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
    fun `a lift that drives down reads its drive as going down and its top as a pause`() {
        // #134 named the down stroke of a drive-down lift CONCENTRIC. The ring
        // captioned CONCENTRIC "Driving up" and the pause at the top "Lockout"
        // whatever the lift, so a pushdown's drive read "Driving up" and its
        // stretch at the top read "Lockout".
        val words = Phase.entries.associateWith { caption(it, pushdown) }
        assertEquals(
            mapOf(
                Phase.IDLE to "Ready",
                Phase.ECCENTRIC to "Raising",
                Phase.BOTTOM_PAUSE to "Bottom pause",
                Phase.CONCENTRIC to "Driving down",
                Phase.TOP_PAUSE to "Top pause",
            ),
            words,
            "pushdown",
        )
    }

    @Test
    fun `a drive-down lift the stack rule inverts reads in the lifter's frame`() {
        // A pushdown a plan declared `concentric: down`, on the stack table:
        // SetGeometryPolicy.stackInversion's rule 3 inverts it, so the tracker
        // measures the HANDLE's travel and its drive is the lifter's downward
        // drive. The up and down words are true there and stay.
        val inverted = LiftDirection(
            startsWith = StartPhase.CONCENTRIC,
            concentricUp = false,
            sensorInverted = true,
            sensorOnStack = true,
        )
        val words = Phase.entries.associateWith { caption(it, inverted) }
        assertEquals(
            mapOf(
                Phase.IDLE to "Ready",
                Phase.ECCENTRIC to "Raising",
                Phase.BOTTOM_PAUSE to "Bottom pause",
                Phase.CONCENTRIC to "Driving down",
                Phase.TOP_PAUSE to "Top pause",
            ),
            words,
            "declared drive-down pushdown on the stack, inverted",
        )
    }

    @Test
    fun `a horizontal lift names no up or down`() {
        // The tracker orients a horizontal lift so the drive is positive, so
        // TOP_PAUSE there is the end the drive reaches and BOTTOM_PAUSE the end
        // it starts from. Neither is a top or a bottom, and neither stroke
        // goes up or down.
        val words = Phase.entries.associateWith { caption(it, chestPress) }
        assertEquals(
            mapOf(
                Phase.IDLE to "Ready",
                Phase.ECCENTRIC to "Returning",
                Phase.BOTTOM_PAUSE to "Pause",
                Phase.CONCENTRIC to "Driving",
                Phase.TOP_PAUSE to "Pause",
            ),
            words,
            "horizontal chest press",
        )
    }

    @Test
    fun `a withdrawn label draws no caption, where the set's opening idle reads Ready`() {
        // #95 withdraws a run's label past MAX_LABELLED_RUN_S and the phase
        // reads IDLE. The ring captioned IDLE "Ready", a word that says the
        // set has not started, mid-set. Withdrawn means no word at all.
        listOf(squat, pushdown, chestPress).forEach { d ->
            assertEquals(null, caption(Phase.IDLE, d, labelWithdrawn = true), "$d, withdrawn")
            assertEquals(
                "Ready",
                caption(Phase.IDLE, d),
                "$d, before the first stroke",
            )
        }
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
