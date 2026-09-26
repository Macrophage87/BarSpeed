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
     * was left", anchored as reps in reserve at 7 to 10 and, below that, as
     * headroom in whichever noun the exercise progresses in -- load, reps,
     * seconds or a bare feeling, named by [SetExport.rpeScale] from 1.19;
     * this is the whole workout on 1 to 10, and the two
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
    /**
     * The prescribed sets the lifter deliberately did not do, in the order they
     * were dropped (#300). Empty means none was, and the key is omitted.
     *
     * WHY THE DOCUMENT NEEDS IT, and the sentences that were false without it.
     * [SetExport.added]'s KDoc says adherence is read *"from how many sets an
     * exercise carries against how many the plan asked for"*, and
     * [SetExport.rpeScale]'s says *"the plan is not in the export"* and that a
     * plan *"can be edited or deleted after the session it drove"*. Both stay
     * true. What follows from them is that a session carrying three squat sets
     * where the plan asked for four is, to this document, indistinguishable
     * from a plan that asked for three, from a set the app lost, and from a
     * session that stopped early. Until #300 no control could drop one set, so
     * the hole was always the whole remainder of a session; now it can be one
     * set in the middle, and an archive that cannot say so publishes a
     * deliberate decision as data loss.
     *
     * ADHERENCE, AS A READER SHOULD COUNT IT: recorded-and-not-voided sets over
     * prescribed sets. A set in this list was prescribed and not performed, and
     * it is not in [exercises] at all -- there is no row for it, so nothing here
     * reads as a set of zero reps. A set that WAS performed and marked not-done
     * afterwards is [SetExport.voided] instead, published with its row.
     *
     * WHAT IT DOES NOT SAY. Nothing distinguishes a skip from a set the lifter
     * never reached, because a session that simply ends still drops its
     * remainder and writes no entry here. So an entry is evidence of a
     * decision; an absent entry is not evidence that everything prescribed was
     * done.
     *
     * CAPTURED AT THE SESSION CLOSE and nowhere else. The list is held in the
     * app's own memory while the session runs and is written with the end time,
     * so a session the process does not survive publishes no skips even where
     * the lifter made some, and those holes read as the data loss this key was
     * minted to rule out. The session row exists at every skip -- a set of the
     * block has been recorded before a skip is offered -- so the write could
     * happen at the tap; it rides to the close with [sessionRpe] and the
     * session's HRV instead.
     */
    val skippedSets: List<SkippedSet> = emptyList(),
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
         * from. Under 1.13 as well, and for the same reason -- 1.13 was
         * unreleased when that change landed. Absent on every ordinary
         * one-sensor set, which is what keeps a single-sensor export
         * byte-for-byte what 1.12 wrote apart from the version string itself.
         *
         * 1.13 carries a FOURTH change, additive on the same terms and under
         * the same version for the same reason (#159): a session may carry
         * [sessionRpe], the lifter's own 1-to-10 answer to how the whole
         * workout felt. No key from 1.12 changes type or stops being written
         * for it, and it is absent on every session the lifter did not rate
         * and on every session recorded before this version.
         *
         * 1.13 carries a FIFTH change (#176, #173) and a SIXTH (#157, #174),
         * both under the same number because 1.13 was unreleased when they
         * landed, and NEITHER of them additive: `voiceCues` gains the rep
         * call the guide then merged into a stroke's own word, so an existing
         * array's contents change (the guide stopped merging at 1.20, which
         * that entry states; this one is 1.13's and is left as 1.13's); and `plannedReps` / `plannedDuration_s`
         * publish what the plan declared, frozen at import, rather than the
         * box the lifter left
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
         * 1.14 carries a SECOND change, under the same number because 1.14 was
         * unreleased when that change landed and the two are one design (#187),
         * and it is NOT additive either: [SetExport.rpe] is a 1-to-10 scale
         * whose rungs are anchored
         * DIFFERENTLY ALONG ITS LENGTH. 7 to 10 stay reps in reserve -- three,
         * two, one, none -- and 6, 4 and 1 are load headroom, "could have
         * added one increment / two increments / much more", asked in seconds
         * instead on a hold. (THAT IS WHAT 1.14 DID. From 1.19 the noun is the
         * exercise's own declared progression and a set says which in
         * `rpeScale`; the eleventh 1.19 entry below is the one to read for a
         * document declaring that version or later.) No key changes type or stops being written, and
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
         * [VALID_NO_REPS_REASONS] and names why the list is empty, nothing
         * more. "Names WHICH GATE of the segmenter emptied the list" stood
         * here and is deleted. It was never true: `afterSetEndCue`, which 1.18
         * itself shipped, names a set whose spans the set's own END CUE
         * excluded, and that value's own KDoc says segmentation did not fail.
         * It is false of `mountNotDeclared` too, added at 1.20 and set before
         * the segmenter runs at all. This is the THIRD framing of the
         * sentence: the second was written by "Refuse the analysis when the
         * fallback unit's mounting is unknown", which claimed the clause had
         * been true of everything 1.18 shipped, and review round 2 missed it.
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
         *
         * 1.19 carries a FIFTH change, under the same number and for the
         * reason the entries above state: a number takes further entries
         * until it ships, and 1.19 is unreleased. The closed `limiter`
         * vocabulary gains a ninth answer, `setup`: the set was set up wrong
         * before it was ever a test of the muscle (#146). Additive in what
         * the app writes -- no key changes type or stops being written and no
         * existing answer changes meaning -- but NOT nothing to a validator,
         * because `limiter` is a CLOSED enum: a reader validating against
         * 1.18, which v0.1.50 shipped carrying eight answers, rejects a
         * document carrying the ninth. It does NOT apply retroactively and
         * nothing backfills it: the answer is the lifter's own, given at the
         * set or not at all. Storage does not move -- the column is TEXT
         * holding the answer's own name, so no migration is owed and this
         * change does not move `DATABASE_VERSION`.
         *
         * 1.19 carries a SIXTH change, under the same number and for the
         * reason the entries above state -- 1.19 is unreleased, and a number
         * takes further entries until it ships. THIS PARAGRAPH AND THE SET-UP
         * ANSWER ABOVE IT both read FIFTH after the rebase that merged this
         * branch onto `main`, so a reader asking for the fifth 1.19 entry
         * found two different answers; the set-up answer keeps FIFTH and this
         * widening is SIXTH. The ordinal names a position in the merged log,
         * re-verified against the rebased tree rather than carried from
         * before the rebase. `limiter` and `limiterNote`
         * may now appear on a set that did NOT fail (#191): a completed set
         * the lifter rated at the counted end, `rpe` 7 through 10, is asked
         * the same question the failed set has been asked since #189, and
         * stores the same answer in the same key. Additive to a validator --
         * no key changes type, no key stops being written, the closed
         * vocabulary does not move, and this change does not move
         * `DATABASE_VERSION` because the column has always been on every set
         * row. NOT nothing to
         * a READER: a consumer that inferred `failed` from the presence of
         * `limiter`, which nothing in this repository does but which the
         * published description previously invited by saying only failed sets
         * are asked, is wrong from this number on. Read `failed`. It does not
         * apply retroactively and nothing backfills it. A completed set rated
         * in the headroom rungs is not asked, so absence still covers
         * skipped, never asked, and recorded before the app could ask. The
         * published description of `limiter` is corrected with it.
         *
         * 1.19 carries a SEVENTH change, under the same number the mint above
         * states (#209): an armed unit that delivered too few frames to
         * analyse no longer holds the analysis and no longer reads as a
         * unit that delivered. `analysedFellBack`, `silent` and
         * `soleSilent` each widen from "delivered nothing at all" to
         * "delivered fewer than
         * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES]"; `present` does not
         * move, because the archive holds that unit's file. The consequence
         * for a reader is that `expected` minus `present` is no longer the
         * unit the analysis moved off and no key here publishes a frame
         * count. The published log in
         * `docs/schemas/session-export.schema.json` carries the full entry
         * and is the one to read.
         *
         * 1.19 carries an EIGHTH change, under the same number the mint
         * above states, and it is NOT additive (#243, "Guided rep counter
         * announces reps only to planned minus two, says Last rep on some
         * tempos only, and never announces the final rep"): the guided
         * metronome's rep call names a DIFFERENT REP. Until this version the
         * call counted FINISHED reps -- "Rep 6" spoken while the seventh was
         * under way -- so the last number of a set was planned-2. From this
         * version the call names the rep now due: a set of twelve says
         * "Rep 2" through "Rep 11", then "Last rep" for the twelfth and
         * "Done" after it. No key here changes type or stops being written
         * and no cue row is renamed; what changes is WHICH rep a `Rep N` row
         * names, so a consumer aligning cue rows to reps is off by one across
         * the boundary and nothing in a row says which side of it the set was
         * recorded on. It does NOT apply retroactively -- cue rows are stored
         * as they are spoken -- so this document's reading rule for a set
         * recorded before this version is the 1.13 entry above, not this one.
         * The published log in `docs/schemas/session-export.schema.json`
         * carries the full entry, including the last-rep warning that is no
         * longer withheld and the one tempo count that moves with it, and is
         * the one to read.
         *
         * 1.19 carries a NINTH change, under the same number the mint
         * above states (#125): a set may carry
         * [SetExport.refusedDetections], how many detections the analyzer
         * judged were not reps of the set, and
         * [SetExport.refusedDetectionReason], the single word saying why.
         *
         * NOT A FIFTH ENTRY UNDER 1.18, which is where it was drafted:
         * 1.18 SHIPPED in v0.1.50. `git rev-list -n1 v0.1.50` is
         * a7dfa323a3565ed2365ac4977b7384a0bd99d98c and
         * `git show v0.1.50:core/model/.../SessionExport.kt` declares
         * `"1.18"`. Extending a number a consumer has already been handed
         * redefines what they were handed, which is exactly the rule 1.18's
         * own first entry states about 1.17. 1.19 was already open on `main`,
         * so this is an entry under it and not a mint.
         *
         * THE FOUR ENTRIES ABOVE THAT CALL 1.18 UNRELEASED ARE LEFT
         * STANDING. Each was true when it landed -- all four were present at
         * v0.1.50 and shipped together in it, checked by `git show
         * v0.1.50:...SessionExport.kt | grep "1.18 carries a"` -- so what
         * expired is the premise, not the entries.
         *
         * The rule is `RepRefusal` in `:core:dsp`: a detection that resolved
         * no eccentric partner and whose range exceeds 4.5x the median range
         * of the set's OTHER detections is not a rep. Field session 37's set
         * 10 published `peakConVel_mps` 1.044 and `peakPower_w` 552.4 from one
         * such detection, ranging 1.746 m on an assisted pull-up whose other
         * four ranged 0.330 to 0.481 m; the surviving four publish 0.435 and
         * 101.6.
         *
         * WHY THE COUNT IS PUBLISHED AT ALL. A set that had a phantom and a
         * set that never did are otherwise identical in this document, and
         * they are not the same set: the first has had a figure removed and a
         * reader comparing it against an earlier export of the same session
         * has no way to learn that from anything else here. This is the gap
         * `detectionsAfterSetEndCue` still has -- computed since 1.12, stored,
         * and published nowhere -- and it is not repeated for this rule.
         *
         * ABSENCE IS A THIRD STATE AND STAYS ONE. `refusedDetections: 0` means
         * a bound was derived and refused nothing. The key ABSENT means no
         * bound could be derived, which is every set that resolved fewer than
         * four detections, and also every set recorded before this number
         * ships: the value is frozen into the stored analysis when the set is
         * analysed and nothing re-runs the segmenter at export time.
         *
         * WHAT IT DOES NOT SAY. It is not a count of the set's phantoms. The
         * bound is fitted, and it is fitted to err toward admitting a phantom
         * rather than refusing a rep -- a refused real rep would move
         * `velocityLoss_pct`, which is best rep to LAST rep, against the wrong
         * rep. A `refusedDetections: 0` therefore means "nothing was far
         * enough out to refuse", never "this set's figures are sound".
         * `RepRefusal`'s KDoc names a detection on the same session that is
         * not refused and publishes 507.0 W, and a second on the neighbouring
         * set that is kept because it resolved both its phases and publishes
         * 407.4 W from one corrupt sample.
         *
         * Additive on the terms 1.2 and 1.3 were: no key changes type, none
         * stops being written, and a reader that ignores both keys reads a
         * document carrying them exactly as it read one without. What DOES
         * move for such a reader is the VALUE of `summary`, `velocityLoss_pct`,
         * `velocityLossBasis`, `repMetrics` and `repMetricsComplete` on a set
         * carrying a non-zero count, and these two keys are how it can tell.
         *
         * 1.19 carries a TENTH change, under the same number the mint above
         * states and for the reason the entries above state -- a number takes
         * further entries until it ships, and 1.19 is unreleased (#245). A set
         * may carry [SetExport.detectionsBeforeWorkStart], how many detections
         * finished before the set's WORK began and are therefore not reps of
         * it, and `summary.noRepsReason` gains an eighth word,
         * `beforeWorkStart`.
         *
         * The analysed window had a tail bound and no head bound. `SetEnd` has
         * removed detections whose drive began after the terminal cue since
         * 1.12; nothing removed movement made during the PREP -- settling into
         * the seat, cleaning a pair of dumbbells to the shoulders, one practice
         * stroke -- so it was segmented as reps and published as reps. On field
         * session 38, 19 of 226 detections began before their own set's
         * `workStartedAt_ms`, spread over 14 of the 16 dynamic sets, and 14
         * finished before it, spread over 10 of them. On four sets one of
         * those detections is the fastest rep of the set that
         * `velocityLoss_pct` divides by: 27.4 -> 11.6, 62.1 ->
         * 40.3, 58.3 -> 48.7 and 41.2 -> 36.1. Two of those four sets are
         * committed here as test fixtures; the other two are measured in that
         * session's archive, which is not in this repository. One set's
         * `summary.peakPower_w` moves 402.5 -> 320.1, a 25.7% overstatement
         * produced during the countdown.
         *
         * The rule is `WorkStart` in `:core:dsp`: a detection whose drive ENDED
         * before the instant the set's work began is not a rep of it. The
         * drive's end and not its start, so a drive begun during the prep and
         * still under way when the work started is KEPT -- the mirror of the
         * straddling drive `SetEnd` keeps at the other end, and the direction
         * that errs toward admitting a phantom rather than refusing a real
         * first rep.
         *
         * NOT ADDITIVE, on the terms 1.4 and 1.5 were not: no key changes type
         * and none stops being written, but `summary`, `velocityLoss_pct`,
         * `velocityLossBasis`, `repMetrics`, `repMetricsComplete` and `reps` --
         * where the count is the analyzer's rather than the lifter's -- are
         * computed over a different population on any set carrying a non-zero
         * count, and `noRepsReason` is a CLOSED enum, so a reader validating
         * against a schema without the eighth word rejects a document carrying
         * it.
         *
         * WHY A SEPARATE KEY RATHER THAN A SECOND
         * [VALID_REFUSED_DETECTION_REASONS] WORD, which is where this was
         * expected to go: what the two ABSENCES mean. `refusedDetections` is
         * absent when a set resolved fewer than four detections; the new key is
         * absent when a set has no work-start instant. A heavy triple with a
         * known instant is in one state and not the other, so a single count
         * could not carry both facts, and a single reason word could not name
         * two rules at once. [VALID_REFUSED_DETECTION_REASONS] is UNCHANGED.
         *
         * It does NOT apply retroactively and cannot, for the reason 1.16 and
         * the fourth 1.18 entry state twice over: the exclusion happens when
         * the set is ANALYSED and nothing re-runs the segmenter at export time,
         * and the instant itself is a capture fact stored only from database
         * v15 on (#216). A set recorded before either carries no key and
         * publishes what it always published. `DATABASE_VERSION` does not move:
         * the instant is already stored and this reads it.
         *
         * WHAT IT DOES NOT SAY. It is not a claim that the surviving count is
         * the count the lifter performed. On the capture this was written from,
         * one set keeps ten detections against eight reps counted by hand.
         * Over-segmentation is a separate defect and is not touched here.
         *
         * 1.19 carries an ELEVENTH change, under the same number and for the
         * reason the entries above state -- a number takes further entries
         * until it ships, and 1.19 is unreleased; v0.1.50 shipped 1.18, read
         * by `git show v0.1.50:core/model/.../SessionExport.kt` rather than
         * assumed (#244). A rated set may carry [SetExport.rpeScale], one of
         * `load`, `reps`, `time` or `feel`, saying WHICH QUESTION its
         * [SetExport.rpe] answers.
         *
         * The headroom rungs -- 1, 4 and 6 -- used to be worded by the set's
         * KIND alone: load on anything dynamic, seconds on a hold. From this
         * version they are worded by the EXERCISE's declared `progression`
         * (plan 1.11), so a pull-up block declared `"reps"` is asked how many
         * more reps were left rather than how much more weight, and an
         * exercise declared `"none"` is asked for a feeling with no quantity
         * attached. A `6` therefore carries three different claims depending
         * on the exercise, and no other key in this document separates them:
         * the plan is not in the export, `progression` is not published per
         * set, and a plan can be edited or deleted after the session it drove.
         * This key is how a reader tells them apart.
         *
         * THE WORDS AND WHAT EACH MEANS AT EACH RUNG. `load`: 6 could have
         * added one equipment increment, 4 two, 1 much more. `reps`: 6 about
         * three or four reps left, 4 five or more, 1 many more -- these start
         * ABOVE the counted end, which already covers one, two and three left
         * at 9, 8 and 7. `time`: 6 about 15 s longer, 4 about 30 s longer, 1
         * much longer. `feel`: 6 comfortable, 4 easy, 1 very easy, with no
         * quantity at any rung. The counted end, 7 through 10, is UNCHANGED on
         * every scale.
         *
         * THE TIMED WORDING ALSO MOVED, and it is a change to what a stored
         * value means rather than a new key: 6 was "could have gone 15-30 s
         * longer" and 4 "about a minute longer" from 1.14 through 1.18, and
         * they are now about 15 s and about 30 s. A timed 4 recorded before
         * this version claimed roughly twice what a timed 4 recorded after it
         * claims. Nothing rewrites stored values and nothing can, so a reader
         * comparing timed headroom across the boundary is comparing two
         * different questions; `schemaVersion` is what says which side a
         * document is on.
         *
         * FROZEN AT WRITE TIME, not derived at export. The word is resolved
         * when the set is recorded and stored on the row, for the reason
         * [SetExport.bodyWeightKg] is stored and one step further: the RESOLVED
         * scale is kept rather than the raw declaration, because this is a
         * capture-time fact about which question the lifter was SHOWN, and a
         * later change to how a declaration maps onto a question must not
         * restate what a past lifter saw.
         *
         * ABSENT ON A SET RECORDED BEFORE THIS VERSION, and the reading rule
         * for those is today's behaviour rather than a guess: `load` on a
         * dynamic set, `time` on a timed one, which is exactly what the app
         * asked before the key existed. It says nothing about what those
         * exercises PROGRESSED on -- nothing recorded that -- and it is not
         * backfilled, because a past set's progression is not recoverable from
         * any column, here or on `sessions`. ALSO ABSENT on a set carrying no
         * `rpe` at all: the word qualifies the number and is published beside
         * it or not at all, which is `failedByLifter`'s rule.
         *
         * Additive to a reader that ignores it -- no key changes type, none
         * stops being written -- but NOT to a validator: the key is a CLOSED
         * enum, so a reader validating against 1.18 rejects a document
         * carrying it. `DATABASE_VERSION` DOES move, 16 -> 17, which is the
         * unshipped hop #220's body-weight column also rides.
         *
         * 1.19 carries a TWELFTH change, under the same number and for the
         * reason every entry above states -- a number takes further entries
         * until it ships, and 1.19 is unreleased; v0.1.50 shipped 1.18, read
         * at the tag rather than assumed (#250). A set may carry
         * `velocityLossRegime`, either `maxIntent` or `controlled`, saying
         * WHICH QUESTION its `velocityLoss_pct` answers.
         *
         * Best-rep-to-last-rep velocity loss has been published on every
         * dynamic set and read everywhere as fatigue. That reading assumes
         * maximal intent on every concentric. On a tempo-prescribed controlled
         * movement the concentric speed IS the prescription -- a `2011` fly
         * asks for a one-second stroke on every rep -- so the same figure
         * measures how well the lifter held the count. Field-38 is a whole
         * session of those: sixteen dynamic sets, every one tempo-prescribed,
         * and the first archive recorded with two accelerometers on every set.
         * Its sets 12 and 13 put two units on ONE rail-guided stack -- one
         * travel, rep counts agreeing exactly -- and the published figure
         * still moves 5.6 and 7.3 points with the choice of unit (41.9
         * against 36.3, and 27.0 against 19.7). One travel and one lifter
         * leaves no fatigue in that difference.
         *
         * THE RULE, from the owner. `maxIntent` when the set has NO tempo, or
         * its kind is `explosive`, or the prescribed tempo's CONCENTRIC digit
         * is `X`; `controlled` otherwise. The concentric digit is read through
         * the geometry, PLANE FIRST: on horizontal work digit 3 is the
         * concentric by phase, whatever `geometry.concentric` says, because a
         * seated row has no up or down; only on VERTICAL work does the drive
         * direction decide, digit 3 while it moves up and digit 1 when it
         * moves down, on a leg curl, a lat pulldown or a pushdown. Never
         * digit 3 blindly, and never the direction blindly. The velocity
         * TARGET plays no part.
         *
         * `velocityLoss_pct` IS STILL PUBLISHED IN BOTH REGIMES and no key
         * stops being written. What changes for a reader is what to do with it:
         * on a `controlled` set read `tempoCompliance`, `summary.romSpread_pct`
         * and the rating as the autoregulation figures, and read
         * `velocityLoss_pct` as compliance rather than fatigue.
         *
         * DERIVED, NOT STORED. All four inputs are already frozen on the
         * set's row -- its prescription, and the plane, drive direction and
         * kind of its geometry -- so the word is computed at export time and
         * `DATABASE_VERSION` does not move. A set re-exported by this build
         * gains the word however long ago it was recorded, provided its
         * geometry was stored.
         *
         * ABSENT where the regime is not decidable, and absence is a state
         * rather than a low-confidence word: a set with no stored geometry, a
         * hold or a carry, and a tempo string this build cannot parse. The
         * reading rule for all three is today's behaviour rather than a guess
         * -- read velocity loss as this document has always said to, which is
         * `maxIntent`'s reading.
         *
         * A VERTICAL CONCENTRIC-DOWN LIFT CANNOT BE `maxIntent` WHILE CARRYING
         * A TEMPO, and the limit is in the plan contract rather than here: the
         * plan schema accepts `X` only in digit 3, so digit 1 is always a
         * number and the drive of a leg curl always has a prescribed speed.
         * Widening that pattern is #258. This paragraph said "a concentric-down
         * lift", without the plane, and that was wrong for horizontal work,
         * whose concentric IS digit 3 -- round 1 finding 2 on the branch that
         * added this key. A chest press or a chest-supported row prescribed
         * `30X0` is `maxIntent` under today's contract however its
         * `geometry.concentric` was declared.
         *
         * Additive to a reader that ignores it -- no key changes type, none
         * stops being written -- but NOT to a validator, because the key is a
         * CLOSED enum, so a reader validating against 1.18 rejects a document
         * carrying it.
         *
         * 1.20: MINTED HERE, because 1.19 HAS SHIPPED and a shipped number
         * takes no further entries. `git tag --sort=-creatordate | head -1`
         * is v0.1.52, and `git show
         * v0.1.52:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.19"` -- read at the tag rather than
         * assumed, which is the rule the ninth 1.19 entry states for v0.1.50.
         * This entry named v0.1.51 as what that command returns. That was
         * true when it was written and is not now: v0.1.52 has since been
         * cut, and the sentence is corrected rather than left standing. Both
         * releases ship 1.19 -- neither bumped the number -- so 1.20 is still
         * this branch's own mint and did not have to move a second time.
         * The change below was written as a FURTHER ENTRY under 1.19 while
         * 1.19 was still unreleased; the release closed that number
         * underneath it, so it is renumbered rather than left claiming a
         * version it cannot be part of. This is the FIRST 1.20 entry.
         *
         * IT WAS DRAFTED AS THE ELEVENTH, not the twelfth. A sentence here
         * said "written as a TWELFTH 1.19 entry" and it is DELETED: the
         * newest pre-rebase form of the commit subject "Write the export
         * entry the ecc-first fallback has owed since round 1" files this
         * change as `1.19 carries an ELEVENTH change`, and the TWELFTH in
         * the log above is #250's `velocityLossRegime`, which came from
         * `main` and not from this branch. Two older forms of the same
         * commit filed it as the TENTH and the FIFTH; the ordinal moved
         * with every rebase, which is why the entry now carries a number
         * of its own instead.
         *
         * THE ELEVEN ENTRIES ABOVE THAT STAND UNDER AN OPEN 1.19 ARE LEFT
         * STANDING, on the precedent the NINTH 1.19 entry sets for 1.18.
         * Each was true when it landed, and all eleven -- SECOND through
         * TWELFTH -- plus the mint above them shipped together in v0.1.51:
         * `git show v0.1.51:core/model/.../SessionExport.kt` carries every
         * one of the twelve, read at the tag rather than assumed. What
         * expired is the premise, not the entries. Five of the eleven say
         * in so many words that 1.19 is unreleased -- the FIFTH, SIXTH,
         * TENTH, ELEVENTH and TWELFTH -- and the other six file under the
         * mint without using the word. The published JSON twin of this log
         * says SIX, because its eighth entry carries the word where this
         * one's does not; each copy states the count for itself. Nothing
         * filed under 1.19 moves to 1.20: 1.19 is still accepted and still
         * published.
         *
         * WHAT AN OLDER READER DOES, and it is not symmetric. `schemaVersion`
         * is a CLOSED enum and the published schema sets
         * `"additionalProperties": false` at its root, so the 1.19 schema
         * v0.1.51 shipped REJECTS a 1.20 document on the version string alone,
         * before looking at a single key. The 1.20 schema still lists every
         * version from 1.0 through 1.19, so it ACCEPTS a 1.19 document
         * unchanged. And because this entry adds, removes and retypes NO key,
         * a 1.19 READER that does not validate reads a 1.20 document
         * correctly -- the rejection is the validator's, not the reader's.
         *
         * THE CHANGE (#72): on an ECCENTRIC-FIRST set a rep may now omit
         * `ecc_s`. The key was already nullable and already omitted on
         * concentric-first sets, where a drive with no detectable return has
         * always been published on the drive alone; what changes is the SET OF
         * SETS that can emit such a rep. Before this, an eccentric-first set
         * required both phases, so a rep whose lowering never became a phase
         * was DELETED rather than published without one -- and which of the
         * two a lifter got was decided by nothing but the phase the plan
         * declared the set opens with. A reader that treats a missing `ecc_s`
         * as "this rep had no eccentric" was already wrong on concentric-first
         * sets and is now wrong on more sets; the right reading is "the
         * eccentric was not measured", never zero. Not retroactive: the
         * segmentation is frozen into the stored analysis at record time and
         * nothing re-runs the segmenter at export time, so an old session
         * re-exports unchanged. `DATABASE_VERSION` does not move.
         * `RepSegmenter.pairEccentricFirst` in `:core:dsp` is where the rule
         * lives.
         *
         * NOT PURELY ADDITIVE. No key changes type or stops being written, but
         * an eccentric-first set may now publish MORE reps, so every figure
         * derived from `reps` moves with the array -- including
         * `repMetricsComplete`, which `Exporters.kt` computes as
         * `reps.size == record.actualReps` and which can therefore now read
         * FALSE on a set the lifter counted correctly. This entry said the
         * change "adds no key to this document" and stopped there; that was
         * true and incomplete, and the omission is corrected here.
         *
         * 1.20 carries a SECOND change, filed under the same number because
         * 1.20 is unreleased: `git tag --sort=-creatordate | head -1` is
         * v0.1.52 and `git show
         * v0.1.52:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.19"`, both read this round rather than
         * relayed, so the number above is still this line of work's own mint
         * and takes further entries.
         *
         * THE CHANGE (#247): `summary.noRepsReason` gains a NINTH word,
         * `mountNotDeclared`, and a set that would previously have published
         * figures now publishes none. It fires on one shape only -- the
         * analysis moved off the unit the set armed, and the exercise's
         * geometry declares a MOUNT (`sensorOnStack`, `sensorInverted`, or a
         * `travelRatio` other than 1.0) rather than only the lift. The
         * declared geometry belongs to the ARMED unit, so running the DSP over
         * a different unit under it swapped the concentric and the eccentric
         * outright: the drive became the return, tempo grading inverted, and
         * `velocityLoss_pct` was computed over the wrong stroke, at a rep
         * count that need not move with it -- identical on the synthetic
         * negation pair, 13 under the declared stack geometry against 18 under
         * the lifter-side one on field-38 set 14's partner -- with nothing in
         * the document saying so.
         *
         * THE ALTERNATIVE WAS TO INFER THE OTHER UNIT'S MOUNT AND IT IS
         * REFUSED. Field-38 recorded a triceps pushdown and a lat pulldown
         * under the SAME declaration -- `sensorOnStack` true, `sensorInverted`
         * true -- with, on the owner's word, the two units co-mounted on the
         * stack for one and split between stack and bar for the other. One
         * declaration, two mounts, so inferring the partner's geometry from
         * the declaration is a coin flip whose losing half publishes an
         * inverted record silently. Refusing keeps the capture: every stream
         * is archived either way and can be re-derived under any geometry.
         *
         * NOT PURELY ADDITIVE, and in a direction a reader must be told about.
         * `noRepsReason` is a CLOSED enum, so a validator running the 1.19
         * schema rejects the new word; and on the shape above a set now
         * publishes `reps: 0` -- the count key is an integer and `repMetrics`
         * is not written at all -- and an empty `summary`, unless the lifter
         * states or corrects the count, where it used to publish a full one.
         * `sensors.analysedFellBack` was already true on exactly those sets
         * and remains the statement that the analysis moved.
         *
         * NOT RETROACTIVE, for the reason every entry here gives: the value is
         * computed when the set is analysed and frozen into the stored
         * analysis, and nothing re-runs the segmenter at export time. No set
         * already on disk gains the word or loses its figures.
         * `DATABASE_VERSION` does not move -- no column changes.
         *
         * ALSO UNDER 1.20, a THIRD entry rather than a mint, because v0.1.52
         * ships 1.19 -- read by
         * `git show v0.1.52:core/model/.../SessionExport.kt` rather than
         * assumed -- and nothing has shipped 1.20 yet: a set carries
         * [SetExport.repsSource], the word saying WHOSE COUNT its `reps` figure
         * is, and [SetExport.liveReps], what the sensor's live detector counted
         * while the set was performed (#286).
         *
         * `reps` was one integer carrying four different claims. The sensor
         * counting live, the lifter's own taps, the cadence guide's schedule
         * and the batch segmenter's figure taken after the set all land in it,
         * and only one bit separated them: `repsManual`, true both for a tally
         * the lifter kept and for a correction of a count something else made.
         * On the straight-reps barbell work #284 traces, whose count the number
         * is is the FIRST question to ask of it.
         *
         * DERIVED, NOT STORED. `RepsSourcePolicy` takes the row's live
         * count, its `repsManual` flag, whether it is measured in seconds and
         * whether a CADENCE RAN -- derived from the frozen tempo and the
         * frozen geometry's kind, because an explosive lift carrying a tempo
         * is paced by nothing -- and returns one of `sensor`, `manual`,
         * `metronome`, `corrected` or `analysis`. Three collapses are stated
         * rather than hidden: a corrected manual set reads `manual`, a
         * corrected guided set reads `metronome`, and a row with no stored
         * geometry reads its tempo as the guide because nothing on it says
         * what kind of exercise it was.
         *
         * ABSENT ON A TIMED SET, and absence means one thing only -- nothing
         * counted reps. A hold publishes whatever the segmenter made of one
         * long movement, and no counter stands behind it.
         *
         * NOT PURELY ADDITIVE, in one respect a reader must be told about.
         * [SetExport.repMetricsComplete] changes what it is measured AGAINST.
         * Its description said that when `repsManual` is false the recorded
         * count IS the segmenter's count, so the two agree by construction --
         * true while every such row carried the segmenter's own figure, and
         * false from here: on a sensor-counted set `repsManual` is false and
         * the recorded count is the LIVE one, so a false value is the live and
         * batch detectors disagreeing. That sentence is DELETED rather than
         * reworded, here and in the published schema. No key changes type and
         * none stops being written.
         *
         * NOT RETROACTIVE either. `liveReps` is a column written when the set
         * is recorded (`DATABASE_VERSION` 18) and nothing backfills it, so
         * every row already on disk publishes `manual`, `metronome`,
         * `analysis` or no word at all -- which is what those rows were.
         *
         * ALSO UNDER 1.20, a FOURTH entry rather than a mint, and on a reading
         * taken this round rather than a relayed one: `git tag
         * --sort=-creatordate | head -1` is v0.1.52 and `git show
         * v0.1.52:core/model/.../SessionExport.kt` reads
         * `SCHEMA_VERSION = "1.19"`, so nothing has shipped 1.20 and the
         * number still takes further entries. IT ADDS NO KEY TO THIS DOCUMENT
         * (#263): what the RAW ARCHIVE's `meta.json` publishes gains
         * `concentricSource` beside the `concentric` it already carried,
         * naming which of `declared`, `seeded` or `default` supplied that
         * direction -- never `inferred`, because guessing the drive from an
         * id is deliberately refused. `SetGeometryPolicy.describe` resolves
         * this one value with `inferable = false`, so the fourth word cannot
         * be produced for it, and [GeometrySource.DEFAULT]'s own KDoc gives
         * the reason. It is otherwise the same word this document has
         * published at `geometry.source.concentric` since the geometry block
         * landed.
         *
         * The manifest is the only file a reader who opens the archive's CSVs
         * has, and those samples are device-frame and carry no phase labels,
         * so the drive direction stated there decides which stroke every
         * figure derived from them belongs to. Field-39 (#263) ran four
         * `lat_pulldown` sets from a plan that declared the mount and not the
         * drive; all four published `"concentric": "up"`, every one of them
         * the app's default standing because nothing said, and the manifest
         * gave a reader no way to tell that from four the plan meant.
         *
         * ADDS NO KEY HERE, so it changes nothing about what an older reader or
         * validator does with this document: the 1.19 schema still REJECTS a 1.20
         * document on the version string alone, exactly as the first 1.20 entry above
         * states, and a 1.19 reader that does not validate still reads it correctly.
         * The asymmetry is unchanged, not resolved. The closed `additionalProperties:
         * false` on `$defs.geometrySource` is untouched, and it is what would have
         * made an EIGHTH key there non-additive -- an older reader validating against
         * 1.19 would refuse the whole document rather than ignore one key. That is
         * the reason this provenance is published in the manifest, where it was
         * actually missing, and not added to a closed object where it already exists.
         * `meta.json` has no published schema at all, so a reader of the
         * archive either reads the new key or ignores it; nothing validates it
         * and nothing can refuse it.
         *
         * NOT RETROACTIVE, for the reason every entry here gives, with one
         * narrower limit worth stating: the provenance is frozen into the
         * set's row when the set is RECORDED, so a row re-exports with
         * whatever its stored `sources` object holds, and a row with no stored
         * geometry publishes neither the direction nor its source, as it
         * always has. `DATABASE_VERSION` does not move; no column changes.
         *
         * ALSO UNDER 1.20, a FIFTH entry rather than a mint, for the reason
         * the entry above states -- `git show
         * v0.1.52:core/model/.../SessionExport.kt` declares `"1.19"`, read at
         * the tag this round, and nothing has shipped 1.20: a set's figures
         * are bounded by its `Done` cue ONLY where a CADENCE ran (#285).
         *
         * 1.12 said the figures cover only the detections whose drive began
         * at or before the set's own `Done`, and listed the sets nothing
         * bounds. That list gains a fourth member, and on straight-reps
         * barbell work it is the common one: a set the LIFTER counted by
         * tapping. `VoiceMilestonePolicy` speaks `Done` at the planned count
         * as a rep-count MILESTONE -- the app saying the number has been
         * reached, not that the set is over -- and every spoken word is
         * written to `voiceCues` as the same string a metronome's terminal
         * call writes. So every drive begun after that tap was dropped from
         * `repMetrics`, `velocityLoss_pct`, `velocityLossBasis`,
         * `repMetricsComplete` and every field of `summary`. A lifter who
         * does more reps than were prescribed did those reps, and they are
         * analysed from here.
         *
         * `Set ended` still bounds every set, cadence or none: the app writes
         * that word itself as the set ends and it is never a milestone.
         *
         * HOW A READER TELLS WHICH RULE APPLIED: [SetPrescriptionExport.tempoPrescribed].
         * A `Done` row in `voiceCues` on a set carrying no `tempoPrescribed`
         * bounded nothing. One caveat, because the two are not one field: the
         * rule reads whether a CADENCE RAN -- `LeadInPolicy.prepCase == CUED`,
         * a parsed tempo on an untimed non-explosive lift -- while
         * `tempoPrescribed` publishes the string as prescribed, so an
         * explosive lift carrying a tempo, or an ad-hoc tempo string the app
         * could not parse, publishes a tempo and ran no cadence.
         *
         * NOT PURELY ADDITIVE, for the reason 1.12 was not: no key changes
         * type or stops being written, but on a manually counted set that
         * reached its planned count with the voice on, those figures are
         * computed over a larger population of reps. Measured on the
         * committed capture `field-ohp-3010-8rep-s38-set05` under a synthetic
         * manual cue track, because no manual-set capture is committed here:
         * 8 detections and 62.2% velocity loss bounded, 15 and 79.2%
         * unbounded. NEITHER is claimed to be the truth -- the lifter
         * hand-counted 8, and #284 measured this concentric-first corpus
         * over-counting six captures of six. What the change removes is a
         * rep list decided silently by a display toggle.
         *
         * NOT RETROACTIVE in `session.json`: the exporter re-derives these
         * figures from the STORED rep list and nothing re-runs segmentation
         * at export time, so every set already on disk publishes what it
         * published before. RETROACTIVE in the raw archive's `meta.json`,
         * which is the half a reader would otherwise be caught by:
         * `rollExcursion_deg` IS recomputed from the stored streams at export
         * time, so on a manually counted set whose track says `Done` its
         * window now runs past the tap. `rollExcursionBasis` says so on the
         * row -- `toTerminalCue` becomes `wholeCapture`, `workingWindow`
         * becomes `fromWorkStart` -- so the change is readable rather than
         * silent. `DATABASE_VERSION` does not move: no column changes.
         *
         * 1.20 carries a SIXTH change, filed under the same number because
         * 1.20 is unreleased: `git tag --sort=-creatordate | head -1` is
         * v0.1.52 and `git show
         * v0.1.52:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.19"`, both read this round rather than
         * relayed from the entries above.
         *
         * THE CHANGE (#293): the guided metronome speaks its rep call at the
         * START of the rep it names, IN PLACE OF THAT REP'S FIRST STROKE WORD.
         * The owner asked for it in those terms, after a session on a 3010
         * overhead press whose lowering was hard to follow: "Have the rep
         * number be at the start of the rep, and replace the relevant up or
         * down, etc." Three things change in `voiceCues` and no key moves.
         *
         * WHERE A CALL IS HEARD, on every schedule with a free second at the
         * START of the rep: every `Rep N` and `Last rep` row from such a set
         * lands on the first second of the rep it names. From 1.13 through 1.19
         * that was not so and the published description says so: on the two
         * schedules that merged the call into a stroke it was spoken during the
         * rep it named, and on the schedule whose prescription ends in a pause
         * it was spoken in the previous rep's closing pause, before that rep had
         * begun. That rule is the reading rule for every set recorded under
         * those versions and is kept as theirs, on the precedent the 1.13
         * entries set.
         *
         * THIS ENTRY SAID THE 1.20 PLACEMENT WAS UNIFORM. The SEVENTH entry below
         * gives 1.20 a second placement, so that sentence is DELETED rather than
         * scoped: it was written under 1.20 about 1.20, and a claim about the
         * version being described cannot be filed as history of a shipped one the
         * way the 1.13-through-1.19 rule can.
         *
         * THE FIRST STROKE'S WORD IS WRITTEN ONCE PER SET, NOT ONCE PER REP,
         * and this is the half a reader must act on. The call replaces it, and
         * a cue row is what the app SAID, so the word is not written on the reps
         * that carry a call. Rep 1 carries none -- it keeps its word, its place
         * and its own tempo counts -- so a newly recorded eccentric-first press
         * publishes ONE `Down` row for a set of six where every earlier archive
         * publishes six. WHICH word thins out is the (tempo, lift) pair's: the
         * `Down` on an eccentric-first press, the `Up` on a concentric-first
         * overhead press, from the same `3010`. A consumer counting stroke rows
         * to count reps must count the call rows instead -- one per rep after
         * the first, on the rep's own first second -- plus rep 1's stroke word.
         * The OTHER stroke's word is still written on every rep, so a guided
         * track still carries a stroke word in every rep and both of them in
         * rep 1, which is what the published discriminator between this
         * counter and the unguided one now rests on.
         *
         * THE GUIDE NO LONGER WRITES TWO ROWS AT ONE INSTANT BY DESIGN. 1.13's
         * fifth change published a merged call as two rows at one `t_ms`, the
         * stroke word and the call; a replacing call is one row and the
         * replaced word is not written at all. Each utterance still reads the
         * clock for itself, so two rows from different utterances may
         * coincide.
         *
         * THE TEMPO COUNTS OF THAT STROKE ARE RENUMBERED, not dropped. The
         * number stands where the word stood, so it is that stroke's first
         * count and the rest continue from it: a three-second opener publishes
         * `2` then `3` where it published a single `2`, and rep 1 publishes `1`
         * then `2`. A bare digit still means a tempo count and never a rep
         * number, which does not move. The count a merged call used to give up
         * is spoken again, so the missing-`1`-per-rep fingerprint that dated the
         * unwritten calls of a 0.1.43 archive does not appear in a 1.20 one.
         *
         * WHAT STAYS TRUE, quoted so it is not re-litigated: the 1.19 EIGHTH
         * entry's subject is WHICH rep a call names -- "what changes is WHICH
         * rep a `Rep N` row names, so a consumer aligning cue rows to reps is
         * off by one across the boundary" -- and #293 does not touch it. A call
         * still names the rep now due: a set of twelve says `Rep 2` through
         * `Rep 11`, then `Last rep`, then `Done`.
         *
         * NOT ADDITIVE, and not retroactive. No key is added, removed or
         * retyped; the CONTENTS of an existing array change, which is the shape
         * 1.4, 1.8, 1.9, 1.12, 1.13 and the 1.19 EIGHTH each carry. Cue rows
         * are stored as they are spoken, so no archive already on disk moves,
         * and a reader of one should apply the version's own rule.
         * `DATABASE_VERSION` does not move and the plan schema is untouched --
         * nothing about the prescription changes, only what is said over it.
         *
         * WHAT IT IS PINNED AGAINST. `CadenceVoice.script` in `:core:dsp`
         * writes these rows, and the new script is asserted row for row against
         * cue tracks from three sessions: field-41 set 1 and field-42 sets 5 and
         * 13 (app 0.1.52), field-39 sets 3, 5 and 7 (0.1.50) and session 33 sets
         * 1, 5 and 13 (0.1.43). No beat moves on any of them, and no set
         * changes length.
         *
         * 1.20 carries a SEVENTH change, filed under the same number because
         * 1.20 is unreleased: `git tag --sort=-creatordate | head -1` is
         * v0.1.52 and `git show
         * v0.1.52:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.19"`, both read this round rather than
         * relayed from the entries above.
         *
         * THE CHANGE (#266): a schedule with NO free second at the start of the
         * rep -- two one-second strokes and no closing pause -- now names the rep
         * AT THE END OF THE DRIVE, where it named no rep at all. The owner asked
         * for it after field-39, whose two `1010` sets counted nothing aloud:
         * *"When you can't have a rep call or an up or down, have the end of the
         * concentric phase be the rep number."* And on why that instant: *"People
         * are used to the reps being counted at lockout, so this would be an easy
         * cue."* So a `1010` press publishes `Up`, `Rep 1`, `Up`, `Rep 2`, and so on, where
         * field-39's archive publishes `Up`, `Down`, `Up`, `Down` and so on.
         *
         * WHICH SETS, and it is the (tempo, lift) pair's answer as always. Only
         * the dense prescriptions, and only on a lift whose DRIVE opens the rep:
         * the number lands on the beat that opens as the concentric ends, which
         * on a `1010` is the return's beat and on a `1110` with a mid-rep pause is
         * the `Hold`. The same prescription on a lift whose drive CLOSES the rep
         * publishes no call at all, exactly as before, because the instant its
         * drive ends is the next rep's first second.
         *
         * THE CALL IS PUBLISHED ON EVERY REP INCLUDING REP 1, which no other
         * schedule does. A set of six publishes `Rep 1` through `Rep 5`, then
         * `Last rep`, then `Done`. The reason rep 1 is silent elsewhere is that
         * the call would take the word the set OPENS on, which is under the start
         * cue's own contract; here it takes a later beat's word, so the opening
         * word survives. A set of ONE publishes `Last rep` and no number, because
         * the rep in hand is the planned last from its first rep.
         *
         * THE REPLACED WORD IS PUBLISHED NOWHERE IN SUCH A SET, and this is the
         * half a reader must act on. The SIXTH entry's rule -- the first stroke's
         * word once per set -- had rep 1 keeping it; here there is no rep that
         * keeps it, so a `1010` concentric-first set of six publishes six `Up`
         * rows and NO `Down` row, where an archive before 1.20 publishes six of
         * each. A consumer counting stroke rows to count reps gets zero and must
         * count the call rows instead, one per rep. The OTHER stroke's word is
         * still published on every rep, so a guided track still carries a stroke
         * word in every rep -- which is what the discriminator between this
         * counter and the unguided one rests on, since the unguided one publishes
         * none. "Both words in rep 1", which the SIXTH entry offered, does not
         * hold on these sets.
         *
         * NO TEMPO COUNT MOVES. These prescriptions are two one-second strokes,
         * and a one-second stroke has no interior second to count; a mid-rep
         * `Hold` is not a stroke and is not counted either. So the renumbering
         * the SIXTH entry describes cannot arise here, and a bare digit still
         * means a tempo count.
         *
         * NOT ADDITIVE, and not retroactive. No key is added, removed or
         * retyped; the CONTENTS of an existing array change, which is the shape
         * the SIXTH entry carries too. Cue rows are stored as they are spoken, so
         * no archive already on disk moves -- field-39's own two tracks are
         * committed as fixtures and are the before side of this entry.
         * `DATABASE_VERSION` does not move and the plan schema is untouched:
         * nothing about the prescription changes, only what is said over it.
         *
         * WHAT IT IS PINNED AGAINST. `LockoutRepCallTest` in `:core:dsp` asserts
         * field-39 sets 4 and 9 as recorded, then the rows the guide writes
         * instead, at the same thirteen seconds with the same thirteen rows; the
         * `1110` geometry is synthetic and pinned beside it. No beat moves and no
         * set changes length, which `CadencePlanTest` holds across every tempo
         * any plan can express.
         *
         * 1.21 MINTS A KEY, and 1.20 is why it is a mint rather than an eighth
         * entry under 1.20: `git tag --sort=-creatordate | head -1` is v0.1.53
         * and `git show
         * v0.1.53:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.20"`, both read this round rather than
         * relayed from the entries above. 1.20 has SHIPPED, so extending it
         * would change what a version already in the field means.
         *
         * THE CHANGE (#300): a new session-level [skippedSets], an array of
         * `{exercise, setNumber}` naming the PRESCRIBED sets the lifter
         * deliberately did not do. The owner asked for the control it records:
         * *"I'd also like a mechanism to remove an upcoming set."* Before it,
         * the only way to be rid of an unwanted prescribed set was to finish
         * the session, which drops every remaining exercise rather than one
         * set.
         *
         * WHY A KEY WAS NEEDED AT ALL, since the preference is always to derive.
         * Nothing in this document publishes how many sets the plan prescribed:
         * the root carries `planRef`, a NAME, and `$defs.exercise` carries
         * exactly `exercise` and `sets`. [SetExport.rpeScale]'s entry already
         * says why that cannot be worked around -- *"the plan is not in the
         * export"* and a plan *"can be edited or deleted after the session it
         * drove"* -- so a reader cannot recover the prescribed count later, and
         * deriving it at export time from a plan row would attribute today's
         * plan to a session recorded months ago. With one set droppable
         * mid-session, an exercise carrying three sets where four were
         * prescribed became indistinguishable from a plan that asked for three
         * and from a set the app lost. The key is what separates a decision
         * from a loss.
         *
         * ADHERENCE IS RECORDED-AND-NOT-VOIDED OVER PRESCRIBED, which is the
         * reading rule this key exists to keep readable. A skipped set has NO
         * entry in `exercises`: no row was written, nothing was measured, and
         * no set of zero reps appears. A set that was performed and afterwards
         * marked not-done is [SetExport.voided] instead and stays published with
         * its row. An entry here is evidence of a decision; the ABSENCE of
         * entries is not evidence that everything prescribed was done, because
         * a session that simply ends still drops its remainder silently.
         *
         * ADDITIVE, and not retroactive. One key is added and nothing is
         * removed or retyped, so a 1.20 reader is unaffected except that it
         * cannot see skips. No archive already on disk moves: the column
         * carrying this is new, so every session recorded before it publishes
         * nothing here, and absence on those is "the app could not record a
         * skip" rather than "none was made". `DATABASE_VERSION` DOES move, to
         * 19, because the list is stored on the session row -- `MIGRATION_18_19`
         * appends `skippedSetsJson` and backfills nothing. The plan schema is
         * untouched: what the plan prescribes has not changed, only what the
         * session says it did with it.
         *
         * PINNED IN BOTH DIRECTIONS. `SchemaSkippedSetContractTest` asserts the
         * published `$defs.skippedSet` properties and
         * `serialKeysOf(SkippedSet.serializer())` are the same set, so neither
         * the Kotlin type nor the document can gain a key alone; the block is
         * `additionalProperties: false` with both keys required, and the
         * published example carries an entry so the ajv step in `ci.yml`
         * actually validates it.
         *
         * SECOND 1.21 ENTRY (#259, and #249's ask rides it): a timed set may
         * carry [SetExport.durationEndedBy], one of `clock`, `sensor`,
         * `lifter` or `corrected`, saying which of them decided the
         * `duration_s` beside it.
         *
         * A FURTHER ENTRY UNDER 1.21 RATHER THAN A MINT, and the test is the
         * same one the first entry applied: `git tag --sort=-creatordate | head
         * -1` is v0.1.53 and `git show v0.1.53:core/model/.../SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.20"`, both read this round. 1.21 has NOT
         * shipped, so it is still open and a key added to it changes nothing
         * any reader has seen.
         *
         * WHY A KEY, since the preference is to derive. At export time the row
         * offers `duration_s`, `plannedDuration_s`, its end instant and -- from
         * the prep stream -- `workStartedAt_ms`, so the measured span IS
         * recoverable and can be compared against what was recorded. What that
         * comparison cannot do is separate a figure the SENSOR shortened from
         * one the LIFTER corrected: both are a stored value that is neither the
         * span nor the target, and telling those two apart is the whole of
         * #249. A correction that happens to land on the target would read as
         * the clock as well. So the word is stored, in `set_records` on the v19
         * hop, which shipped in v0.1.54, and published from there.
         *
         * WHAT THE `sensor` WORD CHANGES ABOUT `duration_s` ITSELF, stated
         * because a reader comparing holds across versions needs it: on a hold
         * the lifter ended by hand with an armed unit that saw a release, the
         * figure now runs to the release rather than to the tap, and is
         * therefore SHORTER than the same set would have published under 1.20
         * by the length of the walk back to the phone -- 7 s and 6 s on the two
         * field-38 dead hangs this was measured against. The published meaning
         * of `duration_s` is unchanged: it is, as it was, the seconds the hold
         * lasted. What changed is that the app can now tell where it ended. A
         * set with no armed unit publishes exactly what it published before.
         *
         * ADDITIVE. One optional key is added, nothing is removed or retyped,
         * and a 1.20 reader is unaffected except that it cannot see the word.
         * No archive on disk moves: the column is new, so every session already
         * recorded publishes nothing here.
         *
         * PINNED. `SchemaHoldEndContractTest` asserts the published enum in
         * `docs/schemas/session-export.schema.json` and `HoldEndSource`'s own
         * four words are the same set, so neither can gain a word alone, and
         * the published example carries a hold with the key so the ajv step in
         * `ci.yml` validates it.
         *
         * THIRD 1.21 ENTRY, filed under the same number because 1.21 is
         * UNRELEASED: `git tag --sort=-creatordate | head -1` is v0.1.53 and
         * `git show
         * v0.1.53:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.20"`, both read this round rather than
         * relayed from the two entries above. So 1.21 is the open number and
         * #300's mint above stands; this entry adds to it and mints nothing. It
         * is the THIRD rather than the second because #259's entry landed on
         * main first and this work was re-parented onto it.
         *
         * THE CHANGE (#290, #255): a set may say how many of its samples the
         * sensor cannot have measured, each rep may say how many of them landed
         * inside itself, and the set's published PEAK pair is taken over the
         * reps that carry none. Two additive integer keys --
         * [SetExport.artefactSamples] and `artefactSamples` on each
         * `repMetrics` row -- plus a narrowing of the population two existing
         * keys are computed over, `summary.peakConVel_mps` and
         * `summary.peakPower_w`.
         *
         * THE RULE. A sample whose TOTAL support acceleration exceeds 4 g is not
         * lift kinematics. An implement at rest reads 1 g and net drive on a
         * tempo-prescribed set stays well under 1 g, so the exercise's own
         * ceiling is near 2 g; 4 g admits three g of net drive, which nothing a
         * lifter does to a loaded bar reaches. A rep whose own span -- both
         * phases and the turnaround between them -- contains one is left out of
         * the set's peak pair, because a peak is a MAXIMUM and one impossible
         * reading is sufficient to be the answer.
         *
         * THE FIGURES IT MOVES, measured on the captures committed with it.
         * field-42 set 2 is a 24.9476 kg seated overhead press that publishes
         * `peakPower_w` 3606.3 and `peakConVel_mps` 2.516 -- 4.9 g of net drive
         * on a tempo-`3010` press -- and publishes 329.4 and 1.155 under this
         * rule, from the same NINE detections. field-43 set 5 is an 83.9 kg
         * deadlift that publishes 4347.4 W, from samples where the
         * accelerometer RAILS at its own 16 g full scale, and publishes 722.3 W.
         * field-42 sets 5 and 7 and field-37 set 3 move too; six published
         * figures in the committed corpus do NOT, five of them still
         * implausible and one -- field-42 set 13 at 244.3 W -- needing no
         * movement, and `ArtefactPeakWithholdingTest` names each of them.
         *
         * WHY A COUNT AND NOT A REPAIR, which is the design decision a reader
         * should know was taken deliberately. Substituting an out-of-range
         * sample before the velocity integration -- by holding the last
         * in-range reading or by interpolating the neighbours -- MOVES THE REP
         * COUNT on nine of the eleven committed captures that carry one, and
         * does not fix the railed case at all: field-43 set 5 still publishes
         * 974.5 W or 1336.6 W, and field-43 set 6 gets WORSE, 2386.5 W to
         * 2869.1 W. So nothing is repaired; the raw stream is published
         * unchanged as always, and every figure but the two summary peaks is
         * bit-identical to what the same capture published at 1.20.
         *
         * ABSENT, 0 AND POSITIVE ARE THREE FACTS on both keys, the doctrine
         * `refusedDetections` states. Absent is permanent on every set already
         * on disk: the counts are frozen into the stored analysis when a set is
         * analysed and nothing re-runs the estimator at export time. A rep
         * carrying no count is KEPT in the peak population, so no archived set
         * loses the peak it has always published.
         *
         * `DATABASE_VERSION` does not move for THIS entry -- the counts ride
         * inside `analysisJson`, which is already a stored blob -- and the plan
         * schema is untouched. (#300's entry above does move it, to 19, for its
         * own reason.) `VALID_REFUSED_DETECTION_REASONS` is UNCHANGED and gains
         * no word: no detection is refused by this rule, which is the correction
         * written at that constant.
         *
         * PINNED IN BOTH DIRECTIONS. `SchemaArtefactSampleContractTest` asserts
         * both published keys, their floors, that neither is required and that
         * both objects are still closed; `ArtefactPeakWithholdingTest` and
         * `ArtefactRuleAlternativesTest` in `:core:dsp` carry the corpus figures
         * above and the substitution measurement that argued them.
         *
         * FOURTH 1.21 ENTRY, filed under the same number because 1.21
         * is UNRELEASED: `git tag --sort=-creatordate | head -1` is v0.1.53 and
         * `git show
         * v0.1.53:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.20"`, both read this round rather than
         * relayed from the entries above. So 1.21 is still the open number and
         * #300's mint stands; this entry adds to it and mints nothing.
         *
         * THE CHANGE (#291): each `repMetrics` row may say whether the analysis
         * can BOUND its displacement, and the two set-level RANGE claims --
         * `summary.meanRom_m` and `summary.romSpread_pct` -- are taken over the
         * reps it holds for rather than over every rep. The mean is ABSENT with
         * no bounded rep and the spread below two of them. One additive boolean
         * key, [RepMetricsExport.romBounded], plus a narrowing of the population
         * two existing keys are computed over.
         *
         * THE FIGURES IT MOVES. field-42 set 7 is a 56.7 kg bench press
         * performed to a 3010 count that publishes `romSpread_pct` 98.1 from a
         * rep reading `rom_m` 1.592 m on a lift travelling about 0.45 m;
         * field-42 set 9 is a seated cable row publishing 1.880 m on about
         * 0.5 m; field-42 set 13 an assisted pull-up publishing 1.938 m on about
         * 0.6 m. Under this rule all three publish NO range figures, and neither
         * does any other capture in the committed corpus: not one rep of the
         * eleven is bounded, so every one of them withholds both the mean and
         * the spread. `RomBoundCorpusTest` carries that column and
         * `RomDriftBaselineTest` the before side. The rep COUNTS, every
         * velocity, every power figure and every per-rep `rom_m` are
         * bit-identical to 1.20.
         *
         * THE BOUND, derived and not fitted. The ZUPT pass is the only stage
         * that pins the velocity integral to zero, and
         * `VelocityEstimator.anchorAcceptable` caps the displacement the drift
         * correction may erase between two consecutive anchors IT ACCEPTED at
         * `DspConfig.minRomM`, 0.10 m. It does not cap every anchor: after a
         * stretch with nothing acceptable, `applyZupt` re-anchors on starvation
         * alone and the ramp back to that anchor erases whatever it erases. So a
         * rep's travel is bounded only when the interval it sits in was spent on
         * it alone AND every interval its span crosses was closed by an anchor
         * the caps approved. `RomBound` states the rule once, and
         * `AnchorRouteTest` measures what the uncapped intervals of this corpus
         * erased: 1.0180 m, 5.0198 m and 2.3153 m on the three spans a
         * route-blind form of the rule admitted.
         *
         * WHY NO REPAIR, which is the decision a reader should know was taken
         * deliberately. A rep begins and ends at rest, so a per-rep detrend
         * looks like the fix. Two forms of it were measured and they DISAGREE by
         * more than the defect: on the same bench rep that publishes 1.592 m a
         * two-point detrend reads 1.544 m and a net-zero symmetrisation 1.780 m,
         * and on the cable row's 1.880 m rep the same two read 0.666 m and
         * 1.880 m. Both phases of the inflated reps are inflated together and no
         * accepted anchor lies inside any rep span on either capture, so there
         * is no offset to remove. The figure is withheld instead, which is the
         * owner's rule and the treatment #290's entry above gives a peak.
         *
         * A RETRACTION OF #291's OWN CLAIM. The issue says `rom_m` "multiplies
         * into `meanConPower_w` and `peakPower_w`". It does not: those are
         * `load * (g + a/ratio) * (v/ratio)` over the drive window with no
         * displacement term, recomputed and reproduced exactly in
         * `RomDriftBaselineTest`. They are corrupted by the same VELOCITY, which
         * is a separate remainder and is NOT narrowed here.
         *
         * ABSENT, FALSE AND TRUE ARE THREE FACTS, the doctrine
         * `artefactSamples` states. Absent is permanent on every set already on
         * disk -- the flag is frozen into the stored analysis and nothing re-runs
         * the estimator at export time -- and a rep carrying no flag is KEPT in
         * both populations, so no archived set loses the mean or the spread it
         * has always published.
         *
         * `DATABASE_VERSION` does not move for this entry: the flag rides inside
         * `analysisJson`, which is already a stored blob. The plan schema is
         * untouched and `VALID_REFUSED_DETECTION_REASONS` gains no word -- no
         * detection is refused by this rule.
         *
         * PINNED IN BOTH DIRECTIONS. `SchemaRomBoundContractTest` asserts the
         * published key, that it is not required, that the object is still
         * closed and that the example demonstrates the narrowing;
         * `RomWithholdingDifferentialTest` in `:core:dsp` and
         * `SessionExportRomBoundTest` in `:core:data` are the differentials.
         *
         * 1.22 MINTS A KEY (#260): a set's [SetSensorsExport] may carry
         * `unitAddresses`, which names the PHYSICAL UNIT behind each role it
         * armed, keyed by role, valued with the device address.
         *
         * A MINT AND NOT A FURTHER ENTRY UNDER 1.21, which reverses what this
         * entry said while it was drafted: `git tag --sort=-creatordate |
         * head -1` is v0.1.54 and `git show
         * v0.1.54:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.21"`, both read at the tag this round
         * rather than relayed. 1.21 has SHIPPED, so extending it would change
         * what a version already in the field means, and the four 1.21 entries
         * above are closed.
         *
         * WHAT AN OLDER READER DOES, and it is not symmetric. `schemaVersion` is
         * a CLOSED enum and the published schema sets
         * `"additionalProperties": false` at its root, so the 1.21 schema
         * v0.1.54 shipped REJECTS a 1.22 document on the version string alone,
         * before looking at a single key. The 1.22 schema still lists every
         * version from 1.0 through 1.21, so it ACCEPTS a 1.21 document
         * unchanged. And because this entry adds one OPTIONAL key and removes
         * or retypes none, a 1.21 READER that does not validate reads a 1.22
         * document correctly -- the rejection is the validator's, not the
         * reader's.
         *
         * WHAT WAS UNSAYABLE. Roles are not positional --
         * [SensorCapturePolicy.roster] reads the label the lifter gave each
         * paired unit -- so role `a` is one fixed unit for as long as the
         * pairing stands, and the owner, asked which unit that was on field-38,
         * said *"I'm not really sure. Will check each time, they're likely to
         * get mixed up a lot."* Both units are magnet-mounted and identical to
         * look at. Every dual-unit mount inference in the corpus therefore
         * rested on that memory, with nothing in either document to check it
         * against: the `sensors` block named roles and the raw manifest's
         * `sensors` array carried role, file, samples and rate.
         *
         * DERIVED AT EXPORT TIME. The addresses are the pairing store read
         * while the document is written, not a fact recorded with the set --
         * nothing stores the address a capture came from -- so re-labelling the
         * units between a session and its export publishes the new labelling
         * over the old capture. That limit is in the published description
         * rather than left for a reader to discover, and it is why no
         * `DATABASE_VERSION` moves: this adds no column and stores nothing.
         *
         * ADDITIVE. One optional key is added; none is removed or retyped, so a
         * 1.21 reader is unaffected except that it cannot see unit
         * identities. Every archive already on disk is
         * unchanged, and a session recorded before this key re-exports WITH it
         * wherever the pairing still names both units -- which is correct and
         * is the point, since the labels are the same labels those sets were
         * recorded under.
         *
         * PINNED IN BOTH DIRECTIONS. `SchemaContractTest` asserts
         * `serialKeysOf(SetSensorsExport.serializer())` equals the published
         * `$defs.setSensors` property set, so neither side can move alone;
         * `SchemaUnitIdentityContractTest` asserts the key's shape, this entry's
         * own wording and that the published example carries it, so `ci.yml`'s
         * ajv step validates a document that actually has one.
         *
         * 1.22 TAKES A SECOND ENTRY (#278): a set's [SetSensorsExport] may carry
         * `analysedRoleBasis`, saying WHY [SetSensorsExport.analysedRole] is the
         * role it is -- `declared`, `stackSignature` or `fallback`, which are
         * [AnalysedRoleBasis]'s published spellings.
         *
         * A FURTHER ENTRY and not a mint, on evidence read at the tag this
         * round rather than relayed from the entry above: `git tag
         * --sort=-creatordate | head -1` is v0.1.54 and `git show
         * v0.1.54:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.21"`, so 1.21 HAS SHIPPED and takes no
         * further entries, and 1.22 -- minted by the entry above -- is the open
         * number. This paragraph said 1.21 was unreleased and this was its
         * THIRD entry; the tag shipped 1.21 while this branch was in review, so
         * both halves are deleted rather than reworded.
         *
         * WHAT WAS UNSAYABLE. A set's mount declaration -- `sensorOnStack`,
         * `sensorInverted`, `travelRatio` -- describes the ARMED unit, and
         * there is no per-role mount field on [ExerciseDef], on the set row or
         * in either published document, so on a two-unit set the second unit's
         * mount was inferred from the exercise. Field-42's three seated cable
         * rows armed the unit clipped to the rotating handle under a declared
         * stack mount and published 3, 4 and 2 reps of the 8 the lifter
         * performed. The app now measures each unit's own roll over the set's
         * working window -- `StackRollSignature` in `:core:dsp` -- and analyses
         * the one whose roll is consistent with riding the stack; this key says
         * which of [AnalysedRolePolicy]'s three rules produced the answer.
         *
         * THE FACT THAT IS NOT DERIVABLE, which is why the key exists at all:
         * `declared` on a stack-declared two-unit set means the rule RAN AND
         * DECLINED -- neither unit's roll qualified, or both did -- and nothing
         * else in this document separates that from nothing having looked. The
         * mirror case is `stackSignature` on a set whose analysed role IS the
         * armed one: a confirmation rather than a shrug.
         *
         * READ IT WITH [SetSensorsExport.analysedFellBack] AND NOT INSTEAD OF
         * IT. That flag goes on meaning exactly one thing -- the armed unit
         * delivered too few frames -- because `SetAnalyzer` blanks a set on it
         * under a mount-specific declaration (#247). A `stackSignature` set can
         * therefore name a role the set did not arm with no flag beside it, and
         * that is deliberate: the analysis moved because the other unit is the
         * one the declaration describes, not because anything went quiet.
         *
         * ADDITIVE, and not retroactive in either document. One optional key is
         * added and none is removed or retyped, so a reader written against the
         * first 1.22 entry is unaffected except that it cannot see the basis.
         * No set already on disk moves: the basis is decided when a set
         * is RECORDED and stored on its row, so every earlier set publishes
         * nothing here and is not given a defaulted `declared`.
         * `DATABASE_VERSION` does NOT move -- the declaration rides in the
         * row's existing `sensorsJson` column.
         *
         * PINNED IN BOTH DIRECTIONS. `SchemaContractTest`'s key-set equality
         * covers the Kotlin side; `SchemaAnalysedBasisContractTest` asserts the
         * published property's shape, that the published enum is
         * [AnalysedRoleBasis]'s own vocabulary in order, that this entry is
         * filed once under the 1.22 marker the entry above minted, and that the
         * published example carries the key so `ci.yml`'s ajv step validates a
         * document that has one.
         *
         * 1.22 TAKES A THIRD ENTRY (#302, the live count's descriptions), and it
         * CHANGES NO KEY: three published descriptions said something false
         * about where a sensor-counted set's live count comes from, and are
         * corrected here and in the published schema in the same commit.
         *
         * A FURTHER ENTRY under the unreleased 1.22 and not a mint: `git tag
         * --sort=-creatordate | head -1` is v0.1.54 and `git show
         * v0.1.54:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.21"`, both read at the tag this round.
         *
         * WHAT WAS FALSE. From v0.1.54 (#301) a sensor-counted set is counted
         * live by `DriveImpulseCounter`, which reads no velocity at all. The
         * published [SetExport.liveReps] said the live detector ran the batch
         * pairing rule over a causal velocity estimate;
         * [SetExport.repMetricsComplete] called the live and batch counts one
         * pairing rule over two velocity estimates; and [SetExport.repsSource]'s
         * reading key said the live detector had never been scored against a
         * real straight-reps set and quoted `LiveRepCaller`'s corpus figures as
         * its score. All three were written for v0.1.53's detector and were
         * false in every 1.21 document v0.1.54 wrote. They now name the detector
         * that produces the count and its measured score on the only two
         * sessions it has been scored on, field-43 and field-44, including that
         * it called nothing at 111.1 and 120.2 kg (#305).
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. Nothing about the data moves:
         * `liveReps` is the same integer on the same sets, `repsSource` has the
         * same five words derived the same way, and the schema accepts and
         * rejects exactly the documents it did before this entry. An older
         * reader and a newer one therefore read every value of a 1.21 or a
         * 1.22 document identically; the asymmetry the 1.22 mint states -- a
         * 1.21 validator refusing a 1.22 document on its version string -- is
         * unchanged. Only the prose a reader weighs the count by changes. A
         * `liveReps` on a set RECORDED by v0.1.53 still came from the retired
         * detector and keeps it when re-exported, and nothing in the document
         * records which build recorded a set. `DATABASE_VERSION` does NOT move
         * and the plan schema is untouched.
         *
         * PINNED. `SchemaRepsSourceContractTest` asserts the new figures in the
         * published reading key AND in `PLAN_PROMPT`, string for string, and
         * that neither copy nor the two neighbouring descriptions still carries
         * a deleted claim. It cannot see this KDoc.
         *
         * 1.22 TAKES A FOURTH ENTRY (#302, countTrusted): a set may carry
         * [SetExport.countTrusted], whether the LIVE velocity integrator
         * stayed within its displacement bound through the set. A FURTHER
         * ENTRY under the unreleased 1.22, on the tag reading the third entry
         * states.
         *
         * WHAT WAS UNSAYABLE. `StreamingSetTracker` latches this flag whenever
         * it refuses a run past its displacement cap, and nothing published
         * it, so a reader holding `velocityLoss_pct`, `rom_m` and power built
         * from a stream the live integrator could not bound had nothing in the
         * document to say so. On field-43's three deadlifts it latched false on
         * every stream (`DeadliftLiveCountFieldTest`).
         *
         * WHAT IT DOES NOT SAY is anything about the count: since #301 the
         * count comes from `DriveImpulseCounter`, which reads no velocity, and
         * the published description forbids reading the flag as an instruction
         * to count by hand.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. The key is optional and
         * nothing is removed or retyped, so a reader written against the
         * earlier 1.22 entries reads every other value unchanged and cannot see
         * the flag, and the schema accepts every document those entries
         * described; a 1.21 validator still refuses a 1.22 document on its
         * version string, as the mint states. NOT RETROACTIVE: the value is
         * taken from the tracker when the set is recorded and stored with it,
         * so every set already on disk publishes nothing here. Absence is
         * neither false nor true. `DATABASE_VERSION` does NOT move: the flag
         * rides in the row's existing `analysisJson` as
         * `SetAnalysis.liveCountTrusted`. The plan schema is untouched.
         *
         * PINNED. `SchemaCountTrustedContractTest` asserts the published key,
         * its description's reading rules, this entry's marker, the example and
         * `PLAN_PROMPT`'s line; `SessionExportCountTrustedTest` asserts the
         * exporter publishes the stored latch and invents none;
         * `SessionRepositoryLiveTrustTest` and `PublishedCountTrustedTest` pin
         * the storage and the tracker's null.
         *
         * 1.22 TAKES A FIFTH ENTRY (#264, the explosive drive's word), and it
         * CHANGES NO KEY: a guided cue track on VERTICAL work may now carry
         * `Drive`. A FURTHER ENTRY under the unreleased 1.22, on the tag
         * reading the third entry states: the latest tag is v0.1.54 and it
         * ships 1.21.
         *
         * THE CHANGE. The guide speaks a drive the tempo writes as `X` -- digit
         * 3, on a lift whose drive moves up -- as `Drive` where it said `Up`,
         * because until now `20X0` and `2010` were the same audio row for row
         * (field-39 sets 2, 6 and 10). The stroke keeps its one-second beat and
         * carries no count, and no row moves to another second.
         *
         * WHERE IT APPEARS depends on which stroke opens the rep. On a lift
         * whose rep opens with that drive, a plan that names the rep at the
         * rep's start publishes it ONCE per set, opening rep 1, because from
         * rep 2 the rep number takes that stroke's word (1.20); a plan that
         * names the rep at the end of the drive (#266) -- a `10X0` does --
         * publishes it on every rep. On a lift whose rep opens with the
         * lowering it is published on every rep. An `X` in digit 3 of a
         * drive-DOWN lift is the return and keeps `Up`, and horizontal work
         * already said `Drive` for every drive and is unchanged -- so on
         * horizontal work `20X0` and `2010` are still the same audio.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry, so an older and a newer reader read every
         * value identically; the asymmetry the 1.22 mint states is unchanged.
         * What moves is what one cue string may mean: `Drive` was published on
         * horizontal work only and is not now, so a reader inferring the plane
         * from the stroke words must read `geometry.plane` instead, and a
         * reader counting `Up` rows finds none on such a set where it found one
         * per rep or one per set. NOT RETROACTIVE: a cue row is what the app
         * said when the set was recorded, so every set already on disk keeps
         * its `Up`. `DATABASE_VERSION` does NOT move and the plan schema is
         * untouched.
         *
         * PINNED. `SchemaExplosiveDriveCueContractTest` asserts this entry's
         * marker in the published log and the `voiceCues` sentence that says
         * `Drive` is not evidence of horizontal work; it cannot see this KDoc.
         * `TempoScheduleTest`, `ExplosiveDriveCueTest` and
         * `TwoSecondStrokeCountTest` in `:core:dsp` pin the words the guide
         * says.
         *
         * 1.22 TAKES A SIXTH ENTRY (#305, the live count's descriptions), and it
         * CHANGES NO KEY: from v0.1.55 a sensor-counted set is counted live by
         * `CycleRepCounter`, the full-cycle detector, rather than the
         * `DriveImpulseCounter` v0.1.54 armed, so the four descriptions the third
         * and fourth entries wrote around that detector are corrected --
         * [SetExport.repsSource]'s reading key, [SetExport.liveReps],
         * [SetExport.countTrusted] and [SetExport.repMetricsComplete] -- here, in
         * the published schema and in `PLAN_PROMPT`, in one commit.
         *
         * A FURTHER ENTRY under the unreleased 1.22 and not a mint: `git tag
         * --sort=-creatordate | head -1` is v0.1.54 and `git show
         * v0.1.54:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.21"`, both read at the tag this round.
         *
         * WHAT WENT FALSE. Each of the four named the drive-impulse detector as
         * the one that counts, which is not true of any set a build carrying
         * #305 records. They now name the full-cycle detector and its measured
         * score -- 5, 5, 5, 4 and 2 of 5, 5, 5, 4 and 2 on field-44 with the
         * failed pull not called (`CycleLiveCountFieldTest`) -- say it speaks as
         * the bar lands and can speak a rep late, and keep the drive-impulse
         * figures as what a v0.1.54 recording carries.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. Nothing about the data moves:
         * `liveReps` is still the integer the live detector spoke, `repsSource`
         * has the same five words derived the same way, and the schema accepts
         * and rejects exactly the documents it did before this entry, so an
         * older reader and a newer one read every value identically; the 1.22
         * mint's asymmetry is unchanged. Only the prose moves. A `liveReps` on a
         * set RECORDED by v0.1.54 still came from the drive-impulse detector and
         * keeps it when re-exported, and nothing in the document records which
         * build recorded a set. `DATABASE_VERSION` does NOT move and the plan
         * schema is untouched.
         *
         * PINNED. `SchemaCycleCounterContractTest` asserts the new statements
         * in the published schema AND in `PLAN_PROMPT`, the absence of each
         * sentence that went false, and this entry's marker. It cannot see this
         * KDoc.
         *
         * 1.22 TAKES A SEVENTH ENTRY (#311, a hold let go before its target),
         * and it CHANGES NO KEY: [SetExport.durationEndedBy] may now read
         * `sensor` on a hold the app's own clock ended. A FURTHER ENTRY under
         * the unreleased 1.22, on the tag reading the third entry states: the
         * latest tag is v0.1.54 and it ships 1.21.
         *
         * WHAT GOES FALSE. Under 1.21 `clock` said `duration_s` IS the target
         * and `sensor` said the release came before a TAP, so a hold the lifter
         * let go before its target and never tapped -- the clock then ended it
         * at the target -- recorded the target under `clock`. Field-45's rope
         * dead hang, 35 s planned, was ended by the clock at 35 s, and the code
         * as shipped records 35 under `clock` on that shape; its export carries
         * 30 only because the lifter restated it with the rest screen's
         * correction, while its armed unit's stream shows the release 30.742 s
         * into the hold.
         *
         * THE CHANGE. On a hold the clock ended, a release the armed unit saw
         * decides `duration_s` where believing it takes 1 to 20 whole seconds
         * off the target -- the window a tapped hold already had,
         * `HoldEndPolicy.MAX_TRIM_S` -- and the key reads `sensor`; a release at
         * or after the target, or none, leaves `clock` and the target.
         *
         * THE VERDICT. The release writes neither failure fact. `failed` is
         * derived from `duration_s` at the write as before, so such a hold can
         * now derive short where its target would not have; `failedByLifter`
         * never moves.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry, so an older and a newer reader read every
         * value identically; the asymmetry the 1.22 mint states is unchanged.
         * What moves is what two words may mean: a `sensor` hold may never have
         * been tapped, and a `clock` hold is one whose armed unit, if any, saw
         * no release taking 1 to 20 whole seconds off its target. NOT
         * RETROACTIVE: the word and the seconds are decided when the set is
         * recorded and stored with it, so every set already on disk keeps its
         * `clock` and its target. `DATABASE_VERSION` does NOT move and the
         * plan schema is untouched.
         *
         * PINNED. `SchemaClockReleaseContractTest` asserts this entry's marker
         * and its reading rules in the published log, and the `durationEndedBy`
         * and `duration_s` sentences that state the clock-ended case; it cannot
         * see this KDoc. `HoldEndPolicyDifferentialTest` and `HoldEndPolicyTest`
         * pin the rule, and `HoldReleaseFieldTest` in `:core:dsp` pins it on the
         * committed hold streams.
         *
         * 1.22 TAKES AN EIGHTH ENTRY (#312, which build wrote the export), and it
         * CHANGES NO KEY. A FURTHER ENTRY under the unreleased 1.22, on the tag
         * reading the third entry states: the latest tag is v0.1.54 and it
         * ships 1.21.
         *
         * WHAT MISLED. [SetExport.liveReps] said, truly of this document, that
         * nothing in it records which build recorded a set, and `PLAN_PROMPT`
         * said nothing in the export does -- which was false.
         * `SessionDetailViewModel.exportName` names every export
         * `BarSpeed-v<BuildConfig.VERSION_NAME>-<session start>-<suffix>` and
         * `RawExporter` writes `appVersion` into the raw zip's `meta.json`; both
         * name the build that WROTE the export, which is the recording build
         * unless the session was exported after an update. `liveReps` now says
         * so beside its unchanged sentence, and the prompt's false clause is
         * deleted and replaced by the same pointer. The earlier entries that
         * say nothing in this document records the build are left as written:
         * they are true of this document.
         *
         * ALSO FROM #312, moving no sentence here because no description states
         * the spacing: a hold's or carry's voice now names the time left every
         * 5 s until 10 s are left, where it named it every 15 s
         * (`TimedSetEndPolicy.MARK_EVERY_S`), so a timed set recorded by a build
         * carrying #312 can have more `N seconds` rows on its cue track than
         * one recorded before.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry, so an older and a newer reader read every
         * value identically. `DATABASE_VERSION` does NOT move and the plan
         * schema is untouched.
         *
         * PINNED. `SchemaExportBuildContractTest` asserts this entry's marker,
         * the `liveReps` pointer and the prompt's replacement clause; it cannot
         * see this KDoc.
         *
         * 1.22 TAKES A NINTH ENTRY (#313, no rpe beside a derived failure), and
         * it CHANGES NO KEY. A FURTHER ENTRY under the unreleased 1.22: the
         * latest tag is v0.1.54 and its own `SessionExport.kt` reads
         * `SCHEMA_VERSION = "1.21"`, both read at the tag this round.
         *
         * WHAT WAS UNSTATED, AND UNEVEN. A failure the lifter stated in the rest
         * screen's correction stored no rpe (#310), while a failure the app
         * DERIVED -- a hold's recorded seconds under 90 % of its target, or a
         * count short of the plan -- kept whatever rating stood, so one set
         * could carry an rpe beside a failure nobody tapped.
         *
         * THE CHANGE. Every write that can pair a rating with a failure -- the
         * set write, the rest screen's re-rating and the correction's SAVE --
         * reads `FailedSetRatingPolicy.storedRpe` and stores no rpe on a set
         * that failed by either fact; `failedByLifter` never moves. [SetExport.rpe]
         * says so.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry. What moves is what an absent rpe beside
         * `failed` may mean: on a set recorded by a build carrying #313 it is
         * the owner's rule, not a skipped question. NOT RETROACTIVE: the rating
         * is stored when the set is written or corrected, so every set already
         * on disk keeps the rpe it has. `DATABASE_VERSION` does NOT move and the
         * plan schema is untouched.
         *
         * PINNED. `SchemaFailedSetRpeContractTest` asserts this entry's marker
         * and the `rpe` sentence; it cannot see this KDoc.
         * `FailedSetRatingPolicyTest` and, in `:app`, `DerivedFailureRatingTest`
         * pin the rule and the three writes.
         *
         * 1.22 TAKES A TENTH ENTRY (#314, a carry the clock ended keeps its
         * target), and it CHANGES NO KEY. A FURTHER ENTRY under the unreleased
         * 1.22, on the tag reading the ninth entry states.
         *
         * WHAT WENT FALSE. The seventh entry's check -- a release 1 to 20 whole
         * seconds before the target decides a clock-ended set's `duration_s` --
         * reached every timed kind, so a timed CARRY (`farmers_walk`,
         * `suitcase_carry`) took it too, and [SetExport.durationEndedBy] and
         * [SetExport.durationS] said so of any set the clock ended. No
         * walking-carry stream exists in any capture, and a footstrike could
         * read as a let-go.
         *
         * THE CHANGE. `HoldEndPolicy.releaseConsulted`: the check applies to a
         * HOLD only, so a carry the clock ended records its target and reads
         * `clock` whatever its stream shows. A carry the lifter tapped is
         * unchanged. Both descriptions now say HOLD.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry. What moves is what `clock` may mean on a carry:
         * the target, never a release. NOT RETROACTIVE: the word and the
         * seconds are decided when the set is recorded and stored with it.
         * `DATABASE_VERSION` does NOT move and the plan schema is untouched.
         *
         * PINNED. `SchemaCarryClockContractTest` asserts this entry's marker and
         * both descriptions' carry sentences, and `SchemaClockReleaseContractTest`
         * now asserts `duration_s` says HOLD; neither can see this KDoc.
         * `HoldEndCarryGuardTest` pins the rule.
         *
         * 1.22 TAKES AN ELEVENTH ENTRY (#295, a timed set's `Time` is a terminal
         * cue), and it CHANGES NO KEY. A FURTHER ENTRY under the unreleased
         * 1.22, on the tag reading the ninth entry states.
         *
         * WHAT WAS FALSE. A timed set's track ends on `Time` when its clock ends
         * it, and `SetEnd` did not count that word as calling the set over, so
         * the raw archive's `rollExcursionBasis` on every such hold read
         * `fromWorkStart` beside a word spoken on the tick that ended it --
         * field-42's six hold streams, `Time` 30.014 to 30.020 s after work
         * start on a 30 s prescription. [SetExport.voiceCues]' published
         * description sent a reader to `Done` or `Set ended` for that instant,
         * and [SetPrescriptionExport.restS]'s said every hold rests from its end instant.
         *
         * THE CHANGE. `SetEnd.TIME_UP` joins `SetEnd.TERMINAL_CUES` and bounds
         * `SetEnd.of`: the roll window, the stack-mount verdict's window and the
         * rest seed where no release decided the seconds. Both descriptions say
         * so, and `rest_s` now states the release case it never did.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry. RETROACTIVE IN THE RAW ARCHIVE: its roll figure
         * is recomputed from the stored cue track at export time, so a hold
         * recorded by any build whose track carries `Time` now reads
         * `workingWindow` where it read `fromWorkStart` (`toTerminalCue` where it
         * read `wholeCapture`), over the samples up to `Time` -- the same figure
         * on field-42's six hold streams, which stop 1 to 65 ms before the word.
         * NOT RETROACTIVE elsewhere: the rest instant and the stack-mount verdict
         * are decided when the set is recorded. `DATABASE_VERSION` does NOT move
         * and the plan schema is untouched.
         *
         * PINNED. `SchemaTimeTerminalContractTest` asserts this entry's marker and
         * both descriptions; it cannot see this KDoc. `HoldTerminalCueFieldTest`
         * and `FailedSetBoundaryTest` in `:core:dsp` and `RawExporterRollWindowTest`
         * in `:core:data` pin the rule.
         *
         * 1.22 TAKES A TWELFTH ENTRY (#288, a timed set ended early says `Set
         * ended`), and it CHANGES NO KEY. A FURTHER ENTRY under the unreleased
         * 1.22, on the tag reading the ninth entry states.
         *
         * WHAT WAS SILENT. A timed set the lifter ended before its clock reached
         * the target said no word at the break and wrote none, so nothing on the
         * record said when it stopped: field-41 set 21, a 30 s dead hang broken
         * at 5 s, carries `Ready`, `Brace`, `Hold` and nothing after.
         *
         * THE CHANGE. `SetEnd.terminalCall` asks a timed set the clock did not
         * end, where the timed voice is on, and it says and writes `Set ended`
         * at the tap -- the word a guided set already says when it ends without
         * `Done`. It bounds the working window and seeds the rest unless a
         * release decided the seconds. A timed set the clock ended says `Time`,
         * as before. [SetExport.voiceCues]' and [SetPrescriptionExport.restS]'s published
         * descriptions say so.
         *
         * WHAT A READER DOES, IN BOTH DIRECTIONS. No key is added, removed or
         * retyped and the schema accepts and rejects exactly the documents it
         * did before this entry. What moves is what `Set ended` may mean: it can
         * end a timed set's track, so it is not evidence the set was guided, and
         * it is still not evidence the set failed. NOT RETROACTIVE: a cue row is
         * what the app said when the set was recorded. `DATABASE_VERSION` does
         * NOT move and the plan schema is untouched.
         *
         * PINNED. `SchemaTimedStopContractTest` asserts this entry's marker and
         * both descriptions; it cannot see this KDoc. `TimedStopCallTest` in
         * `:core:dsp` pins the rule on field-41 set 21's committed track.
         *
         * 1.22 TAKES A THIRTEENTH ENTRY (#306, which reps a set's peaks and its
         * velocity loss are taken over). A FURTHER ENTRY under the unreleased
         * 1.22 and not a mint: `git tag --sort=-creatordate | head -1` is
         * v0.1.54 and `git show
         * v0.1.54:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.21"`, both read at the tag this round.
         *
         * THE CHANGE. A rep is PEAK-ELIGIBLE when its own span carries no
         * sample above 4 g (#290), the 0.4 s GUARD BAND before the span carries
         * none either, and its displacement is bounded (`romBounded`, #291).
         * `AccelArtefact.isPeakEligible` in `:core:dsp` states the rule once.
         * `summary.peakConVel_mps` and `summary.peakPower_w` are taken over the
         * eligible reps, and withheld where the best of them falls below the
         * mean of the same quantity the summary publishes over every rep.
         * `velocityLoss_pct` takes its best over the eligible reps, and is
         * withheld unless at least two reps are eligible AND the set's last rep
         * is one; [SetExport.velocityLossBasis] then reads the new word
         * `noEligiblePair`. The word `noReference` narrows with it: it now
         * says no peak-eligible rep carried a positive drive velocity, where
         * before 1.22 it said no rep did. The word `terminalRepIsFastest`
         * narrows the same way: the last rep is compared with the
         * peak-eligible reps, where before 1.22 it was compared with every
         * rep. Each `repMetrics` row may carry
         * [RepMetricsExport.guardArtefactSamples], the band's own count.
         *
         * WHAT WAS WRONG. field-44 set 4, a 111.1 kg deadlift, published
         * `peakPower_w` 7762.4 from a rep whose span opens 0.03 s after a 21.4 g
         * floor contact and holds no sample above the bound itself. field-44
         * set 5 published `peakConVel_mps` 0.376 against a `meanConVel_mps` of
         * 0.382. field-45 set 2 published 1.937 m/s and 623.6 W from an
         * unbounded 1.961 m detection with no artefact in it. field-44 set 3's
         * `velocityLoss_pct` of 84.9 took its best from the rep whose power #290
         * already withheld.
         *
         * NOT PURELY ADDITIVE. One optional key and one basis word are added
         * and nothing is removed or retyped, but three existing keys are now
         * taken over a narrower population, so comparing them across versions
         * compares different quantities. On the committed field corpus not one
         * capture has two eligible reps, so every capture with two or more reps
         * withholds `velocityLoss_pct`, and the peak pair is withheld on every
         * capture whose reps are all unbounded. RETROACTIVE FOR v0.1.54
         * RECORDINGS: the export re-asks the stored reps, and a set recorded by
         * v0.1.54 carries `romBounded` and `artefactSamples`, so re-exporting it
         * withholds figures v0.1.54's own export published. A set recorded
         * before v0.1.54 carries neither and keeps every figure it published,
         * and no stored rep carries a band count, so the band decides only on
         * sets recorded from v0.1.55. `DATABASE_VERSION` does NOT move: the
         * count rides in `analysisJson`. The plan schema is untouched.
         *
         * PINNED. `SchemaPeakEligibilityContractTest` asserts the key, the word,
         * the three descriptions, this entry's marker and an example that shows
         * the narrowing; `PeakEligibilityTest` and `GuardBandProvenanceTest` in
         * `:core:dsp` and `SessionExportPeakEligibilityTest` in `:core:data` are
         * the differentials.
         *
         * 1.23 MINTS FIVE KEYS (#157, folding #151, #76 and #219): a set may
         * carry `workingReps`, `workingLoad_kg`, `workingDuration_s`,
         * `plannedTempo` and `restMeasured_s`.
         *
         * A MINT AND NOT A FOURTEENTH ENTRY UNDER 1.22: `git tag
         * --sort=-creatordate | head -1` is v0.1.55 and `git show
         * v0.1.55:core/model/src/main/kotlin/com/macrophage/barspeed/model/SessionExport.kt`
         * reads `SCHEMA_VERSION = "1.22"`, both read at the tag this round.
         * 1.22 has SHIPPED and its thirteen entries are closed.
         *
         * THREE LAYERS PER TARGET. PLANNED is what the plan prescribed, frozen
         * when the plan was flattened, and no in-app control moves it:
         * `plannedReps`, `plannedLoad_kg`, `plannedDuration_s`, `plannedTempo`
         * and `rest_s`. WORKING is the target the set ran against, fixed at
         * START: `workingReps`, `workingLoad_kg`, `workingDuration_s` and
         * `tempoPrescribed`, whose name is historical. ACTUAL is what the set
         * is recorded as, after any rest-screen correction: `reps`,
         * `load_kg`, `duration_s` and `tempoCompliance`. The owner plans at a
         * productive floor and raises the load or the reps with the in-app
         * buttons when a set shows headroom, so a working figure above the
         * planned one is intended use. A working figure below the planned one
         * is a lowered target, and a set that met it is COMPLETED -- the
         * owner, 2026-09-25: "It's completed even if the target is lowered,
         * just note the discrepancy." The note is the two figures side by
         * side; nothing judges it.
         *
         * WHAT WAS UNSAYABLE. Until database v20 the working targets were
         * dropped at the write. field-45's set 8 -- planned 10, lowered to 6,
         * 6 done -- was recorded before v20 and reads "planned 10, did 6,
         * not failed", a shortfall that never happened; nothing backfills it.
         * field-41's seven raised targets read as over-performance. They were
         * recoverable only from where the cue track's `Last rep` fell.
         *
         * THE MEASURED REST. `restMeasured_s` is the seconds from the instant
         * this set's rest ran from -- the instant `rest_s`'s countdown starts
         * at -- to the START tap on the next set of the session, rounded to a
         * tenth by [RestMeasurePolicy.measuredS]. `rest_s` is a MINIMUM -- the
         * owner: "I consider rests a minimum. If it takes more time to setup I
         * do." -- so only a measured rest SHORTER than `rest_s` is a
         * discrepancy.
         *
         * ONE DESCRIPTOR. Every planned, working and rest key is published from
         * one [SetPrescriptionExport], which the session document carries flat
         * and the raw archive's `meta.json` splices into its set descriptor, so
         * the two documents cannot list different keys again. That is #219:
         * `meta.json` carries `plannedLoad_kg`, `plannedDuration_s` and
         * `rest_s` for the first time.
         *
         * WHAT AN OLDER READER DOES, and it is not symmetric. The 1.22 schema
         * v0.1.55 shipped REJECTS a 1.23 document on the version string alone,
         * and a validator on it would reject the five keys too, its set being
         * `additionalProperties: false`. This schema still lists 1.0 through
         * 1.22 and ACCEPTS a 1.22 document unchanged.
         *
         * NOT RETROACTIVE. The columns behind the keys arrived at database v20
         * with no backfill, so a set recorded before v20 publishes none of the
         * five. Every set recorded from v20 carries `workingLoad_kg`, which is
         * how a reader tells the two apart. `DATABASE_VERSION` does NOT move in
         * this entry, and the plan schema is untouched.
         *
         * PINNED. `SchemaWorkingTargetContractTest` asserts the five keys, the
         * corrected descriptions and this entry's marker in the published log;
         * `SetPrescriptionExportTest` pins the Kotlin keys against the schema;
         * `RawExporterPrescriptionParityTest` and
         * `SessionExportWorkingTargetsTest` in `:core:data` are the
         * differentials, and `PlanPromptWorkingTargetContractTest` pins the
         * reading guide the plan prompt carries.
         *
         * FURTHER 1.23 ENTRY (#231). `voiceCues`' published description named
         * two producers of a bare digit, the guide's tempo count and a timed
         * set's countdown, and left out a third: the sensor-driven counter,
         * which counts the seconds of a phase it detected. It now names all
         * three, says where the sensor-driven counter speaks digits -- from
         * v0.1.53 (#286) only on a set prescribed a tempo with nothing to play
         * it -- and says a hold or a carry recorded by v0.1.49 or earlier may
         * carry its digits beside the clock's (#217). Description text only: no
         * key, type, value or example moves. `SchemaBareDigitContractTest` pins
         * the description and this entry's marker in the published log.
         *
         * 1.23 FURTHER ENTRY (#71, a timed set publishes no reps): `reps` is no
         * longer required on every set. A set measured in seconds -- a hold or
         * a carry -- publishes no `reps` key, in session.json and in the raw
         * archive's meta.json alike, and the schema requires `reps` on any set
         * carrying neither `duration_s` nor `abandonedInPrep: true`.
         *
         * WHAT WAS FALSE. The set's required list obliged every set to carry an
         * integer, so a hold published `"reps": 0`, a number nothing counted,
         * which a reader could not tell from a dynamic set that scored none.
         * The row still stores that 0 (the column is NOT NULL); the exporter
         * withholds it where the row's duration column marks the set timed --
         * [RepsSourcePolicy.publishedReps], on the marker `repsSource` has read
         * since 1.20.
         *
         * THE LOOSENESS, stated. The document cannot tell a timed set abandoned
         * in its prep from a rep set abandoned in its prep -- both carry
         * `abandonedInPrep` and no duration -- so the schema lets `reps` be
         * absent on that one shape. This exporter still writes `reps` on every
         * rep set, 0 included, and [SetExport]'s init refuses a set that is
         * neither timed nor counted.
         *
         * NOT PURELY ADDITIVE: a reader that indexes `reps` on every set breaks
         * on a hold. The first 1.23 entry's sentence saying no existing key is
         * removed, and that a reader ignoring unknown keys reads 1.23 as it
         * read 1.22, is DELETED rather than reworded. RETROACTIVE: the rule
         * reads a column written on timed sets since database v2, so
         * re-exporting an older session withholds `reps` on its holds and
         * carries too. `DATABASE_VERSION` does NOT move, and the plan schema is
         * untouched.
         *
         * PINNED. `SchemaTimedRepsContractTest`, and `TimedSetRepsPublishedTest`
         * in `:core:data`.
         *
         * 1.23 FURTHER ENTRY (#294, repMarks described as what they hold): NO
         * KEY, VALUE OR FILE CHANGES; the descriptions of `repMarks` and
         * `voiceCues` change. MEASURED by `RepMarkTrackTest` on the thirteen
         * committed captures that carry a rep-mark stream, all guided sets: all
         * 103 marks fall within 1 ms of a row of the same set's cue track, 94 on
         * the same millisecond -- the next cycle's opening stroke word or
         * `Done` -- spaced at the tempo's sum. So on a `metronome` set each mark
         * is the instant the cadence guide finished one prescribed cycle, the
         * prescribed grid, and not an instant anyone observed a rep; #294 found
         * a failed set carrying more marks than reps. On a `manual` set the
         * marks are the lifter's taps. NO TRUE REP INSTANT IS STORED on a guided
         * set -- the per-rep rows carry no clock, the cue track is what the app
         * said, and the live count is an integer -- so the content is kept, and
         * the key and the archive's `_reps.csv` filename keep their names: a
         * rename would break every reader and add no fact. DELETED rather than
         * reworded: `repMarks`' opening and its "the guide writes a mark as it
         * calls a rep", and `voiceCues`' clause calling `repMarks` what was
         * counted. `DATABASE_VERSION` does NOT move.
         *
         * PINNED. `SchemaRepMarksGridContractTest`; the content is guarded in
         * `:core:data` by `SessionExportRepMarksTest`'s failed guided set.
         */
        const val SCHEMA_VERSION = "1.23"

        /**
         * `"1.10"` is not the number 1.1 -- a reader that parses this field as
         * a float collides 1.10 with 1.1, which is a different contract.
         */
        val SUPPORTED_SCHEMA_VERSIONS =
            setOf(
                "1.0", "1.1", "1.2", "1.3", "1.4", "1.5",
                "1.6", "1.7", "1.8", "1.9", "1.10", "1.11", "1.12", "1.13", "1.14", "1.15",
                "1.16", "1.17", "1.18", "1.19", "1.20", "1.21", "1.22", "1.23",
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
            setOf("measured", "notEnoughReps", "noReference", "terminalRepIsFastest", "noEligiblePair")

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
         * about the bar or the lifter, with ONE exception: `mountNotDeclared`
         * says the segmenter was never run, because the analysis had moved
         * onto a unit whose mount nothing on the record declares (#247, schema
         * 1.20). `runsExceedDisplacementCap` in
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
                "beforeWorkStart",
                "mountNotDeclared",
            )

        /**
         * Why a set refused a detection, the values
         * [SetExport.refusedDetectionReason] is drawn from. Schema 1.19,
         * issue #125.
         *
         * The names are owned by `RepRefusal` in `:core:dsp`, which this
         * module cannot see -- the dependency runs the other way. They are
         * mirrored here so the published schema has a Kotlin constant to be
         * pinned against, the same arrangement [VALID_NO_REPS_REASONS] uses,
         * and `RefusedDetectionAnalysisTest`'s "the refusal words are the
         * ones the export publishes" asserts the two lists are equal from
         * the side that can see both.
         *
         * One word today, and it stays one. The sample-level half of the same
         * defect -- an accelerometer reading the sensor cannot have measured
         * landing inside a real rep, which sets that rep's `peakConVel_mps`
         * and `peakPower_w` while leaving its range and mean ordinary -- IS
         * handled now, at 1.21, and it needed no word here. This sentence used
         * to say it "would need a second word if it ever is"; that is DELETED
         * rather than reworded, because the rule that landed refuses no
         * detection at all. [SetExport.artefactSamples] and each rep's own
         * count are what mark it, the detection is KEPT with every figure it
         * measured, and only the SET's published peaks are taken over a
         * narrower population. Issues #290 and #255.
         */
        val VALID_REFUSED_DETECTION_REASONS = setOf("unpairedRangeOutlier")

        /**
         * Which scale a set's [SetExport.rpe] was given on, the values
         * [SetExport.rpeScale] is drawn from. Schema 1.19, issue #244.
         *
         * A literal set rather than a projection of [EffortAsk], the same
         * arrangement [VALID_NO_REPS_REASONS] uses: this is the WIRE
         * vocabulary and the enum is a Kotlin type, so writing one from the
         * other would let a Kotlin rename redefine what a stored word means
         * with nothing to red. `HeadroomScaleContractTest` asserts the two
         * agree, in both directions, from the side that can see both.
         *
         * Each word names the QUESTION the lifter was asked at the moment the
         * set ended, never a re-reading of the plan: plans are editable and
         * deletable, and an exercise's `progression` can move under an old
         * set. The word is frozen into the row when the set is written, for
         * the reason [SetExport.bodyWeightKg] is.
         */
        val VALID_RPE_SCALES = setOf("load", "reps", "time", "feel")

        /**
         * Whose count a set's `reps` figure is, lowercased [RepsSource] wire
         * words (#286).
         *
         * Derived from the enum rather than written out, the arrangement
         * [VALID_VELOCITY_LOSS_REGIMES] uses: the WIRE word is what a row is
         * compared against, so a reordered enum cannot reinterpret an export,
         * and a word added to the enum without being published is caught by
         * `SchemaRepsSourceContractTest` rather than shipping.
         */
        val VALID_REPS_SOURCES = RepsSource.entries.map { it.wireName }.toSet()

        /**
         * The words a set's `velocityLossRegime` may take, #250.
         *
         * DERIVED from the enum rather than written out, so the published
         * schema, the exporter and this set cannot state three different
         * vocabularies. [VelocityLossRegime] is where the rule lives and its
         * KDoc is the one account of it.
         */
        val VALID_VELOCITY_LOSS_REGIMES = VelocityLossRegime.entries.map { it.wireName }.toSet()
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
    @Serializable(with = SetExportListSerializer::class) val sets: List<SetExport>,
)

@Serializable
data class SetExport(
    @SerialName("load_kg") val loadKg: Double,
    /** Same load in pounds, for readers who think in lb; kg remains canonical. */
    @SerialName("load_lb") val loadLb: Double? = null,
    /**
     * The set's targets and its rest, published FLAT beside the keys below by
     * [SetExportWireSerializer]: the planned, working and rest keys
     * [SetPrescriptionExport] declares. Grouped so the raw archive's manifest
     * publishes them from the same object (#219).
     *
     * No default: every set states its prescription, and a defaulted property
     * is one the exporter could silently stop passing.
     */
    val prescription: SetPrescriptionExport,
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
    /**
     * How many reps the set is RECORDED as; [repsSource] says whose count.
     *
     * ABSENT on a timed set (1.23, #71): nothing counts reps on a hold or a
     * carry, and the 0 the row stores there is not a count.
     * [RepsSourcePolicy.publishedReps] is the rule. Present on every other
     * set, 0 included -- there 0 is a count -- and the init block below
     * refuses a set carrying none of `reps`, [durationS] or [abandonedInPrep],
     * which is a rep set that lost its count.
     *
     * Defaulted so a document that omits the key decodes; the init block is
     * what stops the default standing in for a count the exporter forgot.
     */
    val reps: Int? = null,
    /** True when reps were entered or corrected manually rather than sensor-counted. */
    val repsManual: Boolean = false,
    /**
     * WHOSE COUNT [reps] is, as the published word (1.20, #286).
     *
     * One of [SessionExport.VALID_REPS_SOURCES]. `sensor` is the sensor's live
     * count as the lifter heard it; `manual` the lifter's own taps;
     * `metronome` the cadence guide's schedule, which it kept whether or not
     * the lifter followed it; `corrected` a sensor count the lifter then
     * disagreed with, with [liveReps] beside it holding what the sensor said;
     * and `analysis` the batch segmenter's figure, taken after the set from
     * the archived stream.
     *
     * DERIVED at export by `RepsSourcePolicy` from [liveReps], [repsManual],
     * whether the set is measured in seconds, and whether A CADENCE RAN --
     * which is `RepsSourcePolicy.guideCounted` over the frozen tempo and the
     * frozen geometry's kind, not the tempo alone. No column holds the word.
     *
     * THREE COLLAPSES follow and are stated rather than hidden. A corrected
     * MANUAL set reads `manual` and a corrected GUIDED set reads `metronome`,
     * because nothing on the row records that the rest-screen control was
     * used. The third is a row with NO STORED GEOMETRY: it cannot say what
     * kind of exercise it was, so its tempo is read as the guide -- which is
     * what a tempo'd row almost always was. It matters for one shape, an
     * EXPLOSIVE LIFT CARRYING A TEMPO: no cadence is played on one, because it
     * is judged on peak velocity, so the lifter taps its count. With the
     * geometry on the row that set publishes `manual`; without it the tempo is
     * all there is to read and it publishes `metronome` for a set no guide
     * counted.
     *
     * ABSENT on a timed set, where nothing counted reps at all, and absence
     * means that and nothing else -- never a sixth word and never a stand-in
     * for `manual`.
     *
     * READ `sensor` AS A MEASUREMENT AND NOT A VERIFIED COUNT. From v0.1.55
     * (#305) it is `CycleRepCounter`'s count -- a drive, measured by the
     * velocity it gains, met by its brake and called only once the bar is back
     * at the floor -- and [liveReps] says what that means beside the batch
     * count. It speaks as the bar lands, about 1.2 s after the pull ends
     * (a median 1.16 s after the batch detector's concentric window ends, on
     * replayed captures), can speak a rep late after a light soft landing,
     * and does not count an attempt that is back on the floor, or falls,
     * within 1.2 s of its drive. On straight-reps work the LIFTER'S HAND
     * COUNT is the ground truth and this word says which counter to score
     * against it (#286). The batch detector, separately, over-counts all six
     * committed concentric-first captures that carry a hand count, by +1 to
     * +4 (#284).
     *
     * WHAT IT HAS BEEN SCORED ON, against the lifter's settled count -- the
     * hand count, except field-44 set 4, where the capture's 4 replaced a
     * hand count of 5 --: straight-reps deadlifts from two sessions and
     * nothing else, both replayed through it after the session
     * (`CycleLiveCountFieldTest`). Field-43: 5, 5 and 5
     * calls for 5, 5 and 5 at 61.2, 83.9 and 102.1 kg, one of the last set's
     * the set-up pull and that set's rep 4 missed. Field-44: 5, 5, 5, 4 and 2
     * of 5, 5, 5, 4 and 2 at 61.2 to 120.2 kg, the failed third pull of the
     * last set not called. No other lift has been scored as the app runs it:
     * on the six committed seated overhead-press captures -- tempo'd sets the
     * metronome counts -- it would call 7, 8, 6, 10, 9 and 2 against hand
     * counts of 6, 7, 5, 8, 8 and 2, the nearest measurement of the one tempo'd
     * shape it does count, an explosive lift carrying a tempo.
     *
     * A set RECORDED by v0.1.54 was counted by `DriveImpulseCounter` (#301),
     * an upward acceleration impulse followed by braking, with no velocity in
     * it, and keeps that figure when re-exported. On field-43, replayed
     * (`LiveCountDifferentialTest`): 5, 5 and 3 of 5, 5 and 5, no phantom call,
     * the two misses the slowest pulls of the heaviest set. On field-44, as the
     * app counted it during the session: 5, 5, 5, 0 and 0 of 5, 5, 5, 4 and 2
     * -- NOTHING at 111.1 or 120.2 kg, each dead-stop pull above its
     * acceleration threshold for too short a time to be called. So on a set
     * recorded by v0.1.54 a low or zero count on a heavy set is more likely a
     * miss than a short set. The same six presses read 8, 8, 7, 10, 11 and 2
     * under it.
     *
     * The sentence that stood here naming `DriveImpulseCounter` as the
     * detector "from v0.1.54" went false for every set a build carrying #305
     * records, and is DELETED rather than reworded; its figures are kept above,
     * scoped to the v0.1.54 recordings they describe.
     *
     * A set RECORDED by v0.1.53 was counted by `LiveRepCaller`, the retired
     * detector [liveReps] names, which called 3, 1 and 2 on the same three
     * field-43 sets and keeps its figure when re-exported.
     *
     * The paragraph that stood here said the live detector "has never been
     * scored against a real straight-reps set" and quoted `LiveRepCaller`'s
     * corpus figures -- 35 calls against 103 marks over thirteen tempo'd
     * captures -- as the live detector's. Both went false with #301 in
     * v0.1.54, and both are DELETED rather than reworded (#302).
     */
    val repsSource: String? = null,
    /**
     * What the sensor's LIVE detector counted while the set was performed
     * (1.20, #286).
     *
     * The figure the lifter heard, published whether or not it is still
     * [reps]: a rest-screen correction rewrites the count and never touches
     * this, so `repsSource` `corrected` and this key together say what was
     * corrected and to what.
     *
     * NOT the batch segmenter's count, which is `repMetrics.length` where
     * per-rep detail was asked for and is the figure [repMetricsComplete]
     * compares [reps] against. THEY ARE DIFFERENT DETECTORS. A set recorded by
     * v0.1.55 or later was counted live by `CycleRepCounter` (#305): a drive,
     * measured by the velocity it gains off the acceleration, met by its brake
     * and called only once the bar is back at the floor, with no running
     * velocity integrator and no displacement in it. A set recorded by v0.1.54
     * was counted by `DriveImpulseCounter` (#301): an upward acceleration
     * impulse followed by braking, read off the bias-corrected acceleration,
     * with no velocity, no integrator and no displacement in it. The segmenter
     * pairs phases of an integrated, drift-corrected velocity. So the live and
     * batch counts disagree wherever the two rules do, and neither is a check
     * on the other. The sentence that stood here said every set recorded by
     * "v0.1.54 or later" was counted by the impulse detector; #305 made it
     * false and it is DELETED rather than reworded.
     *
     * A set recorded by v0.1.53, the only earlier release that stored this
     * key, was counted by `LiveRepCaller` -- the segmenter's pairing rule over
     * a causal velocity estimate -- and keeps that figure when re-exported.
     * Nothing in the document records which build recorded a set; the
     * export's filename as the app names it
     * (`BarSpeed-v<version>-<session start>-<suffix>`, from
     * `SessionDetailViewModel.exportName`) and the raw zip's `meta.json`
     * `appVersion` (`RawExporter`) name the build that WROTE the export, which
     * is the recording build unless the session was exported after an update
     * (#312). A session whose `startedAt` precedes v0.1.54's release on
     * 2026-09-18 cannot have been counted by the impulse detector. The sentence that stood here said
     * the live detector runs the same pairing rule over a causal velocity
     * estimate; that was v0.1.53's detector, it was false for every set
     * v0.1.54 recorded, and it is DELETED rather than reworded (#302).
     *
     * ABSENT where no live counter ran: a set the lifter counted, a set the
     * guide counted, a timed set, and every set recorded before database v18.
     * A sensor-counted set whose detector resolved nothing publishes 0, and 0
     * is a count.
     */
    val liveReps: Int? = null,
    /**
     * Whether the LIVE velocity integrator stayed within its displacement
     * bound through this set
     * (1.22, #302): `StreamingSetTracker.publishedCountTrusted`, frozen when
     * the set was recorded and read here out of the stored analysis.
     *
     * FALSE: that tracker carried one movement run further than any real
     * phase of the lift can (`DspConfig.maxRunDisplacementM`, converted
     * through the declared ratio), so its integral lost its zero and the flag
     * latched. READ IT AS: `velocityLoss_pct`, `rom_m` and power on this set
     * are not derived from a trusted velocity. Those figures come from the
     * batch analysis, a separate drift-corrected estimate over the same
     * stream, so false does not measure how wrong they are; TRUE CERTIFIES
     * NOTHING about them, only that the bound was never crossed.
     *
     * NOT ABOUT THE COUNT. Since #301 a sensor-counted set is counted by a
     * detector that reads no velocity -- `DriveImpulseCounter` in v0.1.54,
     * `CycleRepCounter` from v0.1.55 -- so this says nothing about [reps] or
     * [liveReps], and #301's design round forbids wiring it to a "count this
     * set by hand" warning: replayed over field-43's three deadlifts it was
     * false on every stream while the impulse counter called 13 of 15 reps and
     * the full-cycle counter counted 14 with one phantom. No screen reads it.
     *
     * WHICH TRACKER: the one running when the set ended. On a two-unit set
     * whose readout was rebuilt on the other unit mid-set, that tracker saw
     * only the frames after the switch.
     *
     * ABSENT where no tracker covered the set's end -- none was fed a sample,
     * or the readout was given up mid-set -- and on every set recorded before
     * this key. Absent is neither false nor true.
     */
    val countTrusted: Boolean? = null,
    /**
     * Hold/carry seconds recorded for timed sets (planks, farmer's walks).
     *
     * The ACTUAL seconds, after any rest-screen correction. Since #168 a timed
     * set ENDS when its clock reaches its working target, published from 1.23
     * as [SetPrescriptionExport.workingDurationS] beside the plan's
     * [SetPrescriptionExport.plannedDurationS], so a set that ran to its
     * target publishes its working target here; one the lifter ended by hand
     * publishes what it lasted, and one corrected afterwards on the rest
     * screen publishes the corrected
     * seconds. WHICH of them produced this figure is [durationEndedBy] from
     * 1.21; the sentence that stood here -- that the three are not
     * distinguishable and that [repsManual] has no counterpart for duration
     * -- is deleted rather than reworded. A reader comparing holds across
     * 1.12 and 1.13 is still comparing figures whose upper end moved: under
     * 1.12 every timed set carried the walk back to the phone inside it, and
     * from 1.21 a hold whose armed unit saw the release does not.
     *
     * From 1.22 (#311) a HOLD the CLOCK ended publishes the span to a release
     * instead of the target where its armed unit saw the implement let go and
     * believing it takes 1 to 20 whole seconds off the target, so such a set
     * is SHORTER than its target though the clock ran to it. A release at or
     * after the target, or none, leaves the target. From 1.22 (#314) a CARRY
     * the clock ended publishes the target whatever its stream shows: no
     * walking-carry stream has been captured, and a footstrike could read as
     * a release.
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
     * was measured, and any [failed] on such a set is the app's derivation --
     * read [failedByLifter] to confirm. The raw stream is real and worth
     * reading; what it captured is a lead-in. On a rep set [reps] is 0, which
     * is nothing counted rather than nothing lifted; it is absent on a timed
     * set (1.23, #71). The clause that stood here said [reps] is 0 on every
     * such set, and is DELETED rather than reworded.
     *
     * Omitted when false, and omission is NOT proof of the opposite: whether
     * the work began is a database column added at v15, so every set recorded
     * before it publishes nothing here. A missing key means "the work began,
     * or the app could not tell".
     */
    val abandonedInPrep: Boolean = false,
    /**
     * Which of four things decided [durationS]: `"clock"`, `"sensor"`,
     * `"lifter"` or `"corrected"`. Schema 1.21, issues #259 and #249.
     *
     * The words are `HoldEndSource`'s and the enum is the only place they are
     * spelled, so the column, this key and the archive manifest cannot drift
     * apart over them. What each means:
     *
     * - `clock` -- the app's own clock reached the target and ended the set
     *   (#168), so [durationS] is the target itself and the lifter heard `Time`
     *   a beat before it. From 1.22 (#311), on a HOLD, only where its armed
     *   unit, if any, reported no release taking 1 to 20 whole seconds off the
     *   target; a timed CARRY the clock ended is never offered the release
     *   (#314), so it reads `clock` and its target whatever its stream shows.
     * - `sensor` -- an armed unit's stream showed the implement being let go,
     *   and [durationS] runs to THAT instant rather than to the tap that
     *   followed it -- or, from 1.22 (#311), rather than to the target on a
     *   hold the clock ended, where the release came 1 to 20 whole seconds
     *   before the target, so a `sensor` hold may never have been tapped at
     *   all. On a hands-full hold the tap is 5-10 s late by the owner's own
     *   account, and every one of those seconds used to be inside this figure.
     * - `lifter` -- the tap decided, and nothing else spoke: no unit was armed
     *   for the set, or the armed unit's stream carried no release. The reach
     *   is still inside [durationS] on these.
     * - `corrected` -- the lifter restated the figure on the rest screen
     *   afterwards. It REPLACES whichever of the other three stood before it,
     *   so a corrected figure does not also publish what produced the figure
     *   it replaced; the raw span is still recoverable from the archive's
     *   `workStartedAt_ms` and the row's own end instant.
     *
     * ABSENT, not defaulted, on every set that is not timed and on every timed
     * set recorded before database v19 -- where absence means "the build could
     * not say", never "the lifter ended it". #249 asked for exactly this
     * distinction and it is what the key is for.
     */
    val durationEndedBy: String? = null,
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
     *  - 6, 4 and 1 are HEADROOM, and from 1.19 the noun they ask in is the
     *    EXERCISE's own, named by [rpeScale]. On a `load` scale the caption
     *    names a figure rather than a notch, because there is no declared
     *    equipment increment anywhere in this codebase and the app cannot know
     *    which is in front of the lifter: 6 "could have added 10-15 lb" or
     *    "5 kg", 4 "20-30 lb" or "10 kg", 1 "much more", the pound band
     *    spanning a bar's 10 lb and a stack's 15 lb. On `reps`: 6 "about 3-4
     *    reps left", 4 "five or more", 1 "many more" -- above the counted end,
     *    which already covers three and below. On `time`: 6 "about 15 s
     *    longer", 4 "about 30 s longer", 1 "much longer". On `feel`: 6
     *    "comfortable", 4 "easy", 1 "very easy", naming no quantity at all.
     *
     * WHICH SCALE THE LIFTER WAS ASKED ON IS RECORDED, in [rpeScale], and a 6
     * cannot be read without it: the same integer is a plate claim on one
     * exercise and a rep claim on another. Absent on a set recorded before
     * 1.19, where the rule is `load` on a dynamic set and `time` on a timed
     * one -- what the app asked then. WHICH UNIT'S caption was on screen is
     * still not recorded: that is a display decision taken at set end, and a
     * reader wanting it reads the session's own unit. [EffortScale] owns the
     * captions; the figures in SECONDS and in REPS are authored rather than
     * measured, while the pound and kilogram bands come from the equipment
     * the lifter actually meets.
     *
     * A TIMED 4 CHANGED MEANING AT 1.19. It was "about a minute longer" from
     * 1.14 and is "about 30 s longer" now, and a timed 6 was "15-30 s longer"
     * and is "about 15 s". Nothing rewrites stored values, so a reader
     * comparing timed headroom across that boundary is comparing two different
     * questions.
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
     *
     * FROM 1.22 (#313) A SET THAT FAILED CARRIES NO RPE, whichever of the two
     * failure facts says so: the owner's rule is that a failed set is not
     * rated, so `FailedSetRatingPolicy.storedRpe` clears a rating standing
     * when a failure arrives -- a hold corrected below 90 % of its target, a
     * count corrected short -- and stores none given beside one. [failedByLifter]
     * is unchanged by it. A set recorded by an earlier build may carry both.
     */
    val rpe: Int? = null,
    /**
     * Which question [rpe] answers, from [SessionExport.VALID_RPE_SCALES]
     * (1.19, #244).
     *
     * `load` a weight the lifter could have added, `reps` a count of reps left,
     * `time` seconds a hold could have run on, `feel` a bare feeling with no
     * quantity. It names the HEADROOM rungs only: the counted end, 7 through
     * 10, is reps in reserve on every scale.
     *
     * ABSENT on a set recorded before 1.19 -- read `load` on a dynamic set and
     * `time` on a timed one, which is what the app asked then -- and absent on
     * any set carrying no [rpe], because a word with no number beside it names
     * a grid that was drawn rather than a rating that was given.
     *
     * It is a fact about the QUESTION and not about the exercise. It does not
     * say what the exercise progresses on: those agree today, and freezing the
     * resolved word rather than the declaration is what keeps a past rating
     * readable if the mapping between them ever changes.
     */
    val rpeScale: String? = null,
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
     * NOT ONLY FAILED SETS (#191). A completed set the lifter rated at the
     * counted end -- `rpe` 7 through 10 -- is asked the same question, and a
     * set carrying this key may therefore have `failed` absent. Read `failed`
     * for whether the set finished; this key says what limited it either way.
     * A completed set rated in the headroom rungs is not asked at all.
     *
     * ABSENT IS NOT AN ANSWER. The page is skippable in one tap and a whole
     * class of set is never asked, so a missing key covers a question
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
     * WHY A READER NEEDS IT. Adherence is read from
     * [SetPrescriptionExport.plannedReps] beside [reps], and from how many
     * sets an exercise carries against how many the plan asked for. An
     * appended set occupying a prescribed slot corrupts both readings at
     * once: it inflates the count, and -- because it has no prescription of
     * its own -- it publishes no [SetPrescriptionExport.plannedReps] either,
     * so it reads as a prescribed set whose prescription went missing.
     *
     * An appended set therefore publishes NO
     * [SetPrescriptionExport.plannedLoadKg],
     * [SetPrescriptionExport.plannedReps] or
     * [SetPrescriptionExport.plannedDurationS], and that absence is a
     * statement rather than a gap: nothing prescribed it.
     * [SetPrescriptionExport.tempoPrescribed] is NOT in that list: it is read
     * from the same run-value rule `load_kg` and `reps` use, not from a
     * frozen plan declaration, so an appended set on a block that declares a
     * tempo publishes it -- naming a tempo nothing prescribed for that
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
     * [SetPrescriptionExport.restS] beside them is the one planned value in
     * this type whose name does not say it is planned, so a reader takes a
     * prescription for an observation. That is issue #76.
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
    /**
     * Which QUESTION [velocityLossPct] is an answer to on this set, drawn from
     * [SessionExport.VALID_VELOCITY_LOSS_REGIMES]. Schema 1.19, #250.
     *
     * `maxIntent`: the lifter drove every concentric as hard as they could, so
     * a slowing rep is a tiring lifter and the figure is fatigue. `controlled`:
     * a tempo fixed the drive's speed, so the figure measures how well the
     * count was held and the set is read on [tempoCompliance],
     * `summary.romSpread_pct` and the rating instead.
     *
     * [velocityLossPct] IS STILL PUBLISHED IN BOTH. The word says how to read
     * the number; it does not withhold it.
     *
     * AND THIS KEY IS PUBLISHED ON EVERY SET THE RULE CAN PLACE A WORD ON,
     * including sets carrying no [velocityLossPct] at all -- one too short
     * for a best-to-last figure, one whose last resolved rep was its fastest,
     * one with no eligible pair (1.22, #306).
     * It names which question the figure WOULD answer, so it stands whether
     * or not the figure came out; that is the opposite of [rpeScale], which
     * is withheld on a set with no [rpe] because a word with no number beside
     * it names a grid that was drawn rather than a rating that was given.
     *
     * DERIVED at export time from [SetPrescriptionExport.tempoPrescribed] and
     * the plane, drive direction and kind of the set's frozen [geometry] --
     * all four already on the row, so no column and no `DATABASE_VERSION`
     * hop. That is the opposite of [rpeScale], which records which question a
     * lifter was SHOWN and cannot be re-derived. [VelocityLossRegime] is the
     * one statement of the rule.
     *
     * ABSENT where the regime is not decidable, which is a state and not a
     * word: a set with no stored [geometry] (every set recorded before that
     * column existed), a hold or a carry, which has no concentric for the
     * question to be about, and a tempo string this build cannot parse. A
     * reader that finds no word reads the set as every reader read every set
     * before this key existed, which is `maxIntent`'s reading.
     */
    val velocityLossRegime: String? = null,
    /**
     * How many detections the analyzer judged were not reps of this set, or
     * absent when no bound could be derived to judge them against. Schema
     * 1.19, issue #125.
     *
     * ABSENT, 0 and a positive number are three different facts. Absent: the
     * set resolved fewer than four detections, so there was no median of
     * others to derive a bound from -- or the set was recorded before this
     * number shipped, which is permanent, because the value is frozen into
     * the stored analysis and nothing re-runs the segmenter at export time.
     * 0: a bound ran and refused nothing. Positive: that many detections were
     * removed from the list every figure in this set is computed over.
     *
     * NOT A COUNT OF THIS SET'S PHANTOMS, and reading it as one is the
     * mistake to avoid. See [SessionExport.SCHEMA_VERSION]'s 1.19 entry for
     * which direction the bound errs in and why.
     *
     * Not gated on `includeRepDetail`. It qualifies `summary`,
     * `velocityLoss_pct` and `velocityLossBasis`, which the summary-only
     * export publishes, so a caveat that appeared only in the detailed
     * artifact would leave that reader holding the figures with the warning
     * removed -- the argument `sensors` and `repMetricsComplete` are
     * published on.
     */
    val refusedDetections: Int? = null,
    /**
     * Why, drawn from [SessionExport.VALID_REFUSED_DETECTION_REASONS], and
     * absent whenever [refusedDetections] is absent or 0.
     */
    val refusedDetectionReason: String? = null,
    /**
     * How many detections finished before this set's WORK began and are
     * therefore not reps of it, or absent when nothing on the record says when
     * the work began. Schema 1.19, issue #245.
     *
     * ABSENT, 0 and a positive number are three different facts, the doctrine
     * [refusedDetections] carries. Absent: the set has no work-start instant --
     * an ad-hoc set that ran no prep, a set ended while its prep was still
     * running, or any set recorded before that instant was stored, which is
     * permanent. 0: an instant bounded this set's head and nothing came before
     * it. Positive: that many detections were removed from the list every
     * figure in this set is computed over.
     *
     * A SEPARATE KEY FROM [refusedDetections] RATHER THAN A SECOND WORD UNDER
     * IT, and the reason is what each ABSENCE means. [refusedDetections] is
     * absent when the set held too few detections for a range bound; this is
     * absent when the set has no instant. A three-detection set with a known
     * instant is in one state and not the other, so one key cannot carry both
     * without making one of the two unsayable -- and
     * [refusedDetectionReason] is a single word that could not name two rules
     * at once either.
     *
     * Not gated on `includeRepDetail`, for [refusedDetections]' reason: it
     * qualifies `summary`, `velocityLoss_pct` and `velocityLossBasis`, which
     * the summary-only export publishes.
     */
    val detectionsBeforeWorkStart: Int? = null,
    /**
     * Samples in this set's analysed IMU stream whose acceleration magnitude is
     * above 4 g of total support acceleration -- readings a lifted implement
     * cannot produce. Schema 1.21, issues #290 and #255. See the property's own
     * description in `docs/schemas/session-export.schema.json` for the rule and
     * the bound's derivation.
     *
     * ABSENT, 0 and a positive number are three different facts, the doctrine
     * [refusedDetections] states. Absent: the set was analysed before the count
     * existed, which is permanent -- it is frozen into the stored analysis and
     * nothing re-runs the estimator at export time. 0: the stream was counted
     * and carried none. Positive: that many samples could not have been
     * measured.
     *
     * OVER THE STREAM, NOT OVER THE REPS, so it is NOT the sum of the per-rep
     * counts under `repMetrics`: it includes samples between detections and
     * samples the set-end and work-start bounds excluded. It answers "how much
     * of this capture could the sensor not have measured"; the per-rep counts
     * answer which reps it reached.
     *
     * Not gated on `includeRepDetail`, for [refusedDetections]' reason: it
     * qualifies `summary.peakConVel_mps` and `summary.peakPower_w`, which the
     * summary-only export publishes.
     */
    val artefactSamples: Int? = null,
    val hr: HrSetSummary? = null,
    /** Per-rep detail; included only when the user enables detailed export. */
    val repMetrics: List<RepMetricsExport>? = null,
    /** Spoken cues with epoch-ms stamps, cross-referenceable with the raw IMU stream (detailed export only). */
    val voiceCues: List<VoiceCue>? = null,
    /**
     * The instants this set's COUNTER advanced, epoch milliseconds on the same
     * clock as the raw IMU, heart-rate and cue streams. Detailed export only,
     * the same terms [voiceCues] is published on.
     *
     * WHICH COUNTER, and so what a mark is, is what [repsSource] says (1.23,
     * #294). On a `metronome` set each mark is the instant the cadence guide
     * finished one prescribed cycle -- the prescribed grid, spaced at the
     * tempo's sum and kept on the guide's own schedule whether or not the
     * lifter moved with it -- so these are NOT instants anyone observed a rep,
     * and a failed or early-ended set can carry more marks than [reps].
     * `RepMarkTrackTest` measures it on the thirteen committed captures that
     * carry marks, all guided: all 103 marks within 1 ms of a row of the same
     * set's cue track, the next cycle's opening stroke word or `Done`. On a
     * `manual` set they are the lifter's taps, and on a straight-rep set
     * carrying no tempo the only per-rep instants in the document: [repMetrics]
     * entries carry no clock and [voiceCues] is what the app SAID.
     *
     * NO TRUE REP INSTANT IS STORED on a guided set, which is why the content
     * is kept and described rather than replaced; the name, and the raw
     * archive's `_reps.csv` holding the same instants, are historical. The
     * opening that stood here called these the instants a rep was counted and
     * said the guide writes a mark as it calls a rep; both are DELETED rather
     * than reworded -- the guide marks the end of its cycle, not its rep call.
     *
     * Absent rather than empty, and the absence is weak. A sensor-counted set
     * produces no marks at all -- a correction made during it writes none --
     * and neither does any set recorded before the app stored them; nothing
     * here tells those two apart, and neither is evidence that no rep was
     * performed.
     *
     * The number of marks may disagree with [reps], in both directions. A
     * rest-screen correction rewrites [reps] and cannot reach a mark already
     * written, and the guide keeps its schedule whether or not the lifter
     * followed it. Where they disagree, [reps] is what the set was recorded as.
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
     * WHAT IT IS MEASURED AGAINST depends on who counted, which [repsSource]
     * now says. On a `manual`, `metronome` or `corrected` set this compares the
     * segmenter with a count a person or the guide kept, and false is the two
     * disagreeing. On a `sensor` set it compares the segmenter with the LIVE
     * detector, which since #301 is a different detector read off the
     * acceleration, not a velocity -- a drive impulse in v0.1.54, a full cycle
     * from v0.1.55 (#305) -- so false there is two DETECTORS disagreeing, and
     * either may be the one that is wrong. On an `analysis` set the two agree by construction and this says
     * nothing. The clause that stood here called the live and batch counts one
     * pairing rule over two velocity estimates; that was v0.1.53's live
     * detector, and the clause is DELETED rather than reworded (#302).
     *
     * The sentence that stood here -- "when [repsManual] is false the stored
     * rep count IS the segmenter's count, so the two agree by construction" --
     * is DELETED rather than reworded. It was true while every such row carried
     * the segmenter's own figure, and stopped being true when a sensor-counted
     * set began recording the live count with [repsManual] false.
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
) {
    init {
        // The twin of the schema's anyOf (#71): a set may go without `reps`
        // only where it is timed -- it carries `duration_s`, or it ended in
        // its prep. The exporter cannot trip this: a timed row either began
        // its work, and then publishes its duration, or did not, and then
        // publishes abandonedInPrep (AbandonedSetPolicy.published).
        require(reps != null || durationS != null || abandonedInPrep) {
            "a set that is not timed must carry reps"
        }
    }
}

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
     * Since #207, widened by #209, it is a role that delivered ENOUGH FRAMES
     * TO ANALYSE wherever one did -- the boundary is when the set was
     * RECORDED, not what the document's `schemaVersion` says: a set armed to
     * analyse a unit that delivered too few frames to analyse is analysed
     * from the unit that delivered enough, and [analysedFellBack] is what
     * says the app moved. Read that key rather than comparing this one with
     * [present], which no longer separates the two cases.
     *
     * It can still name a role absent from [present] in three situations, and
     * none of them has figures drawn from a surviving stream: a set where
     * NOTHING streamed, whose summary is empty because there was no capture;
     * a set whose armed unit delivered NOTHING while the only unit that did
     * stream delivered fewer than
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES], where the analysis stayed
     * on the armed role and [present] is NOT empty; and a set recorded by a
     * build that predates this behaviour, whatever the document's
     * `schemaVersion` says, which kept the armed role whatever happened. Null
     * when no role is in play.
     */
    val analysedRole: String? = null,
    /**
     * True when [analysedRole] is not the role the set armed for analysis,
     * because that unit delivered too few frames to analyse and another one
     * delivered enough (#207, widened by #209).
     *
     * TOO FEW, not none. A unit that put one to seven frames in its buffer
     * still put a file in the raw archive, so a set carrying this key can name
     * the unit it moved off in [present], and [expected] minus [present] is no
     * longer that unit. Nothing in this document publishes a frame count, so
     * this key is what says the move happened, beside
     * [SetSensorsExport.analysedRoleBasis] reading `fallback`, which since #278
     * carries the same fact.
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
     * Which ARMED roles delivered too few frames to analyse across the whole
     * set, keyed by role, with what the app could see of each one's link when
     * the set ended (#213, widened by #209).
     *
     * The boundary is [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES], the point
     * below which the estimator refuses a stream outright, so a role named
     * here may still have a file in the raw archive -- one frame is a file.
     *
     * A sibling of [shortfall] rather than a member of it, because the two
     * answer different questions about different things. [shortfall] is about
     * the device ROSTER before the set -- two paired units the app cannot tell
     * apart -- and says nothing about whether either was switched on. This is
     * about THE SET, is observed at the end of it, and is the only statement
     * in this document about whether an armed unit actually delivered.
     *
     * The ROLE is a key rather than a second list. It was [expected] minus
     * [present] until #209, which a reader could compute; it is now [expected]
     * minus the roles that delivered enough to analyse, which a reader holding
     * only this document cannot. A duplicate list of them would still be the
     * duplicate statement [expected]'s own description refuses. The VALUE is
     * what was always new.
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
     * carries no role, or absent when that link delivered a stream the
     * analysis could run on (1.17, #224; the bar moved from "any frames at
     * all" to "enough frames to analyse" by #209).
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
     * ABSENT MEANS THE SET CAPTURED A STREAM THE ANALYSIS COULD RUN ON from
     * that one link -- at least [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] of
     * it, since #209 -- or, on a set shorter than
     * [ArmedSilencePolicy.SILENT_AFTER_MS], that its last frame arrived during
     * the preceding rest and read as delivering -- or that the document was
     * written by a build that could not observe an unroled link at all. A
     * link that fed enough of a set to analyse and then went silent publishes
     * nothing here either: the word is refused wherever the set's buffer
     * holds a capture the analysis could run on. One that sent fewer than
     * [SensorCapturePolicy.MIN_ANALYSABLE_FRAMES] and stopped is named here.
     * Absent rather than empty on the ordinary set, [analysedFellBack]'s
     * rule.
     */
    val soleSilent: String? = null,
    /**
     * Which physical unit carried each role this set armed, by device address,
     * keyed by role (#260).
     *
     * WHY IT EXISTS. A role is not positional: `SensorCapturePolicy.roster`
     * reads the label the lifter gave each paired unit, so role `a` is one
     * fixed unit for as long as the pairing stands. The lifter holding two
     * identical magnet-mounted WT901 units cannot tell which one that is, and
     * said so when asked which unit was role a: *"I'm not really sure. Will
     * check each time, they're likely to get mixed up a lot."* Until this key,
     * every dual-unit mount inference in the corpus rested on that memory. An
     * address is the only thing about a WT901 that survives a power cycle, so
     * it is the only durable answer, and it lets an analysis say "role a = the
     * unit ending 1D:3F" once the lifter has put those characters on a sticker.
     *
     * DERIVED AT EXPORT TIME, NOT RECORDED WITH THE SET, and no reader may take
     * it for the second thing. Nothing in `RawStreamEntity`, `SetRecordEntity`
     * or the raw archive stores the address a capture came from; this is the
     * pairing store read while the document is being written. Re-labelling the
     * two units between recording a session and exporting it therefore
     * publishes the NEW labelling over the OLD capture, and nothing here dates
     * it. Labels move only by a deliberate tap on the Devices screen -- not by
     * pairing, not by forgetting, not by which unit connects first -- so the
     * ordinary session is labelled correctly; the exposure is why the published
     * description says derived rather than recorded.
     *
     * ONLY THE ROLES THE SET ARMED, which is [expected], and never more. A
     * pairing store holds labels for units a given set never used, so an entry
     * for a role absent from [expected] would attach an address to a capture
     * that carries no role at all.
     *
     * A ROLE TWO ADDRESSES CLAIM HAS NO ENTRY. Forgetting a labelled unit does
     * not clear its label, so pairing a replacement and giving it the same
     * label leaves two addresses claiming one role permanently; one of them
     * recorded the set and nothing can tell which, so the role is omitted while
     * its partner is still named. Absence, never a coin flip published as an
     * identity. [SensorCapturePolicy.unitAddresses] is the rule and the only
     * copy of it.
     *
     * Absent rather than empty wherever there is nothing to say -- a one-sensor
     * set, a set whose stream carries no role, a build or an install with no
     * pairing labelled, and every set exported by a build that predates this
     * key. `encodeDefaults = false` drops the empty map, which is
     * [silent]'s rule and for [silent]'s reason: there is no informative empty
     * here, and an empty object would read as "the app looked and found no
     * units".
     */
    val unitAddresses: Map<String, String> = emptyMap(),
    /**
     * Why [analysedRole] is the role it is: `declared`, `stackSignature` or
     * `fallback`, [AnalysedRoleBasis]'s published spellings (#278).
     *
     * READ WITH [analysedFellBack] AND NOT INSTEAD OF IT. That flag says the
     * armed unit delivered too few frames; this says which of three rules
     * produced the answer, and a `stackSignature` set can name a role the set
     * did not arm with no flag beside it -- the analysis moved because the
     * OTHER unit is the one the set's mount declaration describes, not because
     * anything went quiet.
     *
     * THE FACT A READER CANNOT DERIVE is `declared` on a two-unit set that
     * declared a stack mount: it means the rule RAN AND DECLINED -- neither
     * unit's roll qualified, or both did -- which is a different statement from
     * nothing having looked, and nothing else in this document separates them.
     * `stackSignature` on a set whose analysed role IS the armed one is the
     * mirror case: a confirmation, and not a shrug.
     *
     * Absent on every set recorded by a build that could not decide this,
     * whatever the document's `schemaVersion` says -- the basis is frozen into
     * the row when the set is RECORDED and only copied out at export, so an
     * earlier row has none and is not given a defaulted `declared`, which
     * would claim a rule ran over a set nothing looked at. `encodeDefaults =
     * false` drops the null.
     */
    val analysedRoleBasis: String? = null,
)

/**
 * How the lift moved and how the sensor was mounted, as the app resolved it for
 * this set — not as a plan declared it, because the app applies a precedence
 * chain and a plan's text may have been overridden.
 *
 * This is what makes the rest of the set checkable.
 * [SetPrescriptionExport.tempoPrescribed] is positional notation — digit 1 is
 * the down stroke, digit 3 the up stroke — so which stroke is the eccentric
 * follows from [concentric] and [plane], not from the digit order. Without
 * those, [SetExport.tempoCompliance] is a verdict whose input the reader
 * cannot see.
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
 * ONE of [GeometryExport]'s eight values is missing here: `sensorInverted`.
 * Plan schema 1.12 widened its plan key to `Boolean?`, so a declared `false`
 * and an omitted key are two distinct states there now and it COULD carry a
 * source the way the seven published values do; none is published because
 * that would add a required key to this object for a value no consumer reads
 * yet.
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
    /**
     * Best instantaneous concentric (drive) velocity across the set, m/s.
     *
     * From schema 1.22 (#306) over the PEAK-ELIGIBLE reps only: a rep whose own
     * span carries a sample above the physical bound (`artefactSamples`, 1.21),
     * whose guard band before the span carries one (`guardArtefactSamples`), or
     * whose displacement is not bounded (`romBounded` false) is left out, and a
     * rep carrying none of those keys -- an older recording -- is kept. ABSENT
     * when no rep is eligible, and ABSENT when the best eligible peak falls
     * below [meanConVelMps], which is over every rep: a peak below the set's
     * own mean is not a peak. A consumer taking the max over
     * `repMetrics[].peakConVel_mps` will not reproduce this figure.
     */
    @SerialName("peakConVel_mps") val peakConVelMps: Double? = null,
    @SerialName("meanEcc_s") val meanEccS: Double? = null,
    @SerialName("meanCon_s") val meanConS: Double? = null,
    /**
     * Mean per-rep range of motion, metres. Over the reps whose displacement
     * the analysis can bound, from schema 1.21 -- see `romBounded` on each
     * `repMetrics` row; a consumer averaging `repMetrics[].rom_m` will not
     * reproduce this figure on a set carrying an unbounded rep. Absent, never
     * 0, when NO rep is bounded -- one is enough for a mean, where the
     * dispersion below needs two.
     */
    @SerialName("meanRom_m") val meanRomM: Double? = null,
    /**
     * How far the reps of this set disagree with each other about rom_m: the
     * population standard deviation as a percentage of [meanRomM]. Over the
     * same bounded reps [meanRomM] is, from schema 1.21. Absent, never 0, below
     * two bounded reps or when they average no displacement -- dispersion is
     * undefined there. See SetAnalyzer.romSpreadPct.
     */
    @SerialName("romSpread_pct") val romSpreadPct: Double? = null,
    /**
     * Best instantaneous concentric power across the set, watts. Over the same
     * peak-eligible reps [peakConVelMps] is, from schema 1.22 (#306) -- no
     * `artefactSamples` in the span, no `guardArtefactSamples` in the band,
     * `romBounded` not false -- and ABSENT when none is eligible or when the
     * best eligible peak falls below [meanConPowerW]. A consumer taking the max over `repMetrics[].peakPower_w`
     * will not reproduce this figure.
     */
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
    /**
     * Whether the analysis can BOUND this rep's displacement -- schema 1.21,
     * issue #291. See `RomBound` in `:core:dsp` for the rule and its
     * derivation.
     *
     * [romM] is published unchanged whichever way this reads. FALSE has two
     * causes and does not say which: the drift correction's whole licensed
     * budget was spent across several reps at once, or one of the intervals this
     * rep's span crosses was closed by an anchor taken on starvation, which caps
     * nothing at all. Either way this row's displacement rests on nothing the
     * analysis can state a limit for, and the rep was left out of the set's
     * `summary.meanRom_m` and `summary.romSpread_pct` -- and, from 1.22 (#306),
     * out of `summary.peakConVel_mps`, `summary.peakPower_w` and the pair
     * `velocityLoss_pct` is taken over, since a mean drive velocity is this
     * rep's displacement over its drive time. TRUE DOES NOT SAY THE
     * DISTANCE IS RIGHT: the bound is on what the correction was licensed to
     * remove, never on the residual an uncorrected bias leaves, and it is that
     * licence PER INTERVAL the rep's span crosses rather than 0.10 m per rep.
     *
     * Absent when the rep was analysed before the question was asked, which is
     * permanent -- the answer is frozen into the stored analysis and nothing
     * re-runs the estimator at export time. Absent and false are different
     * facts, and an absent flag KEEPS the rep in both summary populations.
     */
    @SerialName("romBounded") val romBounded: Boolean? = null,
    @SerialName("peakPower_w") val peakPowerW: Double? = null,
    @SerialName("meanConPower_w") val meanConPowerW: Double? = null,
    /**
     * Samples inside THIS rep's own span -- both phases and the turnaround
     * between them -- above 4 g of total support acceleration. Schema 1.21,
     * issues #290 and #255.
     *
     * A positive count says [peakConVelMps] and [peakPowerW] ON THIS ROW were
     * taken across a reading the sensor cannot have measured, and that this rep
     * was therefore left out of the set's `summary.peakConVel_mps` and
     * `summary.peakPower_w` -- and, from 1.22 (#306), out of the pair
     * `velocityLoss_pct` is taken over. The row still publishes what its own
     * window measured; this is the key that says not to trust the pair.
     *
     * Absent when the rep was analysed before the count existed, which is
     * permanent. Absent and 0 are different facts: 0 is a counted clean span.
     */
    @SerialName("artefactSamples") val artefactSamples: Int? = null,
    /**
     * Samples above 4 g of total support acceleration in the GUARD BAND before
     * this rep's span: the 0.4 s that end where the span begins, the span
     * excluded. Schema 1.22, issue #306.
     *
     * [artefactSamples] counts inside the span. This counts just before it,
     * because a deadlift's floor contact sits there: the pull that follows it
     * opens within a few hundredths of a second, carries no sample above the
     * bound itself, and on field-44 set 4 published 7762.4 W at 111.1 kg. A
     * positive count left this rep out of the set's peak pair and the
     * `velocityLoss_pct` pair. The band can reach into the previous rep's span,
     * so one sample can be counted here and in that rep's [artefactSamples].
     *
     * THE BAND IS MEASURED: 0.362 s is the longest a floor contact rang above
     * 2 g on the sixteen deadlift streams the analysis was checked against, and
     * 0.4 s is that rounded up. It says where ringing was measured to reach,
     * not how far it moved a velocity.
     *
     * Absent when the rep was analysed before the band existed, which is
     * permanent; absent and 0 are different facts, and an absent count KEEPS
     * the rep in both populations.
     */
    @SerialName("guardArtefactSamples") val guardArtefactSamples: Int? = null,
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
