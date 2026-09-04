package com.macrophage.barspeed.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Root of a session export; contract is docs/schemas/session-export.schema.json. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionExport(
    // Always written, even though it equals its default: the exporter drops
    // defaults, and an export without its version is unreadable by anything
    // that has to tell 1.0's field meanings from 1.1's.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schemaVersion: String = SCHEMA_VERSION,
    val startedAt: String,
    val endedAt: String? = null,
    /**
     * Where the device was and what UTC offset it was on when this session was
     * recorded, so a reader can recover the local time of day it happened at.
     *
     * [startedAt] and [endedAt] are UTC instants and stay that way: they are
     * correct, and a conforming reader gets the same moment out of them either
     * rendered with an offset or rendered with a `Z`. What it cannot get out of
     * them is the time on the wall, and time of day is a training variable —
     * it separates a fasted early session from an evening one and is how a
     * session lines up against sleep.
     *
     * Absent means the session was recorded before the app captured this, and
     * that is permanent for those sessions. Nothing durable — not the session
     * row, not the set rows, not the raw IMU, heart-rate or cue CSVs, all of
     * which carry epoch milliseconds — records the offset a past session was
     * on, so it cannot be recomputed the way a DSP figure can. Filling it in at
     * export time from the device's current zone was refused deliberately: it
     * would be right for a session recorded in the zone the phone is in now,
     * silently wrong for one recorded before a flight, and indistinguishable
     * from a value that was actually measured.
     */
    val timeZone: RecordedTimeZone? = null,
    val planRef: String? = null,
    val notes: String? = null,
    /**
     * How the whole session felt to the lifter, [SessionRpe.MIN] to
     * [SessionRpe.MAX], stated once when they finished it (#159).
     *
     * NOT THE PER-SET SCALE. [SetExport.rpe] is one set's answer to "how much
     * was left", anchored as reps in reserve at 7 to 10 and as load or time
     * headroom below that; this is the whole workout on 1 to 10, and the two
     * must never be averaged or compared as one quantity. Both now span the
     * same published range, so only these descriptions tell them apart.
     * [SessionRpe] states the difference once and the published schema states
     * it again in both descriptions, because an archive reader has only the
     * descriptions to tell two 1-to-10 integers apart.
     *
     * Absent means UNRATED, which is not a low rating. The rating is skippable
     * with one tap, a lifter who skips it records no answer, and every session
     * recorded before this version is absent for the different reason that the
     * app could not ask. Those two are not distinguishable here and no attempt
     * is made to distinguish them: both mean the lifter never said.
     *
     * Ground truth rather than a proxy. A downstream reader can already
     * estimate session effort by aggregating set RPEs; where this key is
     * present it is the lifter's own answer and should be preferred over any
     * such estimate, and where it is absent no estimate should be written into
     * it.
     */
    val sessionRpe: Int? = null,
    val heartRate: HrSessionSummary? = null,
    val exercises: List<ExerciseExport>,
) {
    companion object {
        /**
         * 1.1 — velocityLoss_pct became best→LAST rep (was best→worst), unknown
         * phases report null instead of 0, tempo compliance scores movement
         * digits only, and repMetricsComplete says whether the per-rep array
         * covers the whole set.
         *
         * 1.2 — a set may carry the direction and geometry it was measured
         * with, and where each of those values came from. Purely additive: no
         * existing key changed type or stopped being written, so a reader
         * written against 1.1 works unchanged against 1.2. The key is absent on
         * sets recorded before the app captured it.
         *
         * 1.3 — a session may carry [timeZone], the device's zone and the UTC
         * offset in effect when it was recorded. Purely additive on the same
         * terms: `startedAt` and `endedAt` are byte-for-byte what 1.2 wrote,
         * still UTC with a `Z`, so a 1.2 reader works unchanged against 1.3.
         * Re-rendering them with an offset was considered and refused — both
         * forms are the same instant to a conforming parser, so it would buy a
         * correct reader nothing, while a reader that strips the designator
         * would silently start treating a local time as UTC and lose the
         * instant altogether. The key is absent on sessions recorded before the
         * app captured it.
         *
         * 1.4 through 1.10 are documented in the `schemaVersion` description of
         * `docs/schemas/session-export.schema.json`, which is the published
         * contract, and are deliberately not repeated here -- this KDoc stopped
         * being kept up at 1.3, and backfilling seven entries from memory into a
         * second statement of a contract that already has one is how the plan
         * contract came to have four statements that disagree. The drift is real
         * and is named rather than fixed here.
         *
         * 1.11 -- a set may carry `plannedPrep_s` and `prep_s`. Purely
         * additive: no key from 1.10 changed type or stopped being written, so
         * a 1.10 reader works unchanged against a 1.11 export. Both keys are
         * absent on a set that played no prep, and on every set recorded before
         * this version.
         *
         * 1.12 -- a set's figures cover only the detections whose drive began
         * at or before the set's own `Done` cue. NOT purely additive: no key
         * changes type or stops being written, but `repMetrics`,
         * `velocityLoss_pct`, `velocityLossBasis`, `repMetricsComplete`,
         * `reps` on a sensor-counted set and every field of `summary` are
         * computed over a different population of reps. It does not apply
         * retroactively: the exporter re-derives these from the STORED rep
         * list, and a stored rep carries durations, velocities, a range and an
         * ordinal index -- an index into the rep list, not into the samples --
         * but no instant, so an already-recorded row cannot be placed against
         * its own cue track without re-running segmentation over its stored
         * raw IMU stream, which the exporter does not do. The published
         * schema's `schemaVersion` description carries the argument and the
         * measured sizes; this is the warning, not a second copy of it.
         *
         * 1.13 -- `duration_s` on a timed set is the figure the set was
         * RECORDED with rather than the seconds its clock measured: the
         * prescription on a set that reached its target, the measurement on
         * one the lifter stopped, the stated figure on one corrected on the
         * rest screen. NOT purely additive: no key changes type or stops
         * being written, but the value changes for the majority case. It is
         * not retroactive -- the exporter reads the stored `actualDurationS`
         * column rather than re-deriving it, so a set recorded before this
         * version publishes exactly what it published before. The published
         * schema's `schemaVersion` description carries what a 1.12 reader may
         * assume and a 1.13 reader may not; this is the warning, not a second
         * copy of it.
         *
         * 1.13 also carries a second, ADDITIVE change (#158): a set may carry
         * [SetExport.repMarks], the instants a rep was counted at. One version
         * with two changes of different kinds, because 1.13 is unreleased at
         * the time this is written and minting 1.14 for a key nothing has
         * shipped a reader for would publish a version boundary that never
         * existed. The two halves must not be read as one: `duration_s`'s
         * semantic is not additive and a 1.12 reader must be re-checked
         * against it, while `repMarks` changes no existing key and is absent
         * on every set that produced no marks.
         *
         * 1.13 carries a THIRD change, additive on the same terms (#156): a
         * set may carry [SetExport.sensors], which says how many
         * accelerometers it was armed with, which roles they carried, which
         * of those reached the archive and which one the set's figures came
         * from. Under 1.13 as well, and for the same reason -- the version is
         * still unreleased. Absent on every ordinary one-sensor set, which is
         * what keeps a single-sensor export byte-for-byte what 1.12 wrote
         * apart from the version string itself.
         *
         * 1.13 carries a FOURTH change, additive on the same terms and under
         * the same version for the same reason (#159): a session may carry
         * [sessionRpe], the lifter's own 1-to-10 answer to how the whole
         * workout felt. No key from 1.12 changes type or stops being written
         * for it, and it is absent on every session the lifter did not rate
         * and on every session recorded before this version.
         *
         * 1.13 carries a FIFTH change (#176, #173) and a SIXTH (#157, #174),
         * both under the same unreleased version for the same reason and
         * NEITHER of them additive: `voiceCues` gains the rep call the guide
         * merges into a stroke's own word, so an existing array's contents
         * change; and `plannedReps` / `plannedDuration_s` publish what the plan
         * declared, frozen at import, rather than the box the lifter left
         * behind. The published schema's `schemaVersion` description carries
         * both arguments in full and the measured sizes; these two lines are
         * the pointer, not a second copy.
         *
         * 1.13 carries a SEVENTH change, additive on the same terms and under
         * the same version for the same reason (#177): a set may carry
         * [SetExport.added], saying the LIFTER appended it to the exercise
         * mid-session rather than the plan prescribing it. No key from 1.12
         * changes type or stops being written for it, and it is absent on every
         * prescribed set and on every set recorded before database v12.
         *
         * SEVEN CHANGES UNDER ONE NUMBER is what an unreleased version is FOR;
         * the count is not itself the warning. The warning is WHICH of them are
         * not additive: `duration_s`, `voiceCues`, and the `plannedReps` /
         * `plannedDuration_s` pair. A 1.12 reader must be re-checked against
         * those three and need not be re-checked against `repMarks`, `sensors`,
         * `sessionRpe` or `added`. That sentence used to read "ONE of the four
         * -- `duration_s`", which was true when the fourth change landed and
         * has been false since the fifth; it is corrected here rather than
         * reworded around, because it undercounted the re-checks a reader owes
         * by two.
         *
         * 1.14 is a RELEASED boundary being crossed, not another change under
         * an open number: 1.13 shipped in v0.1.44, read at the tag. Its first
         * change (#187) is `warmup`. The key keeps its name, its type and its
         * place, and BOTH what writes it and what it means to a reader change:
         * it is a PLAN DECLARATION now, from the new plan-schema 1.9 key of the
         * same name, where until now its only producer was an effort tile the
         * lifter tapped. A warm-up set therefore carries a real [SetExport.rpe]
         * from v0.1.45 on, where before the tile stored `warmup = true` and
         * `rpe = null` together and threw the effort away. NOT additive: no key
         * changes type or stops being written, but the published description
         * stops telling a reader to exclude these sets from effort analysis,
         * because that instruction is now false. Not retroactive: a set
         * recorded before this version publishes exactly what it published
         * before, and on those sessions a `warmup: true` set carrying no `rpe`
         * means the app could not record both rather than that the lifter
         * declined to rate it.
         *
         * 1.14 carries a SECOND change, under the same number because 1.14 is
         * unreleased and the two are one design (#187), and it is NOT additive
         * either: [SetExport.rpe] is a 1-to-10 scale whose rungs are anchored
         * DIFFERENTLY ALONG ITS LENGTH. 7 to 10 stay reps in reserve -- three,
         * two, one, none -- and 6, 4 and 1 are load headroom, "could have
         * added one increment / two increments / much more", asked in seconds
         * instead on a hold. No key changes type or stops being written, and
         * no stored value is rewritten; what changes is what a NEWLY written
         * value means at the low end and which values the app can write at
         * all. 6 is the value to read carefully: it was the FLOOR of the old
         * 6-to-10 grid, meaning "easy, 4+ reps left", so it absorbed
         * everything the new 1 and 4 now take, and 46% of this lifter's
         * historical ratings sit on it. It is reused rather than moved to 5
         * because the standard RPE-to-RIR chart puts 6 at 4 RIR, which is
         * roughly the state "could have added one increment" describes, so the
         * old values stay interpretable on the same ruler. 7 through 10 are
         * unchanged in meaning across the boundary.
         *
         * 1.14 carries a THIRD change, additive, under the same number for the
         * reason the first two share, and it is worth restating because it was
         * asked for as 1.15: 1.14 was UNRELEASED WHEN THAT WAS WRITTEN and is
         * not now. v0.1.44 shipped 1.13, read at tag
         * `7cf6e8c3cc546ab8d64c9fb2be86de2129250b43`, and v0.1.45 shipped 1.14,
         * read at tag `c44f1c531d6343d0071f82c062344e7f4eff950f` and unchanged
         * at v0.1.46. The changes below rode under one number on the strength
         * of a sentence that was true when written; that window is closed --
         * the same rule seven changes rode under 1.13 for. The change (#189):
         * a set may carry [SetExport.limiter], why it
         * ended, from a closed vocabulary, and [SetExport.limiterNote], the
         * lifter's own words where that answer is `other`. No key from 1.13
         * changes type or stops being written, and both are absent on every
         * set nobody was asked about and on every set recorded before database
         * v13.
         *
         * 1.14 carries a FOURTH change (#194), under the same number and NOT
         * additive: `warmup` gains a SECOND PRODUCER. Until now
         * the plan declared it and nothing else could; the lifter may now mark
         * or unmark the set on the rest screen, and where both exist the
         * lifter's mark wins. No key changes type and no stored value is
         * rewritten, but a 1.13 reader that treats `warmup: true` as "the plan
         * said so" must be re-checked -- which is why this is flagged rather
         * than filed beside the additive changes. The new key
         * [SetExport.warmupByLifter] says which of the two a given set
         * carries, and is absent on every set the lifter never marked, which
         * is every set recorded before database v13. What it does NOT do is
         * publish the plan's overridden declaration: where the mark disagrees,
         * the document carries the answer and its author, and the row keeps
         * both.
         *
         * 1.15: `plannedCount` is REMOVED from a set's `sensors` block and
         * [SetSensorsExport.shortfall] is added to it (#198). NOT additive:
         * a reader that requires `plannedCount` must be changed. The key
         * said how many accelerometers the PLAN prescribed, and no plan
         * prescribes any -- the app records from whatever is connected, one
         * bar sensor writing one stream and two paired units labelled A and
         * B arming two, on every set of every exercise. A key that kept
         * emitting the old default of 1 would tell a reader a coach
         * intended something. `shortfall` carries what the pair used to
         * carry between `plannedCount` and `count`, and the published copy
         * of this entry in `docs/schemas/session-export.schema.json` is the
         * one to read for what it means and which older exports carry the
         * retired key.
         *
         * A NEW NUMBER rather than a fifth change under 1.14, and this
         * paragraph is a correction of the one above it rather than an
         * addition beside it: that paragraph argued 1.14 was unreleased and
         * that minting 1.15 would publish a boundary that never existed.
         * True when written, false by the time this branch read it --
         * v0.1.45 shipped 1.14. The published JSON copy of this log was
         * corrected in the same round the constant moved to "1.15" and this
         * Kotlin copy was not, so the two disagreed for a commit and this
         * paragraph closes it. Nothing detects that:
         * `SchemaSensorContractTest` reads the JSON only, and no test in
         * this repository can guard a KDoc.
         *
         * 1.16: `bottomPause_s` and `topPause_s` measure the turnaround
         * INSIDE a rep, and exactly one of them is published per rep (#93).
         * NOT additive: both were REQUIRED under 1.15 and neither is now, and
         * the key that is still written carries a different quantity on some
         * lifts than it did. It does NOT apply retroactively: `repMetrics` is
         * built at export time, but from the analysis frozen into the set's
         * row when the set was RECORDED, so a document declaring 1.16 still
         * carries both keys, with the old quantities, on every rep it
         * publishes from a set recorded before this version. The published
         * copy of this entry in
         * `docs/schemas/session-export.schema.json` is the one to read for
         * which key a given lift writes, why the other is absent rather than
         * zero, and the segmentation limit that survives.
         *
         * 1.16 carries a SECOND change, under the same number -- the rule
         * the 1.13 entry states at length and the 1.14 entry applied twice
         * more, for its third and fourth changes (#189 and #194). The
         * change (#141):
         * a guided set that ends without the guide having called `Done`
         * speaks and records a terminal cue, `Set ended`. That is a set
         * the lifter ended early AND a guided set given no rep target,
         * which the guide never finishes on its own and which therefore
         * completes normally carrying this word. NOT additive, for the
         * reason the 1.13
         * `voiceCues` change was not: no key changes type or stops being
         * written, but an existing array gains a row, and a reader matching
         * only `Done` to find the end of a set now misses that ending. It
         * qualifies 1.12's list of unbounded cases -- the third member, a
         * guided set the lifter ended before the prescription was called
         * through, no longer occurs. It does NOT tighten any rep list: the
         * tap that ends a set is the tap that stops the recording, so
         * nothing can lie past the boundary. What changes is that such a
         * set stops being an absence. (Two drafts of this paragraph were
         * wrong and are corrected here rather than quietly reworded: the
         * first said v0.1.46 shipped 1.15, where that tag carries 1.14; the
         * second minted 1.16 as a NEW number against a `main` still
         * declaring 1.15, and #93 landed 1.16 while this branch sat, so the
         * cue rides under that number rather than beside it.) The published
         * copy of this log in `docs/schemas/session-export.schema.json` is
         * the one to read for what a reader must do about it.
         *
         * 1.17: a set's `sensors` block may carry `analysedFellBack`, true
         * when the analysed role is NOT the role the set armed -- that unit
         * produced no stream, another one did, and the figures come from the
         * one that did (#207). The app now analyses a role that STREAMED
         * wherever one did; before this, a set armed to analyse a unit left
         * switched off published an EMPTY summary over a capture from the
         * other unit the app was holding. Purely additive on the wire -- the
         * key is absent unless true, and no existing key changed type or
         * stopped being written -- but NOT behaviourally neutral: a set that
         * would have published nothing derived now publishes a summary from
         * the surviving stream, and `analysedRole` on such a set names that
         * stream rather than the armed one. It does NOT apply retroactively,
         * for 1.16's reason: which stream a set was analysed from is frozen
         * into its row when the set was RECORDED, so a set recorded before
         * this version publishes what it published whatever its document's
         * `schemaVersion` says.
         *
         * A NEW number rather than a THIRD change to 1.16, and this REVERSES
         * what two earlier drafts on this branch asserted. Both said 1.16 was
         * unreleased and that a key added to it therefore extended a number
         * no consumer had ever seen; each was true when it was written.
         * Neither is true now: 1.16 SHIPPED in v0.1.48 while this change sat
         * in review, read by
         * `git show v0.1.48:core/model/.../SessionExport.kt` rather than
         * assumed, so extending it would have redefined a number a consumer
         * has already been handed. That is the 1.15 entry's mistake taken in
         * the opposite direction, and it is corrected here rather than
         * reworded away. The published copy of this entry in
         * `docs/schemas/session-export.schema.json` says the same.
         *
         * 1.17 carries a SECOND change, under the same number: 1.17 was
         * UNRELEASED WHEN THAT WAS WRITTEN and is not now -- v0.1.49
         * shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt`. `load_kg` may be
         * corrected on the rest screen after the set is over, so it is no
         * longer necessarily the mass the set's power figures were
         * computed from (#205). NOT additive: no key
         * changes type or stops being written, but a reader that inferred
         * `summary.peakPower_w`, `summary.meanConPower_w` and each rep's
         * `peakPower_w` / `meanConPower_w` under `repMetrics` were derived
         * from the `load_kg` published beside them can no longer do so.
         * Power is computed as the set is recorded and frozen into the set's
         * row; `SessionDao.overrideLoad` rewrites the stored load alone and
         * nothing recomputes the analysis. It EXTENDS 1.17 rather than
         * minting 1.18, under the rule the 1.16 entry above applies to its
         * own second change: a number takes further entries until it ships,
         * and a new one is minted only once the previous number has shipped.
         * 1.17 was minted on `main` by #207. 1.17 was UNRELEASED WHEN THAT WAS
         * WRITTEN and is not now -- v0.1.49 shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt`. The published copy of
         * this log in `docs/schemas/session-export.schema.json` says the
         * same.
         *
         * 1.17 carries a THIRD change, under the same number and for the same
         * reason the paragraph above gives: 1.17 was UNRELEASED WHEN THAT WAS
         * WRITTEN and is not now -- v0.1.49 shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt` rather than assumed. The
         * change (#213): a set's `sensors` block may carry `silent`, an object
         * keyed by role naming each ARMED unit that put nothing in a buffer
         * for the whole set, and what the app could see of that unit's link
         * when the set ended. Purely additive on the wire -- the key is absent
         * unless some armed unit was silent, and no existing key changed type
         * or stopped being written. It is a fact a reader could NOT derive:
         * `expected` minus `present` already says WHICH unit was missing, and
         * this says what the app observed of it, which is the difference
         * between "power it on", "pair the right unit" and "power-cycle it".
         * Its vocabulary is deliberately weak, and the published description
         * says so -- `notLinked` merges powered-off, out of range, refused,
         * an OS bond removed behind the app's back, and a connect that
         * failed service discovery, because nothing in this app reads
         * `BluetoothDevice.getBondState()` and a discovery failure looks the
         * same as a link that never opened. It does NOT apply
         * retroactively, for 1.16's reason: the reading is frozen into the
         * set's row when the set is RECORDED, so a set recorded before this
         * version publishes nothing here whatever its document's
         * `schemaVersion` says, and that absence is correct rather than a
         * default -- no earlier build could observe delivery at all. The raw
         * archive's `meta.json` moves with it, as it did for
         * `analysedFellBack`: a set descriptor carries `sensorsSilent` under
         * the same rule, written only when something was silent.
         *
         * 1.17 carries a FOURTH change, under the same number for the reason
         * the entry just above states: a number takes further entries until
         * it ships. `geometry.source` gains a sixth key, `sensorOnStack`,
         * reading `declared`, `seeded` or `default`. Behind it the plan's
         * `sensorOnStack` key became nullable, so an omitted key on one of
         * the machines the app ships a mount for -- the assisted pull-up,
         * chin-up and dip machines, the lat pulldown, seated row, seated
         * cable row, cable row and triceps pushdown, the leg curl, seated
         * and lying leg curl and leg extension -- now resolves to the stack
         * rather than to the bar, and a plan that means the sensor was on
         * the handle must say `"sensorOnStack": false` for that to win.
         * NOT purely additive: `geometry.source` is a closed object gaining
         * a REQUIRED key, so a reader validating against 1.17 as it stood
         * before this must accept the sixth; and `geometry.sensorOnStack`
         * may now read true on a set whose plan said nothing, which changes
         * which axis the DSP measured on horizontal work. A row stored by
         * any build up to and including v0.1.48 -- before `sensorOnStack`
         * joined the source block -- decodes with the new key defaulted to
         * `default` rather than failing to decode, and re-exports with
         * `geometry.source.sensorOnStack` reading `"default"` regardless of
         * what the plan declared, because no build before this one tracked
         * that provenance at all; the default cannot be recovered into a
         * true answer after the fact. The geometry VALUES such a row
         * already carried, `geometry.sensorOnStack` included, are
         * unchanged -- only the new provenance key is affected, and only
         * rows recorded from this version on carry it. (#223)
         *
         * 1.17 carries a FIFTH change, under the same number and for the
         * reason the paragraphs above give: 1.17 was UNRELEASED WHEN THAT WAS
         * WRITTEN and is not now -- v0.1.49 shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt` rather than assumed. The
         * change (#224): a set's `sensors` block may carry `soleSilent`, a
         * single word for what the app could see of the ONE armed link on a
         * set whose stream carries no role. The third change keyed its word by
         * ROLE, and a role exists only where two paired units carry two
         * different labels -- so a set armed with one bar sensor, which is the
         * ordinary configuration, published nothing about a paired unit whose
         * link delivered nothing. Purely additive on the wire
         * -- the key is absent unless that link was silent, and no existing key
         * changed type or stopped being written -- but NOT neutral in what a
         * document contains: a one-sensor set whose unit went silent now
         * publishes a `sensors` block at all, with `count` 1 and both role
         * lists EMPTY, where such a set published no block before. A reader
         * that took an absent `sensors` for "recorded with one bar sensor" must
         * now also read `count` 1 with an empty `expected` that way. Never
         * written beside `silent`: the two are one fact in two vocabularies and
         * the published descriptions say so. It does NOT apply retroactively,
         * for 1.16's reason -- the reading is frozen into the set's row when the
         * set is RECORDED. Two published descriptions are REWRITTEN rather than
         * extended: `silent` stated this gap as a permanent absence and named
         * this issue for it, and `shortfall` told a reader that `count` 1 with
         * an empty `expected` and no shortfall is a row written before 1.15,
         * which needs `soleSilent` as its discriminator to stay true. A third,
         * `present`, is corrected: an empty list is a set whose stream carries
         * no role as often as it is a set whose every armed unit went silent,
         * which was already true of an unlabelled pair before this change. The
         * raw archive's `meta.json` moves with it under the third change's
         * rule: such a set's descriptor carries `sensorsSoleSilent`, and
         * because it now carries a declaration at all it also carries
         * `sensorsArmed` 1 and an empty `sensorRolesExpected`, where before
         * this version a one-sensor set's descriptor carried no sensor key
         * whatever.
         *
         * 1.17 carries a SIXTH change (#215), under the same number and for
         * the reason the paragraphs above give: 1.17 was UNRELEASED WHEN THAT
         * WAS WRITTEN and is not now -- v0.1.49 shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt`. The ordinal counts against this file
         * as rebased onto `main`: #223's is the fourth and #224's `soleSilent`
         * is the fifth. The change: `side` is the arm the set WORKED, and
         * `plannedSide` beside it is the arm the plan prescribed. NOT purely
         * additive. No key changes type or stops being written, but `side`
         * answers a different question on a document written by this build:
         * until now it was a copy of the plan's own declaration, so it agreed
         * with the prescription by construction, and a reader who took it for
         * "what the plan asked for" was right by accident. It may now differ,
         * because the lifter can state the arm the next set works and that
         * statement is what is recorded (#144).
         *
         * It does NOT apply retroactively, for 1.16's reason: both values are
         * frozen into the set's row when the set is RECORDED, so every set
         * recorded before database v14 publishes no `plannedSide` at all
         * whatever its document's `schemaVersion` says. That absence is
         * correct rather than a default. It is also the one place a backfill
         * would have been plausible and wrong: on those rows `side` WAS the
         * prescription, so copying it across would assert of every past set
         * that the app knew which limb moved, which is exactly what #144 says
         * it could not. `plannedSide` is absent on bilateral work, on an
         * ad-hoc set and on an appended set for the ordinary reason -- nothing
         * prescribed any of them a side. The raw archive's `meta.json` moves
         * with it, as it did for `analysedFellBack` and `sensorsSilent`.
         *
         * 1.17 carries a SEVENTH change (#225), under the same number and for
         * the reason the entries above give: 1.17 was UNRELEASED WHEN THAT WAS
         * WRITTEN and is not now -- v0.1.49 shipped it, read by `git show
         * v0.1.49:core/model/.../SessionExport.kt`. The ordinal counts against this file
         * as landed: #223's is the fourth, #224's `soleSilent` is the fifth
         * and #215's `plannedSide` is the sixth. The change: the grace floor
         * behind `tooSoon` in `silent` and `soleSilent` is the instant the
         * app last DELIBERATELY pointed that link at a device, rather than
         * the start of the set -- a floor on the arming, not the arming: it
         * starts at the app's own start instant and the reconnect loop can
         * re-point without moving it. The reading a set stores was floored
         * by the set's own start, so a two-second set stored `tooSoon` --
         * "the app does not know yet" -- about a bar sensor the app had
         * watched deliver nothing all session, and that row is written
         * exactly when the set captured nothing, which makes it the row a
         * reader consults to find out why. NOT additive on the terms 1.4 and
         * 1.5 were not: no key changes type or stops being written, both
         * keys carry the same four words, and nothing a reader validates
         * against moves -- but the VALUE these two keys carry changes on
         * short sets, which is where they were most often written, and
         * `tooSoon` now means what it says. It does NOT apply
         * retroactively, for 1.16's reason: the reading is frozen into the
         * set's row when the set is RECORDED. The published descriptions of
         * both keys are corrected with it.
         *
         * 1.18: a set that ended BEFORE its work phase began publishes
         * neither `duration_s` nor `prep_s`, and carries
         * [SetExport.abandonedInPrep] instead (#216).
         *
         * A NEW number rather than an eighth change under 1.17, and the rule
         * is the one the 1.15 and 1.17 entries above each state: a number
         * takes further entries until it SHIPS. 1.17 shipped in v0.1.49, read
         * by `git show v0.1.49:core/model/.../SessionExport.kt` rather than
         * assumed, so extending it would redefine a number a consumer has
         * already been handed.
         *
         * NOT additive: no key changes type, but two keys STOP BEING WRITTEN
         * on one class of set. Such a set published `duration_s: 0`, which is
         * a measurement claim -- the writer drops nulls and prints zeros, so
         * nothing downstream could tell that 0 from a hold attempted and held
         * for no time -- and `prep_s`, which carries the prep the app SET OUT
         * to play rather than the prep that elapsed. On the capture this was
         * written from, `prep_s` matches the measured prep window to within
         * 16 ms on every set whose prep completed, which is what entitles a
         * reader to read it as elapsed and why publishing it on a set whose
         * lead-in was cut is a false statement rather than a harmless one.
         *
         * It does NOT apply retroactively and cannot: whether the work began
         * is a capture fact stored from database v15 on, so a row written
         * before that carries no answer, publishes what it always published,
         * and carries no `abandonedInPrep`. `reps` is UNCHANGED and still
         * required, so an abandoned set goes on publishing `reps: 0`;
         * `abandonedInPrep` is what makes that zero readable, and removing it
         * is tracked separately rather than folded in here.
         *
         * 1.18 carries a SECOND change, under the same number because 1.18 was
         * unreleased when that change landed, and it IS additive (#216,
         * #169): a failed set may carry [SetExport.failedByLifter], saying
         * whether the lifter called the failure or the app derived it. The
         * two facts have always been held apart in `SetRatingTracker` and
         * OR-ed, with only the OR published, so a set the lifter called a
         * grinder and one the app marked short of its prescription reach a
         * reader identical. `limiter` cannot separate them: it is an optional
         * answer to a different question, absent on every set nobody was
         * asked. Absent on a set that did not fail and on every set recorded
         * before database v15.
         *
         * 1.18 carries a THIRD change, under the same number because 1.18 was
         * unreleased when that change landed, and it IS additive (#60): a
         * set may carry [SetExport.voided] and [SetExport.voidReason], the
         * lifter's own statement that they did not perform a recorded set,
         * and optionally why.
         *
         * WHY IT RIDES ON 1.18 RATHER THAN MINTING 1.19. 1.17 shipped in
         * v0.1.49 -- read by `git show v0.1.49:core/model/.../SessionExport.kt`
         * rather than assumed -- so extending it would redefine a number a
         * consumer has already been handed. 1.18 was minted, landed on `main`
         * and unreleased then, which is exactly the state that takes further
         * entries. THIS PARAGRAPH REPLACES ONE THAT SAID 1.18 WAS BEING
         * MINTED BY AN UNLANDED LANE AND THAT THIS BRANCH MUST NOT LAND
         * AHEAD OF IT: that was true when it was written and is not now, and
         * it is deleted rather than reworded.
         *
         * Additive on the terms 1.2 and 1.3 were: no key changes type, none
         * stops being written, and a set that is not voided publishes neither
         * key -- so a 1.17 reader works unchanged against a document carrying
         * them. It does NOT apply retroactively: the mark is a column that
         * exists from database v16 on, absent on every set recorded before
         * it, and nothing backfills one.
         *
         * WHAT DOES NOT MOVE IS THE POINT. A voided set is published with its
         * load, its reps or hold, its prescription, its summary and its raw
         * streams intact. The mark is a reading instruction, not a redaction.
         *
         * NOR DOES THE SESSION'S HEART RATE MOVE. The session's
         * `heartRate.avgBpm` and `maxBpm` are frozen at the session close and
         * are NOT re-derived by a void, so they still include a voided set;
         * recomputing them over the sets this mark tells a reader to count --
         * that is, with the voided ones dropped -- will not reproduce them.
         *
         * 1.18 carries a FOURTH change, under the same number for the same
         * reason the second and third are: 1.18 was unreleased then. It is
         * additive (#138): a set's `summary` may carry `noRepsReason`, a
         * single word saying why the set resolved no reps. A healthy IMU stream --
         * contiguous `sample_idx`, no gap over 100 ms, the whole set window
         * covered -- can segment to nothing, and until now the document said
         * so only by OMISSION: `reps: []`, `summary: {}`, no
         * `velocityLossBasis`, which is byte-identical to a manual set
         * recorded with no sensor at all. The key is drawn from
         * [VALID_NO_REPS_REASONS] and names WHICH GATE of the segmenter
         * emptied the list, nothing more.
         *
         * Additive on the terms 1.4 and 1.5 were not: nothing already written
         * changes type, meaning or presence, and a reader that ignores the key
         * reads a 1.18 document exactly as it read a 1.17 one.
         *
         * IT DOES NOT APPLY RETROACTIVELY, for 1.16's reason. The value is
         * computed when the set is ANALYSED and frozen into its stored
         * analysis; nothing re-runs the segmenter at export time. Every set
         * recorded before this number ships keeps publishing `summary: {}`
         * with no reason, permanently.
         *
         * AND IT SAYS NOTHING ABOUT AN UNDER-RESOLVED SET. It is written only
         * when the rep list is EMPTY. A set resolving 1 of 10 performed reps
         * publishes a full summary computed from that one rep and carries no
         * `noRepsReason` at all. Reading the key's absence as "the reps are
         * trustworthy" is wrong. NO COMMITTED CAPTURE IS NAMED AS AN EXAMPLE:
         * this paragraph named `field-rdl-3010-10rep-s36-set04`, with a single
         * movement run displacing 123.64 m and one surviving rep, and issue
         * #94's runaway correction took that capture to ten reps of ten
         * performed. The example is deleted rather than repointed, exactly as
         * `NoRepsReason`'s own KDoc deletes it.
         *
         * 1.19: [GeometrySourceExport] gains a seventh key, `bodyweight`, so
         * the flag deciding whether the lifter's own mass is a term in
         * [SetExport.loadKg] says who supplied it (#220).
         *
         * A NEW number rather than a FIFTH change under 1.18, under the rule
         * the 1.16 entry states and the 1.17 entries repeat: a number takes
         * further entries until it SHIPS. 1.18 SHIPPED in v0.1.50, read by
         * `git show v0.1.50:core/model/.../SessionExport.kt` rather than
         * assumed, and this branch is rebased onto that tag. THIS REPLACES
         * FOUR PARAGRAPHS THAT FILED THIS ENTRY AND THE THREE BELOW UNDER AN
         * UNRELEASED 1.18; each was true when it was written and the tag made
         * it false.
         *
         * NOT additive, and it breaks BOTH WAYS. `geometry.source` is a
         * CLOSED object with every key required: the 1.19 schema rejects a
         * 1.18 document on this key, and the 1.18 schema rejects a 1.19
         * document. Measured with the ajv invocation `ci.yml` runs --
         * v0.1.50's published example is invalid against this version's
         * schema, `must have required property 'bodyweight'` at
         * `/exercises/0/sets/0/geometry/source`; the same example carrying
         * the new key is invalid against v0.1.50's schema at that path with
         * `must NOT have additional properties`; and this version's example,
         * declaring 1.19, is invalid against v0.1.50's schema at
         * `/schemaVersion` on the enum, which is the first error ajv reports
         * before it reaches the key. 1.17 shipped the same break:
         * v0.1.50's schema rejects v0.1.48's published 1.16 example on
         * `sensorOnStack`, measured by the same command. The published schema
         * validates the CURRENT version's shape; its `schemaVersion` enum
         * listing every number back to 1.0 is not a claim that a document
         * carrying one of them validates. A sentence naming a reader
         * "validating against 1.17 or earlier" as the rejecting one stood
         * here and is DELETED rather than reworded -- the rejecting reader is
         * the released 1.18.
         *
         * Nothing published stops being published or changes meaning. A set
         * recorded before this shipped re-exports `default` whatever its plan
         * said, permanently -- no earlier build stored the answer.
         *
         * 1.19 carries a SECOND change, under the same number the entry above
         * mints, and it IS additive (#220): a set may carry
         * [SetExport.bodyWeightKg], the body weight [SetExport.loadKg] was
         * computed with. On the six of field-37's thirteen sets that are
         * body-weight work the lifter's own mass is the LARGEST term in
         * `load_kg` and no key named it, so cross-session comparison of an
         * assisted pull-up mixed body-weight drift with assistance changes
         * invisibly. `load_kg` and `plannedLoad_kg` are unchanged and still
         * the sums they always were. Optional and absent on loaded work, on
         * every set recorded before database v17, and on a body-weight set
         * the app held no body weight for -- the last two indistinguishable,
         * permanently, since nothing recorded what the lifter weighed on a
         * past date.
         *
         * 1.19 carries a THIRD change, under the same number the mint above
         * states, and it adds no key and changes no key's type or value
         * (#178): `rest_s` gains the description it never had, stating the
         * instant a rest is counted from. Published because the INSTANT
         * changed rather than as a documentation pass -- the raw archive's
         * `rest_before_hrm` window used to open when a set's capture stopped
         * and now opens when the set was CALLED OVER, the instant the
         * countdown has run from since v0.1.44 (#172). On one field session
         * the two disagreed by up to 53.06 s on a guided set that spoke `Done`
         * and kept recording, and by nothing on a set that ended at its
         * terminal cue, so the archive's two documents could not be joined
         * without knowing which instant each had used and neither said. What
         * moves is a STREAM in the raw archive, not a key here; a set's final
         * `hrm` samples can appear in the next set's rest window too, because
         * the capture is copied forward rather than moved, and nothing
         * published from `hrm` changes. Not retroactive and cannot be: no
         * earlier build stored the two windows apart.
         *
         * 1.19 carries a FOURTH change, under the same number the mint above
         * states, and it adds no key to THIS document (#133): the raw
         * archive's `rollExcursion_deg` is measured over the set's WORKING
         * WINDOW -- `workStartedAt_ms`, which that document already carries,
         * to the terminal cue in the set's own cue-track CSV in the same
         * archive -- on a roll signal unwrapped across the +-180
         * degree boundary, with a new `rollExcursionBasis` naming the interval
         * used. It was `max(roll) - min(roll)` over every row of the capture
         * file, which SATURATES, since `roll_deg` is bounded to (-180, 180]:
         * one field session published 358.6 and 360.0 on sets whose unwrapped
         * sweeps are 909.0 and 515.2. And the file is not the set -- a later
         * session published 92.9 and 86.7 on working windows of 54.0 and 63.7,
         * neither set carrying a single sample after its terminal cue, so on
         * those two the whole excess is the PREP. Both keys are withheld
         * together on a window of fewer than two samples: a range over one
         * sample is 0.0 and reads as "this set did not rotate". Retroactive,
         * unlike the other entries under 1.19 -- the figure is measured from
         * the stored stream at export time rather than frozen into the row --
         * so an old session re-exports under the new rule and says which bound
         * it lacked. `RollExcursion` in `:core:dsp` is where the rule and the
         * measurements live.
         */
        const val SCHEMA_VERSION = "1.19"

        /**
         * `"1.10"` is not the number 1.1 -- a reader that parses this field as
         * a float collides 1.10 with 1.1, which is a different contract.
         */
        val SUPPORTED_SCHEMA_VERSIONS =
            setOf(
                "1.0", "1.1", "1.2", "1.3", "1.4", "1.5",
                "1.6", "1.7", "1.8", "1.9", "1.10", "1.11", "1.12", "1.13", "1.14", "1.15",
                "1.16", "1.17", "1.18", "1.19",
            )

        /**
         * Which phase a rep opened with, lowercased [StartPhase] names. 1:1
         * with the enum, so it is pinned in both directions rather than only
         * against the published schema.
         */
        val VALID_STARTS_WITH = setOf("eccentric", "concentric")

        /** How a geometry value was arrived at, lowercased [GeometrySource] names. */
        val VALID_GEOMETRY_SOURCES = setOf("declared", "seeded", "inferred", "default")

        /**
         * Which accelerometer a stream came from, lowercased [SensorRole]
         * names. 1:1 with the enum, so it is pinned in both directions the way
         * [VALID_STARTS_WITH] is rather than only against the published schema.
         *
         * Physical unit identity and nothing else. It is deliberately not the
         * `side` vocabulary: `side` says which limb was worked, this says
         * where a sensor was, and a document in which one word meant both
         * would let a reader believe a per-limb measurement exists.
         */
        val VALID_SENSOR_ROLES = SensorRole.entries.map { it.name.lowercase() }.toSet()

        /**
         * Why a set does or does not carry `velocityLoss_pct`, the values
         * [SetExport.velocityLossBasis] is drawn from.
         *
         * The names are owned by `VelocityLoss` in `:core:dsp`, which this
         * module cannot see -- the dependency runs the other way. They are
         * mirrored here so the published schema has a Kotlin constant to be
         * pinned against, the same arrangement [VALID_STARTS_WITH] uses.
         * `VelocityLossTest` asserts the two lists are equal, from the side
         * that can see both.
         */
        val VALID_VELOCITY_LOSS_BASES =
            setOf("measured", "notEnoughReps", "noReference", "terminalRepIsFastest")

        /**
         * Why a set resolved no reps, the values [SetSummaryExport.noRepsReason]
         * is drawn from. Schema 1.18, issue #138.
         *
         * The names are owned by `NoRepsReason` in `:core:dsp`, which this
         * module cannot see -- the dependency runs the other way. They are
         * mirrored here so the published schema has a Kotlin constant to be
         * pinned against, the same arrangement [VALID_VELOCITY_LOSS_BASES]
         * uses, and `BlankAnalysisReasonTest` asserts the two lists are equal
         * from the side that can see both.
         *
         * Each value names WHICH GATE emptied the rep list and claims nothing
         * about the bar or the lifter. `runsExceedDisplacementCap` in
         * particular says the set's movement runs displaced further than any
         * real phase can, which the DSP reads as unanchored integration drift;
         * no capture in this repository has been checked against a tape
         * measure, so the reading is the DSP's and not an observation.
         */
        val VALID_NO_REPS_REASONS =
            setOf(
                "afterSetEndCue",
                "noMovement",
                "runsExceedDisplacementCap",
                "runsBelowStartThreshold",
                "runsTooBrief",
                "phasesUnpaired",
                "driveBelowMinRom",
            )
    }
}

