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

The authoritative machine-readable schema is docs/schemas/plan.schema.json in the BarSpeed repo, keyed by schemaVersion. That schema forbids keys it does not define, so a plan validated against it should have none: use the names below EXACTLY as written and invent none — no synonyms, no tidier spellings. The app itself is more forgiving, and that is the trap. It imports the plan and lists every key it did not recognise at the approval gate, naming the JSON path, but a name merely CLOSE to a real one is read as a valid ABSENCE rather than as an error: "loadKg" for "load_kg" imports the set with no load at all and records it as bodyweight. It can name that pair only because camelCase and snake_case spellings of one word collide exactly; an ordinary misspelling gets nothing but a line saying an unknown key was ignored. Anything genuinely contradictory is still rejected outright with the path named: reps and duration_s on the same set, a tempo on a timed set, a "kind" that is not one of the four, an unsupported schemaVersion. Copy the warnings and errors back to me either way and I will fix them. This is the full 1.11 contract:

- Top level: {"schemaVersion": "1.11", "planName": string, "notes": optional string, "sessions": [...]} plus one optional pair:
  "bodyweight_kg" or "bodyweight_lb": number — my body weight, used purely for load arithmetic: on every exercise you mark "bodyweight": true, the plan states only what I ADD or what assists me, so the total load is my body weight plus that. It is not a health question and you are not being asked to record anything about my health, at most one of the two, on the PLAN and not on a session (the app stores one figure). Use a real measurement of me from any source you genuinely have — a connected scale, a health platform, this conversation, or a session export I gave you. I do not always tell you my weight directly, and a reading that reached you through a connected source is exactly as usable as one I typed. A measurement up to FOURTEEN DAYS old is fit for purpose — that is the app's own threshold before it asks me again, so the prompt and the app never disagree about what counts as current. If the only measurement you have is older than that, OMIT THE KEY OR WRITE null rather than send it: the app stamps whatever you write with the moment I import the plan, so sending a stale reading as if it were current would make that stamp wrong. IF YOU DO NOT KNOW IT AT ALL, OMIT THE KEY OR WRITE null too: those mean exactly the same thing, nothing is written, and the app keeps whatever I last entered myself. Never invent one, and do not guess it — not from my lifts, not from a typical lifter, not from an old plan. This figure becomes the base load of every set of every exercise you mark "bodyweight": true, so a guess silently mis-states the recorded load and the power of all my bodyweight work: it breaks no rule, warns about nothing, and cannot be recovered from the recording afterwards. A value you do write replaces what the app is holding the moment I import the plan, and the import gate shows me the number so I can see it changed.
