package com.macrophage.barspeed.model

/**
 * The stream from the accelerometer that is not analysed, with the role that
 * identifies it.
 *
 * Non-null role by construction -- see `CompletedSet.secondary` in
 * `:core:data`, whose field this is. The samples may still be empty, which is
 * the armed-but-absent case: the unit was declared, its battery was flat, and
 * no row is written for it. That is a different fact from the set not having
 * been armed for it at all, and `CompletedSet.sensors` is what keeps the two
 * apart.
 *
 * IT MOVED HERE FROM `:core:data` AT #212, unchanged, because [armedCaptureOf]
 * moved out of `:app` into this module and returns one. `:core:model` cannot
 * see `:core:data`, and the alternative -- splitting the partner back into a
 * loose role and a loose list at the module edge -- would put the pairing this
 * type exists to hold back into `:app`, where nothing on the CI path executes
 * it. Both its fields were already `:core:model` types; its home in
 * `SessionRepository.kt` was locality with `CompletedSet`, not a dependency.
 */
data class SecondaryCapture(
    val role: SensorRole,
    val samples: List<ImuSample>,
)

/** [armedCaptureOf]'s three answers, which have to be decided together. */
data class ArmedCapture(
    val samples: List<ImuSample>,
    val sensors: RecordedSensors?,
    val secondary: SecondaryCapture?,
)

/**
 * Which capture the DSP is pointed at, and what the row will say about the
 * choice (#207).
 *
 * IT LIVES IN `:core:model` SINCE #212. It is a pure function of a
 * declaration, a role and two sample lists -- no Android, no Room, no BLE --
 * and while it sat in `:app` the only thing that could execute it was `:app`'s
 * own small test source set, which the house rules treat as a last resort.
 * [SensorCapturePolicy.analysedStream] decides the role half of this same
 * question and has always lived here; this is the buffer half, now beside it.
 * The move changed no behaviour: the same decisions in the same order over the
 * same inputs.
 *
 * A free function rather than a member of [SensorCapturePolicy]: it holds
 * BUFFERS, and every rule in that object answers from counts and roles alone.
 * Keeping it outside is what stops a later reader assuming the object can
 * reach a sample.
 *
 * The DECISION is [SensorCapturePolicy.analysedStream]'s. What is left here is
 * a lookup: the roles are keys and the buffers are values, so nothing on this
 * path can pair a role with the wrong capture.
 *
 * WHICH ROLES THE ANALYSIS MAY BE POINTED AT is
 * [SensorCapturePolicy.analysable]'s answer and NOT [SensorCapturePolicy.present]'s
 * (#209). Those are two questions: `present` is which roles reached the raw
 * archive, which the export publishes and which one frame satisfies, and
 * `analysable` is which roles delivered enough frames for
 * `VelocityEstimator.estimate` to run at all. Between them sits the set #209
 * was filed for -- an armed unit that delivered a handful of frames beside a
 * partner that delivered a full capture -- which this path used to answer with
 * `present` alone, keeping the armed role and publishing an empty summary over
 * the capture it was holding. The export goes on asking `present`, because the
 * archive really does hold that unit's file and a list omitting it would
 * misdescribe the zip.
 *
 * [ArmedCapture.samples] falls back to the analysed buffer whenever no role is
 * in play at all, which is the ordinary one-sensor set and the set that met
 * two paired units it could not tell apart. Both record one unroled stream and
 * neither has a second buffer to choose between.
 */
