package com.macrophage.barspeed.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.macrophage.barspeed.ui.BarColors
import com.macrophage.barspeed.ui.components.SectionCaption

/**
 * Self-contained prompt for any LLM chat. The model decides whether it needs to
 * gather context (fresh conversation) or can convert immediately (established
 * program), and is asked to emit the plan as a downloadable JSON file.
 */
val PLAN_PROMPT =
    """
You are generating a strength-training plan for the BarSpeed app, which imports plans as a single JSON file and then tracks bar velocity, tempo, power, heart rate, HRV, and RPE against them with a bar-mounted accelerometer.

First decide, from this conversation so far, whether you already have what you need:
- If we have an established program here (a plan we've been iterating on, or my session exports and history), do NOT ask questions — produce the JSON now.
- If this is a fresh conversation and you're missing key context, ask me the minimum set of questions first (goals, experience, days/week, equipment, current working weights or estimated 1RMs, injuries), then produce the JSON.

Output rules: produce ONLY a JSON document — as a downloadable .json file if you can create files, otherwise as a single raw JSON code block with no prose around it.

The authoritative machine-readable schema is docs/schemas/plan.schema.json in the BarSpeed repo, keyed by schemaVersion. That schema forbids keys it does not define, so a plan validated against it should have none. The app itself is more forgiving: it imports the plan and lists every key it did not recognise at the approval gate, naming the JSON path, so an invented key costs a warning rather than the whole file — and it says so loudest when a key looks like one it knows, because "loadKg" instead of "load_kg" is read as no load at all rather than as an error. Anything genuinely contradictory is still rejected outright with the path named: reps and duration_s on the same set, a tempo on a timed set, a "kind" that is not one of the four, an unsupported schemaVersion. Copy the warnings and errors back to me either way and I will fix them. This is the full 1.5 contract:

- Top level: {"schemaVersion": "1.5", "planName": string, "notes": optional string, "sessions": [...]}
- Session: {"name": string, "notes": optional string, "exercises": [...]}
- Exercise: {"exercise": snake_case_id, "notes": optional coaching cue shown to me in-app, "sets": [...]} plus these optional keys:
  "start": "top" | "bottom" — where the lift BEGINS, which fixes the direction of the first movement of every rep. "top" = first movement is down (squat, bench, leg curl, lat pulldown); "bottom" = first movement is up (press from the rack, deadlift, leg press, row). ("down"/"up" name that first movement directly and mean the same thing.) DECLARE THIS ON EVERY MACHINE: the same movement pattern starts at opposite ends on different machines, and nothing in the signal can tell which. Omitted → inferred from the id, which is a guess.
  "concentric": "up" | "down" — which way the DRIVING phase moves. Default "up". Use "down" for leg curls, lat pulldowns, triceps pushdowns. This is INDEPENDENT of "start": start says which phase comes first, concentric says which direction the drive goes. Both are needed to know which tempo digit is the eccentric.
  "sensorInverted": true — set this on cable machines whose routing reverses the sensor. The sensor rides the weight stack, so when I pull the handle DOWN the stack (and the sensor) goes UP; without this flag every phase label and velocity on that exercise is backwards.
  "sensorOnStack": true — the sensor is on the cable weight stack rather than on what I hold. The stack travels VERTICALLY no matter which way I move, so this is what decides which axis gets measured.
  "travelRatio": number — lifter-side travel per unit of sensor travel, for pulleys that aren't 1:1 (a 2:1 cable moves the handle twice as far as the stack → 2). Default 1.
  "plane": "vertical" | "horizontal" — the plane I move in. Use "horizontal" for seated rows, chest-press machines and horizontal cable work. This is MY plane, not the sensor's: a seated cable row is "plane": "horizontal" WITH "sensorOnStack": true, because the stack still goes up and down.
  "bodyweight": true — my own body is the load (pull-ups, dips, push-ups). The set's load is then only what I ADD, and it may be NEGATIVE for band or machine assistance; the app adds my body weight to get the real load.
  "implementCount": 2 — how many IDENTICAL objects I hold at once: a pair of dumbbells, two kettlebells, a two-handled carry. Default 1. DISPLAY ONLY — it does not change what "load_kg"/"load_lb" mean, which is ALWAYS the TOTAL across everything held. A pair of 40 lb dumbbells is "implementCount": 2 with "load_lb": 80, and the app shows me "2 × 40 lb". Write 40 there and you have recorded half of what I lifted, and nothing afterwards can tell it from a real 40 lb set. It does NOT multiply the set count, and it is NOT a count of limbs — never take it from "side": a rear-foot-elevated split squat holding two dumbbells is "side": "left" WITH "implementCount": 2, and a suitcase carry is "side": "right" with "implementCount": 1. OMIT it when the objects are NOT identically loaded (40 lb in one hand, 30 in the other): give me the true total and the app shows the total alone instead of inventing a split.
  "optional": true — accessory work I can drop if the session runs long; the app shows it as droppable so the decision is planned rather than improvised.
  "kind": "dynamic" | "hold" | "carry" | "explosive" — what the movement IS. "dynamic" is ordinary rep work; "hold" is an isometric; "carry" is a loaded walk; "explosive" is an Olympic-style lift judged on peak velocity with no tempo. This is NOT how the set is measured — reps versus the clock comes from whether the set says "reps" or "duration_s". DECLARE THIS ON ANY ID THAT IS NOT BUILT IN: omitted, the app guesses from words in the id, and the guess is wrong for names like hanging_leg_raise, walking_lunge or snatch_grip_deadlift, where the word that matches means something else. A declaration always wins, including over a built-in, and the app flags the disagreement so I can check it with you.
  Built-in ids: back_squat, front_squat, bench_press, overhead_press, deadlift, romanian_deadlift, barbell_row, hip_thrust; timed: plank, side_plank, dead_hang, farmers_walk, suitcase_carry; explosive (peak-velocity tracked, no tempo): snatch, power_snatch, clean, power_clean, push_press, kettlebell_swing, kettlebell_snatch, kettlebell_clean. Other snake_case ids are allowed and are the normal case — write them snake_case, one word per underscore-separated token (dead_hang, not deadhang), and declare "kind" on them rather than relying on the guess.
- Set: exactly one of {"reps": int} (dynamic) or {"duration_s": int} (holds/carries). Load: at most one of "load_kg" / "load_lb" (omit both for bodyweight). Optional: "tempo", "side" ("left"/"right" for unilateral work — emit one set per side, which also makes the true set count visible), "note" (commentary for this set alone), "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s".
  "tempo" digits: on VERTICAL exercises they are POSITIONAL — digit 1 = the DOWN stroke, digit 2 = pause at the bottom, digit 3 = the UP stroke, digit 4 = pause at the top — so which stroke is the eccentric follows from "concentric", not from the digit order (on a leg curl with "concentric": "down", "1030" is a 1 s driving pull down and a 3 s eccentric return up). On HORIZONTAL exercises there is no up or down, so the digits revert to the classic phase reading: digit 1 = eccentric, digit 3 = concentric. Either way digit 2 follows the digit-1 stroke and digit 4 follows the digit-3 stroke, and the voice guide plays exactly these seconds — it calls "Down"/"Up" on vertical work and "Return"/"Drive" on horizontal work.

Use tempo and velocity targets deliberately on primary barbell lifts to enable auto-regulation, and put form cues in exercise notes.

Two things the app does NOT accept, so don't invent them: there is no per-side shorthand (write one set object per side, with "side" — which also makes the real set count visible up front), and there is no way to mark prescribed work as skipped. A session that ends early simply has fewer sets in its export.

When I share BarSpeed session exports, read the effort fields with this key. "rpe" is RIR-based, 6-10; the app shows me narrative tiles and stores these numbers:
- Dynamic sets: 6 = easy (4+ reps in reserve), 7 = solid (~3 left), 8 = hard (~2 left), 9 = very hard (1 left), 10 = max (nothing left).
- Timed sets (holds/carries): same 6-10 scale but for time in reserve (6 = plenty of time left ... 10 = held to my limit).
- Explosive sets: rated on bar speed, not reps in reserve (6 = bar was flying, 8 = speed dropping, 10 = barely made the lift).
- "warmup": true (rpe null) = warm-up set, barely any effort — exclude it from fatigue and progression analysis.
- "failed": true = failed set (missed reps, broke a hold early, or missed the lift) — treat as beyond RPE 10.
- rpe null with neither flag = I skipped rating that set. There are no values below 6; easier-than-6 work is what the warm-up flag means.

How to read the rest of a session export, so you don't over-trust it:
- "reps" is authoritative — I counted it, or the voice guide did. The accelerometer is RECORD-ONLY on standard lifts.
- "repMetrics" is the sensor's separate per-rep opinion. When "repMetricsComplete": false it did NOT resolve every rep, so treat the array as a sample of the set, not the set — and treat any summary built from it the same way.
- "tempoCompliance" scores the two MOVEMENT digits only ("scoredPhases" says which). Pauses are measured and reported but never scored, because separating a real pause from a very slow movement needs displacement, which is the least trustworthy thing here. Prefer "actualEccConRatio" vs "prescribedEccConRatio" — the contrast is what a tempo block trains and it survives a constant timing offset.
- "rom_m" and everything derived from it (velocities, power) come from double integration and drift, badly on free weights where the sensor rotates. Compare them WITHIN a set and within a session; do not read them as calibrated distances or watts. "rollExcursion_deg" in the raw zip's meta.json tells you which sets rotated: a few degrees means the kinematics are clean, hundreds means gravity leaked into every sample.
- "velocityLoss_pct" is best rep → LAST rep, at the point I stopped the set. It is ABSENT rather than 0 when the last rep the sensor resolved was also the fastest: best-to-last is then zero by construction and says nothing about fatigue, and it is also what a detection AFTER the set ended looks like. "velocityLossBasis" says which case a set is in — "measured", "notEnoughReps", "noReference" or "terminalRepIsFastest". Do not read a missing "velocityLoss_pct" as a low one.
- "voiceCues" are epoch-ms stamped on the same clock as the raw IMU CSVs, so you can align exactly what I was told to do against what the bar did.
    """.trimIndent()