- Session: {"name": string, "notes": optional string, "exercises": [...]}
- Exercise: {"exercise": snake_case_id, "sets": [...]} plus these optional keys:
  "description": string, MAXIMUM 220 characters — the coaching cue I read between sets without touching the phone, about four lines on screen. THE FIRST SENTENCE IS THE ONE I ACTUALLY READ, so front-load the thing that decides how the set is performed: the brace, the bar path, the safety cue. Everything else goes in "additional_notes". Over 220 characters the app refuses the plan and names the exercise rather than cutting your sentence in half, so split it yourself.
  "additional_notes": string, no limit — the rest of the cue: the setup ritual, the background, the paragraph. I only see it if I tap to expand the note, so nothing that changes how I perform the set belongs here.
  "notes": string — the older single-blob form of the cue, still accepted so plans I already have keep working. Prefer "description" plus "additional_notes" in anything you write now. If you send both "notes" and "description", nothing is lost: the description is what shows and the notes move behind the expand tap.
  "start": "top" | "bottom" — where the lift BEGINS, which fixes the direction of the first movement of every rep. "top" = first movement is down (squat, bench, leg curl, lat pulldown); "bottom" = first movement is up (press from the rack, deadlift, leg press, row). ("down"/"up" name that first movement directly and mean the same thing.) DECLARE THIS ON EVERY MACHINE: the same movement pattern starts at opposite ends on different machines, and nothing in the signal can tell which. Omitted → inferred from the id, which is a guess.
  "concentric": "up" | "down" — which way the DRIVING phase moves. Default "up". ALWAYS DECLARE "down" ON A LEG CURL, LAT PULLDOWN OR TRICEPS PUSHDOWN: the app never infers this one from the id at all, so an undeclared one of these silently stays "up" and every rep is analyzed backwards. This is INDEPENDENT of "start": start says which phase comes first, concentric says which direction the drive goes. Both are needed to know which tempo digit is the eccentric.
  "sensorInverted": true — set this on cable machines whose routing reverses the sensor. The sensor rides the weight stack, so when I pull the handle DOWN the stack (and the sensor) goes UP; without this flag every phase label and velocity on that exercise is backwards.
  "sensorOnStack": true — the sensor is on the cable weight stack rather than on what I hold. The stack travels VERTICALLY no matter which way I move, so this is what decides which axis gets measured. DECLARE IT ANY TIME THE LOAD IS A PIN-SELECTED WEIGHT STACK AND THE SENSOR IS CLIPPED TO THAT STACK: assisted pull-up and dip machines, lat pulldown, seated row, leg curl and leg extension, and cable work in any plane all carry it; leave it false only for free weights and for a sensor worn on the lifter's body or clipped to the handle itself. A leg press or hack squat sled is NOT one of these even though it looks like one: it is plate-loaded and moves on a rail at roughly 45 degrees, not a pin-selected stack, so a declared true there forces the VERTICAL axis onto what is really a horizontal-plane machine — leave "sensorOnStack" false on a sled and declare "plane" instead.
  "travelRatio": number — lifter-side travel per unit of sensor travel, for pulleys that aren't 1:1 (a 2:1 cable moves the handle twice as far as the stack → 2). Default 1.
  "plane": "vertical" | "horizontal" — the plane I move in. Use "horizontal" for seated rows, chest-press machines and horizontal cable work. This is MY plane, not the sensor's: a seated cable row is "plane": "horizontal" WITH "sensorOnStack": true, because the stack still goes up and down.
  "bodyweight": true — my own body is the load (pull-ups, dips, push-ups). The set's load is then only what I ADD, and it may be NEGATIVE for band or machine assistance; the app adds my body weight to get the real load.
  "implementCount": 2 — how many IDENTICAL objects I hold at once: a pair of dumbbells, two kettlebells, a two-handled carry. Default 1. DISPLAY ONLY — it does not change what "load_kg"/"load_lb" mean, which is ALWAYS the TOTAL across everything held. A pair of 40 lb dumbbells is "implementCount": 2 with "load_lb": 80, and the app shows me "2 × 40 lb". Write 40 there and you have recorded half of what I lifted, and nothing afterwards can tell it from a real 40 lb set. It does NOT multiply the set count, and it is NOT a count of limbs — never take it from "side": a rear-foot-elevated split squat holding two dumbbells is "side": "left" WITH "implementCount": 2, and a suitcase carry is "side": "right" with "implementCount": 1. OMIT it when the objects are NOT identically loaded (40 lb in one hand, 30 in the other): give me the true total and the app shows the total alone instead of inventing a split.
  "prep_s": integer seconds — how long I need between tapping START and the set actually beginning. Estimate THE TIME MY HANDS ARE UNAVAILABLE: fastening straps, chalking, setting a hook grip, cinching a belt, lying down on a machine — not walking to the rack or loading plates, which I do before starting the set. A Romanian deadlift with straps is not ready five seconds after I tap START; a cable machine is ready in two. 0 to 120. APPLIES TO A SET WITH A tempo, where the prep runs into the first rep call, and to a timed set of a hold or a carry, where it runs into the word that starts the clock — on those the prep is never counted into the duration the set records. Declared anywhere else it does nothing, and the app says so at the import gate. Omitted means 5. I can change it in the app and the change comes back in my export as prep_s beside plannedPrep_s — read that rather than guessing again.
  "optional": true — accessory work I can drop if the session runs long; the app shows it as droppable so the decision is planned rather than improvised.
  "progression": "weight" | "reps" | "time" | "none" — which dimension of THIS exercise steps up when a set had more in it. After every set I rate how it felt; a rating on the headroom end means I could have done more, and if the set was not a warm-up and the exercise still has sets left, the app shows me a small grid to put that onto the very next set. This key says what that grid raises. "weight" offers 5-30 lb (or 2.5-15 kg) — use it for anything with a load ladder. "reps" offers +1 and +2 — use it where the load is fixed and progression is volume, such as bodyweight work I cannot add plates to. "time" offers +5/+10/+15 s — use it on holds and carries. "none" shows NO grid at all — use it where the point is to hold load and reps across the exercise's sets and I should not be prompted upward however a set felt. OMITTED MEANS "weight", so leaving it out is the same as writing "weight"; omitting it and writing "none" are NOT the same thing. This is the control that makes the productive-floor rule below work — the grid is how the load actually gets pushed up across the sets — so on an exercise you deliberately open light, say which way it steps. Declare "reps" only where the sets prescribe reps and "time" only where they prescribe duration_s; the other way round the app accepts the plan and tells me at the gate that the grid has nothing to offer. It decides only what is OFFERED — I can still change load, reps, hold and tempo myself on any exercise.
  "sensors": 1 | 2 — ACCEPTED BUT IGNORED. It used to say how many accelerometers each set was recorded with; it decides nothing now. The app records from whatever is CONNECTED: one bar sensor writes one stream, two connected units that I have labelled A and B in the app write two, on every set of every exercise. A set can still carry its own "sensors" and that is ignored too. Leave the key out of new plans — it stays accepted only so a plan written against 1.8 or 1.9 still imports, and the import gate lists it as having no effect. Do NOT write a plan whose sets depend on a particular sensor count. A and B are the identity of the physical units and say NOTHING about which end of the bar or which hand each was on — I flip them constantly and that is fixed in post-processing, so never write a plan that depends on A being a particular side.
  "kind": "dynamic" | "hold" | "carry" | "explosive" — what the movement IS. "dynamic" is ordinary rep work; "hold" is an isometric; "carry" is a loaded walk; "explosive" is an Olympic-style lift judged on peak velocity with no tempo. This is NOT how the set is measured — reps versus the clock comes from whether the set says "reps" or "duration_s". DECLARE THIS ON ANY ID THAT IS NOT BUILT IN: omitted, the app guesses from words in the id, and the guess is wrong for names like hanging_leg_raise, walking_lunge or snatch_grip_deadlift, where the word that matches means something else. A declaration always wins, including over a built-in, and the app flags the disagreement so I can check it with you.
  Built-in ids: back_squat, front_squat, bench_press, overhead_press, deadlift, romanian_deadlift, barbell_row, hip_thrust; timed: plank, side_plank, dead_hang, farmers_walk, suitcase_carry; explosive (peak-velocity tracked, no tempo): snatch, power_snatch, clean, power_clean, push_press, kettlebell_swing, kettlebell_snatch, kettlebell_clean. Other snake_case ids are allowed and are the normal case — write them snake_case, one word per underscore-separated token (dead_hang, not deadhang), and declare "kind" on them rather than relying on the guess. Spell the words themselves exactly too: an id is read a whole word at a time against fixed lists of words, so a misspelled word matches nothing and NOTHING warns — "dumbell_bench_press" with one b is not recognised as a dumbbell and is handled as a barbell lift. No key overrides that; the spelling is the only control the plan has over it.
