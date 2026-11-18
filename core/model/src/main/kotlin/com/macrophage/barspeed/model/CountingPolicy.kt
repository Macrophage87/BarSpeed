package com.macrophage.barspeed.model

/**
 * Who counts the reps of a set while it is being performed.
 *
 * Four counters, and a set is counted by exactly one of them. Which one is
 * [CountingPolicy.counterFor]'s decision, and it decides three further things
 * that were decided separately in `:app`: what the ring draws, what the
 * `+1 REP` tap means, and which count the finished set is recorded as.
 *
 * NOT the same question as [SetVoicePolicy.guidesFor], which asks who SPEAKS.
 * The two agree about the sensor -- `SetVoicePolicy`'s sensor term is this
 * decision read for one member -- and deliberately disagree elsewhere: a
 * [RepCounter.MANUAL] set is counted by the lifter and has no voice guide at
 * all, and a [RepCounter.NOBODY] set is spoken over by its own clock while
 * counting no reps.
 */
enum class RepCounter {
    /**
     * The sensor, live: a drive detector run over the stream as it arrives, on a
     * rep-based lift with no prescribed tempo and an IMU connected.
     *
     * WHICH detector is [LiveCounterPolicy.counterFor]'s answer and not a fact
     * about this member. It is `LockoutRepCounter` on a squat since the
     * owner's 2026-11-06 session and `CycleRepCounter` on every other lift;
     * it was `CycleRepCounter` on every lift since issue #305, was
     * `DriveImpulseCounter` from #301 and `LiveRepCaller` from #286 until then;
     * the naming here read `LiveRepCaller`
     * as though the choice were fixed, and it is not.
     *
     * The count it produces can be wrong. Issue #284 measured the BATCH
     * detector over-counting every committed concentric-first capture that
     * carries a hand count, six of six by +1 to +4, and the segmenter-based LIVE
     * detector reading 6 for 5 performed on a synthetic hitch and 1 for 5 on a
     * synthetic drop. The counter that ships now was measured on field-43 at 13
     * of 15 deadlift reps with no phantom and at over-counts of up to +3 on the
     * tempo'd overhead-press captures it does not count
     * (`LiveCountDifferentialTest`). Either way the count can be wrong, which is
     * why the lifter's tap on such a set is a correction
     * ([RepTap.CORRECTION]) rather than being ignored, and why the export says
     * whose count it published.
     */
    SENSOR,

    /**
     * The lifter, by tapping `+1 REP`: a rep-based set with no sensor
     * connected, and an untimed hold or carry.
     *
     * The one count in a set that no reprocessing of any stream can rebuild.
     */
    MANUAL,

    /**
     * The metronome: a tempo'd set's cadence guide, which calls each rep on
     * its own schedule whether or not the lifter follows it.
     *
     * `GuidedCadenceRunner.onRepCounted` is the counter, and the lifter does
     * not tap on such a set ([RepTap.IGNORED]).
     */
    METRONOME,

    /**
     * Nobody. A set measured in seconds has one movement lasting the whole
     * set, so there is no rep to count and no count to record.
     *
     * Distinct from [MANUAL] with a count of zero: the absence of a counter is
     * not a count of nothing. Such a row stores the batch segmenter's count in
     * `actualReps`, which no counter stands behind, and from export 1.23
     * publishes no `reps` (#71, [RepsSourcePolicy.publishedReps]).
     */
    NOBODY,
}

/** What one `+1 REP` tap does on a set counted by a given [RepCounter]. */
enum class RepTap {
    /** The tap IS the count: each one is a rep the lifter says they performed. */
    COUNT,

    /**
     * The tap CORRECTS a count something else made -- it moves the recorded
     * count one rep above what the sensor has called, and the sensor goes on
     * counting behind it.
     *
     * Stored as a correction rather than as the count: the row keeps the
     * sensor's own figure beside the corrected one, so a first session's live
     * count can still be scored against a hand count afterwards.
     */
    CORRECTION,

    /** Nothing. Another counter owns this set and a tap would fight it. */
    IGNORED,
}

