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
 * [analysed] is which role's stream the figures were actually computed from,
 * and since #207 that is a role that STREAMED wherever one did. It is decided
 * at the END of the set by [SensorCapturePolicy.analysedStream] rather than
 * when the set was armed: the preference names the unit whose link is
 * maintained, and pointing the DSP at that unit's empty buffer published an
 * empty summary over a capture the app was holding. [analysedFellBack] is
 * what says the two differ.
 *
 * It can still name a role absent from the present list, and there are
 * exactly two such sets: one where NOTHING streamed, whose figures are empty
 * because there was no capture at all; and one an earlier build recorded,
 * which kept the armed role whatever happened. Neither has figures drawn from
 * a surviving stream.
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
     * True when [analysed] is NOT the role the set was armed to analyse, and
     * the app moved onto it because the armed one produced nothing.
     *
     * A separate fact rather than a comparison a reader is left to make.
     * "Analysed the preferred unit" and "analysed the only unit that turned
     * up" are different statements about how much the figures below can be
     * compared with the rest of the corpus, and deriving the second from
     * `analysed !in present` cannot be done at all once the fallback lands:
     * after it, the analysed role IS present in both cases.
     *
     * False on every row an earlier build wrote, and that is correct rather
     * than a default standing in for the unknown -- no build before this one
     * could move the analysed role, so the role such a row names is the role
     * it armed. Absent from the encoded JSON when false, since the repository
     * encodes with kotlinx's default `encodeDefaults = false`; a row that fell
     * back is the only one that carries the key.
     */
    val analysedFellBack: Boolean = false,
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
    /**
     * Which ARMED roles put nothing in a buffer for this whole set, and what
     * the app could see of each one's link when the set ended (#213).
     *
     * The fact that survives beside [shortfall] rather than inside it.
     * [shortfall] describes the device ROSTER -- two paired units the app
     * cannot tell apart -- and is read before the set from a persisted list
     * with no link state in it at all. This describes THE SET, is read at the
     * end of it, and is the only place in the record that says anything about
     * whether a unit that was armed actually delivered.
     *
     * WHICH roles are silent is [expected] minus the roles that streamed, and
     * a reader can already compute that. The role here is a KEY, not a second
     * statement of it; what is new is the WORD, and the word is the thing
     * field-37 had no way to record. Thirteen sets published `present: ["a"]`
     * and nothing about whether `b` was switched off, out of range, answering
     * with the wrong GATT profile, or connected and silent -- and those have
     * three different remedies, of which the lifter was offered none.
     *
     * [ArmedDelivery] states what each word can and cannot be read as, and
     * `notLinked` in particular is a MERGE: nothing in this app reads
     * `BluetoothDevice.getBondState()`, so it covers powered-off, out of
     * range, refused and a bond removed in Settings behind the app's back.
     *
     * Empty on every ordinary set, and absent from the encoded JSON when
     * empty, since the repository encodes with kotlinx's default
     * `encodeDefaults = false`. Absence therefore reads correctly on every row
     * an earlier build wrote: no build before this one could observe an armed
     * unit's delivery, so a row that says nothing here is a row that was never
     * asked. The same rule [analysedFellBack] follows, and deliberately NOT
     * the one [expected] follows -- there is no informative empty here.
     *
     * It is stored on the row rather than derived at export, for
     * [analysedFellBack]'s reason: what a link looked like at the moment a set
     * ended is not recoverable afterwards from anything.
     */
    val silent: Map<SensorRole, ArmedDelivery> = emptyMap(),
    /**
     * What the app could see of the ONE armed link on a set whose stream
     * carries no role, or null when that link delivered or there was none
     * (#224).
     *
     * [silent]'s answer for the set that has no key to hang it off. A role
     * exists only where two paired units carry two different labels, so on one
     * bar sensor -- the ordinary configuration, and the owner's own -- [silent]
     * is structurally empty and the fact that the unit delivered nothing was
     * unsayable. It is the same reading of the same link by the same function,
     * [ArmedSilencePolicy.soleSilence]; what differs is that there is no label
     * to put in front of it, and inventing one would name a unit nobody
     * labelled.
     *
     * NEVER SET BESIDE [silent], and that is enforced at
     * [SensorCapturePolicy.withSoleSilence] rather than left as a convention: a
     * set carrying both would state one fact in two vocabularies, and the two
     * could then disagree about one link.
     *
     * Covers two shapes of set, both of which capture one unroled stream
     * through the one link the app holds: one paired unit, and two the app
     * cannot tell apart -- the second keeps its [shortfall] beside this, since
     * that describes the ROSTER and this describes the LINK.
     *
     * Null on every ordinary set and absent from the encoded JSON when null,
     * since the repository encodes with kotlinx's default
     * `encodeDefaults = false`. So a one-sensor set whose unit delivered stores
     * no declaration at all and its export is byte-identical to what this app
     * has always written. Absence also reads correctly on every row an earlier
     * build wrote: no build before this one could observe an unroled link's
     * delivery, so a row that says nothing here was never asked.
     */
    val soleSilent: ArmedDelivery? = null,
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
    /**
     * Which role's stream the set is ARMED to analyse; null when no role is in
     * play. [SensorCapturePolicy.analysedStream] decides which stream the
     * figures actually come from, once it is known which units streamed
     * (#207).
     */
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
 * Which role's stream the DSP is pointed at for one set, and whether that is
 * the role the set was armed to analyse (#207).
 *
 * TWO FACTS IN ONE ANSWER, so they cannot disagree. A caller that asked which
 * role to analyse and separately asked whether it fell back would be asking
 * one question twice, and the second answer is exactly the kind that goes
 * stale when the first rule changes.
 *
 * [role] is null only when no role is in play at all, which is the ordinary
 * one-sensor set and the set that met two paired units it could not tell
 * apart. Neither of those has a second stream to move onto.
 */
data class AnalysedStream(
    /** The role whose stream the figures are computed from. */
    val role: SensorRole?,
    /** True when [role] is not the role the set armed for analysis. */
    val fellBack: Boolean,
)

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
     * the DSP looked at a stream it did not look at. THAT IS AN ARMING
     * DECISION AND IS NOT THE LAST WORD, since #207: nothing here has seen a
     * sample, so where the preferred unit turns out to produce none,
     * [analysedStream] moves the analysis onto the role that did and
     * [RecordedSensors.analysedFellBack] records that it moved. This function
     * is unchanged by that, deliberately -- what it answers is which link to
     * hold, which is knowable before a set begins and is not the same
     * question. Where the preference
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
     * True when a set captures ONE stream carrying no role: one paired unit, or
     * two the app cannot tell apart (#224).
     *
     * Stated once, here, because three places need the same answer -- the card
     * before the set, the reading frozen at the end of it, and the declaration
     * that reading lands in -- and three readings of one state is how they come
     * to disagree about a set already running.
     *
     * It is `!isDual` AND something paired, and BOTH halves are load-bearing.
     * Without the first, a dual set would get a roleless word beside its
     * role-keyed one; without the second, a manual set with nothing paired
     * would be reported as holding a silent link, which is absence rendered as
     * a value -- "the app looked and saw nothing" is a different statement from
     * "nothing was ever armed".
     *
     * PAIRED IS NOT CONNECTED, and this says only the weaker thing: the app has
     * a unit to point a link at. Whether that link came up is exactly what
     * [ArmedSilencePolicy.soleSilence] is asked next, and this function does not
     * pretend to answer it.
     */
    fun capturesUnroledStream(roster: SensorRoster, pairedImuAddresses: List<String>): Boolean =
        !roster.isDual && pairedImuAddresses.isNotEmpty()

    /**
     * The declaration a set ends with once it is known what its ONE unroled
     * link was doing (#224).
     *
     * The ordinary one-sensor set still stores NOTHING: [soleSilent] is null
     * there, so this returns whatever [recorded] produced -- null on a set with
     * one bar sensor that delivered, which is what keeps such an export exactly
     * what this app has always written.
     *
     * [soleSilent] IS A WORD FOR A SET THAT CAPTURED NOTHING, and the caller
     * owes that. This function is handed a reading and attaches it; it holds no
     * buffer and cannot check. A reading of one link over a fixed window ending
     * when the set ended says a unit that fed the whole set and dropped in its
     * last seconds is silent, and passing THAT here writes "delivered nothing"
     * onto a row beside a full summary and a real raw stream. `:app` gates on
     * the analysed buffer being empty for exactly this, and #224 round 1 is
     * where the omission was found.
     *
     * Where there IS a word, a set that declared nothing gains a declaration
     * carrying only that word beside a [RecordedSensors.count] of one. The
     * count is 1 and [RecordedSensors.expected] stays EMPTY, because the stream
     * carries no role and inventing one would label a capture nobody labelled
     * -- the rule the whole of #198 turns on and which this change does not
     * touch. A set that met two paired units it could not tell apart keeps its
     * [RecordedSensors.shortfall] and gains the word beside it: one describes
     * the roster before the set, the other the link during it.
     *
     * A declaration that armed ROLES keeps the word out entirely and is
     * returned untouched. That combination is unreachable through
     * [ArmedSilencePolicy.soleSilence], which answers null for a dual roster,
     * and it is refused here as well rather than trusted: the two facts would
     * be one fact in two vocabularies, and the export contract says outright
     * that they never appear together.
     */
    fun withSoleSilence(armed: RecordedSensors?, soleSilent: ArmedDelivery?): RecordedSensors? {
        if (soleSilent == null) return armed
        if (armed != null && armed.expected.isNotEmpty()) return armed
        return (armed ?: RecordedSensors(count = DEFAULT_COUNT)).copy(soleSilent = soleSilent)
    }

    /**
     * Which role the DSP is pointed at once it is known which units actually
     * streamed, and whether that is the role the set armed (#207).
     *
     * THE ANALYSED ROLE MUST BE A ROLE THAT STREAMED. [roster] decides which
     * unit the app is POINTED at before a set begins, from the preferred
     * address and nothing else, and that is the right rule for arming: it
     * names the unit whose link the existing client is maintaining. It is the
     * wrong rule for analysis, because by the time there is anything to
     * analyse it is known which unit produced samples, and pointing the DSP at
     * an empty buffer publishes an empty summary over a capture the app is
     * holding. Field-36 published `summary: {}` on 13 of 14 sets that way,
     * and the other unit's file in each of those 13 holds 3,884-7,104 rows at
     * a span-based 99.33-99.42 Hz. SPAN-BASED, so it says the stream was long
     * and evenly clocked and NOT that it was complete: a dropout is
     * arithmetically indistinguishable from a slower sensor and nothing in
     * that archive can separate them.
     *
     * THE MOVE IS PUBLISHED rather than left to be derived. Before this, an
     * analysed role missing from [present] was the marker for "the figures
     * came from nothing"; after it, the analysed role is present in both the
     * ordinary case and the fallback, so that comparison separates nothing.
     * What remains to be said -- these figures came from the unit the app was
     * pointed at, so they are comparable with a corpus recorded the same way
     * -- is [AnalysedStream.fellBack] and is said outright.
     *
     * NOTHING STREAMING IS NOT A FALLBACK. With no other capture to move onto,
     * the honest answer is the role the set armed: the figures are empty
     * because there was no stream, and renaming the role would say a unit was
     * analysed when none was. Neither is a null [armed], which is the ordinary
     * one-sensor set and the set that met two paired units it could not tell
     * apart -- both record one UNROLED stream, and there is no second buffer
     * and no label to move to.
     *
     * The candidate is the first entry of [present] that is not [armed], which
     * with two [SensorRole] entries is the only one there can be. [present] is
     * the roles that put samples in a buffer, in the armed order, and it is
     * [SensorCapturePolicy.present]'s answer rather than a second reading of
     * the same question -- the export asks that function which roles arrived,
     * and a record path asking it differently is how the two documents come to
     * disagree about one set.
     *
     * IT DECIDES NOTHING RETROACTIVELY. This runs when a set is recorded, and
     * the analysis it selects the stream for is frozen into that set's row.
     * A set already on disk keeps the role and the figures it was written
     * with, and no export re-decides: republishing an old set's summary from
     * the other stream would put figures under a role that did not produce
     * them.
     */
    fun analysedStream(armed: SensorRole?, present: List<SensorRole>): AnalysedStream {
        if (armed == null || armed in present) return AnalysedStream(role = armed, fellBack = false)
        val fallback = present.firstOrNull { it != armed } ?: return AnalysedStream(role = armed, fellBack = false)
        return AnalysedStream(role = fallback, fellBack = true)
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