@Serializable
data class HrSessionSummary(
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /** Session-wide HRV (RMSSD, ms) from R-R intervals. */
    @SerialName("hrvRmssd_ms") val hrvRmssdMs: Double? = null,
)

@Serializable
data class ExerciseExport(
    val exercise: String,
    val sets: List<SetExport>,
)

@Serializable
data class SetExport(
    @SerialName("load_kg") val loadKg: Double,
    /** Same load in pounds, for readers who think in lb; kg remains canonical. */
    @SerialName("load_lb") val loadLb: Double? = null,
    @SerialName("plannedLoad_kg") val plannedLoadKg: Double? = null,
    /**
     * The body weight [loadKg] was computed with, kilograms (1.19, #220).
     *
     * On body-weight work `SetLoadPolicy.totalKg` returns this plus the added
     * load, which may be negative for band or machine assistance -- so this is
     * usually the LARGEST term in [loadKg], and the added or assisting load is
     * `load_kg - bodyWeight_kg`. Published because a reader comparing the same
     * lift across sessions was otherwise mixing body-weight drift with
     * assistance changes and could not see it.
     *
     * SUBTRACTION RECOVERS THE ADDED LOAD TO WITHIN ROUNDING, not
     * bit-exactly: both figures are doubles and the sum was formed in double,
     * so the difference is exact only where the addition was.
     *
     * THE FIGURE THE ARITHMETIC USED, frozen when the set was written, not the
     * lifter's weight today -- the app holds one body weight and it moves.
     *
     * ABSENT, never 0, and three states share the absence: loaded work, which
     * has no body in the load path and is readable from `geometry.bodyweight`
     * beside it; a set recorded before database v17, which no build stored;
     * and a body-weight set recorded while the app held no body weight at all,
     * where `totalKg` used 0 kg (#61). The last two are NOT distinguishable
     * here, and nothing in the document pretends otherwise. A published 0.0
     * would read as a lifter with no mass.
     */
    @SerialName("bodyWeight_kg") val bodyWeightKg: Double? = null,
    val reps: Int,
    /** True when reps were entered or corrected manually rather than sensor-counted. */
    val repsManual: Boolean = false,
    val plannedReps: Int? = null,
    /**
     * Hold/carry seconds recorded for timed sets (planks, farmer's walks).
     *
     * Since #168 a timed set ENDS when its clock reaches the seconds the set
     * was working to -- [plannedDurationS] unless the lifter changed the hold
     * in the change-set dialog, in which case theirs -- so a set that ran to
     * its target publishes [plannedDurationS] only when the lifter did not
     * change it; one the lifter ended by hand publishes what it lasted, and one
     * corrected afterwards on the rest screen publishes the corrected
     * seconds. The three are not distinguishable from each other here --
     * [repsManual] has no counterpart for duration -- so a reader comparing
     * holds across 1.12 and 1.13 is comparing figures whose upper end moved:
     * under 1.12 every timed set carried the walk back to the phone inside
     * it.
     *
     * ABSENT FROM 1.18 on a set that ended before its work phase began, which
     * carries [abandonedInPrep] instead. Such a set stores 0 here and that 0
     * was never a measurement. A set recorded before database v15 carries no
     * answer either way and publishes this key exactly as it always did.
     */
    @SerialName("duration_s") val durationS: Int? = null,
    /**
     * True when the set ENDED BEFORE ITS WORK PHASE BEGAN (1.18, #216).
     *
     * The lifter's tap started the recording, the lead-in was still running,
     * and the set was over before the clock or the cadence started. A
     * statement about the CAPTURE and not about the lifter -- the same write
     * path is taken by a set abandoned in its lead-in for any reason,
     * including a slot the app should not have armed at all.
     *
     * What follows from it: [durationS] and [prepS] are absent because neither
     * was measured, [reps] is 0 because nothing was counted rather than
     * because nothing was lifted, and any [failed] on such a set is the app's
     * derivation -- read [failedByLifter] to confirm. The raw stream is real
     * and worth reading; what it captured is a lead-in.
     *
     * Omitted when false, and omission is NOT proof of the opposite: whether
     * the work began is a database column added at v15, so every set recorded
     * before it publishes nothing here. A missing key means "the work began,
     * or the app could not tell".
     */
    val abandonedInPrep: Boolean = false,
    @SerialName("plannedDuration_s") val plannedDurationS: Int? = null,
    /**
     * Unilateral sets: the arm the set WORKED -- "left" or "right".
     *
     * From 1.17 (#215) this is the lifter's own statement where they made one
     * on the change-next-set control, and the plan's prescription otherwise.
     * Read it against [plannedSide], which carries what the plan asked for:
     * where the two differ the lifter swapped arm order, which is a thing
     * older documents could not say at all (#144).
     */
    val side: String? = null,
    /**
     * The arm the PLAN prescribed for this set, frozen when the set was
     * recorded (#215).
     *
     * Absent on bilateral work, on an ad-hoc set and on an appended set --
     * none of which was prescribed a side -- and on every set recorded before
     * database v14, where the column did not exist. Absence is therefore not
     * "the lifter worked what was asked": it is "nothing asked", or "this set
     * predates the pair".
     */
    val plannedSide: String? = null,
    /**
     * PER-SET RPE, 1 to 10: how much this ONE set had left in it.
     *
     * ONE QUESTION, ANCHORED IN TWO UNITS, because the resolution a lifter can
     * actually supply changes with the distance from failure ([EffortScale]).
     * From 1.14 the app's grid offers exactly these rungs:
     *
     *  - 10 nothing left, 9 one rep left, 8 two reps left, 7 three reps left
     *    -- reps in reserve, on a hold or an explosive lift the same rungs in
     *    that movement's own words.
     *  - 6, 4 and 1 are HEADROOM, and the caption names a figure rather than
     *    a notch, because there is no declared equipment increment anywhere
     *    in this codebase and the app cannot know which is in front of the
     *    lifter. On a rep set: 6 "could have added 10-15 lb" or "5 kg", 4
     *    "20-30 lb" or "10 kg", 1 "much more". On a hold or a carry the same
     *    three rungs ask in seconds: 6 "15-30 s longer", 4 "about a minute
     *    longer", 1 "much longer". The pound band spans a bar's 10 lb and a
     *    stack's 15 lb, which is why one tile serves both.
     *
     * WHICH CAPTION THE LIFTER SAW IS NOT RECORDED. The unit and the load or
     * time branch are display decisions taken at set end and nothing here
     * carries them; a reader wanting the unit reads the session's own, and a
     * reader wanting to know whether the time rungs were drawn reads
     * [durationS]. [EffortScale] owns the captions; the figures in SECONDS
     * are authored rather than measured, while the pound and kilogram bands
     * come from the equipment the lifter actually meets.
     *
     * 2, 3 and 5 are valid values with no tile: the gaps exist so the anchors
     * SORT, and a reader meeting one from an older session is looking at a
     * real value on the same ruler, not corrupt data.
     *
     * WHAT A PRE-1.14 VALUE MEANT. The old grid offered 6 to 10 only, where 6
     * was "easy, 4+ reps left" -- the FLOOR of that scale, so it absorbed
     * everything the new 1 and 4 now take. 7 through 10 are unchanged in
     * meaning. Nothing rewrites stored data.
     *
     * [SessionExport.sessionRpe] is a different instrument over the same
     * published range, and the two must never be averaged or compared as one
     * quantity.
     */
    val rpe: Int? = null,
    /**
     * True when the set is marked failed: the lifter tapped it as failed, the
     * set fell short of its planned reps or duration and the app derived a
     * failure, or both. The derived case needs no lifter input at all.
     * Omitted when false.
     *
     * WHICH OF THE TWO a given set carries is [failedByLifter], from 1.18.
     */
    val failed: Boolean = false,
    /**
     * Whether the LIFTER called this set failed, rather than the app deriving
     * it from a shortfall (1.18, #216, #169).
     *
     * Present only beside [failed], and a `false` is a real statement rather
     * than a gap: the set failed, the app derived it -- short of its
     * prescribed reps or seconds, or ended during its lead-in -- and the
     * lifter never said so.
     *
     * Absent on every set that did not fail, and on every set recorded before
     * database v15, where the tap lived in the rest screen's memory for the
     * life of that screen and was discarded. Nothing backfills it because
     * there is nothing to backfill from.
     *
     * Moves with [failed] and never apart from it: a re-rating or a rep
     * correction on the rest screen rewrites both in one statement, so the
     * pair cannot disagree about one set.
     */
    val failedByLifter: Boolean? = null,
    /**
     * Why the set ended, from a CLOSED vocabulary, or absent (#189).
     *
     * [SetLimiter]'s stored names, and nothing else may appear here. The
     * whole reason it is a vocabulary rather than a sentence is that a coach
     * groups by it; a free string in this key would make that impossible, so
     * the lifter's own words go in [limiterNote] beside it and never inside
     * this one.
     *
     * ABSENT IS NOT AN ANSWER. The page is skippable in one tap and only
     * failed sets are asked at all, so a missing key covers a question
     * skipped, a question never asked, and every set recorded before database
     * v13. None of those is a set that ended for an unknown reason and none
     * may be counted as one.
     *
     * The value a reader should treat differently from the rest is
     * [SetLimiter.OUTSIDE]: the set was interrupted and is not a training
     * signal at all. It exists so analysis can DISCARD such a set rather than
     * read it as capacity, which is what keeps "every unfinished set is a
     * fail" from silently depressing the record.
     */
    val limiter: String? = null,
    /**
     * The lifter's own words, present only where [limiter] is
     * [SetLimiter.OTHER] (#189).
     *
     * Published verbatim, at most [SetLimiter.NOTE_MAX_CHARS] characters, and
     * carrying neither a double quote nor a backslash -- see
     * [SetLimiter.normalizeNote], which states why: the raw archive's set
     * manifest is assembled as text and escapes nothing, so a note is stored
     * already reduced to what both writers can carry rather than being
     * escaped differently by each.
     */
    val limiterNote: String? = null,
    /**
     * True when the LIFTER says they did not perform this set (#60). Omitted
     * when false.
     *
     * THE SET IS STILL HERE, WITH EVERYTHING IT ALWAYS CARRIED. Its load, its
     * reps or hold, its prescription, its summary and its raw streams in the
     * companion archive are all exactly as they were recorded. This key is
     * what tells a reader not to read any of them as work that happened: the
     * figures describe a row, not a performance. A voided set must be dropped
     * from volume, from a set count and from any progression read.
     *
     * PUBLISHED RATHER THAN WITHHELD, and that is the decision this key
     * embodies. Removing the set from the document would make the export
     * disagree with the app's own history and would make a set that was
     * recorded and not performed indistinguishable from one that was never
     * recorded -- a gap this document cannot represent. A reader can see the
     * row was there and was not performed; that is strictly more than it could
     * ever say before.
     *
     * ABSENT MEANS NOT MARKED, which on a set recorded before database v16
     * also means the app could not ask. Those two are not distinguishable here
     * and no attempt is made to distinguish them, for [sessionRpe]'s reason:
     * both mean the lifter never said.
     *
     * NOT DERIVED, EVER. The app cannot tell a set that did not happen from
     * one that failed instantly, and no reader should try: a 0-second failed
     * timed set is the shape of the fabricated row this key was added for
     * (#195) AND the shape of an unrack-and-fail. Only the lifter's own mark
     * appears here.
     */
    val voided: Boolean = false,
    /**
     * The lifter's own words for why the set was not performed, present only
     * on a voided set (#60).
     *
     * Beside [voided] and never inside it, the way [limiterNote] sits beside
     * [limiter]: a reader grouping unperformed sets needs a boolean to filter
     * on, and free text in that position would destroy exactly that grouping.
     *
     * Absent on a voided set the lifter had nothing to add about, which is the
     * ordinary case. Cleared when a set is un-voided, so this key never
     * survives beside a set the lifter says they DID perform.
     */
    val voidReason: String? = null,
    /**
     * True when this set was preparatory -- a ramp set, a warm-up. Omitted
     * when false.
     *
     * A DECLARATION about what the set was for, not a rating of it, and since
     * 1.14 it carries no claim at all about [rpe]: a warm-up set is rated on
     * the same scale as any other set and usually will be. Until 1.14 the only
     * producer was an effort tile, which stored this flag and a null [rpe]
     * together -- so on a pre-1.14 session the pair is a limitation of the old
     * scale rather than a statement that the lifter declined to rate the set.
     *
     * TWO PRODUCERS, AND [warmupByLifter] SAYS WHICH ONE THIS IS (#194). The
     * plan declares it, and the lifter may mark or unmark the set afterwards;
     * where both exist the LIFTER'S mark wins, because the declaration is a
     * prediction written before the session and the mark is a statement by the
     * person who did the set. [WarmupMarkPolicy] owns that composition.
     *
     * A PARAGRAPH THAT USED TO STAND HERE IS DELETED RATHER THAN REWORDED: it
     * said this flag is false on an ad-hoc or appended set "because nothing
     * declared those", and that "the app has no way to say it". Both were true
     * until #194 and are false now -- the rack warm-up is exactly the case the
     * mark exists for.
     */
    val warmup: Boolean = false,
    /**
     * True when the LIFTER stated this set's purpose themselves, rather than
     * leaving the plan's declaration to stand (#194). Omitted when false.
     *
     * It says WHICH FACT [warmup] carries and does not change what [warmup]
     * means. True with `warmup` absent is a set the lifter said was NOT a
     * warm-up -- which is a real statement and the reason the mark is stored
     * as three states rather than two.
     *
     * WHAT THIS DOCUMENT DOES NOT SAY, stated because the absence is easy to
     * read past: where this is true, the plan's own declaration is not
     * published and cannot be recovered from the export. The row keeps both
     * facts; the document publishes the answer and its author. Nothing today
     * needs the overridden declaration, and publishing a fourth key for it was
     * refused rather than forgotten.
     */
    val warmupByLifter: Boolean = false,
    /**
     * True when the LIFTER appended this set to the exercise mid-session, and
     * the plan did not prescribe it. Omitted when false (#177).
     *
     * WHY A READER NEEDS IT. Adherence is read from [plannedReps] beside
     * [reps], and from how many sets an exercise carries against how many the
     * plan asked for. An appended set occupying a prescribed slot corrupts both
     * readings at once: it inflates the count, and -- because it has no
     * prescription of its own -- it publishes no [plannedReps] either, so it
     * reads as a prescribed set whose prescription went missing.
     *
     * An appended set therefore publishes NO [plannedLoadKg], [plannedReps]
     * or [plannedDurationS], and that absence is a statement rather than a
     * gap: nothing prescribed it. [tempoPrescribed] is NOT in that list: it
     * is read from the same run-value rule `load_kg` and `reps` use, not from
     * a frozen plan declaration, so an appended set on a block that declares
     * a tempo publishes it -- naming a tempo nothing prescribed for that
     * occurrence. `rest_s` and `plannedPrep_s` are published too, unchanged
     * from the rest of the block: neither is cleared for an appended slot.
     * Its `load_kg`, `reps` and tempo are what the lifter was standing on
     * when they added it -- the corrected load, not the plan's.
     *
     * OMISSION IS NOT PROOF OF THE OPPOSITE for old documents. The flag is a
     * column added at database v12; every set recorded before it reads false,
     * so on those sessions an appended set is indistinguishable from a
     * prescribed one. A missing key means "prescribed, or recorded before the
     * app could tell".
     */
    val added: Boolean = false,
    /**
     * The prep prescribed before this set, and the prep that played, in whole
     * seconds.
     *
     * Whenever the two differ, the lifter adjusted the prep in the app; they
     * are equal both when no adjustment exists and when the adjustment happens
     * to equal what the plan prescribed. The difference is what lets the next
     * plan be authored from this document instead of re-guessed.
     *
     * [plannedPrepS] is present whenever the set played a prep, including where
     * the plan declared nothing: the app's default is still what was
     * prescribed, and a reader that saw only [prepS] could not tell an
     * adjustment from a declaration without knowing the app's constant.
     *
     * [restS] beside them is the one planned value in this type whose name does
     * not say it is planned, so a reader takes a prescription for an
     * observation. That is issue #76.
     *
     * Both absent on a set that played no prep -- such a set has none -- and
     * both absent on every set recorded before 1.11, and on every hold and
     * carry recorded before a prep reached them. 0 is a value, not an absence: it is
     * the prep in which nothing is spoken before the set begins, and the default
     * here is null precisely so that 0 survives `encodeDefaults = false`.
     *
     * FROM 1.18 [prepS] is ALSO absent on a set that ended before its work
     * phase began: it carries the prep the app SET OUT to play rather than
     * the prep that elapsed, and on such a set the two provably differ.
     * [plannedPrepS] still publishes, because the prescription is still true
     * and without it the withheld figure would read as "no voice guide ran".
     */
    @SerialName("plannedPrep_s") val plannedPrepS: Int? = null,
    @SerialName("prep_s") val prepS: Int? = null,
    /**
     * The rest PRESCRIBED after this set, in whole seconds -- never a
     * measurement of how long the lifter rested (#76).
     *
     * From 1.19 the published description states which instant it is counted
     * FROM, and `RestClockPolicy` owns that instant: the terminal cue on the
     * set's own cue track, or the set's end instant where nothing called it
     * over. The countdown and the archive's `rest_before_hrm` window both
     * begin there (#178); until 1.19 the window began when the set's capture
     * stopped instead, up to 53.06 s later on one measured set. `rest_after_hrm`
     * -- the window a session close writes onto the LAST set, when there is no
     * next set to carry `rest_before_hrm` forward -- follows the same instant
     * and the same copy-forward. A gap this does not close: `endSet` cancels
     * the in-set collector before the app enters its rest stage, so a
     * notification landing in that interval reaches neither capture -- 0.08 to
     * 0.58 s on the one session measured.
     */
    @SerialName("rest_s") val restS: Int? = null,
    val tempoPrescribed: String? = null,
    val tempoCompliance: TempoComplianceExport? = null,
    @SerialName("velocityLoss_pct") val velocityLossPct: Double? = null,
    /**
     * Which case [velocityLossPct] is in, drawn from
     * [SessionExport.VALID_VELOCITY_LOSS_BASES].
     *
     * Present whenever the sensor resolved any reps, including -- especially
     * -- when [velocityLossPct] itself is absent, so that a reader can tell a
     * figure that was WITHHELD from one an older app version simply never
     * wrote. Absent when no reps were resolved at all, the same condition
     * under which [repMetricsComplete] is absent: there is no rep list for it
     * to be a statement about.
     */
    val velocityLossBasis: String? = null,
    val hr: HrSetSummary? = null,
    /** Per-rep detail; included only when the user enables detailed export. */
    val repMetrics: List<RepMetricsExport>? = null,
    /** Spoken cues with epoch-ms stamps, cross-referenceable with the raw IMU stream (detailed export only). */
    val voiceCues: List<VoiceCue>? = null,
    /**
     * The instants a rep was COUNTED during this set, epoch milliseconds on
     * the same clock as the raw IMU, heart-rate and cue streams. Detailed
     * export only, the same terms [voiceCues] is published on.
     *
     * What was counted, never what the bar did. A mark is written when the
     * lifter taps the rep button or when the voice guide calls a rep, so on a
     * straight-rep set carrying no tempo these are the only per-rep instants
     * that exist anywhere in the document: [repMetrics] entries carry
     * durations and an ordinal position and no clock, and [voiceCues] is what
     * the app SAID rather than what was counted.
     *
     * Absent rather than empty, and the absence is weak. A sensor-counted set
     * produces no marks at all, and neither does any set recorded before the
     * app stored them; nothing here tells those two apart, and neither is
     * evidence that no rep was performed.
     *
     * The number of marks may disagree with [reps], in both directions. A
     * rest-screen correction rewrites [reps] and cannot reach a mark already
     * written, and the guide calls a rep on its own schedule whether or not
     * the lifter followed it. Where they disagree, [reps] is what the set was
     * recorded as and this is what was counted while it happened.
     */
    val repMarks: List<Long>? = null,
    /**
     * False when the sensor segmenter resolved a different number of reps than
     * the set records — the lifter or the voice guide counted something else.
     *
     * Stated without reference to [repMetrics], deliberately. Everything drawn
     * from the segmented reps carries this caveat — [velocityLossPct],
     * [tempoCompliance] and [summary] as much as the per-rep array — and those
     * three are published whether or not per-rep detail was asked for, so a
     * caveat that only appears alongside the array leaves the summary-only
     * reader holding the numbers without the warning.
     *
     * True is weaker than it looks and should not be read as an independent
     * check: when [repsManual] is false the stored rep count IS the segmenter's
     * count, so the two agree by construction. Only false carries information
     * the reader could not derive from [repsManual] alone.
     *
     * Null is a third state, not a synonym for false: the segmenter resolved no
     * reps at all, so there is no figure left to qualify.
     */
    val repMetricsComplete: Boolean? = null,
    /**
     * The direction and geometry this set's numbers were measured with.
     *
     * Absent means the set was recorded before the app stored it. Absent does
     * NOT mean vertical, drive-up, sensor-on-the-bar: a wrong declaration is
     * worse than no declaration, so nothing is defaulted in.
     */
    val geometry: GeometryExport? = null,
    /**
     * How many accelerometers this set was armed with, and which stream its
     * figures came from (#156).
     *
     * Absent on the ordinary one-sensor set, which is what keeps a
     * single-sensor export identical to what earlier versions wrote. Absent
     * therefore covers two cases -- a set recorded in single-sensor mode, and
     * a set recorded before the app could capture two -- and deliberately does
     * not distinguish them, exactly as [geometry]'s absence does not.
     *
     * Nothing here counts FILES, and a reader must not either: a set that
     * armed two and captured one is a different fact from a set that armed
     * one, and only [SetSensorsExport.count] against
     * [SetSensorsExport.present] can tell them apart. What is armed is
     * declared; [SetSensorsExport.present], [SetSensorsExport.analysedRole]
     * and [SetSensorsExport.analysedFellBack] are observations of which units
     * streamed, taken when the set was recorded (#207).
     */
    val sensors: SetSensorsExport? = null,
    /** Always-included summary across reps. */
    val summary: SetSummaryExport,
)

