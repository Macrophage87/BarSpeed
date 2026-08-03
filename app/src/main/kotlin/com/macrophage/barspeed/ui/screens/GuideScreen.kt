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

The JSON must conform exactly to this schema:
- Top level: {"schemaVersion": "1.1", "planName": string, "notes": optional string, "sessions": [...]}
- Session: {"name": string, "notes": optional string, "exercises": [...]}
- Exercise: {"exercise": snake_case_id, "notes": optional coaching cue shown to me in-app, "sets": [...]}
  Built-in ids: back_squat, front_squat, bench_press, overhead_press, deadlift, romanian_deadlift, barbell_row, hip_thrust; timed: plank, side_plank, dead_hang, farmers_walk, suitcase_carry; explosive (peak-velocity tracked, no tempo): snatch, power_snatch, clean, power_clean, push_press, kettlebell_swing, kettlebell_snatch, kettlebell_clean. Other snake_case ids are allowed; include words like dumbbell/cable/plank/carry/swing in the id so the app infers the right tracking mode.
- Set: exactly one of {"reps": int} (dynamic) or {"duration_s": int} (holds/carries). Load: at most one of "load_kg" / "load_lb" (omit both for bodyweight). Optional: "tempo" (4-digit like "4010", dynamic sets only), "side" ("left"/"right" for unilateral work — emit one set per side), "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s".

Use tempo and velocity targets deliberately on primary barbell lifts to enable auto-regulation, and put form cues in exercise notes.

When I share BarSpeed session exports, read the effort fields with this key. "rpe" is RIR-based, 6-10; the app shows me narrative tiles and stores these numbers:
- Dynamic sets: 6 = easy (4+ reps in reserve), 7 = solid (~3 left), 8 = hard (~2 left), 9 = very hard (1 left), 10 = max (nothing left).
- Timed sets (holds/carries): same 6-10 scale but for time in reserve (6 = plenty of time left ... 10 = held to my limit).
- Explosive sets: rated on bar speed, not reps in reserve (6 = bar was flying, 8 = speed dropping, 10 = barely made the lift).
- "warmup": true (rpe null) = warm-up set, barely any effort — exclude it from fatigue and progression analysis.
- "failed": true = failed set (missed reps, broke a hold early, or missed the lift) — treat as beyond RPE 10.
- rpe null with neither flag = I skipped rating that set. There are no values below 6; easier-than-6 work is what the warm-up flag means.
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
                "show which plates to load per side. After each set, correct the rep count if needed, " +
                "then tap how hard it felt — that logs the effort (or warm-up/failed) and starts the " +
                "next set in one tap.",
        ),
        GuideSection(
            "Voice",
            "Turn on 'Voice count' to hear the eccentric counted out loud, each rep called at " +
                "lockout, 'last rep', 'done', and the rest countdown. 'Guided tempo' goes further: " +
                "the app calls the whole cadence — 'Down, one, two, three, Up… Rep one' — with a " +
                "3-second lead-in, and counts the reps for you. Guided mode is automatic for tempo " +
                "work when no sensor is connected.",
        ),
        GuideSection(
            "Exports",
            "On any session: Share or Save the compact JSON (for LLM analysis), the detailed JSON " +
                "(per-rep velocity and power), or the Raw zip — sensor CSVs plus the full analysis in " +
                "one archive. Paste a session export into your LLM chat and ask it to revise next " +
                "week's plan; RPE, HRV, power, and velocity-loss data are all in there.",
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
