package com.macrophage.barspeed.model

/**
 * What the app can honestly say about one ARMED accelerometer's stream at a
 * moment in time, issue #213.
 *
 * ## What the stack can and cannot distinguish
 *
 * Four states get talked about for a unit that is not delivering -- no bond,
 * bond but no GATT link, link but no notifications, notifications but no
 * frames -- and this app can observe THREE of them, only one of which is any
 * of those four exactly. Read from `:core:ble` rather than assumed:
 *
 * - **No bond is NOT observable.** Nothing in this repository reads
 *   `BluetoothDevice.getBondState()`. What `DeviceRegistry` calls paired is
 *   the app's OWN remembered list of [com.macrophage.barspeed.model.SensorRole]-labelled
 *   addresses, written when the lifter tapped Pair; the OS bond behind it may
 *   have been removed in Settings and the app would not know. So "not bonded"
 *   is indistinguishable here from "powered off", "out of range" and "left in
 *   a bag", and all four arrive as [NOT_LINKED].
 * - **Bond but no GATT link** is [NOT_LINKED]: `ConnectionState.Disconnected`,
 *   `Connecting`, or `Failed` with `linkEstablished` false. `GattClient.connect`
 *   also produces that last one for "Bluetooth unavailable" and "Bad device
 *   address", so [NOT_LINKED] is genuinely a merge and its advice says so.
 * - **Link but no notifications** is observable in exactly ONE form, and it is
 *   narrower than the phrase suggests. `GattClient.onServicesDiscovered` claims
 *   `linkEstablished = true` only where discovery returned `GATT_SUCCESS` and
 *   no notify characteristic matched -- a device answered with the wrong GATT
 *   profile. That is [LINK_WITHOUT_SENSOR]. The OTHER form of it is NOT
 *   observable: `onDescriptorWrite` is not overridden anywhere, and
 *   `ConnectionState.Connected` is published immediately after `writeDescriptor`
 *   is ISSUED, so a CCC write that the peripheral rejected looks exactly like a
 *   successful subscription.
 * - **Notifications but no frames** is [LINKED_SILENT], and it is observable
 *   only by watching the sample flow. `Connected` on its own asserts nothing
 *   about frames -- see the paragraph above for why -- which is precisely how
 *   field-37 armed a second unit on thirteen sets, received nothing from it,
 *   and drew a connected indicator throughout.
 *
 * So the most specific state the stack can name for a silent armed unit is one
 * of those three, and [ArmedSilencePolicy.advice] is where each one turns into
 * the thing the lifter can go and do about it.
 *
 * NONE OF THIS HAS BEEN WATCHED HAPPEN ON A DEVICE. `:core:ble` has no test
 * source set, nothing on the CI path executes a GATT client, and an emulator
 * cannot simulate BLE. What is pinned here is the mapping from a
 * [ConnectionState] and a frame instant onto a word; whether the GATT stack
 * reports those states when a real WT901 is switched off is a [Field] question
 * and is the verification #213 asks for.
 */
enum class ArmedDelivery {
    /**
     * A frame arrived within [ArmedSilencePolicy.SILENT_AFTER_MS] of now. The
     * only member that says the unit is actually feeding the app.
     */
    DELIVERING,

    /**
     * No frame yet, and the link has not been armed long enough to say so.
     *
     * A distinct answer rather than a quiet [NOT_LINKED], because accusing a
     * unit that is two seconds into its connect is a false claim about
     * hardware and would train the lifter to ignore the one that is true.
     */
    TOO_SOON,

    /**
     * No usable GATT link is up at that moment. Powered off, out of range,
     * unbonded, refused, or one that connected and then failed service
     * discovery -- the app cannot tell which.
     */
    NOT_LINKED,

    /** A device answered and returned a GATT profile with no notify characteristic this app can use. */
    LINK_WITHOUT_SENSOR,

    /** The link is up and subscribed, and nothing has come down it. */
    LINKED_SILENT,
}

/**
 * The two bar-sensor links as [ArmedSilencePolicy] needs them: what each one's
 * state is, when each one last delivered a frame, and when each one was armed.
 *
 * One parameter rather than six, so a caller cannot pair the analysed link's
 * state with the second link's frame instant -- the mistake this shape exists
 * to make impossible rather than merely unlikely. It moved here from `:app`
 * with [ArmedSilencePolicy.liveDeliveryByRole] (#225): the pairing of a role
 * with the link that holds it was the last part of #213's decision sitting in
 * a module where nothing on the CI path could run it.
 *
 * The ARMED INSTANTS are the grace floors, one per link, and they are per-link
 * for the reason the states and the frame instants are: a link may only be
 * excused by its own arming. What `AutoConnectManager` actually puts in them
 * is stated there and is not a claim this module can make.
 */