/**
 * A set's accelerometer configuration: what was armed, what arrived, which of
 * it the numbers came from, whether that was the unit the set armed, and what
 * stopped a second stream.
 *
 * Six statements rather than one because each answers a question the others
 * cannot. [count], [expected] and [shortfall] are declarations made when the
 * set began; [present], [analysedRole] and [analysedFellBack] are
 * observations, decided at the end of the set from which units actually
 * streamed (#207). [present] is stated rather than left to be inferred from
 * filenames this document does not contain.
 *
 * No per-stream sample counts or rates. Those live in the raw archive's
 * `meta.json`, where the exporter already holds the inflated text; putting
 * them here would force the standalone share path to inflate and parse every
 * IMU stream, which it does not do today -- reintroducing the double
 * decompression issue #29 removed.
 *
 * **[expected] and [present] have no Kotlin default, and that is deliberate**
 * -- the reasoning [GeometryExport] gives for its own fields. The exporter
 * writes JSON with `encodeDefaults = false`, so a list defaulted to empty
 * would be DROPPED from the wire exactly when it is empty, and its absence
 * would read as "not stated" when it meant "no role was armed" or "nothing
 * arrived". Those are the two most informative states this object has: a set
 * that met two paired units it could not tell apart and armed neither by role,
 * and one whose every unit went silent. Both are written out.
 */
