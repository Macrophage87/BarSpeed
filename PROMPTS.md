# Ready-made Claude prompts

Copy one of these into a Claude conversation together with your exported session
JSON (Settings → Export, or the share button on a session). Claude's plan replies
can be pasted straight back into the app via Plans → Import → Paste.

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
> in my data. schemaVersion must be "1.0".
>
> Current plan:
> ```json
> {paste current plan JSON}
> ```
>
> This week's sessions:
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

> Attached is a raw CSV export from a bar-mounted IMU (100 Hz; columns:
> timestamp_ms, ax_g, ay_g, az_g, wx_dps, wy_dps, wz_dps, roll_deg, pitch_deg,
> yaw_deg) plus the app's meta.json describing the set. Analyze the bar path and
> velocity profile: integrate vertical acceleration to velocity, segment the reps,
> and tell me where within the range of motion the bar decelerates most (sticking
> point analysis).