data class ArmedLinks(
    val analysedState: ConnectionState,
    val analysedFrameAtMs: Long?,
    val analysedArmedAtMs: Long,
    val secondaryState: ConnectionState,
    val secondaryFrameAtMs: Long?,
    val secondaryArmedAtMs: Long,
)

/**
 * Whether an ARMED unit is delivering, and what to say when it is not -- issue
 * #213.
 *
 * A `:core:model` object for [SensorCapturePolicy]'s reason, which is the same
 * reason stated once there and pointed at here: `:core:ble` has no test source
 * set and no test on the CI path reaches `:app`'s Android classes, so a
 * decision left in either is one nothing can run against. The observation --
 * when a frame last arrived, what state the link is in -- is made in `:app`
 * and `:core:ble` where it must be; every JUDGEMENT about it is here.
 *
 * ## Two instants, one function
 *
 * [deliveryOf] is asked the same question twice at different instants, and
 * that is deliberate rather than a coincidence of shape:
 *
 * - Before a set, at the moment the screen is drawn, so the lifter is told a
 *   unit is silent while they can still do something about it.
 * - At the instant the set ended, with the same three-second lookback, so the
 *   same reading is stored on the row and published in the export.
 *
 * One function means the sentence the lifter read and the word the archive
 * carries cannot disagree about one unit. Two readings of the same question is
 * how this repository has produced disagreeing documents before.
 */
object ArmedSilencePolicy {
    /**
     * How long an armed unit gets before the app is willing to say it is
     * silent, in milliseconds.
     *
     * ## Where the number comes from
     *
     * Three seconds is a CHOSEN figure. Nothing in this repository derives it,
     * and the reconnect loop does not bound it. Two `:core:ble` figures are
     * named here -- named rather than shared, because this module cannot see
     * that one, the dependency runs the other way, and a second copy of a
     * constant is a second fact that can disagree with the first -- and
     * neither is what fixes it:
     *
     * - `AutoConnectManager.maintain` waits `delay(backoffS * 1_000)` between
     *   connect attempts on an armed link: one second on the first, and up to
     *   thirty at its `min(backoffS * 2, 30L)` cap. Its three-second idle is
     *   the NO-ADDRESS branch, taken when the link is armed at nothing, so it
     *   is not the wait an armed link is subject to and it is not the slowest
     *   pass in that loop. A unit under a backed-off retry can therefore be
     *   called silent while the loop has not yet taken its next attempt at
     *   it. This policy accepts that: from the lifter's side, a link that has
     *   been retrying for thirty seconds IS silent.
     * - `WitmotionClient.onReady` posts four configuration commands spaced
     *   `COMMAND_SPACING_MS` apart, the last of them at 1,200 ms. The
     *   descriptor write is issued before the first command is posted, but
     *   whether the subscription was accepted is not observable here, and
     *   nothing has measured when a real WT901 first emits. So 1,200 ms is a
     *   ceiling on the configuration sequence, not a floor on when data may
     *   appear.
     *
     * Three seconds clears the second of those. It is NOT tied to
     * [LeadInPolicy.DEFAULT_S], and that is a decision rather than an
     * oversight: the prep runs AFTER the lifter taps START, so pacing a
     * before-the-set warning by it would measure this window against the wrong
     * one.
     *
     * What no measurement here supports is a claim about how long a real WT901
     * takes to produce its first frame after a subscribe. Nothing in this
     * repository has measured that, and if a field session shows three seconds
     * is too tight, this constant is the one thing to move.
     */
    const val SILENT_AFTER_MS = 3_000L

