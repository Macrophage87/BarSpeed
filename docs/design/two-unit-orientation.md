# Two units, one frame: per-set orientation normalisation

Design note for issue #185. **Nothing here is implemented, and nothing here is
the app's to implement.** It records how an offline analysis should put two
bar sensors' streams into a common frame, set by set, and when it must refuse
to. It is written down so that whoever builds it starts from the measurements
already taken rather than re-deriving them, and so that the refusals are
designed in before the first number is published.

## Who does it, and what the app owes

Settled by the owner on 2026-08-30, quoted on #185: *"It's fine to stay out of
the app so far, as post hoc processing can often be better at denoising than
in stream."* Post hoc has the whole set, can filter forwards and backwards with
no phase lag, and can be re-run when the first method turns out wrong. So:

- **The normalisation is the analysis's.** The app computes no gravity vector,
  no stillness score and no transform.
- **The app owes an honest capture, and it already delivers one.** Both role-
  tagged IMU streams per set (`setNN_*_imu-a.csv`, `-imu-b.csv`), and, since
  4116e198753f1e3bae2d8a2eb2914397fa46be1d (v0.1.48), for each set that ran a
  prep, its `prepStartedAt_ms` and `workStartedAt_ms` in `meta.json` on the
  same epoch-ms clock as the CSVs' `timestamp_ms` column, plus a `prep`
  raw-stream row carrying the same pair. `beginSet` clears the IMU buffers
  before the prep countdown starts, so the prep window's samples are in the
  stored stream and the archive.
- **Anything derived is published beside the raw streams, never in place of
  them.** The raw CSVs are the record; a transform is an opinion about them
  that a better method may later replace. If a derived transform is ever
  written into an archive, it goes in its own file or key, names the method
  and its version, and leaves every raw byte as captured.

## What has been measured