@Serializable
data class SetSensorsExport(
    /**
     * How many sensors the set was actually armed with.
     *
     * A `plannedCount` stood in front of this key until #198 and is gone with
     * the declaration it read. No plan decides how many accelerometers a set
     * records, so there is no planned half of a pair left to publish, and a
     * key that kept emitting the old default would tell a reader a coach
     * intended something.
     *
     * Not [expected]`.size`, and the difference is load-bearing: a set that
     * met two PAIRED units it could not tell apart records `count: 1` with
     * an EMPTY `expected`, because its single stream carries no role and
     * inventing one would label a capture nobody labelled. [shortfall] says
     * which case that is.
     */
    val count: Int,
    /**
     * The roles this set was armed for. Empty when its streams carry no role.
     *
     * Values are drawn from [SessionExport.VALID_SENSOR_ROLES]. A role is the
     * identity of a physical unit and asserts nothing about which end of the
     * bar or which hand it was on -- a mounting swapped between sets is a
     * post-processing question, not a corruption.
     */
    val expected: List<String>,
    /**
     * The roles whose stream reached the archive, in [expected]'s order.
     *
     * The roles MISSING are the set difference. There is no third key for
     * them: a duplicate statement of one fact is one that can disagree with
     * its own inputs.
     */
    val present: List<String>,
    /**
     * Which role's stream every figure in this set was computed from.
     *
     * Since #207 it is a role that STREAMED wherever one did -- the boundary
     * is when the set was RECORDED, not what the document's `schemaVersion`
     * says: a set armed to analyse a unit that produced nothing is analysed
     * from the unit that did, and [analysedFellBack] is what says the app
     * moved. Read that key rather than comparing this one with [present],
     * which no longer separates the two cases.
     *
     * It can still name a role absent from [present] in two situations, and
     * neither has figures drawn from the surviving stream: a set where NOTHING
     * streamed, whose summary is empty because there was no capture; and a set
     * recorded by a build that predates this behaviour, whatever the
     * document's `schemaVersion` says, which kept the armed role whatever
     * happened. Null when no role is in play.
     */
    val analysedRole: String? = null,
    /**
     * True when [analysedRole] is not the role the set armed for analysis,
     * because that unit produced no stream and another one did (#207).
     *
     * The fact a reader cannot derive. "Analysed the preferred unit" and
     * "analysed the only unit that turned up" are different statements about
     * how far these figures can be compared with the rest of a corpus, and
     * once the analysed role is one that streamed, `analysedRole !in present`
     * no longer separates them.
     *
     * Absent rather than false on the ordinary set: the exporter writes with
     * `encodeDefaults = false`, and unlike [expected] and [present] this key
     * has an unremarkable normal that omission reads correctly, the same rule
     * [SetExport.failed] and [SetExport.warmup] follow. Absent is also what
     * every set recorded by a build that predates this key carries, whatever
     * the document's `schemaVersion` says: the flag is frozen into the set's
     * row when the set is RECORDED and only copied out at export. It means
     * the same thing there -- no build before it could move the analysed
     * role.
     */
    val analysedFellBack: Boolean = false,
    /**
     * Why two or more PAIRED units produced one stream, or absent when
     * nothing was in the way.
     *
     * `rolesUnassigned` -- at least one paired unit carried no A/B label.
     * `rolesCollide` -- every paired unit is labelled and two of them share
     * a label. In either case the app recorded ONE stream, because two
     * 20-byte WitMotion frames carry no checksum and interleaving two
     * streams it cannot tell apart fabricates plausible samples rather than
     * failing.
     *
     * PAIRED IS NOT CONNECTED and this key says the weaker thing: the app
     * never opened a link to a second unit in this state, so it means two
     * units are paired and cannot be told apart -- not that both were
     * switched on or in range.
     *
     * Absent on a dual set and absent on the ordinary one-sensor set, where
     * the whole object is absent too. What it exists for is the distinction
     * between "there was one sensor" and "there were two and one was
     * unusable", which are different facts about a session and which nothing
     * else in this document can separate since #198 retired `plannedCount`.
     *
     * IT DESCRIBES THE DEVICE ROSTER RATHER THAN THE SET, so it appears on
     * EVERY set of a session rather than on the ones something went wrong
     * on. It is published per set anyway: the alternative makes a session
     * recorded entirely under an unusable pair indistinguishable from a
     * one-sensor session. One historical exception -- a row written before
     * this version carried its reason as a `plannedCount` this build does
     * not read, and re-exports with no shortfall at all -- is stated in full
     * in the published `shortfall` description, which is the copy a reader
     * of the document has.
     */
    val shortfall: String? = null,
    /**
     * Which ARMED roles delivered nothing for the whole set, keyed by role,
     * with what the app could see of each one's link when the set ended
     * (#213).
     *
     * A sibling of [shortfall] rather than a member of it, because the two
     * answer different questions about different things. [shortfall] is about
     * the device ROSTER before the set -- two paired units the app cannot tell
     * apart -- and says nothing about whether either was switched on. This is
     * about THE SET, is observed at the end of it, and is the only statement
     * in this document about whether an armed unit actually delivered.
     *
     * The ROLE is a key rather than a second list. Which roles were silent is
     * [expected] minus [present] and a reader can already compute it; a second
     * list of them would be the duplicate statement [expected]'s own
     * description refuses. The VALUE is what is new.
     *
     * Values are `notLinked`, `linkWithoutSensor`, `linkedSilent` and
     * `tooSoon`, spelled as [ArmedSilencePolicy.wireOf] spells them, and each
     * is weaker than it looks. `notLinked` merges powered-off, out of range,
     * refused, a bond removed in the phone's settings, and one that connected
     * and then failed service discovery, because nothing in this app reads
     * the OS bond state and a connect that never completes discovery reads
     * the same as one that never started. The published description in
     * `docs/schemas/session-export.schema.json` is the copy a reader of the
     * document has and states each limit in full.
     *
     * Absent rather than empty on the ordinary set, the rule
     * [analysedFellBack] follows and deliberately not the one [expected] and
     * [present] follow: there is no informative empty here, and absence also
     * covers every set recorded by a build that could not observe delivery at
     * all, which is every set before this version. One further meaning this
     * version cannot remove: a set shorter than three seconds where an armed
     * unit's last frame arrived during the preceding rest reads as delivering
     * and is left out.
     *
     * A set armed with one bar sensor arms no ROLE at all, so this map is
     * absent there and [soleSilent] carries the same word without one (#224).
     * The two are never both written.
     */
    val silent: Map<String, String> = emptyMap(),
    /**
     * What the app could see of the ONE armed link on a set whose stream
     * carries no role, or absent when that link delivered (#224).
     *
     * [silent]'s answer for the set that has no key to hang it off. A role
     * exists only where two paired units carry two different labels, so on the
     * ordinary one-sensor set -- the configuration this app is used in most --
     * [silent] is structurally empty, and until this key a whole session of
     * sets recorded through a paired unit whose link delivered nothing
     * published nothing about it at all.
     *
     * A WORD RATHER THAN A ONE-ENTRY OBJECT, because there is no role to key
     * it by and a key invented for the purpose -- "sole", "unroled" -- would put
     * something that reads like a role into a document whose readers are told a
     * role is the identity of a physical unit.
     *
     * Its vocabulary is [silent]'s, spelled as [ArmedSilencePolicy.wireOf]
     * spells it, and every word is weaker than it looks for the reasons stated
     * there. The published description in
     * `docs/schemas/session-export.schema.json` is the copy a reader of the
     * document has and states each limit in full.
     *
     * Never written beside [silent]. It appears on three shapes of set, all of
     * which record one unroled stream through the one link the app holds: a set
     * armed with a single paired unit; one that met two paired units it could
     * not tell apart, which keeps its [shortfall], describing the ROSTER,
     * beside this, describing the LINK; and one whose two paired units ARE
     * labelled apart but whose preferred address names neither of them, which
     * carries no [shortfall] at all -- the roster is not what is wrong, the
     * preference is.
     *
     * ABSENT MEANS THE SET CAPTURED SAMPLES from that one link -- or, on a set
     * shorter than [ArmedSilencePolicy.SILENT_AFTER_MS], that its last frame
     * arrived during the preceding rest and read as delivering -- or that the
     * document was written by a build that could not observe an unroled link
     * at all. A link that fed part of a set and then went silent publishes
     * nothing here either: the word is refused wherever the set's buffer is
     * not empty. Absent rather than empty on the ordinary set,
     * [analysedFellBack]'s rule.
     */
    val soleSilent: String? = null,
)