    /**
     * What this link is doing AT [nowMs]. The frame test is a fixed
     * [SILENT_AFTER_MS] window ending at [nowMs], the [ConnectionState] is
     * read at [nowMs], and [armedAtMs] is used for nothing but the TOO_SOON
     * grace floor.
     *
     * [lastFrameAtMs] is the instant of the most recent frame from this unit,
     * on the same wall clock as [nowMs], or null when this unit has never
     * produced one. It is a LAST-frame instant rather than a first-frame one,
     * and that is what makes this self-healing: a unit that delivered an hour
     * ago and has since gone flat reads as silent, where a first-frame reading
     * would call it delivering for the rest of the session.
     *
     * [armedAtMs] is when this link was last pointed at a device. It exists
     * only as a grace floor, so a link three seconds old is not accused of
     * being dead.
     *
     * Delivery is tested FIRST. A unit that is producing frames is delivering
     * whatever its [ConnectionState] says, because the frames are the fact and
     * the state flag is a report about it.
     *
     * A [nowMs] before [armedAtMs] -- `System.currentTimeMillis` is not
     * monotonic and a clock correction is enough -- yields a negative age,
     * which is below the threshold and answers [ArmedDelivery.TOO_SOON]. That
     * is the conservative direction: it declines to make a claim rather than
     * inventing one.
     */
    fun deliveryOf(state: ConnectionState, lastFrameAtMs: Long?, armedAtMs: Long, nowMs: Long): ArmedDelivery {
        if (lastFrameAtMs != null && nowMs - lastFrameAtMs <= SILENT_AFTER_MS) return ArmedDelivery.DELIVERING
        if (nowMs - armedAtMs < SILENT_AFTER_MS) return ArmedDelivery.TOO_SOON
        // Subject-ful and exhaustive, so a fifth ConnectionState has to be
        // decided here rather than falling into a silent default -- the rule
        // `SensorDot`'s own `when` already follows.
        return when (state) {
            is ConnectionState.Connected -> ArmedDelivery.LINKED_SILENT
            is ConnectionState.Failed ->
                if (state.linkEstablished) ArmedDelivery.LINK_WITHOUT_SENSOR else ArmedDelivery.NOT_LINKED
            is ConnectionState.Connecting, is ConnectionState.Disconnected -> ArmedDelivery.NOT_LINKED
        }
    }

    /**
     * What each ARMED role's link is doing AT [nowMs], for the LIVE reading --
     * the card on READY, the rest screen, the dots.
     *
     * Moved here from `:app` (#225). The judgement was already
     * [deliveryOf]'s; what moved is the PAIRING of a role with the link that
     * holds it, written as a lookup so nothing on this path can read the
     * second link's state for the first link's role.
     *
     * A null [analysed] role is the ordinary one-sensor set and the set that
     * met two paired units it could not tell apart. Neither has a role to
     * report against and neither may be reported: this returns an empty map
     * for both, and [soleSilence] is what covers them.
     *
     * EACH LINK ANSWERS TO ITS OWN ARMING (#225 item 8). Before that this took
     * the LATER of the two instants for both, so re-pointing the second link
     * handed three fresh seconds of excused silence to the first -- a figure
     * measured against the wrong link, and the reason a genuinely dead
     * analysed unit could stay unreported while the lifter fiddled with the
     * other one. The per-sensor dots on Home and Devices already read each
     * link's own instant; this is the same rule for the card.
     */
    fun liveDeliveryByRole(
        analysed: SensorRole?,
        secondary: SensorRole?,
        links: ArmedLinks,
        nowMs: Long,
    ): Map<SensorRole, ArmedDelivery> =
        byRole(analysed, secondary, links, links.analysedArmedAtMs, links.secondaryArmedAtMs, nowMs)

    /**
     * The same question at the instant a set ENDED, with the floor a stored
     * reading gets -- [storedGraceFloor]'s, per link.
     *
     * A separate function from [liveDeliveryByRole] rather than a boolean on
     * it, because the two readings differ in exactly one thing and it is worth
     * naming: GRACE IS A LIVE-WARNING CONCEPT. Before a set it stops the app
     * accusing a link two seconds into its connect, which is how a warning
     * becomes something the lifter learns to ignore. On a row that has already
     * been written there is nothing to act on, and the archive wants the most
     * informative word the app can honestly give.
     *
     * The DECISION per link is still [deliveryOf]'s and the frame window is
     * still the fixed [SILENT_AFTER_MS] ending at [setEndedAtMs] -- it does not
     * narrow to the set.
     */
    fun storedDeliveryByRole(
        analysed: SensorRole?,
        secondary: SensorRole?,
        links: ArmedLinks,
        setStartedAtMs: Long,
        setEndedAtMs: Long,
    ): Map<SensorRole, ArmedDelivery> = byRole(
        analysed,
        secondary,
        links,
        storedGraceFloor(setStartedAtMs, links.analysedArmedAtMs),
        storedGraceFloor(setStartedAtMs, links.secondaryArmedAtMs),
        setEndedAtMs,
    )

