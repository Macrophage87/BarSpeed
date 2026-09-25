package com.macrophage.barspeed.model

/**
 * What the prep countdown tells the lifter about where the coming rep starts.
 *
 * Three parts, because they are drawn at three sizes and one of them is usually
 * absent.
 *
 * The app has always known this. Every set resolves a start before it begins --
 * [ExerciseDef.startsWith] crossed with [ExerciseDef.concentricUp], through
 * [SetGeometryPolicy.resolve] on a plan slot -- and the voice says the first
 * stroke's word the instant the prep ends. The prep itself said nothing, so on
 * a machine the lifter has not used with the app the first call arrives with no
 * warning of which way to move, and a wrong guess puts the whole set's phases
 * backwards in the analysis (#241).
 */
data class StartCue(
    /**
     * The line the countdown draws, meant to be read from the bar:
     * `"Start at the TOP, first movement DOWN"`.
     */
    val phrase: String,
    /**
     * The first stroke's word in upper case -- the same word `TempoSchedule`
     * puts on beat 0 and `CadenceVoice` speaks when the prep ends.
     *
     * Held apart from [phrase] so the two channels can be pinned against each
     * other. The pin lives in `:core:dsp`
     * (`StartCueVoiceContractTest`), not here: `core/dsp` declares
     * `api(project(":core:model"))` and `core/model` declares no project
     * dependency, so only the DSP side can see both the word and the schedule
     * that produced it.
     */
    val word: String,
    /**
     * One line saying the start was not stated by anyone, or null when it was.
     *
     * Absence is a distinct state here rather than an empty string: null means
     * "a plan or the app's own definition of this lift decided this", and the
     * lifter has nothing to check.
     */
    val marker: String?,
)

/**
 * The prep countdown's start cue: one pure decision, so the screen and the
 * voice cannot say different things about the same rep.
 *
 * In `:core:model` beside [ExerciseDef.startsAtTop] because that property is
 * the rule it turns into words, and because `:app` -- where the countdown is
 * drawn -- has almost no test source set: a decision left in a composable is a
 * decision nothing on the CI path ever runs.
 */
object StartCuePolicy {
    /** First movement of a vertical lift that begins at the top of its range. */
    const val DOWN = "DOWN"

    /** First movement of a vertical lift that begins at the bottom. */
    const val UP = "UP"

    /**
     * First movement of a horizontal lift that opens on its working stroke, and
     * of a vertical lift that opens on a drive its tempo prescribes as `X`
     * (#264) -- the word the guide speaks for an explosive drive on either
     * plane.
     */
    const val DRIVE = "DRIVE"

    /** First movement of a horizontal lift that opens on its lowering stroke. */
    const val RETURN = "RETURN"

    /**
     * Said of a start guessed from words in the exercise id
     * ([GeometrySource.INFERRED]), so the lifter knows to check the plan.
     */
    const val GUESSED_MARKER = "Guessed from the name"

    /**
     * Said of a start nothing declared, seeded or guessed
     * ([GeometrySource.DEFAULT]).
     *
     * [SetGeometryPolicy.describe] cannot currently produce that source for
     * `startsWith` -- it passes `inferable = true`, so an id with no seed entry
     * lands on [GeometrySource.INFERRED] instead. The branch exists because the
     * source is a four-value enum read off a STORED geometry object, and
     * calling a value nothing decided a guess made from the name would be a
     * claim about reasoning the app did not do.
     */
    const val UNDECLARED_MARKER = "Not declared"

    /**
     * The cue for a lift with this geometry.
     *
     * Takes the four values it reads rather than an [ExerciseDef] and a
     * [ResolvedGeometry]: `horizontal` and `startsWith` come off the definition
     * the set will run against, while [source] comes off the slot's stored
     * provenance, and a caller holding one without the other should not have to
     * build a fake of the missing half.
     *
     * @param horizontal the LIFTER's plane -- [ExerciseDef.horizontal], not the
     *   plane the sensor happens to travel in. A stack-mounted sensor on a
     *   seated row moves vertically and the lifter still does not.
     * @param explosiveUpStroke the set's tempo writes `X` in digit 3 --
     *   [Tempo.isExplosiveUpStroke] of the tempo the guide is about to play.
     *   No default: a caller that forgot it would show `UP` over a set whose
     *   voice opens on `Drive`, the disagreement #241 exists to prevent.
     */
    fun of(
        startsWith: StartPhase,
        concentricUp: Boolean,
        horizontal: Boolean,
        source: GeometrySource,
        explosiveUpStroke: Boolean,
    ): StartCue {
        val word = firstMovementWord(startsWith, concentricUp, horizontal, explosiveUpStroke)
        return StartCue(phrase = phrase(word, horizontal), word = word, marker = marker(source))
    }

