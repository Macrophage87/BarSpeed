# Build Prompt: AccelerometerLifting — VBT Strength Training Android App

> This document is an engineered prompt intended to be given to an AI coding agent
> (e.g., Claude Code) to implement the app. It also serves as the product/technical
> specification for human contributors. Sections are ordered so an agent can execute
> them incrementally; each milestone is independently shippable and CI-verifiable.

---

## 1. One-paragraph goal

Build a native Android app (Kotlin, Jetpack Compose, min SDK 26, target latest stable)
that records strength training sessions using a **WitMotion WT901BLECL** Bluetooth Low
Energy 9-axis IMU (MPU9250) attached to a barbell, and optionally a **Garmin HRM 600**
heart rate monitor. The app must compute **bar velocity** and **segment every rep into
phases** (eccentric, pause/isometric, concentric, lockout) with per-phase durations, so
a lifter can verify tempo prescriptions (e.g., a prescribed 4-second eccentric really
took 4.0 s). Sessions must be importable/exportable in an LLM-friendly JSON format so
Claude (or another LLM) can analyze training data and revise programs, and the repo
must ship with a complete GitHub Actions CI/CD pipeline from day one.

## 2. Hardware and BLE protocol facts

### 2.1 WitMotion WT901BLECL (MPU9250, BLE 5.0)

- Advertises a custom (non-standard) GATT service. Known UUIDs used by WitMotion BLE
  devices:
  - Service: `0000ffe5-0000-1000-8000-00805f9a34fb`
  - Notify characteristic (sensor data out): `0000ffe4-0000-1000-8000-00805f9a34fb`
  - Write characteristic (commands in): `0000ffe9-0000-1000-8000-00805f9a34fb`
  - **Verify these UUIDs against the live device during development** (log the full
    GATT table on first connect); do not hard-fail if the vendor changes them — allow
    a service-discovery fallback that matches by characteristic properties.
- Default streaming packet ("flag 0x61"): 20 bytes, little-endian:
  `0x55 0x61 axL axH ayL ayH azL azH wxL wxH wyL wyH wzL wzH RollL RollH PitchL PitchH YawL YawH`
  - Acceleration: `int16 / 32768 * 16g` (±16 g range)
  - Angular velocity: `int16 / 32768 * 2000 °/s`
  - Angles: `int16 / 32768 * 180°`
- Registers are read via command `FF AA 27 <reg> 00` and returned in `0x55 0x71`
  frames (used for magnetometer, quaternion, battery). Configuration writes use
  `FF AA <reg> <valL> <valH>` (e.g., set output rate register `0x03`; save config
  register `0x00`).
- Sample rate: request **50 Hz minimum, prefer 100–200 Hz** for bar-speed work.
  Detect the actual delivered rate empirically from packet timestamps and use the
  measured rate in all integration math — never assume the configured rate.
- BLE throughput: request MTU 247 and 2M PHY where supported; packets may arrive
  batched. Timestamp on arrival but reconstruct uniform sample spacing from the
  measured rate.

### 2.2 Garmin HRM 600 (optional device)

- Standard BLE **Heart Rate Service `0x180D`**, Heart Rate Measurement characteristic
  `0x2A37` (notify). Parse flags byte for 8- vs 16-bit HR and RR-interval presence.
  Record HR (bpm) and RR intervals when present.
- Battery Service `0x180F` / `0x2A19` for battery level.
- The app must function fully without the HRM connected; HR is an optional overlay
  on sessions, sets, and rest periods.

## 3. Core signal-processing requirements (the hard part — do this well)

1. **Frame transformation**: rotate device-frame acceleration into the world frame
   using the sensor's roll/pitch/yaw (or quaternion if enabled), then subtract
   gravity to obtain linear acceleration. Bar velocity of interest is the **vertical
   (world-Z) component**; also retain the full 3D vector for bar-path visualization.
2. **Velocity integration with drift control**: integrate vertical acceleration to
   velocity. Raw integration drifts; correct it with:
   - **ZUPT (zero-velocity updates)**: detect stationary periods (acc magnitude ≈ 1 g
     and gyro ≈ 0 within thresholds for ≥ 250 ms) and reset velocity to zero.
   - Per-rep detrending: after rep segmentation, enforce v = 0 at rep boundaries and
     linearly redistribute residual drift across the rep.