    /**
     * [soleSilence] at the instant a set ENDED, with the floor a stored
     * reading gets -- the sibling of [storedDeliveryByRole] for a set whose
     * single stream carries no role (#224).
     *
     * It reads the ANALYSED link out of [links] and no other: that is the only
     * bar-sensor client still holding a device when the roster names no second
     * address. Taking the whole bundle rather than three loose arguments is
     * what stops a caller handing this the second link's frame instant.
     *
     * The floor is [storedGraceFloor]'s, over the ANALYSED link's arming
     * instant -- the same rule [storedDeliveryByRole] uses, so the row a
     * one-sensor lifter gets and the row a two-sensor lifter gets cannot mean
     * different things by the same word.
     */
    fun storedSoleSilence(
        roster: SensorRoster,
        pairedImuAddresses: List<String>,
        links: ArmedLinks,
        setStartedAtMs: Long,
        setEndedAtMs: Long,
    ): ArmedDelivery? = soleSilence(
        roster = roster,
        pairedImuAddresses = pairedImuAddresses,
        state = links.analysedState,
        lastFrameAtMs = links.analysedFrameAtMs,
        armedAtMs = storedGraceFloor(setStartedAtMs, links.analysedArmedAtMs),
        nowMs = setEndedAtMs,
    )

    /**
     * The [ArmedDelivery.TOO_SOON] grace floor for a reading STORED on a set
     * that has ended: the EARLIER of when the set began and when that link was
     * armed (#225 item 8).
     *
     * GRACE IS A LIVE-WARNING CONCEPT. Before a set it stops the app accusing a
     * link two seconds into its connect, which is how a warning becomes
     * something the lifter learns to ignore. A row that has already been
     * written has nothing to act on, and what the archive wants is the most
     * informative word the app can honestly give -- so the floor reaches back
     * past the set to when the link was actually armed, and a two-second set on
     * a link armed an hour earlier and silent throughout stores
     * [ArmedDelivery.LINKED_SILENT] rather than "the app does not know yet".
     *
     * The set's start is a CEILING on it, not the floor itself. The row
     * describes the set's span, so that span is what the reading is measured
     * over; taking the later instant would excuse a link on an arming that
     * happened after the set had begun.
     *
     * Private because nothing outside this object should be choosing a floor:
     * the two stored readings are the callers, and they must not diverge.
     */
    private fun storedGraceFloor(setStartedAtMs: Long, armedAtMs: Long): Long = minOf(setStartedAtMs, armedAtMs)

    /** The lookup both readings share, so they cannot pair a role differently. */
    private fun byRole(
        analysed: SensorRole?,
        secondary: SensorRole?,
        links: ArmedLinks,
        analysedFloorMs: Long,
        secondaryFloorMs: Long,
        nowMs: Long,
    ): Map<SensorRole, ArmedDelivery> = buildMap {
        analysed?.let { put(it, deliveryOf(links.analysedState, links.analysedFrameAtMs, analysedFloorMs, nowMs)) }
        secondary?.let {
            put(it, deliveryOf(links.secondaryState, links.secondaryFrameAtMs, secondaryFloorMs, nowMs))
        }
    }

    /**
     * Which of the armed roles are silent, and what the app can see about
     * each -- in the order they were armed.
     *
     * An armed role with NO entry in [deliveryByRole] is left out, and that is
     * the honest answer rather than a defensive one: "the app did not look at
     * this link" is a different statement from "this unit sent nothing", and
     * folding the first into the second is absence rendered as a value.
     *
     * [ArmedDelivery.TOO_SOON] IS kept, because it is reachable at the one
     * place this answer is stored -- a set shorter than [SILENT_AFTER_MS] whose
     * unit produced nothing. Dropping it there would leave a silent role with
     * no word at all, which reads as a role nobody looked at.
     */
    fun silent(
        armed: List<SensorRole>,
        deliveryByRole: Map<SensorRole, ArmedDelivery>,
    ): Map<SensorRole, ArmedDelivery> = armed.mapNotNull { role ->
        deliveryByRole[role]?.takeIf { it != ArmedDelivery.DELIVERING }?.let { role to it }
    }.toMap()