- Set: exactly one of {"reps": int} (dynamic) or {"duration_s": int} (holds/carries). Load: at most one of "load_kg" / "load_lb" (omit both for bodyweight). Optional: "tempo", "side" ("left"/"right" for unilateral work — emit one set per side, which also makes the true set count visible), "note" (commentary for this set alone), "targetMeanConcentricVelocity_mps", "velocityLossStop_pct", "rest_s", "sensors" (accepted and ignored, as on the exercise — see above).
  "warmup": true — this set is PREPARATORY: a ramp set, a warm-up, work that is not the training stimulus. Mark every ramp set of a block individually; there is no exercise-level form of this key, because the ramp sets and the working set are sets of the same exercise. It says what the set is FOR and nothing about how it went: I still rate a warm-up on the effort scale like any other set, and the rating is worth having — an empty-bar set that feels hard is a signal. Omit it for working sets; an unmarked set is one you considered work.
  "tempo" digits: on VERTICAL exercises they are POSITIONAL — digit 1 = the DOWN stroke, digit 2 = pause at the bottom, digit 3 = the UP stroke, digit 4 = pause at the top — so which stroke is the eccentric follows from "concentric", not from the digit order (on a leg curl with "concentric": "down", "1030" is a 1 s driving pull down and a 3 s eccentric return up). On HORIZONTAL exercises there is no up or down, so the digits revert to the classic phase reading: digit 1 = eccentric, digit 3 = concentric. Either way digit 2 follows the digit-1 stroke and digit 4 follows the digit-3 stroke, and the voice guide plays exactly these seconds — it calls "Down"/"Up" on vertical work and "Return"/"Drive" on horizontal work.

