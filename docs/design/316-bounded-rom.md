# Bounded rep range of motion (issue #316): design note

**Status.** Design round for release 0.2.0. Nothing here changes `RomBound`, the segmenter, #306's
peak/velocity-loss gating, or any `DspConfig` constant. It needs no contract number: no export
key, database column or plan field. The issue's measure of success is velocity loss (section 5).
Set peaks, which #306 gates on the same eligibility, stay withheld; nothing here changes them.
The evidence comes from the committed corpus under `core/dsp/src/test/resources` and nowhere
else. The figures are pinned in
`core/dsp/src/test/kotlin/com/macrophage/barspeed/dsp/RomBoundCauseCorpusTest.kt`.

**Re-derive every figure below:**

```
./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.RomBoundCauseCorpusTest"
```

They were measured at `c976c1a9cbacb69a6519808f0f0dfea7246fce6a` (lane branch
`claude/v020-l15-rom-design`, on top of `claude/release-0.2.0` at
`4ab115f62738c71763f8012a7d6e4cab825d7507`), locally with `-PjvmOnly`. CI runs the same class on
every push to the lane branch. The test is a pure characterization pin. It asserts what the
shipped pipeline does and sets no target.

## 1. What the corpus can and cannot answer

| capture named in #316 | enrolled? | what is on the classpath |
|---|---|---|
| field-44 (deadlift day, 10 sets / 77 reps in the issue) | **partly** | the five deadlift sets, `field-deadlift-straight-*-s44-set01..05` (raw IMU and role-b IMU on all five; cues on sets 1-4, none for set 5), with per-rep truth in `DeadliftTruth` |
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
`RomBound.bounded` span by span, and its bounded count equals `SetAnalyzer.analyze`'s
`romBounded` count, on every capture in `CandidateCorpus.ALL`.

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
  (AFTER_LAST or UNCAPPED). On the 34 UNCAPPED ones the correction erased 0.216 to 5.728 m inside
  the rep's own span; the 7 AFTER_LAST ones have no closing anchor at all. On a working set the
  bar never goes quiet long enough for an acceptable anchor, so the whole working window is one
  interval, closed by starvation or not closed at all. This agrees with the 2026-09-25 comment on
  #316 and with `RomBound`'s own KDoc. The bound is right to refuse them.
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
    field-rdl-3010-10rep, two each, pinned by capture) and **none is a deadlift rep**.
  - Erasure measured per span. 30 UNCAPPED spans had at most 0.10 m erased inside their own
    span, and **none is a completed deadlift rep** (the minimum there is 0.216 m). A per-span
    erasure figure also says nothing about closure; section 3.2 states what the corpus does and
    does not show on that.

  So relaxing either would move non-deadlift figures and leave #316's success measure where it
  is. This note does not propose either for 0.2.0. The SHARED-clause observation is raised here
  for review. If it is accepted, the clause and its KDoc change in their own red-gated round.

## 3. Where a bounded span could come from

### 3.1 Floor-cycle spans from the full-cycle counter (#305)

Each `CycleRepCounter` call on the eight deadlift sets (36 calls, the production rule through
`ClosingFrames`/`CycleCandidate` as `ClosingRuleCandidateTest` licenses it) gives one span. These
are calls, not reps: by the `DeadliftTruth` window nearest each drive, 35 fall on REP windows and
1 on a NOT_REP window (field-43 set 6, call 0).

- It opens at the call's drive start: the bar on the floor before a dead-stop pull, on the
  counter's own premise.
- It closes at the first sample at or after the call that `isQuietSample` accepts, searching no
  further than the next call's drive start.

Both ends are *treated* as rest, so the raw `dv` across the span is read as integration error, and
the velocity is detrended linearly to zero at both ends and then integrated. The end is at best a
single quiet sample, not a `minStationaryS` run, which section 3.3 says is not rest. What closed
the 36 spans:

| span end | calls |
|---|---|
| a quiet sample | 25 |
| the call itself, not quiet: the call came after the next drive had started, so no search ran | 5 |
| the next call's drive start, with no quiet sample before it | 6 |
| the capture's end | 0 |

