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

    /** field-42 set 5's geometry: bench press, ECCENTRIC-first, drive up. */
    private val benchPress = LiftDirection(startsWith = StartPhase.ECCENTRIC, concentricUp = true)

    /** field-41 set 1's geometry: seated overhead press, CONCENTRIC-first, drive up. */
    private val seatedOhp = LiftDirection(startsWith = StartPhase.CONCENTRIC, concentricUp = true)

    private fun plan(tempo: String, direction: LiftDirection) =
        CadencePlan.of(TempoSchedule.of(Tempo.parse(tempo), direction))

    /**
     * What a set of [reps] reps says, one string per rep, in the guide's own
     * comma-separated form.
     *
     * `Done` is dropped: it follows the last rep and belongs to no rep's
     * cycle. The grouping is by whole cycles of the plan, which is what makes
     * one entry one rep.
     */
    private fun spokenByRep(p: CadencePlan, reps: Int): List<String> {
        val spoken = CadenceVoice.script(p, reps).filter { it.utterance != CadenceVoice.DONE }
        return spoken
            .groupBy { it.atSecond / p.deliveredCycleS }
            .toSortedMap()
            .values
            .map { cycle -> cycle.joinToString(", ") { it.utterance } }
    }

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

    /**
     * The guide's worked example is what the script actually says.
     *
     * The example exists so a lifter can hear the first two reps and know
     * the guide is describing their set. Reading it against
     * [CadenceVoice.script] is the whole point: a substring assertion
     * against a copy of the sentence would have passed at every SHA the
     * sentence was false at, which is every SHA from #293's fix until this
     * one.
     *
     * Reps 1 and 2 of an eccentric-first 3010 -- field-42 set 5's geometry,
     * and the geometry the owner's own sentence uses. Rep 1 keeps its stroke
     * word and counts from one; rep 2's word is replaced by the number and
     * its counts continue from it. The two together are the only pair that
     * shows both halves of the rule.
     *
     * The ellipsis between reps is the guide's own, written here as the
     * escape `\u2026` rather than the character, so this assertion does not
     * depend on the encoding this file is compiled with.
     */
    @Test
    fun `the guide's bench 3010 example is what the script says on those reps`() {
        val reps = spokenByRep(plan("3010", benchPress), 3)
        val example = reps[0] + "\u2026 " + reps[1] + "\u2026"
        assertTrue(
            rendered.contains(example),
            "the guide's Voice section does not contain \"" + example + "\", which is what " +
                "CadenceVoice.script says on reps 1 and 2 of an eccentric-first 3010",
        )
    }
}
