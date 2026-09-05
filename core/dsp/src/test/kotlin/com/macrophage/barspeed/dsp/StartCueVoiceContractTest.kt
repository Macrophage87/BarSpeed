package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.ExerciseDef
import com.macrophage.barspeed.model.GeometrySource
import com.macrophage.barspeed.model.StartCuePolicy
import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The word the prep countdown SHOWS is the word the guide SAYS when the prep
 * ends (#241).
 *
 * Two modules state the rule and neither can be written in terms of the other:
 * `StartCuePolicy` is in `:core:model`, which cannot see `TempoSchedule` at all
 * (`core/dsp` declares `api(project(":core:model"))`; `core/model` declares no
 * project dependency), and the schedule is where the voice's word has always
 * come from. This is the only side that can see both, so the agreement is
 * asserted here rather than assumed anywhere.
 *
 * The seam is the one `GuidedCadenceRunner` walks: `TempoSchedule.of` ->
 * `CadencePlan.of` -> the first beat -> `CadenceVoice.beatCall`. Beat 0 is what
 * the runner plays the instant its lead-in returns, so its utterance is
 * literally the first thing the lifter hears after the countdown.
 *
 * Ten tempo/lift pairs: the four movement cases crossed with tempos whose
 * digits are read positionally and by phase, plus an explosive `X` upstroke,
 * whose stroke seconds are null and whose label is unaffected.
 */
class StartCueVoiceContractTest {
    private fun spokenFirstWord(def: ExerciseDef, tempo: String): String {
        val schedule = TempoSchedule.of(Tempo.parse(tempo), def.liftDirection())
        val firstBeat = CadencePlan.of(schedule).beats.first()
        return CadenceVoice.beatCall(firstBeat, announcement = null)!!.utterance
    }

    private fun shownWord(def: ExerciseDef): String =
        StartCuePolicy.of(def.startsWith, def.concentricUp, def.horizontal, GeometrySource.DECLARED).word

    private fun assertAgrees(def: ExerciseDef, tempo: String) {
        val spoken = spokenFirstWord(def, tempo)
        val shown = shownWord(def)
        assertEquals(
            shown,
            spoken.uppercase(),
            "screen says '$shown', voice opens with '$spoken' for ${def.id} at $tempo",
        )
    }

    private fun def(id: String, phase: StartPhase, concentricUp: Boolean = true, horizontal: Boolean = false) =
        ExerciseDef(id, id, startsWith = phase, concentricUp = concentricUp, horizontal = horizontal)

    @Test
    fun `a bench press opens on the word the countdown showed`() {
        assertAgrees(def("bench", StartPhase.ECCENTRIC), "3010")
    }

    @Test
    fun `a deadlift opens on the word the countdown showed`() {
        assertAgrees(def("deadlift", StartPhase.CONCENTRIC), "3010")
    }

    @Test
    fun `a pulldown opens on the word the countdown showed`() {
        assertAgrees(def("lat_pulldown", StartPhase.CONCENTRIC, concentricUp = false), "1030")
    }

    @Test
    fun `a leg curl lowered first opens on the word the countdown showed`() {
        assertAgrees(def("leg_curl", StartPhase.ECCENTRIC, concentricUp = false), "1030")
    }

    @Test
    fun `a seated row opens on the word the countdown showed`() {
        assertAgrees(def("seated_row", StartPhase.CONCENTRIC, horizontal = true), "2011")
    }

    @Test
    fun `a chest press lowered first opens on the word the countdown showed`() {
        assertAgrees(def("chest_press", StartPhase.ECCENTRIC, horizontal = true), "2011")
    }

    @Test
    fun `the agreement holds across every tempo family`() {
        val lifts =
            listOf(
                def("bench", StartPhase.ECCENTRIC),
                def("deadlift", StartPhase.CONCENTRIC),
                def("pulldown", StartPhase.CONCENTRIC, concentricUp = false),
                def("row", StartPhase.CONCENTRIC, horizontal = true),
            )
        for (lift in lifts) {
            for (tempo in listOf("1010", "1120", "2020", "3110", "20X0")) {
                assertAgrees(lift, tempo)
            }
        }
    }

    @Test
    fun `the DSP's own copy of the top-or-bottom rule still agrees with the model's`() {
        for (phase in StartPhase.entries) {
            for (up in listOf(true, false)) {
                assertEquals(
                    ExerciseDef.startsAtTop(phase, up),
                    LiftDirection(startsWith = phase, concentricUp = up).startsAtTop,
                    "LiftDirection and ExerciseDef disagree for $phase / concentricUp=$up",
                )
            }
        }
    }

    @Test
    fun `the shown word is the schedule's own first stroke label`() {
        val lift = def("pulldown", StartPhase.CONCENTRIC, concentricUp = false)
        val schedule = TempoSchedule.of(Tempo.parse("1030"), lift.liftDirection())
        assertEquals(schedule.first.label, shownWord(lift))
    }
}