    /**
     * What the lifter should go and do about one silent unit, or null where
     * there is nothing to say.
     *
     * Null for [ArmedDelivery.DELIVERING] because nothing is wrong, and null
     * for [ArmedDelivery.TOO_SOON] because the app does not know yet.
     * Returning a sentence for either would be the claim-stronger-than-its-
     * evidence class in its most direct form.
     *
     * Each sentence names the unit the lifter has to go and touch, and the
     * remedy, because #213's own reading of field-37 is that the three states
     * have three different remedies and the lifter was offered none of them. It
     * states what the app OBSERVED -- no link, the wrong profile, no data down
     * an open link -- and never why, which the app does not know.
     *
     * A NULL [role] is the set whose single stream carries none (#224), and the
     * subject becomes "the bar sensor". One template with one substitution
     * rather than a second set of sentences beside it: three near-copies of
     * three remedies is the duplicate-documentation class, and the copy that
     * would drift is the one the single-sensor lifter reads. What must not
     * appear there is a letter -- a lifter with one unit owns no A, and sending
     * them to find one is the complaint the dissolved `ONE_SENSOR_PAIRED`
     * shortfall used to make.
     */
    fun advice(delivery: ArmedDelivery, role: SensorRole?): String? {
        val subject = role?.let { "Sensor ${it.name}" } ?: "The bar sensor"
        val which = role?.let { " -- check which unit is paired as ${it.name}." } ?: " -- check which unit is paired."
        return when (delivery) {
            ArmedDelivery.DELIVERING, ArmedDelivery.TOO_SOON -> null
            ArmedDelivery.NOT_LINKED ->
                "$subject is armed but not connected. It will record nothing this set unless you " +
                    "switch it on and bring it near the phone."
            ArmedDelivery.LINK_WITHOUT_SENSOR ->
                "$subject answered but is not a bar sensor this app can read. It will record nothing " +
                    "this set$which"
            ArmedDelivery.LINKED_SILENT ->
                "$subject is connected but has sent no data. It will record nothing this set unless " +
                    "you power-cycle it."
        }
    }

    /**
     * What the app can see of the ONE armed unit on a set whose stream carries
     * no role, or null when there is no such unit or it is delivering (#224).
     *
     * THE HALF #213 COULD NOT REACH. Its answer is keyed by role, and a role
     * exists only where two paired units carry two different labels -- so the
     * owner's habitual configuration, one bar sensor, got no card, no stored
     * fact, and a dot: amber where the link still read Connected, grey or red
     * where it did not -- which state a powered-off unit produces is the
     * [Field] question #213 left. This is the same judgement asked without a
     * key: one link, one reading, and no label invented to hang it off.
     *
     * [SensorCapturePolicy.capturesUnroledStream] decides WHICH sets this
     * applies to, stated there rather than restated here, and it covers three
     * shapes: one paired unit; two the app cannot tell apart; and two it CAN
     * tell apart whose preferred address names neither of them. All three
     * record a single stream through the single link the app holds, so all
     * three can say what that link was doing.
     *
     * The state and the frame instant are that ONE link's, and the grace floor
     * is whatever instant the caller passes. It is asked at both instants
     * [deliveryOf] is -- when the screen draws, and when the set ends -- so ONE
     * FUNCTION DECIDES BOTH. The card passes `RecordState.imuArmedAtMs`
     * directly; the stored reading goes through [storedSoleSilence], whose
     * floor reaches back to the earlier of that instant and the set's start.
     * The two therefore agree about a link armed before the set began and can
     * differ only about one armed DURING it, where the row is floored by the
     * span it describes. (An earlier draft of this paragraph said the card's
     * instant was the process's start and that a short set could read
     * [ArmedDelivery.NOT_LINKED] on screen and store [ArmedDelivery.TOO_SOON];
     * #225 moved both halves and the sentence is deleted rather than
     * reworded.)
     * The stored answer is still not the card's answer in the other
     * direction: what
     * is recorded is gated on the set's analysed buffer holding fewer than
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] frames (#224 round 1,
     * widened from empty by #209), which the card has no equivalent of,
     * because a card drawn before the set has no buffer to read.
     *
     * NULL FOR [ArmedDelivery.DELIVERING], which is why the answer is nullable
     * rather than total: a word for a working unit would put a declaration on
     * every one-sensor row in the corpus and change what an ordinary export
     * looks like for the sake of saying nothing was wrong.
     * [ArmedDelivery.TOO_SOON] survives, the rule [silent] follows and for its
     * reason -- it is reachable where this is stored, on a set shorter than
     * [SILENT_AFTER_MS], and dropping it there would leave a silent unit with no
     * word at all, which reads as a unit nobody looked at.
     */
    fun soleSilence(
        roster: SensorRoster,
        pairedImuAddresses: List<String>,
        state: ConnectionState,
        lastFrameAtMs: Long?,
        armedAtMs: Long,
        nowMs: Long,
    ): ArmedDelivery? {
        if (!SensorCapturePolicy.capturesUnroledStream(roster, pairedImuAddresses)) return null
        return deliveryOf(state, lastFrameAtMs, armedAtMs, nowMs).takeIf { it != ArmedDelivery.DELIVERING }
    }

