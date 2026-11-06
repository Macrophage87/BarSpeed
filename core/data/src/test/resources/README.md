# core/data test fixtures

Provenance for the heart-rate captures here (sessions, app versions, what each
control is for) lives in the KDoc of
`core/data/src/test/kotlin/com/macrophage/barspeed/data/HrFixtures.kt`. Keep it
there; this file only states the enrolment rule.

## Heart-rate fixtures: shift the timestamps before committing

A `field-hrm-*.csv` file (`timestamp_ms,hr_bpm,rr_ms`) holds the lifter's
beat-by-beat heart rate and R-R intervals. Its `timestamp_ms` column is
wall-clock epoch milliseconds as captured, which publishes the day and time
of day he trained. This repo is public.

New heart-rate fixtures are enrolled with shifted timestamps:

1. Copy every stream of the session into this directory.
2. Shift them together, in one call, so the set and rest streams keep their
   spacing:

   ```
   python3 scripts/rebase-hr-fixture-timestamps.py core/data/src/test/resources/field-hrm-<kind>-s<N>-*.csv
   ```

   The earliest sample of the batch lands on 2000-01-01T00:00:00Z. Only the
   timestamp column changes. The script refuses any file that is not a
   heart-rate CSV, and a batch that is already shifted.
3. Record the printed `offset_ms` in the session's paragraph in
   `HrFixtures.kt`, beside its session number and app version.
4. Leave out `rr_ms` only if no test reads R-R for that stream. Today every
   heart-rate test does (`RrIngest.newBeats`, `HrFixtures.sigmaOf`).

Check a batch with `--check`; it exits 1 if any file still carries a real
date.

Do not shift an IMU capture or a cue track with this script. If a new
heart-rate fixture is paired by absolute time with another capture file,
do not shift it alone: shift the pair together or leave both as captured,
and say which in the provenance note.

## The 60 fixtures already here are not shifted yet

`field-hrm-worn-s26-*`, `field-hrm-unworn-s28-*`, `field-hrm-worn-s30-*` and
`field-hrm-worn-s31-*` (sessions 26, 28, 30 and 31, captured Aug 2026) still
carry their capture timestamps. Every test that reads them is in `:core:data`,
so a shift has to land with a `:core:data` test run beside it. Shifting is
forward only: the original timestamps stay in git history.
