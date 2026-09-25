package com.macrophage.barspeed.dsp

import com.macrophage.barspeed.model.RepCallNotePolicy
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

    /**
     * The guide's second worked example, and the one #248 is about.
     *
     * A `2011` is the tempo the owner runs on the accessory sessions they train
     * most weeks, and it is where the count used to disappear: a two-second
     * stroke has exactly one interior second, so the merged call took the set's
     * only interior count and left `1` spoken on rep 1 and the last rep alone.
     * `TwoSecondStrokeCountTest` measures that on field-38 and field-39 and
     * carries the numbers.
     *
     * The 3010 example above cannot show it. Its three-second stroke has two
     * interior counts, so it kept one either way and reads the same to a lifter
     * scanning for whether their own tempo is counted. This example is
     * field-39 set 1's own geometry -- seated overhead press, concentric-first,
     * drive up -- which is the set the owner heard go quiet.
     */
    @Test
    fun `the guide's 2011 example is what the script says on those reps`() {
        val reps = spokenByRep(plan("2011", seatedOhp), 3)
        val example = reps[0] + "… " + reps[1] + "…"
        assertTrue(
            rendered.contains(example),
            "the guide's Voice section does not contain \"" + example + "\", which is what " +
                "CadenceVoice.script says on reps 1 and 2 of a concentric-first 2011",
        )
    }

    /**
     * The guide's third worked example, and the one #266 is about.
     *
     * A dense prescription -- two one-second strokes and no closing pause --
     * has no free second at the start of the rep, so `CadencePlan.of` puts the
     * call on the beat that opens as the drive ends wherever the drive OPENS
     * the rep, and nowhere at all where the drive CLOSES it. The guide's Voice
     * section went on saying such a cadence "calls the strokes only: no rep
     * number and no 'last rep'" of BOTH geometries, which is false of the
     * concentric-first half from `Count the rep at the end of the drive where
     * nothing else can carry it`. `GuideScreen.kt` is touched by no commit of
     * that change.
     *
     * field-39 set 4's own geometry -- seated overhead press, concentric-first,
     * drive up, 1010 -- which is the set the placement was designed on. Reps 1
     * and 2, as the 3010 and 2011 examples above: rep 1 is named here, unlike
     * every other cadence, so the pair shows the number arriving on the first
     * rep and again on the second.
     *
     * The ellipsis is written as `…` rather than the character so this
     * assertion does not depend on the encoding this file is compiled with.
     */
    @Test
    fun `the guide's 1010 example is what the script says on those reps`() {
        val reps = spokenByRep(plan("1010", seatedOhp), 3)
        val example = reps[0] + "… " + reps[1] + "…"
        assertTrue(
            rendered.contains(example),
            "the guide's Voice section does not contain \"" + example + "\", which is what " +
                "CadenceVoice.script says on reps 1 and 2 of a concentric-first 1010",
        )
    }

    /**
     * What the last rep of a drive-end count is called, read off the script.
     *
     * `CadencePlan.announcementFor` returns [CadencePlan.LAST_REP] in place of
     * the number on the last rep wherever a beat can carry a call, and #173's
     * withholding of it on the plans whose only slot is late was reversed. So
     * this family DOES warn, and the sentence the guide used to carry said the
     * opposite in as many words ("no rep number and no 'last rep'"). Asserting
     * on the phrase `last rep` alone would pass on the old copy too -- the
     * section names it twice for other reasons -- so the assertion is the whole
     * last cycle, stroke word included.
     */
    @Test
    fun `the guide names what the last rep of a drive-end count is called`() {
        val last = spokenByRep(plan("1010", seatedOhp), 3).last()
        assertTrue(
            rendered.contains(last),
            "the guide's Voice section does not contain \"" + last + "\", which is what " +
                "CadenceVoice.script says on the last rep of a concentric-first 1010",
        )
    }

    /**
     * The guide quotes the prep note the screen actually draws.
     *
     * `RecordScreen` draws `RepCallNotePolicy.noteFor` under the #241 start-cue
     * line on exactly the sets whose plan counts at the drive's end, and the
     * guide is where a lifter finds out what that line will say before they are
     * standing at the bar reading it. Read from the constant rather than
     * copied, so a reworded note cannot leave the guide quoting a line no
     * screen draws.
     */
    @Test
    fun `the guide quotes the prep note a drive-end count draws`() {
        assertTrue(
            rendered.contains(RepCallNotePolicy.AT_DRIVE_END),
            "the guide's Voice section does not quote \"" + RepCallNotePolicy.AT_DRIVE_END +
                "\", the note RepCallNotePolicy draws on a set that counts at the drive's end",
        )
    }

    /**
     * The eccentric-first half of the same dense-cadence family is deliberately
     * NOT re-pinned here. `plan("1010", benchPress)` -- field-42 set 5's
     * geometry, drive down -- has no beat for a call at all, so
     * `spokenByRep(plan("1010", benchPress), 3)` produces stroke words only and
     * no `Rep` string appears in any of them; a guide quote comparing that
     * silence against itself would assert nothing the eccentric-first pin above
     * does not already cover from the other side. What pins the silence itself
     * -- that this geometry still says nothing, and the reason it does not -- is
     * `LockoutRepCallTest`'s `the geometry whose drive ends the rep still says
     * nothing, and why`.
     */

    /**
     * The guide says how often a hold names the time left (#312), and it says
     * what the voice does.
     *
     * The sentence stood at "every 15 seconds remaining" while nothing pinned
     * it. Two checks: a literal, which reds when the guide states something
     * other than the owner's rule, and the constants, which red when the guide
     * and [TimedSetVoice] part.
     */
    @Test
    fun `the guide says a hold names its time left every five seconds, then counts from ten`() {
        assertTrue(
            rendered.contains("time checks every 5 seconds remaining"),
            "the guide's Voice section does not say a hold names its time left every 5 seconds",
        )
        assertTrue(
            rendered.contains("time checks every ${TimedSetVoice.MILESTONE_EVERY_S} seconds remaining"),
            "the guide's hold spacing is not TimedSetVoice.MILESTONE_EVERY_S",
        )
        assertTrue(
            rendered.contains("count from ${TimedSetVoice.FINAL_COUNTDOWN_FROM_S}."),
            "the guide's countdown start is not TimedSetVoice.FINAL_COUNTDOWN_FROM_S",
        )
    }
}