/**
 * How the lift moved and how the sensor was mounted, as the app resolved it for
 * this set — not as a plan declared it, because the app applies a precedence
 * chain and a plan's text may have been overridden.
 *
 * This is what makes the rest of the set checkable. [SetExport.tempoPrescribed]
 * is positional notation — digit 1 is the down stroke, digit 3 the up stroke —
 * so which stroke is the eccentric follows from [concentric] and [plane], not
 * from the digit order. Without those, [SetExport.tempoCompliance] is a verdict
 * whose input the reader cannot see.
 *
 * **Every field is required, and none has a Kotlin default.** The exporter
 * writes JSON with `encodeDefaults = false`, so a field defaulted to `false`
 * would be dropped from the wire and its absence would read as "not stated"
 * when it meant "stated false" — the exact defect this object exists to fix.
 * Contrast [SetExport.failed] and [SetExport.warmup], where false is the
 * unremarkable normal and omission reads correctly.
 */
@Serializable
data class GeometryExport(
    /** Which phase opened each rep: "eccentric" or "concentric". */
    val startsWith: String,
    /** Which way the driving phase moved: "up" or "down". */
    val concentric: String,
    /** The plane the LIFTER moved in: "vertical" or "horizontal". */
    val plane: String,
    /**
     * True when the sensor rode a cable weight stack. The stack travels
     * vertically however the lifter moves, so this overrides [plane] for the
     * axis that was actually measured.
     */
    val sensorOnStack: Boolean,
    /** True when the sensor moved opposite to the load the lifter drove. */
    val sensorInverted: Boolean,
    /**
     * Lifter-side travel per unit of sensor travel. Every velocity and range of
     * motion in this set is lifter-side, so on a 2:1 pulley they are twice what
     * the sensor saw.
     */
    val travelRatio: Double,
    /** How the movement is performed: "dynamic", "hold", "carry" or "explosive". */
    val kind: String,
    /** True when the lifter's own body was the load, so load_kg includes body weight. */
    val bodyweight: Boolean,
    /** Where each of the resolvable values came from. */
    val source: GeometrySourceExport,
)

