# Ready-made Claude prompts

> The plan contract lives in the app, under **Guide → COPY PLAN PROMPT**; the
> prompts here name the schema version but do not restate it.

Copy one of these into a Claude conversation together with an export from the
session's detail screen (the **Share** or **Save to phone** buttons). Claude's
plan replies can be pasted straight back into the app via **Plans → Import plan
(paste JSON)**.

---

## Analyze a session

> Here is a strength training session exported from my tracking app (JSON below).
> The app measures bar velocity and per-rep phase durations with a bar-mounted IMU.
>
> Analyze it:
> 1. Tempo compliance — where did actual phase times drift from prescriptions, and
>    is there a pattern (e.g. eccentrics shortening as fatigue builds)?
> 2. Velocity trends — mean concentric velocity per set vs. targets, velocity loss
>    within sets, and what that implies about proximity to failure.
> 3. Anything unusual worth flagging (asymmetric reps, long pauses, missed reps).
>
> Be specific and quantitative. Reference set/rep numbers.
>
> Any set with "voided": true was not performed -- exclude it from volume,
> from set counts and from every trend, but read "voidReason" if it is
> there.
>
> ```json
> {paste session-export JSON here}
> ```

## Revise next week's plan

> Below are (1) my current training plan and (2) my exported sessions from this
> week, from an app that measures bar velocity and rep tempo with a bar-mounted
> IMU sensor.
>
> Propose next week's plan. Adjust loads using the velocity data (target the same
> mean concentric velocities unless the data says otherwise), keep exercises the
> same, and fix any tempo prescriptions I consistently failed to hit — either by
> coaching note or by changing the prescription.
>
> IMPORTANT: Reply with a single JSON document conforming EXACTLY to the schema at
> the end. No prose before or after the JSON. Use the same exercise ids that appear
> in my data. schemaVersion must be "1.15". Timed sets (planks, carries) use
> `duration_s` instead of `reps`; bodyweight sets omit the load entirely.
>
> Current plan:
> ```json
> {paste current plan JSON}
> ```
>
> This week's sessions:
>
> Any set with "voided": true was not performed -- exclude it from volume,
> from set counts and from every trend, but read "voidReason" if it is
> there.
>
> ```json
> {paste one or more session exports}
> ```
>
> Plan schema:
> ```json
> {paste docs/schemas/plan.schema.json}
> ```

## Build a plan from scratch

> Create a 4-week strength block for me as JSON conforming EXACTLY to the schema
> below (one plan per week, or one plan with all sessions — your choice, but note
> it in planName). My context: {goals, experience, equipment, days per week,
> current estimated 1RMs}. Use tempo prescriptions where they serve the goal, and
> set targetMeanConcentricVelocity_mps and velocityLossStop_pct on the primary
> barbell lifts so my velocity-tracking app can auto-regulate.
>
> Reply with JSON only.
>
> ```json
> {paste docs/schemas/plan.schema.json}
> ```

## Deep-dive raw data

> Attached is a raw zip exported from my strength training app (one or two
> IMUs on the bar or weight stack). It holds one CSV per set and sensor
> (`setNN_<exercise>_imu*.csv`), `meta.json` (one descriptor per set; schema:
> docs/schemas/raw-meta.schema.json) and `session.json`, the app's own analysis
> (schema: docs/schemas/session-export.schema.json).
>
> Each IMU CSV has 11 columns: timestamp_ms, ax_g, ay_g, az_g, wx_dps, wy_dps,
> wz_dps, roll_deg, pitch_deg, yaw_deg, sample_idx.
>
> Clock: `timestamp_ms` is the phone's BLE packet ARRIVAL time. Several rows
> share one stamp and then it jumps ~30 ms, so it is bursty and must never be
> used as an integration step or to derive a sample rate. Use
> `sample_idx / sampleRate_hz` (sampleRate_hz is that set's key in meta.json)
> as the time axis. That rate is the rate rows were delivered, not the 100 Hz
> the sensor was asked for; it can be far lower (one unit has delivered about
> 44 Hz). Read the set's mounting, lift direction and analysed sensor from
> meta.json before interpreting any axis.
>
> Analyze the bar path and velocity profile: segment the reps, and tell me
> where within the range of motion the bar decelerates most (sticking point
> analysis). Compare against the per-rep figures in session.json and say where
> and why yours differ.