/**
 * Which counter counts a set of this shape, and what follows from it.
 *
 * ## Why this is a type and not `manualSet`
 *
 * It was `manualSet`, computed in `RecordViewModel.beginSet` as
 * `!currentIsTimed && (kind != EXPLOSIVE || !imuConnected)` and forced true
 * again for a guided set. That boolean had four jobs -- which in-set branch
 * draws, whether a `+1 REP` tap is accepted, which counter completion is
 * judged against, and which count is frozen onto the row -- and the four stop
 * having one answer as soon as the sensor counts a straight-reps set: the
 * sensor counts it, the tap corrects it, and completion is still not judged
 * against the sensor's total. One boolean cannot say that. A second copy of
 * the same expression in `RecordScreen`'s START SET label could be reached by
 * no test on the CI path at all.
 *
 * ## The owner's rule
 *
 * 2026-09-12, issue #286: *"The sensor should count the reps."* A straight-reps
 * dynamic set with a sensor connected is counted by the sensor from here on;
 * the deadlift is the first such lift this app will record (#284).
 */
object CountingPolicy {
    /**
     * The counter for a set of this shape.
     *
     * [hasTempo], [isTimed] and [kind] are the set's prescription;
     * [imuConnected] is the device. The tempo/timed/kind pairing is not
     * re-derived here -- [LeadInPolicy.prepCase] owns it, and the metronome
     * counts exactly the set whose prep runs into a cadence, which is why an
     * explosive lift carrying a tempo string is not counted by one.
     *
     * A hold or a carry is never [RepCounter.SENSOR], even with a sensor
     * connected and no tempo. That is issue #217's lesson stated as a counting
     * rule rather than a voice one: what the phase counter counted on
     * field-37's sets 11 and 12 was whatever the sensor made of a lifter
     * hanging still.
     */
    fun counterFor(hasTempo: Boolean, isTimed: Boolean, kind: ExerciseKind, imuConnected: Boolean): RepCounter = when {
        LeadInPolicy.prepCase(hasTempo, isTimed, kind) == PrepCase.CUED -> RepCounter.METRONOME
        isTimed -> RepCounter.NOBODY
        imuConnected && countsReps(kind) -> RepCounter.SENSOR
        else -> RepCounter.MANUAL
    }

    /**
     * Whether the sensor counts this set -- the one question the arriving
     * sample asks. The same answer read for one member, so the two cannot
     * drift apart.
     */
    fun sensorCounts(hasTempo: Boolean, isTimed: Boolean, kind: ExerciseKind, imuConnected: Boolean): Boolean =
        counterFor(hasTempo, isTimed, kind, imuConnected) == RepCounter.SENSOR

    /**
     * Whether the lifter's own tally is the count this set is recorded as,
     * which is the question `manualSet` was asked most often.
     *
     * True for [RepCounter.MANUAL] and [RepCounter.METRONOME], because both
     * write their count into the same tally the row freezes. False for the
     * sensor, whose count is recorded from its own field, and for a set
     * nothing counts.
     */
    fun tallyIsTheCount(counter: RepCounter): Boolean = counter == RepCounter.MANUAL ||
        counter == RepCounter.METRONOME

    /** What one `+1 REP` tap does on a set this counter is counting. */
    fun tapMeaning(counter: RepCounter): RepTap = when (counter) {
        RepCounter.MANUAL -> RepTap.COUNT
        RepCounter.SENSOR -> RepTap.CORRECTION
        RepCounter.METRONOME, RepCounter.NOBODY -> RepTap.IGNORED
    }

    /**
     * Which words the in-set voice says at and just before the planned count.
     *
     * [VoiceMilestonePolicy.Milestones.MARKS_TARGET] on a [RepCounter.SENSOR]
     * set, and that is a defect fix rather than a taste decision.
     * `VoiceMilestonePolicy.repMilestone` says `"Done"` at the planned count
     * under the other answer, and every spoken word is written to the cue
     * track, so a counter reaching the planned count puts a terminal word on
     * the record at that instant. That word no longer bounds the analysed rep
     * list of a set no cadence ran on -- `SetEnd.of` takes
     * `SetTargets.cadenceGuided` since #285 -- but it is still what the lifter
     * HEARS, and a sensor whose count runs ahead calling the set over is a
     * wrong thing to say.
     *
     * A sensor-counted set is exactly the set where the counter can reach the
     * planned count without the lifter having finished, and a max-intent
     * straight-reps set is exactly the set where the lifter may do MORE reps
     * than were prescribed. So the sensor names every rep and never calls the
     * set over: no `"Done"`.
     *
     * A [RepCounter.MANUAL] set keeps the milestone, and keeping it is the
     * decision #285 took: the lifter hears the same word at the planned count,
     * because the voice is the live channel and the count feedback is what it
     * is for. What changed is what the ANALYSER does with the word, not what
     * is said.
     *
     * The planned count itself is handed to every counter. Until this answer
     * existed the sensor was handed none (`milestonePlannedReps`), which was
     * the only way to keep `"Done"` off it.
     */
    fun milestones(counter: RepCounter): VoiceMilestonePolicy.Milestones = if (counter == RepCounter.SENSOR) {
        VoiceMilestonePolicy.Milestones.MARKS_TARGET
    } else {
        VoiceMilestonePolicy.Milestones.CALLS_SET_OVER
    }