/**
 * Declared, seeded, inferred or default, per value.
 *
 * CANONICAL. This KDoc is the one statement of which geometry values carry a
 * provenance and why. `SchemaContractTest` and `SessionExporterTest` point
 * here instead of restating it: three copies of the rule drifted, and two
 * review rounds running corrected them one file at a time.
 *
 * ONE of [GeometryExport]'s eight values is missing here on purpose:
 * `sensorInverted` is a non-nullable boolean in the plan format, so a declared
 * `false` and an omitted key are the same value and no source can be told
 * apart. Stating one would be an invention.
 *
 * `sensorOnStack` was a second until #223 made the plan key nullable, and an
 * omitted key on a machine the app seeds is answered from
 * [ExerciseDef.STACK_MOUNTED_IDS] and published as `seeded`.
 *
 * `bodyweight` was a third until 1.19 (#220), which publishes it. `#227`
 * ("Make bodyweight nullable so an omitted key is not a silent false") made
 * `PlanExerciseDef.bodyweight` a `Boolean?`, the same change #223 made for
 * `sensorOnStack`, so a declared `false` and an omitted key are two distinct
 * states now; [SetGeometryPolicy.bodyweightSource] reads a declared `false`
 * as `declared`, the same as `sensorOnStack`'s pair, since the round-1 fix
 * to issue #178's review.
 */
