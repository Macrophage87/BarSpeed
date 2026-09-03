package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseKind
import com.macrophage.barspeed.model.SetVoiceGuide
import com.macrophage.barspeed.model.SetVoicePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The replay of a timed set, checked against the two holds it was built from.
 *
 * `TimedSetScript` is a model of two loops in `:app` -- the prep runner and
 * the tick job -- and a model is worth exactly what it is checked against.
 * What checks it here is field-37's sets 11 and 12: the same prep, the same
 * target, the same work start and the same sensor-count instants that session
 * recorded, replayed, and asserted label for label against the committed cue
 * tracks. `TimedHoldCueTrackTest` pins those tracks; this pins the model to
 * them.
 */
class TimedSetScriptTest {
    private companion object {
        const val SET11 = "field-ropedeadhang-hold20-s37-set11"
        const val SET12 = "field-ropedeadhang-hold30-s37-set12"

        /** From field-37's `meta.json`: `prep_s`, `duration_s`, `workStartedAt_ms`. */
        const val PREP_S = 12
        const val SET11_TARGET_S = 20
        const val SET12_TARGET_S = 30
        const val SET11_WORK_STARTED_MS = 1788342774422L
        const val SET12_WORK_STARTED_MS = 1788342929396L

        /**
         * The stray stream's instants, read off each committed track: the bare
         * digits within three seconds of the work start, which is where the
         * hold cadence itself says nothing.
         */
        val SET11_SENSOR_COUNTS = listOf(1788342774236L, 1788342775258L, 1788342776245L)
        val SET12_SENSOR_COUNTS = listOf(1788342930181L, 1788342931170L)
    }

    /**
     * The guides field-37 was RECORDED under, written out rather than asked
     * of the policy.
     *
     * The two replays below assert that the model reproduces an archive, and
     * an archive does not change when the policy does. Reading them from
     * `SetVoicePolicy` -- which c1 did -- would turn a claim about what app
     * 0.1.48 said into a claim about what the current build would say, and
     * #217 changes the second and not the first.
     */
    private val guidesAsRecorded = setOf(SetVoiceGuide.TIMED_CLOCK, SetVoiceGuide.SENSOR_COUNT)

    private fun replay(
        targetS: Int,
        workStartedAtMs: Long,
        sensorCountsAtMs: List<Long>,
        guides: Set<SetVoiceGuide> = guidesAsRecorded,
    ) = TimedSetScript.script(
        prepS = PREP_S,
        targetS = targetS,
        startWord = "Hold",
        workStartedAtMs = workStartedAtMs,
        guides = guides,
        sensorCountsAtMs = sensorCountsAtMs,
    )

    /**
     * The replay reproduces what the app said, in the order it said it.
     *
     * Labels and order, not milliseconds: the device speaks on a drifting
     * `delay(1_000)` and the model on an exact grid, so the two agree on what
     * was said and on the sequence, which is what a lifter hears.
     */
    @Test
    fun `the replay of set 11 is the cue track set 11 recorded`() {
        assertEquals(
            CueTrack.read(SET11).map { it.label },
            replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, SET11_SENSOR_COUNTS).map { it.cue },
        )
    }

    @Test
    fun `the replay of set 12 is the cue track set 12 recorded`() {
        assertEquals(
            CueTrack.read(SET12).map { it.label },
            replay(SET12_TARGET_S, SET12_WORK_STARTED_MS, SET12_SENSOR_COUNTS).map { it.cue },
        )
    }

    /**
     * The replay is on the second grid the captures are within 30 ms of.
     *
     * Checked once, on the cadence rows of set 11, so the exact-grid claim in
     * `TimedSetScript`'s own KDoc is asserted rather than described.
     */
    @Test
    fun `the cadence rows of the replay land on exact seconds of the work start`() {
        val cadence = replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, SET11_SENSOR_COUNTS)
            .filterNot { it.atMs in SET11_SENSOR_COUNTS }
        for (cue in cadence) {
            assertEquals(0L, (cue.atMs - SET11_WORK_STARTED_MS).mod(1000L), "${cue.cue} is off the grid")
        }
        assertEquals(
            SET11_WORK_STARTED_MS + 1000L * SET11_TARGET_S,
            cadence.single { it.cue == TimedSetVoice.TIME_UP }.atMs,
        )
    }

    /**
     * The prep contributes `Ready` and `Brace` and no digits, whatever its
     * length.
     *
     * `LeadInPlan.RECORDED` is the rule; this checks the replay obeys it, on
     * the 12 s prep both captures ran, whose countdown says "5", "4" and "3"
     * out loud and writes none of them.
     */
    @Test
    fun `the replay writes only the lead-in words that reach the record`() {
        val beforeWork = replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, emptyList())
            .filter { it.atMs < SET11_WORK_STARTED_MS }
        assertEquals(listOf("Ready", "Brace"), beforeWork.map { it.cue })
    }

    /**
     * A hold speaks no bare digit before the word that starts its clock.
     *
     * The differential #217 exists for, and it is asked of the guides the
     * POLICY hands a hold today rather than of the ones field-37 recorded:
     * replay set 11's prep, target and captured stray-digit instants, and
     * nothing numeric may reach the record at or before `Hold`. Before the
     * fix the first row is a bare `1` 0.186 s early, which is what the lifter
     * heard.
     */
    @Test
    fun `a hold speaks no bare digit before the word that starts its clock`() {
        val guides = SetVoicePolicy.guidesFor(
            hasTempo = false,
            isTimed = true,
            kind = ExerciseKind.HOLD,
            demoMode = false,
            imuConnected = true,
        )
        val script = replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, SET11_SENSOR_COUNTS, guides = guides)
        val early = script.filter { it.atMs <= SET11_WORK_STARTED_MS && it.cue.all(Char::isDigit) }
        assertEquals(emptyList(), early, "a bare digit still precedes the word that starts the hold")
    }

    /**
     * A hold says the cadence and only the cadence.
     *
     * The whole track, not just its opening: whatever the sensor stream did
     * during the hold, the rows are the prep's two words, the start word, the
     * milestone, the ten-digit countdown and the terminal word.
     */
    @Test
    fun `a hold records the clock cadence and nothing beside it`() {
        val guides = SetVoicePolicy.guidesFor(
            hasTempo = false,
            isTimed = true,
            kind = ExerciseKind.HOLD,
            demoMode = false,
            imuConnected = true,
        )
        val script = replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, SET11_SENSOR_COUNTS, guides = guides)
        assertEquals(
            listOf("Ready", "Brace", "Hold", "15 seconds") +
                (10 downTo 1).map { it.toString() } + TimedSetVoice.TIME_UP,
            script.map { it.cue },
        )
    }

    /**
     * Dropping the sensor counter leaves the hold cadence untouched.
     *
     * The guide set is the only input that changes; every other row is
     * identical, which is what makes it a change to WHO speaks rather than to
     * what the hold says.
     */
    @Test
    fun `removing the sensor counter removes its rows and nothing else`() {
        val withSensor = replay(SET11_TARGET_S, SET11_WORK_STARTED_MS, SET11_SENSOR_COUNTS)
        val clockOnly = replay(
            SET11_TARGET_S,
            SET11_WORK_STARTED_MS,
            SET11_SENSOR_COUNTS,
            guides = setOf(SetVoiceGuide.TIMED_CLOCK),
        )
        assertEquals(withSensor.filterNot { it.atMs in SET11_SENSOR_COUNTS }, clockOnly)
    }
}