private data class GuideSection(val title: String, val body: String)

private val SECTIONS =
    listOf(
        GuideSection(
            "Sensors",
            "Pair the WitMotion bar sensor and your heart-rate strap once under Devices — the app " +
                "auto-connects from then on. Mount the sensor on the bar with the strap tight. The dots " +
                "in the top bar show status: green = live, amber = reconnecting. Heart-rate straps " +
                "doze when they lose skin contact; they reconnect on their own. No sensor? Sets still " +
                "work — the app switches to manual counting or voice-guided tempo automatically.",
        ),
        GuideSection(
            "Plans",
            "Import a plan on the Plans screen: paste JSON or pick a .json file. The plan is validated " +
                "and staged — nothing becomes active until you approve it. Tap a plan to review every " +
                "session, exercise, and set before approving. Use the prompt below to have an LLM " +
                "generate the file.",
        ),
        GuideSection(
            "Recording",
            "Start a session from the home screen. Each set shows live bar velocity, the tempo ring, " +
                "and per-rep bars; explosive lifts show peak velocity and cadence; holds and carries " +
                "get a countdown. Equipment busy? 'Switch exercise' reorders the queue. Barbell sets " +
                "show which plates to load per side. The bar sensor is RECORD-ONLY on standard " +
                "lifts: it measures velocity and power while the reps are counted by you (tap) or " +
                "by the voice guide on tempo sets — a miscounted phase switch can't corrupt the " +
                "count. Explosive lifts stay sensor-counted. " +
                "Right when a set ends, tap how hard it felt (warm-up and failed included); a set " +
                "stopped short of its target is logged as failed automatically. Rest follows, then " +
                "'Start next set'.",
        ),
        GuideSection(
            "Voice",
            "Voice count is ON by default: the eccentric counted out loud, each rep called at " +
                "lockout, 'last rep', 'done', and the rest countdown. 'Guided tempo' goes further: " +
                "the app calls the whole cadence — 'Down, one, two, three, Up… Rep one' — with a " +
                "5-second lead-in so you can get set on the bar, and counts the reps for you. " +
                "Lifts that start from the bottom (press, deadlift, row) are called the way " +
                "they're performed: 'Up… Down, one, two, three'. Guided mode is automatic for " +
                "tempo work when no sensor is connected. Holds and carries get spoken time checks " +
                "every 15 seconds remaining, then a second-by-second count from 10.",
        ),
        GuideSection(
            "Exports",
            "On any session: Share or Save the compact JSON (for LLM analysis), the detailed JSON " +
                "(per-rep velocity and power), or the Raw zip — sensor CSVs plus the full analysis in " +
                "one archive. Every spoken cue ('Down', 'Up', 'Rep 4'…) is timestamped on the same " +
                "clock as the sensor streams, so cues can be cross-referenced with the " +
                "accelerometer data. Paste a session export into your LLM chat and ask it to " +
                "revise next week's plan; RPE, HRV, power, and velocity-loss data are all in there.",
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(navController: NavController) {
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guide") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            items(SECTIONS.size) { i ->
                val section = SECTIONS[i]
                Spacer(Modifier.height(if (i == 0) 4.dp else 14.dp))
                SectionCaption(section.title, color = BarColors.Volt)
                Spacer(Modifier.height(4.dp))
                Text(section.body, style = MaterialTheme.typography.bodyMedium, color = BarColors.Sub)
            }
            item {
                Spacer(Modifier.height(18.dp))
                SectionCaption("Generate a plan with an LLM", color = BarColors.Volt)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Copy this prompt into Claude (or any LLM). It will ask for context only if it " +
                        "needs it, then produce a plan file you can import here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BarColors.Sub,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clipboard.setText(AnnotatedString(PLAN_PROMPT)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("COPY PLAN PROMPT") }
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        PLAN_PROMPT,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = BarColors.Sub,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
