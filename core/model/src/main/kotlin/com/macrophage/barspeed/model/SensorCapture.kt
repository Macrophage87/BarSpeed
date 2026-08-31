package com.macrophage.barspeed.model

import kotlinx.serialization.Serializable

/**
 * Which physical accelerometer a stream came from, issue #156.
 *
 * A and B, never left and right, and that is the owner's ruling rather than a
 * preference: *"They're likely to be accidentally flipped a lot and fixed in
 * post processing."* So the role is the identity of a UNIT and carries no
 * anatomical claim at all. Nothing in the export says A was on the left end of
 * the bar or in the right hand; a mounting swapped between sets corrupts
 * nothing, because there was never a statement to contradict. Whatever meaning
 * the two ends of a bar have is assigned downstream, by an analysis holding
 * both streams, and it is assigned to a pair of labelled streams rather than
 * recovered from an unlabelled one.
 *
 * It is also why this is deliberately NOT [PlanFile.VALID_SIDES]' vocabulary.
 * `side` says which limb was WORKED and is a copy of the prescription (#144);
 * this says where a sensor was. Two facts sharing a vocabulary in one document
 * is how a reader comes to believe the app measured something it never looked
 * at.
 *
 * The wire form is the lowercased name, as every other enum-derived vocabulary
 * in this contract is -- `startsWith`, `plane`, `kind`. Read it back with
 * [SensorCapturePolicy.roleFromWire], which answers null for anything it does
 * not know rather than defaulting.
 */
enum class SensorRole { A, B }

/**
 * What a recorded set was armed with, and which stream its figures came from.
 *
 * Stored on the set row because it cannot be re-derived. What ARRIVED is
 * observable from the streams themselves; what was EXPECTED is observable from
 * nothing. Without this, a dual-armed set that captured one stream because a
 * unit's battery died is indistinguishable from an ordinary single-sensor set,
 * permanently -- the gap-that-cannot-be-represented class, which is the reason
 * this type exists at all rather than the role column alone.
 *
 * A `plannedCount` stood beside [count] until #198 and is gone with the
 * declaration it read. Nothing in a plan decides how many accelerometers a set
 * records: one bar sensor records one stream, two units PAIRED and labelled
 * arm two, and a figure written for what a coach intended would be a default
 * dressed as an intention.
 *
 * [count] is DECLARED and is not [expected]`.size`. The two differ in exactly
 * one reachable case and it is the interesting one: a set that met two PAIRED
 * units it could not tell apart records `count = 1` with an EMPTY [expected],
 * because its single stream carries no role and must not be given one. Reading
 * the count off the list would publish 0 sensors for a set that recorded with
 * one, and [shortfall] is what says why.
 *
 * [analysed] is which role the DSP was pointed at, not which role produced
 * data. It is a fact about wiring, true at the moment the set began, and it
 * stays true when that unit's stream turns out to be empty -- the export then
 * shows an analysed role absent from the present list, which is exactly the
 * state a reader has to be able to see.
 */