fun armedCaptureOf(
    armed: RecordedSensors?,
    secondaryRole: SensorRole?,
    analysedBuffer: List<ImuSample>,
    secondaryBuffer: List<ImuSample>,
    deliveryByRole: Map<SensorRole, ArmedDelivery> = emptyMap(),
    soleDelivery: ArmedDelivery? = null,
): ArmedCapture {
    val byRole = buildMap {
        armed?.analysed?.let { put(it, analysedBuffer) }
        secondaryRole?.let { put(it, secondaryBuffer) }
    }
    // Which roles the analysis CAN be pointed at, which is not which roles
    // reached the archive (#209). The frame counts come from the same buffers
    // the captures are taken from, so nothing here can judge one stream and
    // publish another.
    val framesByRole = byRole.mapValues { it.value.size }
    val analysable = SensorCapturePolicy.analysable(armed?.expected.orEmpty(), framesByRole)
    // `analysable` then `analysedStream`, composed once as `analysedFrom`
    // (#211), because a second reader asks the same question: `SetJournalStore`
    // answers it for a recovered capture. Two compositions of two functions are
    // two places for the order to drift. `analysable` is still read here as
    // well, because the silence words below key off the list rather than off
    // the choice.
    val decision = SensorCapturePolicy.analysedFrom(armed?.analysed, armed?.expected.orEmpty(), framesByRole)
    // Which armed roles delivered too few frames to analyse, and what the app
    // could see of each one's link when the set ended (#213, #209). The roles
    // come from `analysable` above and NOT from `present`, so since #209 a
    // role can appear in `present` and in `silent` at once -- that is the
    // change, not a disagreement.
    //
    // `ArmedSilencePolicy.silent` drops a role reading DELIVERING. That
    // combination IS reachable and its error direction is silence: deliveryOf
    // reads a SILENT_AFTER_MS lookback ending at endedAtMs, which can reach
    // back past the set's start, so a short set can publish no word for a role
    // that delivered nothing. Since #209 the same holds for a role that
    // delivered a handful of frames and stopped: if the last of them landed
    // inside that window the reading is DELIVERING, the word is dropped, and
    // `analysedFellBack` is left as the only statement that the analysis
    // moved off it.
    val silent =
        ArmedSilencePolicy.silent(armed?.expected.orEmpty().filterNot { it in analysable }, deliveryByRole)
    // And the same fact for the set whose single stream carries NO ROLE (#224),
    // which is one paired unit -- the ordinary configuration -- or two the app
    // cannot tell apart. `armed` is null on the first of those, so the
    // declaration is CONSTRUCTED rather than copied, and only where there is a
    // word: a one-sensor set whose unit delivered still stores nothing at all.
    // `SensorCapturePolicy.withSoleSilence` decides what the declaration
    // becomes.
    //
    // THE BUFFER IS WHAT DECIDES WHETHER THERE IS A WORD, and the link reading
    // only says WHICH word. `soleDelivery` is a reading of one link taken over
    // a fixed `ArmedSilencePolicy.SILENT_AFTER_MS` window ending when the set
    // ended, so a unit that fed this whole set and dropped in its last seconds
    // reads as silent; writing the word there would put "this unit delivered
    // nothing" onto a row sitting beside a full summary and a real imu.csv,
    // and a reader of that archive has no way to tell which half to believe.
    // Round 1 of #224 found it. `analysedBuffer` is the same source
    // `SensorCapturePolicy.analysable` is read from just above, so the roleless
    // set is judged by the fact the role-keyed set is judged by rather than by
    // a near neighbour of it -- including the SIZE of it, since #209: seven
    // frames is not an empty buffer and is not a capture either, and a
    // one-sensor set that delivered seven publishes "No sensor data recorded."
    // with nothing beside it to say the link went quiet. It is the buffer this
    // capture goes on to publish -- `decision.role` is null on every set that
    // reaches here, so `samples` below IS `analysedBuffer`.
    val sensors =
        SensorCapturePolicy.withSoleSilence(
            armed?.copy(analysed = decision.role, analysedFellBack = decision.fellBack, silent = silent),
            soleDelivery.takeIf { analysedBuffer.size < SensorCapturePolicy.MIN_ANALYSABLE_FRAMES },
        )
    return ArmedCapture(
        samples = decision.role?.let { byRole[it] } ?: analysedBuffer,
        sensors = sensors,
        // The partner is derived from the declaration rather than carried
        // alongside it, so it cannot name a role the declaration does not.
        // A role armed and silent still gets a SecondaryCapture with an empty
        // list, which is what the repository turns into no row and a
        // declaration that still names the role.
        secondary = sensors?.secondaryRole?.let { SecondaryCapture(it, byRole[it].orEmpty()) },
    )
}
