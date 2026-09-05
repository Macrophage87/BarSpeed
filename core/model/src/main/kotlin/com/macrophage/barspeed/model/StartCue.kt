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

    /** First movement of a horizontal lift that opens on its working stroke. */
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
     * Not implemented at this commit. The symbol exists so the differentials
     * that pin the exact words can compile and red one assertion at a time;
     * there was no earlier behaviour to lift, because the prep countdown said
     * nothing about the start at all. Nothing calls this yet.
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
     */
    fun of(startsWith: StartPhase, concentricUp: Boolean, horizontal: Boolean, source: GeometrySource): StartCue =
        TODO("#241 start cue for $startsWith/up=$concentricUp/horizontal=$horizontal/$source")
}