@Serializable
data class RecordedSensors(
    /** How many sensors this set was actually armed with. */
    val count: Int,
    /**
     * The roles this set was armed for, in order.
     *
     * Empty means the stream carries no role, which is every set that did not
     * arm two: one paired unit, or two the app could not tell apart. An empty
     * list is never written alongside a role on a raw stream.
     */
    val expected: List<SensorRole> = emptyList(),
    /** Which role's stream every figure in this set was computed from. */
    val analysed: SensorRole? = null,
    /**
     * Why this set recorded one stream when two units were PAIRED, or null when
     * there was nothing in the way.
     *
     * PAIRED IS NOT CONNECTED, and this field says the weaker thing.
     * [SensorCapturePolicy.roster] reads a persisted list of units the app
     * REMEMBERS and no link state at all, so a shortfall says two units are
     * paired and cannot be told apart -- not that both were switched on or in
     * range. Whether the second one was ever powered is a question this field
     * does not answer and `present` does.
     *
     * Null on a dual set and null on the ordinary single-sensor set, so it is
     * never the difference between them. What it carries is the third state:
     * two paired units the app could not tell apart, which records one stream
     * and would otherwise be indistinguishable from having owned one sensor.
     * Before #198 that fact rode on a `plannedCount` of 2 beside a `count` of
     * 1; with nothing declared there is no such pair, so the reason is stored
     * outright or it is unsayable.
     *
     * IT DESCRIBES THE DEVICE ROSTER RATHER THAN THE SET, so it lands on every
     * set of a session rather than on the ones something went wrong on -- one
     * stale paired unit is enough, and that is now the ordinary way to see it.
     * Written per set anyway: writing it once would make a session recorded
     * entirely under an unusable pair indistinguishable from a one-sensor
     * session, which is the distinction it exists for.
     *
     * ONE HISTORICAL GAP, and it is not fixed here. A row an older build wrote
     * for the same situation stored a `plannedCount` of 2 beside a `count` of
     * 1 and no word; this build does not read that key, so such a row decodes
     * with `shortfall` null and re-exports as though nothing was in the way.
     * Synthesising a reason from the count would publish something this build
     * never observed, so the published contract states the exception instead --
     * see the `shortfall` description in `docs/schemas/session-export.schema.json`
     * and `SessionExportSensorsTest.a row written under the retired planned
     * count re-exports with no reason`.
     */
    val shortfall: DualShortfall? = null,
) {
    /**
     * The role of the stream that is NOT analysed, or null when there is none.
     *
     * Derived rather than stored: with at most two roles it is a lookup, and a
     * stored second copy is a field that can disagree with [expected] and
     * [analysed] after a decode of a document written by another build.
     */
    val secondaryRole: SensorRole? get() = expected.firstOrNull { it != analysed }
}

/**
 * What a set is armed with, resolved from what is paired, what is preferred and
 * what the lifter has labelled.
 *
 * Produced by [SensorCapturePolicy.roster] and consumed by the record flow and
 * by the READY screen. It exists so that ONE function decides whether a second
 * stream is captured; the same decision restated at the collector and at the
 * screen would be two decisions that can disagree about a set already running.
 */
data class SensorRoster(
    /**
     * The roles this set will capture, in order. Empty whenever one stream is
     * what will be recorded -- one unit paired, or two that cannot be told
     * apart, which [shortfall] names.
     */
    val expected: List<SensorRole> = emptyList(),
    /** Which role's stream feeds the DSP; null when no role is in play. */
    val analysed: SensorRole? = null,
    /** The role of the second stream, when there is one. */
    val secondary: SensorRole? = null,
    /** The address of the second sensor, when there is one. */
    val secondaryAddress: String? = null,
    /**
     * Paired IMU addresses carrying no role assignment. The Devices screen's
     * work list, and the reason a dual request is refused rather than guessed
     * at.
     */
    val unassigned: List<String> = emptyList(),
    /** Why two paired units cannot be told apart and one stream is what will be captured. */
    val shortfall: DualShortfall? = null,
) {
    /** True when this set will run two collectors. */
    val isDual: Boolean get() = secondary != null
}

/**
 * Why two or more PAIRED units recorded one stream between them.
 *
 * PAIRED, not connected, and the difference is the pin #198 asked for. This is
 * decided from [DualSensorSetup.step], which reads the paired list and the
 * labels on it and no link state whatsoever, so both members say something
 * about what the app REMEMBERS rather than about what was switched on. A unit
 * left in a bag all session is still paired and still produces one of these.
 *
 * NOT a request that went unmet, since #198: nothing asks for two sensors, so
 * there is nothing to fall short of. Both members say the same thing about the
 * roster -- two or more units are paired and the app cannot tell them apart --
 * and differ in what the lifter has to go and do about it, which is why they
 * are named separately rather than collapsed into a boolean.
 *
 * `ONE_SENSOR_PAIRED` was a third member and is dissolved rather than renamed.
 * One bar sensor is the ordinary case for every exercise, not a degraded two;
 * reporting it as a gap put a permanent complaint in front of every
 * single-sensor lifter about a unit they do not own.
 *
 * BOTH MEMBERS DESCRIBE THE ROSTER RATHER THAN THE SET, so once one is
 * reachable it is reachable on every set of the session until the lifter
 * changes what is paired. That is deliberate and it is stored per set anyway;
 * [RecordedSensors.shortfall] states why.
 *
 * Neither refuses the set. A sensor the app cannot label is not a reason not
 * to lift.
 */
