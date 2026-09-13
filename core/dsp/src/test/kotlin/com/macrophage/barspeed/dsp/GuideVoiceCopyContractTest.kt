package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.StartPhase
import com.macrophage.barspeed.model.Tempo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The in-app guide's Voice section, held against the script that produces the
 * audio it describes.
 *
 * ## Why this exists
 *
 * `GuideScreen.kt`'s Voice section is the only place the app explains what the
 * guided metronome will say, and the lifter reads it before a set rather than
 * during one. Nothing pinned it. #293 changed where the rep number lands and
 * the section went on describing the old placement in two clauses, both false
 * against the code shipped beside them: that a three-second stroke "calls
 * `1, 2`, never its own length", and that "the rep number rides into the NEXT
 * stroke's word rather than standing on its own". A replaced stroke counts
 * `2, 3` -- its own length included -- and a call replaces the word rather than
 * riding it.
 *
 * A description of behaviour that nothing checks drifts from the behaviour;
 * that is the class the four-way disagreement over the plan contract already
 * cost this repo a shipped bug for. So the guide's worked example is not
 * compared with a copy of itself here: it is compared with what
 * [CadenceVoice.script] says, on the same plan the example names.
 *
 * ## How the source is read
 *
 * Through the test classpath, as `GuidePromptContractTest` reads the same file
 * for `PLAN_PROMPT`: a copy of the prose would drift exactly as the prose did.
 * `core/dsp/build.gradle.kts` copies the one file by name.
 *
 * Two normalisations, and nothing beyond them, because the file is not
 * byte-identical across machines: `core.autocrlf=true` with no tracked
 * `.gitattributes` puts a carriage return on every line of a Windows working
 * copy that CI's checkout does not have. [rendered] joins Kotlin's `" + "`
 * string concatenations so an assertion cannot be broken by rewrapping a
 * sentence, and matching is `contains` on a substring that spans no line
 * terminator.
 */
class GuideVoiceCopyContractTest {
    private val guide: String =
        checkNotNull(
            javaClass.getResourceAsStream("/GuideScreen.kt"),
        ) {
            "GuideScreen.kt is not on the test classpath - see guideSourceResource in core/dsp/build.gradle.kts"
        }.readBytes().decodeToString()

    /**
     * The guide's prose as the lifter reads it, with the Kotlin concatenations
     * that wrap it in source joined up.
     *
     * `"...on a " + "bench 3010 that's..."` is one sentence on screen and two
     * literals in source. Without this, an assertion on a sentence would also
     * be an assertion about where someone wrapped the line.
     */
    private val rendered: String =
        guide.lineSequence().joinToString(" ") { it.trim() }.replace("\" + \"", "")

    /** field-41 set 1's geometry: seated overhead press, CONCENTRIC-first, drive up. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    /**
     * The guide tells a lifter on a bottom-start lift which word the set opens
     * on, and that word is rep 1's first stroke -- the one word #293 does NOT
     * replace, because rep 1 carries no call.
     *
     * Three reps rather than two so rep 2 is not the last rep; `Last rep`
     * stands in for the number there ([CadencePlan.announcementFor]) and would
     * make a two-rep set the wrong thing to read an ordinary rep off.
     */
    @Test
    fun `the guide names the word a bottom-start lift opens on`() {
        val opener = CadenceVoice.script(plan("3010", seatedOhp), 3).first().utterance
        assertTrue(
            rendered.contains("starting on '$opener'"),
            "the guide does not say a bottom-start lift starts on '$opener', which is the first word " +
                "CadenceVoice.script speaks on a concentric-first 3010",
        )
    }
}