    /**
     * What the START SET button says about who is about to count.
     *
     * The label read `"START SET — you count"` on every set that was not an
     * explosive lift with a sensor, from a second copy of `beginSet`'s
     * expression held in `RecordScreen`. That copy ignored the tempo, so a
     * guided set -- counted by the metronome -- told the lifter they were
     * counting it.
     */
    fun startSetLabel(counter: RepCounter): String = when (counter) {
        RepCounter.MANUAL -> "START SET — you count"
        RepCounter.SENSOR -> "START SET — sensor counts"
        RepCounter.METRONOME -> "START SET — voice counts"
        RepCounter.NOBODY -> "START SET"
    }

    /**
     * Whether a sensor is there to count the coming set -- the `imuConnected`
     * that [counterFor], [sensorCounts] and `SetVoicePolicy.sensorCounts` are
     * handed at the start of a set.
     *
     * [armedConnected] is the link to the preferred unit, the one a set is
     * armed to analyse. [partnerConnected] is the second labelled unit's link;
     * the caller passes false unless the set is armed for two, and nothing
     * here checks that. [mountSpecific] is
     * `LiftDirection.mountSpecific` for the set's own declaration.
     *
     * UNTIL THIS EXISTED ONLY THE ARMED LINK WAS ASKED. On a dual setup whose
     * preferred unit had gone quiet -- a flat battery, a unit out of range --
     * every set started as [RepCounter.MANUAL] with the partner streaming, and
     * it stayed that way set after set: the lifter heard no rep called, saw
     * "MANUAL COUNT", and nothing on the screen moved the count back to a
     * sensor while the preferred unit stayed down. The owner's report,
     * 2026-11-18.
     *
     * THE PARTNER COUNTS ONLY WHERE THE DECLARATION NAMES NO MOUNT. A partner
     * that is streaming while the armed unit delivers nothing takes the live
     * feed within [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] frames
     * ([LiveFeedPolicy]), and what the count does at that switch is
     * [LiveFallbackPolicy.atSwitch]'s answer: a mount-free declaration --
     * every barbell lift -- describes the partner as well as the armed unit and
     * the count CONTINUES on the partner's stream. A mount-specific one (a
     * stack, an inversion, a pulley ratio) has no live measurement that can
     * answer for the partner on a straight-reps set and is WITHHELD at the
     * switch, so arming such a set as [RepCounter.SENSOR] would only trade
     * "MANUAL COUNT" for a count that stops after a few frames. Those sets
     * keep the lifter's tally, as before.
     *
     * What is CAPTURED does not change: both streams are archived whichever
     * way this answers, and the set's figures stay
     * [SensorCapturePolicy.analysedStream]'s answer over the whole set. WHO
     * THE COUNT IS RECORDED FROM does: a set this turns from
     * [RepCounter.MANUAL] to [RepCounter.SENSOR] is written from the live
     * count and published as `sensor` rather than from the lifter's tally as
     * `manual`.
     */
    fun sensorLinked(armedConnected: Boolean, partnerConnected: Boolean, mountSpecific: Boolean): Boolean =
        armedConnected || (partnerConnected && !mountSpecific)

    /** Whether a set of this kind has reps for a sensor to count at all. */
    private fun countsReps(kind: ExerciseKind): Boolean = kind == ExerciseKind.DYNAMIC ||
        kind == ExerciseKind.EXPLOSIVE
}