enum class DualShortfall {
    /** Two or more are paired and at least one carries no role, so a stream would be unlabelled. */
    ROLES_UNASSIGNED,

    /** Every paired unit is labelled and two of them share a role, so neither stream could be told from the other. */
    ROLES_COLLIDE,
}

/**
 * Every rule about how many sensors a set captures with, and which stream is
 * which.
 *
 * A `:core:model` object rather than logic in `:app` on purpose. `:core:ble`
 * has no test source set at all, and no test on the CI path reaches `:app`'s
 * Android classes, so a decision left in either is a decision nothing can run
 * against; lifted
 * here it is pinned on every push. This is the "extract a pure seam" move made
 * before the first defect rather than after the third.
 */
object SensorCapturePolicy {
    /** One sensor: what a set records unless a second one is connected and labelled. */
    const val DEFAULT_COUNT = 1

    /**
     * The floor the plan's now-inert `sensors` key is still validated against.
     *
     * It decides no capture -- see [roster] -- but a document declaring 0 or
     * -1 is still refused with the path named, because loosening a published
     * bound is a contract change nobody asked for and a nonsense figure says
     * its author misunderstood something worth telling them about.
     */
    const val MIN_COUNT = 1

    /**
     * Two, and this is a capability bound rather than a taste. The record flow
     * runs one collector per stream and [SensorRole] has two entries; a third
     * would need a role, a client, a journal file and a column value that do
     * not exist.
     */
    const val MAX_COUNT = 2

    /** The wire spelling of a role: the lowercased name, as the published schemas state. */
    fun wireOf(role: SensorRole): String = role.name.lowercase()

    /**
     * The wire spelling of a gap: lowerCamel, as `velocityLossBasis`' values
     * already are.
     *
     * A separate name rather than a [wireOf] overload, because `wireOf` is
     * passed as a method reference in FIVE places -- `Exporters.kt` at four
     * call sites and `SessionRepository.kt` at one, counted by
     * `git grep -o 'SensorCapturePolicy::wireOf'` -- and an overload would make
     * every one of them ambiguous. An earlier version of this sentence said
     * three, which was never measured.
     */
    fun shortfallToWire(shortfall: DualShortfall): String = when (shortfall) {
        DualShortfall.ROLES_UNASSIGNED -> "rolesUnassigned"
        DualShortfall.ROLES_COLLIDE -> "rolesCollide"
    }

    /**
     * A role read back off the wire, or null for anything this build does not
     * know.
     *
     * Null rather than a default, and rather than a throw. A role written by a
     * later build has been seen by no validator here, and `enum.valueOf` throws
     * from inside a decode path that has no catch above it -- while a defaulted
     * `A` would silently relabel the other unit's stream. Absence is the only
     * honest third answer.
     */
    fun roleFromWire(wire: String?): SensorRole? =
        wire?.let { w -> SensorRole.entries.firstOrNull { it.name.equals(w, ignoreCase = true) } }