    /**
     * The whole SETUP/READY message, or null when there is nothing to say.
     *
     * One string rather than a list, because the card that draws it has one
     * slot and because both units being silent is one situation for the lifter
     * rather than two. Ordered by [silent]'s order, which is the armed order,
     * so the sentence about role A comes first whenever A is armed first.
     *
     * [sole] is [soleSilence]'s answer for a set whose stream carries no role
     * (#224), and it takes NO DEFAULT. The two shapes are mutually exclusive by
     * construction -- a set that armed roles has no unroled link, and a set with
     * an unroled link armed no roles -- but a defaulted null is exactly how the
     * caller that forgot it draws a blank card on the configuration the owner
     * trains most, which is the failure this issue is.
     *
     * [demoMode] takes no default either, and for the same reason (#225 item
     * 7). IN DEMO MODE THERE IS NOTHING TO SAY: `startDemoStream` fabricates
     * samples with no sensor present, so the set DOES record and "It will
     * record nothing this set" is the one claim demo mode makes FALSE rather
     * than merely fictional -- `SensorDot` beside this card already takes
     * `demoActive` for that reason. The suppression is whole rather than per
     * state: with no unit paired there is nothing to switch on, bring near the
     * phone or power-cycle, so every sentence [advice] can produce names a
     * remedy the lifter cannot carry out.
     *
     * It is the SENTENCE that is suppressed and not the reading. What a set
     * recorded in demo mode stores is decided at the set's end by
     * [storedDeliveryByRole] and [storedSoleSilence], which do not take this
     * flag: a demo set's buffer is not empty, so no word is stored for it
     * anyway, and gating the archive on a display flag is how a display
     * decision comes to change what is recorded.
     */
    fun message(silent: Map<SensorRole, ArmedDelivery>, sole: ArmedDelivery?, demoMode: Boolean): String? {
        if (demoMode) return null
        val sentences =
            silent.entries.mapNotNull { (role, delivery) -> advice(delivery, role) } +
                listOfNotNull(sole?.let { advice(it, null) })
        return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * The wire spelling of a delivery state: lowerCamel, as
     * [SensorCapturePolicy.shortfallToWire]'s values and `velocityLossBasis`'
     * already are.
     *
     * [ArmedDelivery.DELIVERING] has a spelling and is never published, and
     * that is deliberate rather than an oversight. The one published key is
     * `sensors.silent`, whose members are [silent]'s answer and therefore
     * cannot include it; giving it a spelling anyway keeps this function total
     * over the enum, so a member added later cannot reach the wire as a
     * silently missing branch. [PUBLISHED_WIRE] is what the schema declares.
     */
    fun wireOf(delivery: ArmedDelivery): String = when (delivery) {
        ArmedDelivery.DELIVERING -> "delivering"
        ArmedDelivery.TOO_SOON -> "tooSoon"
        ArmedDelivery.NOT_LINKED -> "notLinked"
        ArmedDelivery.LINK_WITHOUT_SENSOR -> "linkWithoutSensor"
        ArmedDelivery.LINKED_SILENT -> "linkedSilent"
    }

    /**
     * The spellings that can appear in the published `sensors.silent`, which
     * is every member except [ArmedDelivery.DELIVERING].
     *
     * Derived from the enum rather than written out, so a state added to
     * [ArmedDelivery] is published or the contract test that compares this set
     * with the schema reddens. Written out in the SCHEMA, because a reader of
     * the document has nothing else to check the wire form against.
     */
    val PUBLISHED_WIRE: Set<String> =
        ArmedDelivery.entries.filter { it != ArmedDelivery.DELIVERING }.map(::wireOf).toSet()
}
