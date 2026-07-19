# BarSpeed

Android app for velocity-based strength training. A WitMotion **WT901BLECL** BLE
IMU on the barbell measures bar speed and the duration of every rep phase — so a
prescribed 4-second eccentric provably takes 4 seconds — and an optional Garmin
**HRM 600** (any standard BLE strap works) adds heart rate. Training plans import
from, and completed sessions export to, LLM-friendly JSON so Claude can analyze
your training and write the next block.

## Features

- **Live set tracking**: bar velocity, rep counting, and phase detection
  (eccentric / bottom pause / concentric / lockout) in real time.
- **Rest-screen coaching**: after every set — tempo compliance per phase, mean and
  peak concentric velocity, velocity-loss %, and rule-based verdicts; plus a
  preview of the next planned set, editable before you start it (deviations are
  recorded as such).
- **Auto-connect**: pair each sensor once; the app reconnects to both in parallel
  from then on. The HRM being absent never blocks the bar sensor.
- **Plan import with approval gate**: paste plan JSON from Claude — or pick a
  `.json` file — it's validated against `docs/schemas/plan.schema.json`,
  summarized, and only activated when you approve it.
- **Voice counting (optional)**: the phone counts each second of the eccentric
  and concentric out loud (hit that 4-second lowering without watching the
  screen) and counts down the last seconds of rest.
- **Exports**: token-efficient session JSON for LLM analysis (optionally with
  per-rep detail), and raw sensor CSV zips for Python/R.
- **Demo mode**: try the whole flow with synthesized bar motion, no hardware.

## Get a training plan out of Claude and into the app

Copy this prompt into Claude (fill in the bracketed context), then in the app:
**Plans → Import plan → paste → Approve**.

> Create a strength training plan for me. My context: [goals, experience,
> days/week, equipment, current working weights or estimated 1RMs].
>
> Reply with ONLY a JSON document (no prose, no code fences) conforming exactly to
> this schema: top level `{"schemaVersion": "1.1", "planName": string,
> "sessions": [...]}`. Each session is `{"name": string, "exercises": [...]}`.
> Each exercise is `{"exercise": snake_case_id, "sets": [...]}` plus an optional
> `"notes"` string (form cues or intent — it's shown to me in the app with each
> set of that exercise) — use ids like
> `back_squat`, `bench_press`, `deadlift`, `overhead_press`, `barbell_row`,
> `romanian_deadlift`, `front_squat`, `hip_thrust`, and for timed work `plank`,
> `side_plank`, `dead_hang`, `farmers_walk`, `suitcase_carry`. Each set has
> exactly one of `{"reps": int}` (dynamic sets) or `{"duration_s": int}` (holds
> and carries — planks, farmer's walks). Load is `"load_kg"` or `"load_lb"` (at
> most one, whichever unit I use; omit both for bodyweight). Optional per set:
> `"tempo"` (4-digit notation like "4010" or "30X0" — eccentric, bottom pause,
> concentric, top pause seconds, X = explosive; dynamic sets only),
> `"targetMeanConcentricVelocity_mps"` (number),
> `"velocityLossStop_pct"` (number, e.g. 20), and `"rest_s"` (int).
>
> The app measures bar velocity and phase durations with a bar-mounted IMU, so
> use tempo prescriptions and velocity targets deliberately: set
> `targetMeanConcentricVelocity_mps` and `velocityLossStop_pct` on primary
> barbell lifts to enable auto-regulation, and tempos where they serve the goal.

The full machine-readable contract is
[`docs/schemas/plan.schema.json`](docs/schemas/plan.schema.json); more prompts
(session analysis, plan revision from velocity data, raw-CSV deep dives) are in
[`PROMPTS.md`](PROMPTS.md).

## Repository layout

| Module | Contents |
|---|---|
| `:app` | Compose UI, record flow, foreground recording service |
| `:core:model` | Domain models, tempo notation, plan/export schemas (pure JVM) |
| `:core:dsp` | Velocity estimation, ZUPT drift correction, rep segmentation, metrics, coaching rules (pure JVM) |
| `:core:witmotion` | WitMotion BLE frame decoder + commands (pure JVM) |
| `:core:hrm` | BLE Heart Rate Profile parser (pure JVM) |
| `:core:ble` | GATT clients, scanner, device registry, auto-connect manager |
| `:core:data` | Room persistence, plan/session repositories, JSON + raw CSV exporters |

`PROMPT.md` is the original engineered specification the app is built against.

## Building

```sh
./gradlew assembleDebug          # full build (needs Android SDK)
./gradlew -PjvmOnly build        # pure-JVM modules only: DSP/codec tests, no SDK needed
```

CI (GitHub Actions) runs ktlint, detekt, unit tests, Android lint, schema
validation, and assembles a debug APK on every push; tagging `v*` builds a
release APK and attaches it to a GitHub Release.

To require green CI before merges to `main` (plus block force-pushes and
deletions), run `./scripts/protect-branch.sh` (or `scripts\protect-branch.ps1` on Windows) once with an authenticated
[GitHub CLI](https://cli.github.com).

The DSP pipeline is deterministic and tested against synthetic fixtures with
known ground truth (a prescribed 4 s eccentric measures 3.66 s ± 0.02 across
reps — the small consistent offset is the velocity dead-band at phase
boundaries). Raw sensor streams are persisted per set, so metrics can be
recomputed as the algorithms improve, and every real recorded set can become a
regression fixture (`ImuCsv` is the shared format for export, fixtures, and
replay).