    /**
     * What a set about to begin is armed with, decided by the hardware and
     * nothing else -- which units are PAIRED, and how they are labelled.
     *
     * The rule, in the owner's words (#198): *"If you've got one use one, if
     * you've got two, use both."* No count is passed, because no count
     * decides. What the plan declared and what the lifter picked in the app
     * both used to gate this and both are gone: recording is cheap and
     * irreversible, not recording is free and unrecoverable, and a second
     * stream nobody wanted is ignored in analysis while a second stream never
     * captured is gone.
     *
     * PAIRED IS NOT CONNECTED, and the distinction is what makes the missing
     * control unnecessary. This function reads the paired set because that is
     * what the app can enumerate before a set begins; a paired unit that is
     * powered off or out of range brings no link up and puts no samples in the
     * buffer, so leaving the second sensor in the bag needs no setting. That
     * makes one requirement load-bearing downstream rather than here: the
     * ARCHIVE must record what streamed, not what was armed. It does --
     * `SessionRepository.recordSet` writes the second stream's row only when
     * that buffer holds samples, and the export's `present` is the roles that
     * reached the archive rather than the roles armed.
     *
     * The single-sensor path returns an empty roster, and that is what keeps a
     * one-sensor set byte-identical to what this app has always written: no
     * role reaches a raw stream, no declaration reaches the row, and no key
     * reaches either export document.
     *
     * Dual is armed only when TWO paired addresses carry two DIFFERENT
     * assigned roles. A positional default -- "the preferred one is A" -- was
     * considered and refused: the preferred address is movable at any time, by
     * "Use this one for analysis" (`DeviceRegistry.setPreferred`) and by
     * forgetting the analysed unit, so the meaning of A would change under the
     * lifter and every capture before and after that moment would be labelled
     * consistently and wrongly. The label has to be a property of the MAC or
     * it is not a label. Two 20-byte WitMotion frames carry no checksum, so
     * interleaved unlabelled streams do not fail loudly -- they fabricate
     * plausible samples, which is why labelling stays required even though
     * counting does not.
     *
     * [preferredAddress] decides which role is [SensorRoster.analysed], and
     * nothing else does. The analysed stream is whichever unit the existing
     * client is maintaining; declaring some other preference would state that
     * the DSP looked at a stream it did not look at. Where the preference
     * names no paired unit the set arms one stream and reports NO shortfall:
     * that state is a stale registry entry rather than a pair the app cannot
     * tell apart, and it used to draw "Fewer than two sensors are paired" on a
     * setup with two.
     *
     * A shortfall never refuses the set. It records one stream, names
     * [DualShortfall] for the screen to explain, and leaves [RecordedSensors]
     * to say afterwards that two units were paired and the pair was unusable.
     *
     * WHICH units are the pair is [DualSensorSetup.step]'s answer, since
     * #192: dual arms only from [DualSetupStep.READY], which is exactly two
     * paired units carrying different labels. A third paired unit therefore
     * arms nothing, and that is a decision rather than a consequence. What it
     * replaced chose the second unit positionally -- the first paired address
     * that is not the analysed one -- so with three units paired, the one the
     * second link held was whichever `DeviceRegistry` happened to list first:
     * an unlabelled third unit was skipped while the set armed dual, and a
     * third unit duplicating the second's label was never compared at all,
     * which armed two units the lifter cannot tell apart while the Devices
     * screen was telling them the labels collide. It is the same positional
     * default the paragraph above refuses for a pair; it survived for a trio
     * only because nobody had three paired.
     *
     * The shortfall a not-ready setup reports is that step's own reading:
     * [DualSetupStep.LABEL_BOTH] is [DualShortfall.ROLES_UNASSIGNED] and
     * [DualSetupStep.LABELS_COLLIDE] is [DualShortfall.ROLES_COLLIDE], so the
     * sentence the Devices screen draws and the reason stored on the set are
     * one reading of one state rather than two that can disagree. The step is
     * consulted FIRST now, ahead of the addresses: it is the only reading that
     * can tell one paired unit from two unlabelled ones, and reading the
     * addresses first is what made a single sensor report a gap.
     *
     * One consequence of consulting it first and unconditionally, named
     * because it is the owner's ordinary state rather than an edge case: a
     * third paired unit puts a shortfall on EVERY set. Two correctly labelled
     * sensors plus one stale paired unit answers [DualSetupStep.LABEL_BOTH],
     * so the row stores `ROLES_UNASSIGNED` for as long as that unit stays
     * paired. Before #198 the count gate returned before `step` was consulted
     * on every plan that did not declare 2, so the same setup stored nothing.
     * The ARMING is unchanged and deliberate; what is new is that the reason is
     * written down every time, and the screens and the published description
     * are worded for it rather than for exactly two units.
     */
    fun roster(
        pairedImuAddresses: List<String>,
        preferredAddress: String?,
        roleByAddress: Map<String, SensorRole>,
    ): SensorRoster {
        val paired = pairedImuAddresses.distinct()
        val unassigned = paired.filter { it !in roleByAddress }
        when (DualSensorSetup.step(paired, roleByAddress)) {
            // Nothing to tell apart. One paired unit is the ordinary case
            // for every exercise and none at all is a manual set; neither is a
            // gap in a setup, and saying so was #198's first correction.
            DualSetupStep.NO_SENSOR, DualSetupStep.ONE_SENSOR ->
                return SensorRoster(unassigned = unassigned)
            DualSetupStep.LABEL_BOTH ->
                return SensorRoster(unassigned = unassigned, shortfall = DualShortfall.ROLES_UNASSIGNED)
            DualSetupStep.LABELS_COLLIDE ->
                return SensorRoster(unassigned = unassigned, shortfall = DualShortfall.ROLES_COLLIDE)
            DualSetupStep.READY -> Unit
        }
        // READY is exactly two paired units carrying different labels, so the
        // only way past here without a pair of addresses is a preference
        // naming neither of them.
        val analysedAddress = preferredAddress?.takeIf { it in paired }
        val secondaryAddress = paired.firstOrNull { it != analysedAddress }
        if (analysedAddress == null || secondaryAddress == null) return SensorRoster(unassigned = unassigned)
        val analysed = roleByAddress[analysedAddress]
        val secondary = roleByAddress[secondaryAddress]
        if (analysed == null || secondary == null) {
            return SensorRoster(unassigned = unassigned, shortfall = DualShortfall.ROLES_UNASSIGNED)
        }
        if (analysed == secondary) {
            return SensorRoster(unassigned = unassigned, shortfall = DualShortfall.ROLES_COLLIDE)
        }
        return SensorRoster(
            expected = listOf(analysed, secondary),
            analysed = analysed,
            secondary = secondary,
            secondaryAddress = secondaryAddress,
            unassigned = unassigned,
        )
    }