6 spans run past the next drive's start: the 5 above, and one whose call sample was itself quiet
(field-43 set 6, call 1). Those six spans take in part of the next lift. Why the call lands that
late is an inference, not measured: one of the counter's closes is the next *armed* drive, and a
drive arms after it has started (`CycleRepCounter`'s KDoc), so such a call would land inside the
next pull. `RepClosed` carries no close reason, so a contact close in the same gap is not ruled
out.

| measure over the 36 cycles | result |
|---|---|
| return to the floor within `minRomM` (\|rise − lowering\| ≤ 0.10 m) | **0 of 36** |
| \|closure\| min / median / max | 0.143 / 1.173 / 2.987 m |
| rest-to-rest erasure `0.5·|dv|·T` within the `anchorAcceptable` budget | 1 of 36 (field-44 set 2, call 0) |

**The evidence does not support floor-cycle spans as a bounded source on this corpus, on this
span definition.** Every figure is scoped to the definition above, ends included. A scratch
variant that closed every span at the call instead found 0 of 36 too (minimum \|closure\| 0.200 m);
that variant is not pinned. This re-measures the 2026-09-25 comment's retraction on a fixed,
pinned definition. That comment used a 10 cm rise-against-lowering check and found 0 of 5 field-44
sets and 0 of 3 field-43 sets.

The same comment's leftover, that the last rep of field-44 sets 1 and 4 rises less than it lowers
to its closing contact, is **not** explained here and stays open under #316.

### 3.2 What the one cycle inside the erasure budget shows

The one cycle inside the budget, field-44 set 2's call 0, has a net `dv = −0.026 m/s` and erases
0.054 m. Its reconstructed displacement ends 1.094 m from its start and never descends (lowering
0.000 m). But it is one of the six spans that run past the next drive's start: it ends, not quiet,
at a call that came after the next pull had begun. It is not a floor-to-floor cycle, so it is no
evidence either way about whether a span that passes the erasure cap can fail closure. A round-1
draft of this note and of the pin read it as two large errors cancelling; nothing measured which
part of `dv` is error, and that reading is withdrawn.

A scratch split of each cycle's `dv`, from an uncommitted harness and not pinned: on 30 of the 36
cycles the samples from 0.05 s before to 0.3 s after a contact carry +1.0 to +3.3 m/s of dv; on the
other six the split is smaller or has no contact window at all. That split does not separate error
from the bar's genuine deceleration at landing, so no claim is made about which part is wrong.

**A future licence for a floor-cycle span should test closure as well as erasure, and its span
must end before the next drive starts.** The first is a design requirement: a net `dv` figure
cannot by itself show that the displacement closed. This corpus does not supply a clean case of a
span passing one and failing the other.

### 3.3 Concentric-only spans (floor rest to lockout rest)

This would integrate only the pull, where less error can accumulate than over the whole cycle:
the counter's drive runs last 0.29 to 0.98 s on these sets (from the uncommitted scratch harness,
not pinned). It needs rest on both sides. Rest is
read the way `applyZupt` reads it, `VelocityEstimator.quietMask`: a run of anchor-candidate samples
at least `minStationaryS` (0.30 s) long. Single quiet samples are not rest: an accelerometer cannot
tell rest from constant velocity (`VelocityEstimator.anchorAcceptable`'s KDoc), which is why rest
is read as a run.

A call has a floor rest when a mask sample lies between the previous call (the capture's start for
the first call) and the drive's start. It has a top rest when one lies between the drive's end and
the call, or the next call's drive start if that comes first: on a call that lands past the next
drive's start, a mask sample after that start belongs to the next pull. Where the bar was in either
window is the counter's premise, not something measured here. The floor window is empty by
construction for a call whose previous call landed after this drive started, so those calls cannot
count a floor rest.

| over the 36 calls | calls |
|---|---|
| floor rest and top rest | **4** |
| floor rest only | 13 |
| top rest only | 3 |

Correction, mine: this note at `6e979760` gave 4 top-only calls. Its top window ran to the call
without stopping at the next drive's start, and field-43 set 6's call 1 counted as top-only on a
mask sample inside the next pull. Capping the window drops it. The other two rows are unchanged.

The four with both are the first call of field-44 sets 4 and 5 and field-43 sets 4 and 6, so the
floor rest is the setup before the set's first pull. Field-43 set 6's call 0 is the one NOT_REP call
of section 3.1, so three of the four are completed reps. That call also lands past the next drive's
start, but its top-rest sample comes before that start, so its figure stands. From the last
floor-rest sample to the first top-rest sample (1.41 to 2.52 s), `0.5·|dv|·T` is **0.236 to
2.123 m**, every one above the 0.10 m `anchorAcceptable` budget. So the supply is thin, and where it
exists the span between the two rests does not pass the cap. This route does not bound a completed
rep on this corpus either.

Correction, mine: round 1 of this note said "none has both, 2 have only the floor run and 3 only
the lockout run". That search walked to the quiet sample nearest the drive on each side and judged
only that run's length, so a short quiet stretch next to the drive hid a longer rest further out.
It is still pinned, labelled as that definition, and it is not how `applyZupt` reads rest.

Issue #316's 2026-10-03 comment reports 8 of 36 deadlift reps with a quiet window of at least
0.3 s "on both sides". Its definition and population are not stated, so it is not re-derived here,
and neither 4 of 36 nor the retracted 0 of 36 is the same claim.

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

Velocity loss is the figure that matters on these sets by the owner's ruling quoted in #250's
2026-09-04 comment: "When the concentric digit is X, then it matters, or if it's straight reps or
explosive. Otherwise I don't look at it." Field-44's deadlifts are straight reps.

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

## 7. Back squat, 2026-11-06 (app 0.2.2): withheld by the owner's ruling

**What was asked.** Robocoach reported no range-of-motion data for the 2026-11-06 back squat
session. Every squat rep in that export carries `romBounded: false`, with `rom_m` running from 0.14 to 1.8 m,
so `summary.meanRom_m` and `romSpread_pct` are absent on all seven squat sets. The seated leg curl
sets in the same export were bounded and published both figures.

**How it was measured.** The seven squat sets are enrolled under
`core/dsp/src/test/resources/live-squat-v022/` (role a, role b, cues; commit `146bdb8` on
`claude/project-thread-jz3ujf`). `VelocityEstimator`'s anchor walk was replayed window by window
on all fourteen streams in a throwaway test that was not committed. The figures below are from
that replay at that commit.

**Why nothing is bounded:**

1. **No capped anchor inside any working window, on all fourteen streams.** The accepted anchors
   sit before the first rep or after the re-rack. The anchor that closes each working window is
   taken on starvation, which caps nothing (section 2's fourth clause).
2. **The bar at rest drifts faster than the cap.** In the rack after the set, raw velocity is flat
   to 0.004-0.017 m/s across a 0.3 s window. That makes the windows `stable`, but the drift since the
   previous anchor runs at about 0.05-0.10 m/s2 against `anchorAcceptable`'s 0.05 m/s2 bias-rate
   cap, so they are refused. The 0.02 m/s stability band independently refuses any window drifting
   faster than about 0.067 m/s2.
3. **The rep spacing makes the displacement cap unreachable.** Reps sit about 4 s apart. Bounding
   one across a 4 s interval needs `0.5 * dv * dt <= 0.10 m`, so drift under about 0.0125 m/s2,
   or 0.13 % of g. Nothing in this session comes close to that.
4. **Plate-knock spikes are present but not binding.** Every squat stream carries 2-6
   single-sample readings of 5-22 g, mostly on both units at the same instant, so the cause is
   mechanical. Each one steps the integrated velocity by up to about 1.5 m/s. Holding the last
   in-range sample in their place changes `rom_m` a lot but adds no anchor on any stream.

**Ruling.** On 2026-11-06 the owner was offered three options: a separately flagged depth estimate,
keep withholding, or look for another signal first. He chose **keep withholding**. Loosening
the caps would publish depths that could be off by about 0.3 m, and no reference depth exists to
check an estimate against. The schema's `summary.meanRom_m` description now tells a consumer to
expect the figure absent on bar-mounted back squats.

**Adjacent and not changed here:** about half of every squat stream's frames repeat the frame
before them exactly, so the unique rate is about 50 Hz at a nominal 99 Hz. The `romSpread_pct`
description's statement that no seated leg curl rep is bounded is contradicted by this export's
leg curl sets. That statement was measured on the committed leg-curl fixtures, not on these sets.