3. **Rep segmentation & phase detection** (state machine over filtered vertical
   velocity, with hysteresis thresholds; make thresholds configurable per exercise):
   - `RACKED/IDLE` → `ECCENTRIC` (sustained negative velocity beyond threshold)
   - `ECCENTRIC` → `BOTTOM_PAUSE` (|v| below dead-band)
   - `BOTTOM_PAUSE` → `CONCENTRIC` (sustained positive velocity)
   - `CONCENTRIC` → `LOCKOUT/TOP_PAUSE` → next rep or `IDLE`
   - Support exercises that start concentric-first (e.g., deadlift) via a per-exercise
     `startsWith: ECCENTRIC | CONCENTRIC` flag.
4. **Per-rep metrics** (all must be computed and stored):
   - Phase durations: eccentric s, bottom pause s, concentric s, top pause s
   - Mean & peak concentric velocity (m/s); mean & peak eccentric velocity
   - Estimated ROM/displacement (m); peak power if load is known (P = m·g·v + m·a·v)
   - Velocity loss % within the set (vs. best rep) — standard VBT fatigue metric
5. **Tempo compliance**: each planned exercise may prescribe a tempo as the standard
   4-digit notation (e.g., `4-0-1-0` = 4 s eccentric, 0 s pause, 1 s concentric,
   0 s pause, or `40X0` where X = explosive). After each rep, show actual vs.
   prescribed per phase with a tolerance band (default ±0.5 s, configurable) and give
   immediate visual + optional audio/haptic feedback ("too fast" / "on tempo").
6. All DSP code must be **pure Kotlin, UI-free, and unit-tested** against recorded
   fixture streams (include synthetic fixtures generated from ideal waveforms plus
   noise, and at least one real recorded CSV once hardware capture exists).

## 4. Product features

### 4.1 Session recording
- Device scan/pair screen (both sensors), connection status, battery levels, live
  signal preview to verify placement before starting.
- Start a session from a plan (see 4.3) or ad-hoc. Within a session: select exercise,
  enter load, record set → live rep counter, live per-rep phase times, live velocity
  readout, HR overlay. Rest timer between sets (auto-starts on set end).
- Everything is recorded locally first (Room DB); raw sensor streams for a set are
  persisted (compressed) so metrics can be recomputed after algorithm improvements.
- Robust to BLE dropouts: auto-reconnect, mark data gaps, never lose a set.

**Set-to-set guidance (rest-screen flow).** The moment a set ends, the rest screen
must show two things side by side (this is the primary between-set surface, readable
at a glance from a bench or the floor):