    /**
     * What to store on a set the record flow has just finished arming, or null
     * when there is nothing to say.
     *
     * Null on the ordinary set: one bar sensor, one stream, no roles. That is
     * what keeps such a set's row and both export documents exactly what this
     * app has always written, and it is the common case.
     *
     * Written in the two states that are not ordinary. A dual set records both
     * roles and which one the DSP was pointed at. A set that met two PAIRED
     * units it could not tell apart records `count = 1`, no roles, and WHY --
     * and that second case is the one a reader could never otherwise recover.
     * Before #198 it rode on a plan's declaration of 2 sitting beside an armed
     * count of 1; with nothing declared, a null here would make two paired
     * units the app could not label indistinguishable from a one-sensor set
     * for the whole life of the corpus.
     */
    fun recorded(roster: SensorRoster): RecordedSensors? {
        if (roster.isDual) {
            return RecordedSensors(
                count = MAX_COUNT,
                expected = roster.expected,
                analysed = roster.analysed,
            )
        }
        val shortfall = roster.shortfall ?: return null
        return RecordedSensors(count = DEFAULT_COUNT, shortfall = shortfall)
    }

    /**
     * Which of the armed roles actually reached the archive, in the order they
     * were armed in.
     *
     * Stated in the export rather than left to be inferred from which files a
     * consumer happens to find: the summary document does not list filenames at
     * all, so a reader holding only `session.json` could not otherwise tell a
     * captured role from a missing one. The roles MISSING are the set
     * difference, deliberately not a third key -- a duplicate statement is one
     * that can disagree with its own inputs.
     */
    fun present(expected: List<SensorRole>, captured: Collection<SensorRole>): List<SensorRole> =
        expected.filter { it in captured }
}