Use tempo and velocity targets deliberately on primary barbell lifts to enable auto-regulation, and put form cues in "description" — short, and the cue that matters first.

Unless the plan or our conversation says otherwise, open the first working set of an exercise near the productive floor — the lowest load you judge likely to show progress — and let that exercise's sets step up from there rather than starting near what you expect to be hard. The post-set effort scale already pushes load up within a session: a set landing in the headroom range says I had more, so raise the next one. A plan that opens high leaves nothing to push, and a failed set records less than a completed one — a completed set banks reps, tempo and velocity that a failed one throws away.

Two things the app does NOT accept, so don't invent them: there is no per-side shorthand (write one set object per side, with "side" — which also makes the real set count visible up front), and there is no way to mark prescribed work as skipped. A session that ends early simply has fewer sets in its export.

When I share BarSpeed session exports, read the effort fields with this key. "rpe" is one 1-10 scale answering "how much did that set have left in it", and its rungs are anchored DIFFERENTLY along its length, because near failure I can count reps and far from it I cannot — the honest answer down there is how much more weight I could have put on. The app shows me narrative tiles and stores these numbers:
- Dynamic sets, counted end: 7 = three reps left, 8 = two, 9 = one, 10 = nothing left.
- Dynamic sets, headroom end: 6 = could have added one equipment increment (10-15 lb / 5 kg), 4 = two increments (20-30 lb / 10 kg), 1 = could have added much more. These are LOAD, not reps: below three reps left a rep count is a guess, so the app stops asking for one.
- Timed sets (holds/carries): same seven rungs, with the headroom end asked in SECONDS (6 = could have gone 15-30 s longer, 4 = about a minute, 1 = much longer) and the counted end in that movement's words (7 = had more in me ... 10 = hit my limit).
- Explosive sets: the counted end is bar speed (7 = fast and crisp, 8 = speed dropping, 10 = barely made it); the headroom end is load, as on any other lift.
- 2, 3 and 5 are valid values that have no tile. The gaps exist so the rungs sort; a value there is real, not corrupt.
- BEFORE v0.1.45 the grid ran 6-10 only and 6 was its FLOOR, "easy, 4+ reps left" — so an older 6 absorbs everything the new 1 and 4 now separate. 7-10 mean the same in both. Do not read an old 6 as specifically "one increment".
- "warmup": true = a PREPARATORY set — a ramp set. Two things can now say so: the plan declares it, and I can mark or unmark a set myself on the rest screen; where both exist MY mark wins, and "warmupByLifter": true tells you the answer is mine rather than the plan's. Where that key is present the plan's own declaration is not in the document at all. A warm-up carries a real "rpe" like any other set, so do not read it as unrated and do not exclude it from EFFORT analysis; excluding it from volume or progression is your call and the flag still supports that. On sessions exported before v0.1.45 a warm-up set has no "rpe" because the app could not record both, not because I declined to rate it, and no set on those sessions carries "warmupByLifter" at all.
- "failed": true = failed set (missed reps, broke a hold early, or missed the lift) — treat as beyond RPE 10.
- "limiter" = why the set ended, in my own answer: muscle, grip, form, pace, slip, pain, outside, other. Read "pain" apart from the rest — it is never a progression signal. Read "outside" as noise and DISCARD the set from capacity analysis; it means I was interrupted, not that I reached a limit. "grip" means train grip rather than dropping the main load; "form" is usually the opposite read from "muscle". "limiterNote" carries my own words and only ever sits beside "other". The key is ABSENT when I skipped the question, when the app never asked, and on everything exported before v0.1.45 — absent is never "ended for an unknown reason", so do not count it as a category.
- rpe null with neither flag = I skipped rating that set, or the set ended before the app could ask.

