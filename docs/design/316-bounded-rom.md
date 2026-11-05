# Bounded rep range of motion (issue #316): design note

**Status.** Design round for release 0.2.0. Nothing here changes `RomBound`, the segmenter, #306's
peak/velocity-loss gating, or any `DspConfig` constant. It needs no contract number: no export
key, database column or plan field. The evidence comes from the committed corpus under
`core/dsp/src/test/resources` and nowhere else. The figures are pinned in
`core/dsp/src/test/kotlin/com/macrophage/barspeed/dsp/RomBoundCauseCorpusTest.kt`.

**Re-derive every figure below:**

```
./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.RomBoundCauseCorpusTest"
```

They were measured at `8cbac5b80cbc54f960779839bdadaabcff04700c` (lane branch
`claude/v020-l15-rom-design`, on top of `claude/release-0.2.0` at
`4ab115f62738c71763f8012a7d6e4cab825d7507`). The test is a pure characterization pin. It
asserts what the shipped pipeline does and sets no target.

## 1. What the corpus can and cannot answer

| capture named in #316 | enrolled? | what is on the classpath |
|---|---|---|
| field-44 (deadlift day, 10 sets / 77 reps in the issue) | **partly** | the five deadlift sets, `field-deadlift-straight-*-s44-set01..05` (raw IMU, role-b IMU, cues), with per-rep truth in `DeadliftTruth` |
| field-45 (accessory day, 15 sets / 180 reps) | **no** | no capture. Only two sets' exported rows, typed into `PeakEligibilityTest` |
| field-43 (deadlift sets 4-6) | yes | raw IMU, cues, truth |
| field-46 (back squat sets 1-5) | yes | raw IMU, role-b IMU, cues. No per-rep truth |

Two consequences:

- The accessory-day cause cannot be re-measured here. Field-45 is a `[Field]` item (section 6).
- The issue's table counts 5 `romBounded: true` rows on field-44, read off the v0.1.54 export.
  Re-analysed at the SHA above, the five enrolled field-44 deadlift sets bound **0 of 38** spans.
  The five `true` rows could sit in field-44's five non-enrolled sets, or the export's stored
  analysis could differ from a re-analysis at this tree. Nothing on the classpath tells the two
  apart, so this goes to `[Field]` and is not explained here.

## 2. Why `RomBound` refuses, clause by clause

`RomBound.bounded` passes a span only when all three of these hold:

- an accepted anchor follows it (AFTER_LAST when none does);
- every inter-anchor interval in its bracket was closed by an anchor that `anchorAcceptable`
  accepted (UNCAPPED when a starvation anchor closed one);
- no other span lies inside the bracket (SHARED).

The pin works out the three flags separately. Its licence is that its verdict equals
`SetAnalyzer.analyze`'s `romBounded` count on every capture in `CandidateCorpus.ALL`.

### 2.1 Whole corpus (every `CandidateCorpus.ALL` capture)

| spans | bounded | AFTER_LAST | UNCAPPED | SHARED (with an anchor after) | UNCAPPED, not SHARED | SHARED alone | UNCAPPED with ≤ 0.10 m erased inside the span |
|---|---|---|---|---|---|---|---|
| 528 | 6 | 73 | 445 | 394 | 55 | 4 | 30 |

### 2.2 Per capture, deadlift and back-squat days

| capture | spans | bounded | AFTER_LAST | UNCAPPED | SHARED |
|---|---|---|---|---|---|
| field-44 set 1 (5 reps) | 6 | 0 | 0 | 6 | 6 |
| field-44 set 2 (5 reps) | 7 | 0 | 0 | 7 | 7 |
| field-44 set 3 (5 reps) | 13 | 0 | 0 | 13 | 10 |
| field-44 set 4 (4 reps) | 8 | 0 | 0 | 8 | 6 |
| field-44 set 5 (2 reps + 1 failed) | 4 | 0 | 0 | 4 | 4 |
| field-43 set 4 | 9 | 0 | 1 | 8 | 7 |
| field-43 set 5 | 7 | 0 | 7 | 0 | 0 |
| field-43 set 6 | 7 | 0 | 2 | 5 | 5 |
| field-46 set 1 (squat) | 6 | 0 | 6 | 0 | 0 |
| field-46 set 2 | 5 | 0 | 0 | 5 | 5 |
| field-46 set 3 | 6 | 0 | 6 | 0 | 0 |
| field-46 set 4 | 6 | 0 | 0 | 6 | 6 |
| field-46 set 5 | 7 | 0 | 0 | 7 | 7 |