    /**
     * The word the first stroke of every rep is called by.
     *
     * The same inputs `TempoSchedule.of` resolves its first stroke's label
     * from, in the same order of questions: horizontal work is called by PHASE
     * because there is no up or down on a seated row, and vertical work by
     * DIRECTION -- except a vertical DRIVE the tempo prescribes as `X`, which
     * is called [DRIVE] (#264). `X` is legal in digit 3 only, the up stroke, so
     * that stroke opens the rep only when the lift starts at the bottom, and it
     * is the drive only when the drive moves up. A vertical lift that starts at
     * the bottom with its drive DOWN opens on the return, and an `X` there is a
     * fast return, not a drive, so it keeps [UP]. Stated twice in two modules,
     * so it is pinned equal in `:core:dsp` rather than trusted.
     */
    fun firstMovementWord(
        startsWith: StartPhase,
        concentricUp: Boolean,
        horizontal: Boolean,
        explosiveUpStroke: Boolean,
    ): String = when {
        horizontal -> if (startsWith == StartPhase.CONCENTRIC) DRIVE else RETURN
        ExerciseDef.startsAtTop(startsWith, concentricUp) -> DOWN
        explosiveUpStroke && concentricUp -> DRIVE
        else -> UP
    }

    /**
     * Where the lifter stands before the first movement, said in the plane's
     * own words.
     *
     * Horizontal work gets the movement and NOT a position, deliberately.
     * Issue #241 asked for `"Start EXTENDED, first movement RETURN"` and
     * `"Start CONTRACTED, first movement DRIVE"`, and neither is written here.
     * That pairing holds on a horizontal PRESS and is inverted on a horizontal
     * PULL, and nothing in [ExerciseDef] distinguishes the two. A chest press
     * opening on the RETURN opens EXTENDED, which is what #241 says, because
     * the drive that preceded it ended extended; a seated row opening on the
     * RETURN opens CONTRACTED, the opposite, because its drive ended
     * contracted. One pair of words cannot be right for both. The word is what
     * the app knows; the position is not.
     *
     * A vertical lift that does not start at the top starts at the bottom,
     * whether its first word is [UP] or an explosive [DRIVE].
     */
    private fun phrase(word: String, horizontal: Boolean): String = when {
        horizontal -> "First movement $word"
        word == DOWN -> "Start at the TOP, first movement $DOWN"
        else -> "Start at the BOTTOM, first movement $word"
    }

    private fun marker(source: GeometrySource): String? = when (source) {
        GeometrySource.DECLARED, GeometrySource.SEEDED -> null
        GeometrySource.INFERRED -> GUESSED_MARKER
        GeometrySource.DEFAULT -> UNDECLARED_MARKER
    }
}

/**
 * The one extra line the prep countdown draws about WHERE in the rep the guide
 * will say the rep number (#266).
 *
 * Beside [StartCuePolicy] because it is the same kind of decision on the same
 * screen at the same moment -- a phrase the lifter reads standing at the bar,
 * under the line that tells them which way to move first -- and because `:app`,
 * where the countdown is drawn, has almost no test source set: a phrase left in
 * a composable is a phrase nothing on the CI path ever runs.
 *
 * ## Why a note is needed at all, and why only here
 *
 * On almost every prescription the number arrives at the START of the rep, in
 * place of that rep's first stroke word, so the lifter hears `"Rep 3"` where
 * they would have heard `"Down"` and nothing has to be explained (#293). On a
 * prescription of two one-second strokes and no closing pause there is no free
 * second at the start of the rep, and the owner ruled that those count at the
 * END of the drive instead: *"People are used to the reps being counted at
 * lockout, so this would be an easy cue."* and *"Have a note for that during
 * prep phase."*
 *
 * The note confirms a habit rather than teaching one, which is why it is one
 * short line and not an instruction. It is drawn only on the sets that use that
 * rule: a line present on every set would say nothing, and a line present on
 * the wrong set would tell the lifter to expect a number a second later than it
 * comes.
 *
 * ## What decides the flag, which is NOT here
 *
 * `CadencePlan.announcesAtConcentricEnd` in `:core:dsp`, off the (tempo, lift)
 * pair. `:core:model` declares no project dependency, so this side cannot see a
 * plan and takes the answer as a Boolean; the two are pinned equal in
 * `:core:dsp`, where a test can see both, exactly as `StartCueVoiceContractTest`
 * pins [StartCuePolicy.firstMovementWord] against the schedule's own beat 0.
 *
 * `[Field]`: no device has drawn this line. What it looks like under the #241
 * phrase on a narrow screen at a large font scale is unobserved, and the
 * discharge is the same session that checks the #241 block itself.
 */
object RepCallNotePolicy {
    /**
     * Said when the rep number lands at the end of the drive.
     *
     * "Drive" rather than "concentric" deliberately: the lifter's word, and the
     * one the app already uses aloud for the working stroke of a horizontal
     * lift. "End of each drive" is also true of a vertical lift whose drive is
     * DOWN -- a pulldown, a leg curl -- where "lockout" would not be.
     */
    const val AT_DRIVE_END = "Rep numbers mark the end of each drive"

    /**
     * The note for the set now being prepped, or null when it has none.
     *
     * Absence is a state rather than an empty string: null means the number
     * arrives where it arrives on every other set, and the lifter has nothing
     * extra to hold.
     */
    fun noteFor(repNumberAtDriveEnd: Boolean): String? = if (repNumberAtDriveEnd) AT_DRIVE_END else null
}