| capture | what it shows |
|---|---|
| field-34 (v0.1.44), two back-squat sets | Units not in a common frame and not constant across the session: pitch difference +52.9 deg (sd 41.1) then +41.1 deg (sd 28.2); vertical-acceleration correlation r = +0.78 then **-0.98**. Three of four streams had **no** leading still window (gyro magnitude < 8 deg/s). |
| field-35, calibration, both units stationary | Gravity alone recovers the stated mounting to 1-5 deg: both flat 1.3 deg apart, one turned over 178.7 deg, one on its side 95.3 deg, and says which unit moved. No within-set drift. Per-unit scale: \|g\| reads 0.997-0.999 on `a`, 1.019 on `b`. |
| field-36 set 1 | az correlation -0.690, pitch difference +117.9 deg with sd 23.5 deg: not constant within the set either. |
| field-38 sets 12-13 | The first true co-mount (both on one stack): orientation-free correlation +0.966 / +0.994, yet per-rep `rom_m` disagrees by 39.7% and 22.5% median. Four distinct transforms in one session, one a sign flip. |
| field-42 sets 1-7, 14-16 | Ten co-mounted pairs, and the transform has a **time** term: role a's content 600-620 ms late on that session's starved 44 Hz link (#292). |
| field-41, 43, 44, 45 | One-body lags within -120 to +80 ms bar one set, sign wandering between sets. |

`ImuSample`'s KDoc in `:core:model` is the canonical statement of what an
arrival stamp is and of the #292 numbers; this note does not restate more of
them than the method needs.

## The method, per set, in order

Every step names its refusal. A refusal is an output, never a fitted average:
the analysis states which step refused and why, and publishes no transform for
that set.

### 1. Align the clocks first

Both streams are stamped on arrival, and one link of five ran 600 ms behind
(#292). No orientation estimate that uses both streams at once is meaningful
until the two are on one time base.

- Estimate the lag per set: gyro magnitude (a rigid-body invariant, so it
  needs no orientation), 20 ms bins, empty bins forward-filled, lags scanned
  over +/-2 s, over the working window `workStartedAt_ms` to the set's end.
- **Only on a one-body set.** The magnitudes are one signal only when both
  units ride one rigid body; #292 used the gyro RMS ratio a/b, which read
  0.96-1.02 on co-mounted sets and 0.01-0.02 or above 7 when one unit was still
  or elsewhere. A set outside a declared ratio band gets no lag estimate.
- **Refuse:** ratio outside the band (not one body), or peak correlation below
  a declared floor, or a peak that is not stable across the set's thirds (the
  check that told #292's constant offset from drift).

Steps 2 and 4 use each unit's stream on its own and do not need this step;
step 3's yaw estimate and any sample-by-sample comparison do.

### 2. Gravity in the pre-set still window: two of three axes

- Window: `prepStartedAt_ms` to `workStartedAt_ms`, taken from `meta.json`
  rather than inferred from the samples. A set with no stored window has no
  input here: one recorded before v0.1.48, one ended during its lead-in, or
  one that ran no prep at all (no tempo, not timed) -- `PrepWindowPolicy`
  stores no window for it, and that is the ordinary ad-hoc set, not the
  exotic one. Expect most sets of an unplanned session to refuse at this step.
- Within it, keep samples whose gyro magnitude is below a declared still
  threshold (field-34's reading used 8 deg/s) and require a minimum continuous
  still span. Field-34 set 1 b had 3.39 s (340 samples) where it had one at
  all; field-35's calibration was still throughout.
- Each unit's mean accelerometer vector over that span is its gravity vector.
  Its direction fixes the unit's tilt; field-35 shows that is good to about
  1-2 deg on these units.
- Its magnitude is a per-unit scale factor, published beside the direction:
  field-35's 0.997 against 1.019 is a 2% difference that would bias any
  magnitude comparison between the units if left in.
- **Refuse:** no stored window; no still span long enough inside it; a still
  span whose \|g\| is far from 1 (the unit was not still, or is reading badly).

### 3. The third axis

Gravity says nothing about rotation **about** the vertical, so step 2 leaves
each unit's heading free.

- **The parallelism question does not need it.** Whether the two ends of a bar
  rise and fall together needs each unit's acceleration projected onto its own
  gravity axis; heading does not enter that projection. An analysis that only
  asks that question stops after step 2 and publishes a two-axis result,
  labelled as such.
- **A full transform needs it, and only a one-body set can supply it.** On one
  rigid body the two units see the same angular-velocity vector expressed in
  two frames, so after step 1's lag correction the heading is the rotation
  about the shared vertical that best aligns the two gyro vectors over the
  working window (a least-squares rotation fit, constrained to the vertical
  axis step 2 fixed). Field-38 sets 12-13 and field-42's ten co-mounted pairs
  are the material to develop it on.
- **Refuse the third axis** (not the whole set) when the set is not one body,
  when step 1 refused, or when the fit's residual is above a declared bound.
  The two-axis result from step 2 still stands.

### 4. Refuse when a unit moved mid-set

A transform estimated before the work is a statement about the start of the
set. A unit that rotated on its mount during the set has no single transform,
and the honest output is a refusal (#185's body), not a transform fitted to a
blend of two mountings.

- Repeat step 2 over a closing still window, after the set's cadence `Done`
  -- the boundary `SetEnd.of(cues, cadenceGuided = true)` returns, not
  `calledOver` -- and before the stream ends: the eleven completed sets of
  session 32 record 4.3-13.7 s past it (recorded on `SetEnd`). Only a set
  that ends on a cadence `Done` has samples past the boundary `SetEnd.of`
  returns. A set ended by tap has none -- `endSet` cancels the sample
  collectors before it reads the clock, so its last sample is never later
  than the tap -- and that covers a tap-ended guided set (`Stopped`). An
  unguided rep set carries no word that bounds it: with the voice on it records
  only `Rep N` / `Last rep` milestones (no planned count, as on every
  sensor-counted set, or a lifter who stops short of it), with the voice
  off nothing; or, at a reached planned count, `Done` as the rep-count
  milestone (#285), which is not a call to stop lifting and opens no
  closing window, though the lifter's extra reps past it are samples past
  a cue `calledOver` counts as terminal. A clock-ended timed set (`Time`)
  has none either: the tick that speaks `Time` calls `endSet`, which
  cancels the collectors first -- read from source; no clock-ended set has
  been measured.
  Field-41 set 21, a hang ended by tap at 5 s of 30, is the tap case: its
  last sample is 33 ms before its write. Each of those has no closing
  window, so the default below refuses it. Session 32's five failed sets were
  recorded before `Stopped` existed and carry no terminal word
  (`FailedSetBoundaryTest`, set 9); they end 0.482-0.832 s past their last
  cue, a count cue, not a `Stopped`. Note the `Done` window also holds
  re-rack handling; it qualifies only where the still threshold holds.
- Compare each unit's opening and closing gravity directions. **Refuse** the
  set when either unit's two directions differ by more than a declared angle,
  and name which unit moved -- field-35 shows the vectors can say so without
  being told.
- A set with an opening window and no closing one cannot show its unit did not
  move. Per the standing ruling to withhold any figure the analysis cannot
  bound, the default is to refuse it too, with its own reason. Whether a
  separately labelled "opening only" basis should be published instead is an
  open question for the owner, not decided here.

### 5. What is published, and where

Per set, beside the raw streams and never in place of them: the lag and its
correlation (step 1), each unit's gravity direction and scale (step 2),
whether the third axis was resolved (step 3), the opening/closing agreement
(step 4), the method's version, and -- for any set that did not get a
transform -- which step refused and why. A reader must be able to tell "not
attempted", "refused, unit moved", and "aligned" apart without inferring it
from absent keys.

## What would make it falsifiable

Field-35 validates step 2 against stated ground truth, stationary on a
surface. Nothing yet validates steps 1, 3 and 4 against stated truth during
real lifting. The session that would, from #185's own proposal, folded into
lifting that happens anyway:

- **Set A, both units mounted the same way.** Hold the bar still about 3 s
  after starting the set, then lift. The method must recover approximately
  identity. Inventing a rotation here means the method is wrong.
- **Set B, one unit deliberately rotated 180 deg**, the other untouched, same
  hold. The method must recover approximately that flip and name which unit
  moved.
- **Report which unit was flipped and roughly how.** That sentence is the
  ground truth; without it neither set proves anything.

## [Field]

None of this is dischargeable from this repository.

- The validating session above, two sets on one exercise with the recorded
  intent. Pass: set A within 5 deg of identity, set B within 5 deg of a
  180 deg flip on the unit the lifter names.
- The rack-pin tap test on #292 (three taps, both units on the bar, impact
  instants within 40 ms), which is step 1's ground truth.
- On a machine set with both units on the stack: are the two streams close
  enough during the prep window that their disagreement reads as sensor noise?
  (Carried from #185's landing comment.)