### 2.3 Per rep on the eight deadlift sets, by `DeadliftTruth` label

| label | spans | bounded | AFTER_LAST | UNCAPPED | SHARED |
|---|---|---|---|---|---|
| REP (completed) | 41 | 0 | 7 | 34 | 32 |
| NOT_REP | 19 | 0 | 3 | 16 | 12 |
| FAILED | 1 | 0 | 0 | 1 | 1 |

There are 41 REP spans for 36 completed reps because the batch path splits some lifts in two.
`DeadliftTruth` merges those, field-44 set 4's first lift among them. On the 34 UNCAPPED REP
spans, the drift correction erased between **0.216 m and 5.728 m inside the rep's own span**,
against the 0.10 m `anchorAcceptable` licenses. Every one is above the budget.

### 2.4 Reading: segmentation, drift, or the bound itself

- **Drift is what refuses the completed deadlift reps.** All 41 REP spans fail a drift clause
  (AFTER_LAST or UNCAPPED), and the erasure inside each is metres. On a working set the bar never
  goes quiet long enough for an acceptable anchor, so the whole working window is one interval,
  closed by starvation or not closed at all. This agrees with the 2026-09-25 comment on #316 and
  with `RomBound`'s own KDoc. The bound is right to refuse them.
- **Segmentation is wrong on part of the population, and that is not the cause.** 19 of the 61
  labelled deadlift spans are NOT_REP: plate handling, bounces, the bar roll, set-downs. Some
  completed lifts are split in two. Removing every segmentation error would leave the completed
  reps exactly as refused, because their own clauses fail.
- **The bound itself is stricter than its derivation in two places, and neither reaches the
  deadlifts.**
  - The SHARED clause. Inside one interval the offset is a single-signed linear ramp, so the
    displacement it erases over any sub-span is at most the interval's total `0.5·dv·dt`. When
    that interval is capped, the total is at most `minRomM`. A rep sharing a capped interval is
    therefore bounded by the same 0.10 m per crossed interval as a rep alone in it. This
    contradicts `RomBound`'s KDoc sentence that a shared interval "bounds none of them
    individually". Four corpus spans fail on SHARED alone (field-bench-rotating-6rep-ok and
    field-rdl-3010-10rep, two each) and **none is a deadlift rep**.
  - Erasure measured per span. 30 UNCAPPED spans had at most 0.10 m erased inside their own span, and **none is a completed deadlift rep** (the
    minimum there is 0.216 m). Section 3.2 shows why a per-span erasure figure would not license
    a rep anyway.

  So relaxing either would move non-deadlift figures and leave #316's success measure where it
  is. This note does not propose either for 0.2.0. The SHARED-clause observation is raised here
  for review. If it is accepted, the clause and its KDoc change in their own red-gated round.

## 3. Where a bounded span could come from

### 3.1 Floor-cycle spans from the full-cycle counter (#305)

Each `CycleRepCounter` call on the eight deadlift sets (36 calls, the production rule through
`ClosingFrames`/`CycleCandidate` as `ClosingRuleCandidateTest` licenses it) gives one span:

- it opens at the call's drive start: the bar on the floor before a dead-stop pull, on the
  counter's own premise;
- it closes at the first sample at or after the call that `isQuietSample` accepts, bounded by
  the next drive.

Both ends are taken as rest, so the true velocity change across the span is zero and the raw
`dv` is integration error. The velocity is detrended linearly to zero at both ends and then
integrated.

| measure over the 36 cycles | result |
|---|---|
| return to the floor within `minRomM` (\|rise − lowering\| ≤ 0.10 m) | **0 of 36** |
| \|closure\| min / median / max | 0.143 / 1.173 / 2.987 m |
| rest-to-rest erasure `0.5·|dv|·T` within the `anchorAcceptable` budget | 1 of 36 (field-44 set 2, call 0) |

**The evidence does not support floor-cycle spans as a bounded source on this corpus.** This
re-measures the 2026-09-25 comment's retraction on a fixed, pinned definition. That comment used
a 10 cm rise-against-lowering check and found 0 of 5 field-44 sets and 0 of 3 field-43 sets.

### 3.2 Why the erasure cap alone cannot license a floor-cycle span