How to read the rest of a session export, so you don't over-trust it:
- "reps" is authoritative — I counted it, or the voice guide did. The accelerometer is RECORD-ONLY on standard lifts.
- "repMetrics" is the sensor's separate per-rep opinion. When "repMetricsComplete": false it did NOT resolve every rep, so treat the array as a sample of the set, not the set — and treat any summary built from it the same way.
- "tempoCompliance" scores the two MOVEMENT digits only ("scoredPhases" says which). Pauses are measured and reported but never scored, because separating a real pause from a very slow movement needs displacement, which is the least trustworthy thing here. From schema 1.16 a rep publishes only ONE of "bottomPause_s" and "topPause_s" -- the turnaround between its two phases -- and the end where the rep BOUNDARY falls publishes nothing, because the stillness there is rest and not a pause; treat an absent key as unmeasurable, not as zero. A rep carrying BOTH keys is pre-1.16 data whatever the document's "schemaVersion" says: the per-rep figures are frozen into the set's row when the set is RECORDED and only copied out at export, so every rep published from a set recorded before this version carries both, with the old quantities, and the one at the end where that rep's BOUNDARY falls is the interval to the next drive rather than a pause. Do not compare those two figures with each other or with a post-1.16 rep's single one. Prefer "actualEccConRatio" vs "prescribedEccConRatio" — the contrast is what a tempo block trains and it survives a constant timing offset.
- "rom_m" and everything derived from it (velocities, power) come from double integration and drift, badly on free weights where the sensor rotates. Compare them WITHIN a set and within a session; do not read them as calibrated distances or watts. "rollExcursion_deg" in the raw zip's meta.json tells you which sets rotated: a few degrees means the kinematics are clean, hundreds means gravity leaked into every sample.
- "velocityLoss_pct" is best rep → LAST rep, at the point I stopped the set. It is ABSENT rather than 0 when the last rep the sensor resolved was also the fastest: best-to-last is then zero by construction and says nothing about fatigue, and it is also what a detection AFTER the set ended looks like. "velocityLossBasis" says which case a set is in — "measured", "notEnoughReps", "noReference" or "terminalRepIsFastest". Do not read a missing "velocityLoss_pct" as a low one.
- "voiceCues" are epoch-ms stamped on the same clock as the raw IMU CSVs, so you can align exactly what I was told to do against what the bar did.
- "sensors" appears on sets recorded with two accelerometers, on sets where two units were PAIRED and the app could not tell them apart, AND on a single-sensor set whose one armed unit delivered nothing for the whole set -- that third shape publishes "count" 1 with BOTH role lists EMPTY and carries "soleSilent". Read all eight fields: "count" is how many streams the set armed, "expected" is the roles armed, "present" is the roles whose stream actually reached the raw zip, "analysedRole" says which one every figure in that set was computed from, "analysedFellBack" is present and true only when that is NOT the role the set armed -- the armed unit produced nothing, another did, and the figures come from the one that did -- "shortfall" — "rolesUnassigned" or "rolesCollide" — says why two PAIRED units produced one stream, "silent" names each ARMED role that delivered nothing for the whole set with one of "tooSoon", "notLinked", "linkWithoutSensor" or "linkedSilent", and "soleSilent" is that SAME vocabulary for a set whose one unroled link had no role to key a word by. "silent" and "soleSilent" are NEVER both written on one set -- they are one fact in two vocabularies, and which one a set gets depends on whether it armed a role at all. PAIRED IS NOT CONNECTED: the app decides this from the list of sensors it remembers, never from a live link, so a "shortfall" means two units are paired and cannot be told apart — NOT that both were switched on or in range; "silent" and "soleSilent" are each an OBSERVATION OF A LINK too, never a claim about a battery or a mounting. It also describes my DEVICE LIST rather than the set, so expect "shortfall" on every set of a session rather than on the ones that went wrong; one stale paired sensor writes it on every row. A role absent from "present" captured nothing. The app analyses a role that STREAMED wherever one did, so an analysed role absent from "present" now means one of two things and NEITHER is "the surviving stream was analysed": either nothing streamed at all, and the summary is empty because there was no capture; or the set was recorded by a build that predates this key, whatever the document's "schemaVersion" says -- every document declaring 1.16 or earlier predates it -- when the analysis stayed on the armed unit whatever happened and published nothing derived when that unit was silent. Do not derive the fallback by comparing "analysedRole" with "present" -- on any set that streamed at all the analysed role is in "present" either way, and "analysedFellBack" is the only thing that separates "analysed the unit I meant" from "analysed the only one that turned up". Treat a set that fell back as single-sensor data from the role it names. Absent "sensors" means one sensor that DELIVERED, which is the normal case -- not merely one sensor, since a single armed unit that delivered nothing now publishes this block too. There is no "plannedCount" any more and exports declaring 1.13 or 1.14 carry one inside this block: it was what the plan asked for, and the plan no longer asks. Exports declaring 1.12 or earlier have no "sensors" block at all, so its absence there is not a loss. And a set recorded before 1.15 that carried a "plannedCount" of 2 beside a "count" of 1 re-exports today as "count" 1 with an empty "expected", NO "shortfall" AND NO "soleSilent" — the reason it recorded one stream is gone from the document, so do not read that shape as "nothing was in the way"; a document that carries "soleSilent" instead is telling you what it saw, not omitting it. The roles A and B are physical unit identities and carry no claim about which end of the bar or which hand — do NOT infer sides from them, and do not derive per-side metrics in this release: exactly ONE stream is analysed per set and "analysedRole" says which, nothing is computed from the two together, and the stream that was not analysed is captured only so that the analysis can be designed against real data.
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
                "Right when a set ends, tap how hard it felt — on every set, whether or not it hit " +
                "its target, warm-up always among the choices. A set that met its target also gets " +
                "a 'failed the set' tile; one stopped short does not, because it is logged as " +
                "failed automatically. Rest follows, then 'Start next set' — after the last " +
                "planned set, that screen offers 'Finish session' instead, or lets you add " +
                "another set if you want one.",
        ),
        GuideSection(
            "Voice",
            "Voice count is ON by default: the eccentric counted out loud, each rep called at " +
                "lockout, 'last rep', 'done', and the rest countdown. 'Guided tempo' goes further: " +
                "the app calls the whole cadence — 'Down, one, two, three, Up… Rep one' — with a " +
                "lead-in so you can get set on the bar — 5 seconds unless the plan says " +
                "otherwise or you change it on the rest screen — and counts the reps for " +
                "you. " +
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