@Serializable
data class GeometrySourceExport(
    val startsWith: String,
    val concentric: String,
    val plane: String,
    val kind: String,
    val travelRatio: String,
    val sensorOnStack: String,
    val bodyweight: String,
)

@Serializable
data class SetSummaryExport(
    @SerialName("meanConVel_mps") val meanConVelMps: Double? = null,
    @SerialName("peakConVel_mps") val peakConVelMps: Double? = null,
    @SerialName("meanEcc_s") val meanEccS: Double? = null,
    @SerialName("meanCon_s") val meanConS: Double? = null,
    @SerialName("meanRom_m") val meanRomM: Double? = null,
    /**
     * How far the reps of this set disagree with each other about rom_m: the
     * population standard deviation as a percentage of [meanRomM]. Absent,
     * never 0, below two reps or when the reps average no displacement --
     * dispersion is undefined there. See SetAnalyzer.romSpreadPct.
     */
    @SerialName("romSpread_pct") val romSpreadPct: Double? = null,
    /** Best instantaneous concentric power across the set, watts. */
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
    /** Mean of per-rep average concentric power, watts. */
    @SerialName("meanConPower_w") val meanConPowerW: Double? = null,
    /**
     * Why this set resolved no reps -- schema 1.18, issue #138. Drawn from
     * [SessionExport.VALID_NO_REPS_REASONS].
     *
     * The one key in this object written when every other key is absent. A
     * healthy stream can segment to nothing, and a `summary: {}` carrying no
     * reason is byte-identical to a manual set recorded with no sensor; this
     * is what separates them.
     *
     * ABSENT ON A SET THAT RESOLVED ANY REP AT ALL, including one that
     * resolved 1 of 10. Its absence is not a statement that the reps are
     * right. Absent too on every set recorded before 1.18, because the value
     * is frozen into the stored analysis when the set is recorded and nothing
     * re-runs the segmenter at export time.
     */
    @SerialName("noRepsReason") val noRepsReason: String? = null,
)