The one cycle inside the erasure budget, field-44 set 2's first rep, has a net
`dv = −0.026 m/s` and erases 0.054 m. It still ends **1.094 m** above where it began. Its net
error is small only because large errors of opposite sign cancel.

A scratch split of each cycle's `dv` illustrates this. It came from an uncommitted harness and
is not pinned, so treat it as illustration only. The ~0.35 s
around each floor contact carries +1.0 to +3.3 m/s, and the rest of the cycle −0.4 to −2.8 m/s.
That split does not separate error from the bar's genuine deceleration at landing, so no claim is
made about which part is wrong. It only shows why a net figure can understate the error. A rep
can pass the cap and still fail closure.

**Any future licence for a floor-cycle span must therefore test closure, not only erasure.**

### 3.3 Concentric-only spans (floor rest to lockout rest)

This would integrate only the pull, where less error can accumulate than over the whole cycle:
the counter's drive runs last 0.29 to 0.98 s on these sets. It needs a rest window, a run of `isQuietSample` at least `minStationaryS`
(0.30 s) long, both on the floor before the pull and at lockout. Of the 36 calls, **none has
both**, 2 have only the floor run and 3 only the lockout run. The anchor supply is not there.
Single quiet samples are not rest: on the scratch run, the first quiet sample after the drive
often sat within 0.1 m of the floor, which is a bar moving at roughly constant speed.

### 3.4 A drive-gain figure instead of a velocity

The full-cycle counter already integrates each drive's acceleration into a velocity gain. A
velocity loss taken over drive gains would need no displacement at all. But it is **not** the
published `velocityLoss_pct`, which is defined over mean concentric velocity. Publishing it
would be a new export key, so it would need export schema 1.26, which only lane L1 mints.
Nothing in the corpus can check it against a truth. **Left unbuilt.** It needs an owner decision
and its own contract round.

## 4. Recommendation for 0.2.0

1. **Keep withholding.** Under the owner's ruling, a figure the analysis cannot bound is withheld.
   No candidate measured here bounds a completed deadlift rep on the committed corpus. Velocity
   loss stays `noEligiblePair` on the deadlift sets. The per-rep rows keep their values and flags,
   so nothing is lost from the record.
2. **Keep the pin as the re-measurement.** `RomBoundCauseCorpusTest` iterates
   `DeadliftTruth.SETS`. Enrolling a new deadlift capture there with its truth windows re-runs
   every deadlift table above on it. The publishing lane opens when an enrolled capture shows
   completed reps whose floor-cycle spans close within `minRomM`. That is the condition the
   2026-09-25 tracking comment set, now made mechanical.
3. **Review the SHARED-clause observation (section 2.4)** in its own round. It does not affect
   the deadlifts.

Nothing ships for #316 in 0.2.0 beyond this note and the pin, and no user-visible change follows.

## 5. Success measure

> Velocity loss is published on field-44's deadlift sets, and the reps it is taken over match the
> owner's hand counts: 5, 5, 5, 4 and 2 completed reps on sets 1-5, with set 5's third pull a
> failed attempt.

- **Today, measured:** not met. At the SHA above, 0 of field-44's 38 deadlift spans are bounded,
  so no set has the two eligible reps velocity loss needs.
- **`[Field]`:** whether a published figure is *right*. The hand counts are counts, not
  velocities. Checking a published velocity loss needs a per-rep velocity reference the corpus
  does not have, for example the owner's own reading of each rep on video or against a second
  instrument. Pass criterion, readable in a gym:
  - which sets: field-44 deadlift sets 1-5, conventional, from the floor, dead stop;
  - what must hold: a velocity loss is published on each set and is taken over exactly the reps
    the owner counted;
  - what to compare: the first and last counted reps' mean concentric velocities, each within
    0.05 m/s of the reference.

## 6. `[Field]`

- **Field-45 is not enrolled.** It needs the raw per-set captures from the owner's machine. Then
  check:
  - whether the accessory-day cause is the one the 2026-09-25 tracking comment states (12 of 15
    sets with no anchor window inside the set that passes the drift cap);
  - which clause of section 2 refuses its reps.
- **Field-44's five non-enrolled sets.** Check whether the five `romBounded: true` rows of the
  v0.1.54 export sit there. If they do, which clause passed them? A re-analysis at this tree
  bounds none of the enrolled five.
- **Lifts that do not start from the floor.** Every floor-cycle route assumes a dead-stop floor
  start. No plan field declares one today, and that is unmeasured.
- **The success measure's correctness half**, section 5.