1. **Feedback on the set just completed:**
   - Reps performed vs. planned; load used.
   - Tempo compliance per phase: prescribed vs. actual (mean and worst rep), with a
     clear pass/warn verdict per phase (e.g., "Eccentric: 3.8 s avg vs. 4 s target —
     on tempo"; "Concentric: slowed from 0.8 s to 1.6 s by rep 5").
   - Velocity summary: mean/peak concentric velocity, velocity loss % across the set,
     and — when the plan sets `targetMeanConcentricVelocity_mps` or
     `velocityLossStop_pct` — whether the set hit or violated those targets.
   - One-line coaching verdict derived from rules (not an LLM call): e.g., "on
     target", "bar speed low — consider reducing load next set", "velocity-loss
     stop was reached at rep 4; extra rep exceeded plan". Rules live in `:core:dsp`
     and are unit-tested.
   - HR at set end and recovery trend during rest, when the HRM is connected.
2. **Preview of the next planned set:** exercise, set number (e.g., "Set 3 of 5"),
   planned reps, load, tempo, target velocity / velocity-loss stop, and remaining
   rest countdown. If the next set is a different exercise, flag the transition
   prominently (equipment/sensor may need to be moved). At the end of the last set
   of the session, show a session summary instead.
- **In-set adjustments:** from the rest screen the user can edit the next set's load
  or reps before starting it (deviating from plan). Deviations are recorded and
  marked as such in the session export (`plannedLoad_kg` vs. `load_kg`), so LLM
  analysis can distinguish plan changes from plan non-compliance.
- Ad-hoc (plan-less) sessions get the same feedback panel; the preview panel shows
  the last set's parameters as defaults for a quick repeat.

### 4.2 Exercise & history
- Seeded exercise library (squat, bench, deadlift, OHP, rows, etc.) with per-exercise
  segmentation config; user-defined exercises.
- History views: per-session summary, per-set rep table, velocity/load charts over
  time, estimated 1RM trend via load-velocity profile.

### 4.3 Plan import / results export (LLM interop — first-class feature)
- **Import** a training plan as JSON (schema below) via share-sheet, file picker, or
  paste. Validate against the schema and show a human-readable diff/summary before
  accepting.
- **Export** a completed session (or date range) as JSON conforming to the schema,
  via share sheet, file save, or copy-to-clipboard.
- Schemas live in `docs/schemas/` as versioned JSON Schema files
  (`plan.schema.json`, `session-export.schema.json`) — they are the contract with
  the LLM and must be validated in CI.
- Include a `PROMPTS.md` with ready-made prompts the user can paste into Claude
  alongside an export ("analyze this training block, flag tempo non-compliance,
  propose next week's plan **as JSON conforming to plan.schema.json**").
- Design the export to be token-efficient: summary metrics by default; raw per-rep
  arrays only behind an "include detail" toggle.

**Plan JSON (import) — shape sketch** (formalize into JSON Schema):
```json
{
  "schemaVersion": "1.0",
  "planName": "Hypertrophy Block W3",
  "sessions": [{
    "name": "Lower A",
    "exercises": [{
      "exercise": "back_squat",
      "sets": [{ "reps": 5, "load_kg": 120, "tempo": "4010",
                 "targetMeanConcentricVelocity_mps": 0.5,
                 "velocityLossStop_pct": 20, "rest_s": 180 }]
    }]
  }]
}
```

**Session export — shape sketch**:
```json
{
  "schemaVersion": "1.0",
  "startedAt": "2026-07-18T17:04:00Z",
  "planRef": "Hypertrophy Block W3 / Lower A",
  "heartRate": { "avg": 112, "max": 156, "perSetSummary": true },
  "exercises": [{
    "exercise": "back_squat",
    "sets": [{
      "load_kg": 120, "plannedLoad_kg": 120, "reps": 5, "plannedReps": 5,
      "repMetrics": [{
        "ecc_s": 3.9, "bottomPause_s": 0.2, "con_s": 0.8, "topPause_s": 1.1,
        "meanConVel_mps": 0.52, "peakConVel_mps": 0.85, "rom_m": 0.61
      }],
      "velocityLoss_pct": 14.2, "tempoCompliance": { "prescribed": "4010", "withinTolerance": 4, "of": 5 }
    }]
  }]
}
```

### 4.4 LLM connectivity roadmap (Claude-first, phased)

Bidirectional LLM integration is a core product goal, delivered in three phases of
increasing automation. All three phases consume the SAME versioned schemas from
`docs/schemas/` — the schemas are the contract; nothing phase-specific may leak into
them. Build Phase 1 in v1; design interfaces so Phases 2–3 slot in without
restructuring (e.g., plan import must be a single code path used by file import,
clipboard paste, API responses, and future sync alike).

- **Phase 1 — Manual round-trip (v1, part of milestone 5).** Export session JSON via
  share sheet / clipboard; user pastes it into Claude with a prompt from
  `PROMPTS.md`; Claude replies with a plan JSON conforming to `plan.schema.json`;
  user imports it back via paste/file. No network code, works with any LLM.
- **Phase 2 — Direct Claude API integration (post-v1).** An in-app "Analyze &
  propose next plan" action sends selected session exports to the Claude API and
  receives a plan JSON back, using structured outputs constrained to
  `plan.schema.json`. Requirements:
  - User supplies their own Anthropic API key (stored in Android Keystore-encrypted
    preferences, never exported, never logged). Model selectable, sensible default.
  - The analysis prompt templates ship in the app but are user-editable (goals,
    constraints, coaching philosophy).
  - Network failures degrade gracefully to Phase 1 (copy the same payload out).
- **Phase 3 — MCP sync (future; design for, don't build yet).** Sessions sync to a
  user-owned remote store fronted by a remote MCP server exposing tools such as
  `get_recent_sessions`, `get_velocity_trends`, `import_plan`; added to claude.ai as
  a custom connector so any Claude conversation can pull training data and push
  plans back, which the app picks up on next sync. Keep the repository layer
  sync-agnostic so this becomes an additional plan/session source, not a rewrite.

**Plan-approval gate (all phases, non-negotiable):** an LLM-proposed plan is never
activated automatically. Every imported plan — regardless of arrival path — lands in
a "staged" state and is shown as a human-readable summary (and a diff against the
current plan, when one exists) that the user must explicitly accept. Schema
validation failures produce actionable errors that can be copied back to the LLM
for correction. Keep the LLM out of the real-time loop: in-set and between-set
feedback remains rules-based and offline (see 4.1); LLM involvement is
between-session analysis and programming only.

### 4.5 Future (design for, don't build yet)
- Watch app companion; ANT+ sensor support.

## 5. Architecture & stack (prescriptive)

- Kotlin 2.x, Jetpack Compose + Material 3, single-activity.
- Modules: `:app` (UI), `:core:ble` (transport), `:core:witmotion` (protocol codec),
  `:core:dsp` (pure-JVM signal processing — no Android deps, fully unit-testable),
  `:core:data` (Room, repositories, import/export + schema validation), `:core:model`.
- Hilt DI, coroutines + Flow end-to-end, Room with schema export committed.
- Foreground service for recording (survives screen-off; BLE + notification).
- Permissions: `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+) with graceful
  legacy-location fallback for API 26–30.
- A **"replay" debug mode** that streams a recorded CSV through the full pipeline in
  place of live BLE — critical for development without hardware and for UI tests.

## 6. CI/CD (GitHub Actions — set up in the FIRST commit that contains code)

- `ci.yml` on push/PR: Gradle cache; `ktlintCheck` + `detekt`; `lintDebug`; unit
  tests for all modules (DSP fixture tests are the crown jewel); `assembleDebug`;
  JSON Schema validation of `docs/schemas/` + example payloads; upload test/lint
  reports as artifacts.
- Instrumented tests on an emulator via `reactivecircus/android-emulator-runner`
  (API 34) for Room migrations and the replay-mode happy path.
- `release.yml` on tag `v*`: build signed release AAB/APK (signing config via GitHub
  Secrets, skip signing gracefully if absent), attach APK to a GitHub Release,
  generate changelog from conventional commits.
- Dependabot/Renovate for Gradle + Actions; PRs must be green before merge.

## 7. Milestones (implement in order; each ends green-CI and demoable)

1. **Skeleton + CI**: project scaffold, modules, CI pipeline green, replay-mode
   stub, schemas drafted and validated in CI.
2. **BLE + protocol**: scan/connect both devices, decode WitMotion frames, live raw
   data screen, HR decoding, CSV capture of raw streams.
3. **DSP core**: frame transform, ZUPT integration, rep segmentation, phase timing,
   metrics — unit-tested on fixtures; live per-rep readout in replay mode.
4. **Recording UX**: sessions/sets/rest flow with last-set feedback and next-set
   preview on the rest screen, Room persistence, history views.
5. **Plan import / export + tempo compliance UI + PROMPTS.md** (LLM Phase 1,
   including the staged-plan approval gate).
6. **Polish**: charts, 1RM trends, release pipeline, on-device validation notes.
7. **Claude API integration** (LLM Phase 2): in-app analyze-and-propose flow with
   structured outputs, Keystore-encrypted API key, editable prompt templates.

## 8. Quality bar & constraints

- No blocking calls on main thread; all BLE callbacks marshaled onto a dedicated
  dispatcher. Battery-conscious (no wake-lock outside active recording).
- DSP determinism: same input stream ⇒ identical metrics (needed for CI fixtures).
- All timestamps stored UTC (ISO-8601 in exports); durations in seconds (float).
- Accessibility: TalkBack labels on recording controls; large-text friendly live
  readouts (readable from the floor mid-set).
- No analytics/tracking; data stays on-device unless the user explicitly exports.
