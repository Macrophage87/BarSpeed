# BarSpeed

Android app for velocity-based strength training. Two WitMotion **WT901BLECL**
BLE IMUs, labelled **A** and **B**, clip to the barbell or to a weight stack and
measure bar speed and the duration of every rep phase, so a prescribed
4-second eccentric provably takes 4 seconds. An optional Garmin **HRM 600** (any
standard BLE strap works) adds heart rate. The phone counts tempo and reps out
loud (voice is on by default). Plans import from JSON, and every session
exports as a raw zip that robocoach reads to write the next plan.

## Install and update

- Use **one** install channel and stay on it: either the Play internal testing
  track, or the `BarSpeed-vX.Y.Z.apk` from
  [Releases](https://github.com/Macrophage87/BarSpeed/releases) installed over
  the old one.
- **Never uninstall.** Uninstalling deletes every session, plan, sensor
  pairing and body weight on the phone.
- The two channels are signed with different keys (Play re-signs with its own
  Google-generated key), so a Play build cannot update a GitHub APK, or the
  reverse. Switching channels means uninstalling. Before any switch, use
  **Export all sessions** and save the zips off the phone.

## Exporting to robocoach

Open the session (tap it in History on Home, or **View session & export** at the
end of a workout), then **Raw zip**:

- under **Share**, pick robocoach (or wherever you hand it files), or
- under **Save to phone**, save it (defaults to Downloads).

The zip holds a CSV per set and sensor, `meta.json` (described by
[`docs/schemas/raw-meta.schema.json`](docs/schemas/raw-meta.schema.json)) and
`session.json`, the full analysis (described by
[`docs/schemas/session-export.schema.json`](docs/schemas/session-export.schema.json)).

## Importing a plan

Plans come from robocoach. In the app: **Plans → Import plan (paste JSON)** or
**Import plan from file → Approve**. The plan contract for any LLM is in the app
at **Guide → COPY PLAN PROMPT**; that is the one copy kept in step with the
code. The machine-readable form is
[`docs/schemas/plan.schema.json`](docs/schemas/plan.schema.json). A plan file
starts `{"schemaVersion": "1.15", ...`; that is the version this app accepts.
You can also open a `.json` plan from Files or share it to BarSpeed.

## Reporting a bug

- If the app crashed, Home shows a **CRASH REPORT** card: tap **SEND IT TO ME**
  and share the file.
- Otherwise, [open an issue](https://github.com/Macrophage87/BarSpeed/issues/new/choose)
  with the bug form. Give the app version (shown on the **Guide** screen), what
  you did and what happened, and attach the crash file or the session's raw zip
  if you have one.

## Features

- **Live set tracking**: bar velocity, rep counting, and phase detection
  (eccentric / bottom pause / concentric / lockout) in real time.
- **Rest-screen coaching**: after every set — tempo compliance per phase, mean and
  peak concentric velocity, velocity-loss %, and rule-based verdicts; plus a
  preview of the next planned set, editable before you start it (deviations are
  recorded as such).
- **Two sensors**: pair each unit once in Devices and label it A or B (the app
  cannot tell two unlabelled streams apart), and mount it on the bar or on the
  weight stack. The app reconnects to every paired unit in parallel from then
  on; a missing HR strap never blocks the IMUs.
- **HRV**: R-R intervals from the strap (standard BLE, incl. Garmin HRM 600)
  drive a live RMSSD readout on the rest screen and a per-session HRV stored,
  shown in history, and included in exports for recovery analysis.
- **Plan import with approval gate**: paste plan JSON — or pick a
  `.json` file — it's validated against `docs/schemas/plan.schema.json`,
  summarized, and only activated when you approve it.
- **Voice counting (on by default)**: the phone counts each second of the eccentric
  and concentric out loud (hit that 4-second lowering without watching the
  screen), calls each rep at lockout ("rep 3"), "last rep" going into the
  final planned rep, "done" when you hit the count, and counts down the last
  seconds of rest.
- **Exports**: the raw zip robocoach reads (per-set sensor CSVs, `meta.json`
  and the full `session.json` analysis in one archive), plus token-efficient
  session JSON for LLM analysis (optionally with per-rep detail incl. drive
  power).

## For developers

More LLM prompts (session analysis, raw-data deep dives) are in
[`PROMPTS.md`](PROMPTS.md).

### Repository layout

| Module | Contents |
|---|---|
| `:app` | Compose UI, record flow, foreground recording service |
| `:core:model` | Domain models, tempo notation, plan/export schemas, Android permission policy (pure JVM) |
| `:core:dsp` | Velocity estimation, ZUPT drift correction, rep segmentation, metrics, coaching rules (pure JVM) |
| `:core:witmotion` | WitMotion BLE frame decoder + commands (pure JVM) |
| `:core:hrm` | BLE Heart Rate Profile parser (pure JVM) |
| `:core:ble` | GATT clients, scanner, device registry, auto-connect manager |
| `:core:data` | Room persistence, plan/session repositories, JSON + raw CSV exporters |

`PROMPT.md` is the historical seed prompt; it does not describe the current app.

### Building

```sh
./gradlew assembleDebug          # full build (needs Android SDK)
./gradlew -PjvmOnly build        # pure-JVM modules only: DSP/codec tests, no SDK needed
```

CI (GitHub Actions) runs ktlint, detekt, unit tests, Android lint, schema
validation, and assembles a debug APK on every push. Tagging `v*` (or
dispatching the Release workflow, which creates the tag) runs the unit tests and
a minified build, then builds a signed APK and AAB, attaches them to a GitHub
Release, and uploads the AAB to the Play internal track when the
`PLAY_SERVICE_ACCOUNT_JSON` secret is set.

To protect `main`, run `./scripts/protect-branch.sh` (or `scripts\protect-branch.ps1`
on Windows) once with an authenticated [GitHub CLI](https://cli.github.com). It
asserts a complete desired state — green CI required, force-pushes and
deletions blocked, admins bound by the same required check, and linear
history enforced (no merge commits) — rather than patching individual
fields, because the underlying GitHub API replaces the whole protection
object on every call. Three settings are flags rather than fixed values:
`ENFORCE_ADMINS=0` / `-NoEnforceAdmins` exempts admins, `REQUIRE_LINEAR_HISTORY=0`
/ `-NoRequireLinearHistory` allows merge commits, and `REQUIRE_REVIEWS=1` /
`-RequireReviews` turns on a required PR approval (off by default).

The DSP pipeline is deterministic and tested against synthetic fixtures with
known ground truth (a prescribed 4 s eccentric measures 3.66 s ± 0.02 across
reps — the small consistent offset is the velocity dead-band at phase
boundaries). Raw sensor streams are persisted per set, so metrics can be
recomputed as the algorithms improve, and every real recorded set can become a
regression fixture (`ImuCsv` is the shared format for export, fixtures, and
replay).