@Serializable
data class RepMetricsExport(
    /** Null when no eccentric was measurable — never 0, which would read as an instant phase. */
    @SerialName("ecc_s") val eccS: Double? = null,
    /**
     * Seconds still at the BOTTOM turnaround INSIDE this rep, absent when the
     * rep has no bottom turnaround to measure -- schema 1.16. See the
     * property's own description in `docs/schemas/session-export.schema.json`.
     */
    @SerialName("bottomPause_s") val bottomPauseS: Double? = null,
    @SerialName("con_s") val conS: Double,
    /** Seconds still at the TOP turnaround, on the same rule as [bottomPauseS]. */
    @SerialName("topPause_s") val topPauseS: Double? = null,
    /** Mean drive velocity, positive in the direction the drive moves. */
    @SerialName("meanConVel_mps") val meanConVelMps: Double,
    @SerialName("peakConVel_mps") val peakConVelMps: Double,
    @SerialName("meanEccVel_mps") val meanEccVelMps: Double? = null,
    @SerialName("rom_m") val romM: Double,
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
    @SerialName("meanConPower_w") val meanConPowerW: Double? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TempoComplianceExport(
    val prescribed: String,
    @SerialName("tolerance_s") val toleranceS: Double,
    /**
     * Reps within tolerance on every scored phase THAT REP RESOLVED, out of
     * [of], the reps that resolved at least one. Pauses are reported but
     * never scored. A phase the sensor did not measure is not counted against
     * the lifter and does not appear in [scoredPhases], so read that field to
     * know what this ratio covers: on a slow concentric-first lift it is
     * often the drive alone. `of: 0` means nothing was gradeable.
     */
    val withinTolerance: Int,
    val of: Int,
    /**
     * Which phases were scored — the movement digits only, and only those
     * actually measured. Always written, including empty: the exporter drops
     * defaults, and an absent key reads as "not stated" when it means
     * "nothing was graded".
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val scoredPhases: List<String> = emptyList(),
    /** Prescribed eccentric:concentric contrast — what a tempo block actually trains. */
    val prescribedEccConRatio: Double? = null,
    val actualEccConRatio: Double? = null,
)

@Serializable
data class HrSetSummary(
    val endOfSetBpm: Int? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /**
     * The lowest bpm this set's trusted samples support -- the same
     * population [avgBpm] and [maxBpm] are drawn from, never the whole
     * stream. :core:model has no dependency on :core:data, so the reasoning
     * lives where the divergence is concrete: SessionExporter.setExport in
     * :core:data computes this one figure fresh from the set's raw HRM
     * stream at export time, while its three siblings here are read off
     * columns frozen at record time.
     */
    val minBpm: Int? = null,
)
