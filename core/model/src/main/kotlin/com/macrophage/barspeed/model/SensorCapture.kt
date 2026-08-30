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
 * [plannedCount] and [count] are the pair, designed in from the start rather
 * than retrofitted (#151's lesson, applied at #156's first commit): the plan
 * declares a count, the lifter may change it in the app, and both halves are
 * recorded. They are equal when nothing was adjusted, which is the common case.
 *
 * [count] is DECLARED and is not [expected]`.size`. The two differ in exactly
 * one reachable case and it is the interesting one: a set that asked for two
 * sensors and could not arm them -- one unit paired, or the pair carrying no
 * role assignment -- records `count = 1` with an EMPTY [expected], because its
 * single stream carries no role and must not be given one. Reading the count
 * off the list would publish 0 sensors for a set that recorded with one.
 *
 * [analysed] is which role the DSP was pointed at, not which role produced
 * data. It is a fact about wiring, true at the moment the set began, and it
 * stays true when that unit's stream turns out to be empty -- the export then
 * shows an analysed role absent from the present list, which is exactly the
 * state a reader has to be able to see.
 */
@Serializable
data class RecordedSensors(
    /** What the plan declared for this set, or the app's default when it declared nothing. */
    val plannedCount: Int,
    /** How many sensors this set was actually armed with. */
    val count: Int,
    /**
     * The roles this set was armed for, in order.
     *
     * Empty means the streams carry no role: either a single-sensor set whose
     * plan asked for two, or a dual request that could not be armed. An empty
     * list is never written alongside a role on a raw stream.
     */
    val expected: List<SensorRole> = emptyList(),
    /** Which role's stream every figure in this set was computed from. */
    val analysed: SensorRole? = null,
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
     * The roles this set will capture, in order. Empty in single-sensor mode
     * and whenever a dual request could not be armed -- see [shortfall].
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
    /** Why two sensors were asked for and one is what will be captured. */
    val shortfall: DualShortfall? = null,
) {
    /** True when this set will run two collectors. */
    val isDual: Boolean get() = secondary != null
}

/**
 * Why a request for two sensors could not be armed.
 *
 * Each is a configuration gap the lifter can close before the set, which is why
 * they are named separately rather than collapsed into a boolean: the screen
 * has to say which thing to go and do. None of them refuses the set -- a
 * missing sensor is not a reason not to lift.
 */
enum class DualShortfall {
    /** Only one IMU is paired, so there is no second unit to capture from. */
    ONE_SENSOR_PAIRED,

    /** Two are paired and at least one carries no role, so a stream would be unlabelled. */
    ROLES_UNASSIGNED,

    /** Both paired units are labelled with the SAME role, so neither stream could be told from the other. */
    ROLES_COLLIDE,
}

/**
 * Every rule about how many sensors a set captures with, and which stream is
 * which.
 *
 * A `:core:model` object rather than logic in `:app` on purpose. `:core:ble`
 * has no test source set at all and `:app` has one file over one pure function,
 * so a decision left in either is a decision nothing can run against; lifted
 * here it is pinned on every push. This is the "extract a pure seam" move made
 * before the first defect rather than after the third.
 */
object SensorCapturePolicy {
    /** One sensor: the default everywhere, for every exercise, forever. */
    const val DEFAULT_COUNT = 1

    const val MIN_COUNT = 1

    /**
     * Two, and this is a capability bound rather than a taste. The record flow
     * runs one collector per stream and [SensorRole] has two entries; a third
     * would need a role, a client, a journal file and a column value that do
     * not exist.
     */
    const val MAX_COUNT = 2

    fun clamp(count: Int): Int = count.coerceIn(MIN_COUNT, MAX_COUNT)

    /**
     * What the PLAN prescribed for a set: its declaration, or the default.
     *
     * The planned half of the pair [resolve] answers the actual half of. Both
     * exist from the first commit rather than one being retrofitted, which is
     * #151's lesson: a figure worth recording is worth pairing with what was
     * asked for, and the pair cannot be reconstructed afterwards.
     */
    fun planned(declared: Int?): Int = clamp(declared ?: DEFAULT_COUNT)

    /**
     * The count a set will run with: the lifter's in-app adjustment, else the
     * plan's declaration, else the default. [LeadInPolicy.resolve]'s precedence
     * exactly, because it is the same kind of decision and two orderings would
     * be two rules.
     */
    fun resolve(declared: Int?, override: Int?): Int = clamp(override ?: declared ?: DEFAULT_COUNT)

    /** The wire spelling of a role: the lowercased name, as the published schemas state. */
    fun wireOf(role: SensorRole): String = role.name.lowercase()

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
     * What a set about to begin is armed with.
     *
     * The single-sensor path returns an empty roster, and that is what keeps a
     * one-sensor set byte-identical to what this app has always written: no
     * role reaches a raw stream, no declaration reaches the row, and no key
     * reaches either export document.
     *
     * Dual is armed only when TWO paired addresses carry two DIFFERENT assigned
     * roles. A positional default -- "the preferred one is A" -- was considered
     * and refused: the preferred address is movable at any time, by "Use this
     * one for analysis" (`DeviceRegistry.setPreferred`) and by forgetting the
     * analysed unit, so the meaning of A would change under the lifter and
     * every capture before and after that moment would be labelled
     * consistently and wrongly. The label has to be a property of the MAC or
     * it is not a label. An earlier draft rested this on `DeviceRegistry.pair`
     * making every newly paired device its role's preferred address; it stopped
     * doing that in the same branch, and the premise is deleted rather than
     * reworded. The conclusion is untouched.
     *
     * [preferredAddress] decides which role is [SensorRoster.analysed], and
     * nothing else does. The analysed stream is whichever unit the existing
     * client is maintaining; declaring some other preference would state that
     * the DSP looked at a stream it did not look at.
     *
     * A shortfall never refuses the set. It downgrades to one sensor, records
     * [DualShortfall] for the screen to explain, and leaves [RecordedSensors]
     * to say afterwards that two were asked for.
     */
    fun roster(
        pairedImuAddresses: List<String>,
        preferredAddress: String?,
        roleByAddress: Map<String, SensorRole>,
        requestedCount: Int?,
    ): SensorRoster {
        val paired = pairedImuAddresses.distinct()
        val unassigned = paired.filter { it !in roleByAddress }
        if (clamp(requestedCount ?: DEFAULT_COUNT) < MAX_COUNT) return SensorRoster(unassigned = unassigned)
        val analysedAddress = preferredAddress?.takeIf { it in paired }
        val secondaryAddress = paired.firstOrNull { it != analysedAddress }
        if (analysedAddress == null || secondaryAddress == null) {
            return SensorRoster(unassigned = unassigned, shortfall = DualShortfall.ONE_SENSOR_PAIRED)
        }
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
     * Null on the ordinary set: one sensor asked for, one armed. Everything
     * else is written, INCLUDING a set that asked for two and armed one --
     * that is the case a reader could never otherwise recover, and leaving it
     * null would make a shortfall indistinguishable from a plain single-sensor
     * set for the whole life of the corpus.
     */
    fun recorded(plannedCount: Int, roster: SensorRoster): RecordedSensors? {
        val planned = clamp(plannedCount)
        val count = if (roster.isDual) MAX_COUNT else DEFAULT_COUNT
        if (planned == DEFAULT_COUNT && count == DEFAULT_COUNT) return null
        return RecordedSensors(
            plannedCount = planned,
            count = count,
            expected = roster.expected,
            analysed = roster.analysed,
        )
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
