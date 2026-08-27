# Live state — the one copy

The volatile facts every BarSpeed agent needs and none of them should carry its own copy of.
Seven agent definitions used to restate this material in seven paraphrases; they drifted, and
the drift shipped false claims about Room's schema baseline into six files at once (#162).
This file is the single copy. A definition points here rather than summarising here, except
for the one-line class names carved out at §15 — a definition keeps those inline.

**Every entry names how it was measured.** A fact without a SHA, a command or an API call is a
claim about a state that may no longer exist. Where an entry is known stale it says so rather
than being quietly reworded — rewording a false claim has produced a fresh false claim every
time it has been tried here.

Unless an entry says otherwise, tree measurements were taken at
`849bcc834c422bd5a3f16ffc545554e408c32649` and GitHub facts by the `gh` call quoted beside them.
**Re-verify before you rely on any of it.** That instruction is the point of the file, not a
disclaimer on it.

---

## 1. Branch protection and the landing contract

Live source, never this sentence — field-selected, per §16's rule:

```
gh api repos/Macrophage87/BarSpeed/branches/main/protection --jq \
  '{contexts: .required_status_checks.contexts, strict: .required_status_checks.strict, enforce_admins: .enforce_admins.enabled, linear: .required_linear_history.enabled, reviews: .required_pull_request_reviews, force_push: .allow_force_pushes.enabled, deletions: .allow_deletions.enabled}'
```

Verified live at `gh version 2.96.0`, at the time of writing it returns:

```
{"contexts":["Build, lint, test"],"deletions":false,"enforce_admins":true,"force_push":false,"linear":true,"reviews":null,"strict":true}
```

`enforce_admins` and `required_linear_history` were both **false** when the first agent
definition was written and the owner has since inverted them — which is exactly why they are
read live rather than quoted. There is still **no review requirement**, and a red commit
(e199119) reached `main` before `enforce_admins` was turned on. GitHub is a partial backstop
now, not none; this loop is still the primary gate.

**`Build, lint, test` is a four-way contract** — `ci.yml:14` (`jobs.build.name`), both
`scripts/protect-branch.ps1` and `scripts/protect-branch.sh`, and the live required-status
context on `main`. Renaming the CI job silently disables the only automated gate this repo has,
and nothing verifies the coupling: neither script is invoked by any workflow. Treat the literal
as rename-forbidden. Any diff touching the job name is blocking unless all four move together.

**History is strictly linear.** `git rev-list --merges --count origin/main` returns `0` across
354 commits. Work **lands** by fast-forward and is never merged; PR #40 is the first
non-Dependabot PR and it landed that way too, with no merge commit created. Pressing GitHub's
merge button would create the repo's first.

**Landing is a gate action** requiring three things, not one: an explicit instruction from the
owner, a stated Accept from the gate, and a green `Build, lint, test` check-run on that exact
SHA. Say plainly which of the three failed.

> When the owner says **Land** → Read `.claude/skills/land/SKILL.md` before touching any ref.
> Every step in that checklist but the first has caught a real problem at least once.

## 2. The branch namespace and the force-push grant

`ci.yml:4-5` fires push CI only on `main` and `claude/**`. A branch named `fix/…` or `feature/…`
gets **no push CI at all**, and the absence is silent — you push, see no run, and either wait
forever or conclude it passed. All work happens on a single `claude/<slug>` branch; never push
to any other ref, `main` included, without explicit permission.

Start or reset with `git fetch origin main && git checkout -B claude/<slug> origin/main`. Landed
history is finished history; never stack on it.

**Force-push is standing-authorised on unlanded `claude/**` branches this loop created and you
were dispatched onto.** The owner pre-granted it so a branch under active work can be reset or
rebased without asking each round. The boundary does not widen by inference:

- `claude/**` yes; `main` never; anything already fast-forwarded into `main` never.
- **Deletion is not covered** by the grant. Never delete a branch without being told to.
- Matching the namespace does not make a branch yours. 57 `claude/**` branches exist on the
  remote (`git ls-remote --heads origin | grep -c 'refs/heads/claude/'`), and at least one is
  still live in-flight work:
  `claude/strength-training-android-app-11lidw` at `c3c9c521c99515b912ce57914dfb1afc3c1e8b22`,
  confirmed unlanded by `git merge-base --is-ancestor c3c9c52 origin/main` failing. Run that
  check before treating any `claude/**` branch as stale.

## 3. Reading CI — never infer anything from the NUMBER of runs

`ci.yml` fires on `push` (for `main` and `claude/**`) **and** on `pull_request`, and
`concurrency: ci-${{ github.ref }}` with `cancel-in-progress: true` (`ci.yml:8-10`) means a
second push cancels the first push's in-flight run outright. So one SHA can carry one, two or
three runs for reasons that have nothing to do with which refs it lives on, and a cancelled run
reports `conclusion: cancelled`, which is neither pending nor pass.

**`event` and `headBranch` are fields on the workflow run, not on a check-run.** The
`commits/<SHA>/check-runs` endpoint has no `event` field at all, so reading row [0] there is a
coin flip. The command that carries them:

```
gh run list --repo Macrophage87/BarSpeed --commit <FULL-40-CHAR-SHA> --workflow ci.yml \
  --json databaseId,event,headBranch,status,conclusion,url
gh api repos/Macrophage87/BarSpeed/commits/<SHA>/check-runs \
  --jq '.check_runs[] | "\(.name)\t\(.conclusion)"'
```

`--commit` requires the full 40 characters — `git rev-parse HEAD`, never a typed abbreviation. A
short SHA returns `[]` and exits 0, which reads identically to "no CI ran".

**Worked example, re-verified live.** PR #40's head is
`91cbce8c36e2423b3eaecf935e5f8f799a235343` (`gh pr view 40 --json headRefOid`), and the run list
above returns three rows for it:

| databaseId | event | headBranch | conclusion |
|---|---|---|---|
| 31890471544 | `push` | `main` | success |
| 31890365938 | `pull_request` | `claude/agent-tiers` | success |
| 31889768803 | `push` | `claude/agent-tiers` | success |

One push to one branch, plus that branch's own open PR, plus its landing on `main`. The count
alone cannot distinguish those three causes from a re-run.

You report **before** landing, so a single `push`-event run scoped to your branch is the normal
case. Say which case you are in and report exactly what the API returned, `event` included.
Where two runs genuinely are the same workflow twice, phrase it honestly: *"two runs of the same
workflow on the same runner pool for one SHA: a flake check, not independent evidence."*

**Waiting for a run to finish is one bounded poll, never a sequence of model turns each spent
re-reading one status field.** The shape:

```
until [ "$(gh run list --repo Macrophage87/BarSpeed --commit <FULL-40-CHAR-SHA> --workflow ci.yml \
  --json status --jq '[.[].status] | map(select(. == "in_progress" or . == "queued")) | length')" \
  = "0" ]; do sleep 20; done
```

Verified against `f2fde863b24b2dbcce8dd7820eb71e97e83d3977`: both rows for that SHA (`main`'s
push and the branch that landed it) are `completed`, so the `jq` filter counts zero in-progress or
queued rows and the loop exits on its first check rather than looping — the same command that
blocks on a live run also returns immediately on a finished one, which is what makes it safe to
call before you know which case you are in. One call, one number, one branch — never a bare
`gh run list` re-read on each turn to see whether the enum changed. The emulator boot wait in
`.claude/skills/bench-test/SKILL.md` is the same shape with a different predicate: `adb
wait-for-device`, then poll `adb shell getprop sys.boot_completed` until it prints `1`.

**Step order.** CI steps run sequentially with no `continue-on-error`, and **ktlint + detekt runs
first**, unrestricted across all seven modules, before any test. The first failure hides
everything downstream: a red run reporting only a formatting error tells you nothing about
tests, lint or the APK. detekt is `maxIssues: 0` with no baseline, so one new finding reds CI
before a single test result exists. All ktlint configuration lives in `.editorconfig` — there is
no `ktlint { }` block anywhere: `max_line_length = 120`, `ktlint_code_style = intellij_idea`
(not `ktlint_official`, so wrapping and trailing-comma expectations differ from the default you
will reach for), `end_of_line = lf`.

## 4. Test totals — never bare, always with the SHA and the command

Write every total as `<N>, measured at <40-char SHA> by <command>`. Bare numbers go stale on
every rebase and this entry is the proof, now three times over.

**CI's command is unrestricted `./gradlew test`** (`ci.yml:31`). That is the number that matters.
`-PjvmOnly test` is a faster subset that drops `:app`, `:core:ble` and `:core:data` from the
build graph entirely (`settings.gradle.kts:27`), so it omits `:core:data`'s real, executed test
runs — not merely uncompiled sources.

**The last executed total recorded anywhere in these definitions is 278 by `./gradlew test` at
`302e64fe0dcfb2cd0ea41774badeef930cd33c5d`, with `-PjvmOnly test` at 206. That measurement is
stale; do not requote it as current.** A static `@Test`-annotation count at
`849bcc834c422bd5a3f16ffc545554e408c32649` — an upper bound on distinct annotated methods, *not*
an executed total, and no substitute for running the suite — gives `:core:model` 369,
`:core:dsp` 222, `:core:hrm` 52, `:core:witmotion` 6, `:core:data` 237, `:app` 5. Re-measure with
`./gradlew test --rerun-tasks` and name your own parent SHA.

The history of this entry, kept because it names two distinct ways to be wrong:

- **The digit.** It read 71 when the first definition was written (true at `e697787`). The count
  then moved 71 → 80 → 84 → 94 → 97 → 106 → 111 → 127 → 132 → 136 across the next dozen-odd
  commits, as recorded in each commit's own body from `e665ea8` through `eb88bf3`, while the
  stale 71 kept circulating.
- **The command.** The correction that fixed the digit to 136 told the reader to re-measure with
  `-PjvmOnly test --rerun-tasks` — already wrong, because `:core:data` gained its first tests at
  `d69f299`/`52ccb55` and `-PjvmOnly` structurally cannot see them. Fixing the number and
  leaving the wrong command standing produced a fresh false claim inside the correction.

**There is no test-name pin file.** `scripts/` contains only `protect-branch.ps1` and
`protect-branch.sh`. Do not invent one and do not pretend one exists. The substitute is manual
and must be stated as manual: record the total before and after, with its SHA and its command,
and name every test added, renamed or removed in the commit body. Nothing mechanically detects a
deleted, renamed or widened test — widening `reps.size in 4..6` to `3..7` is invisible to CI.

**Never `-q` alone for the executed-task count, and pin the console mode.** `-q` suppresses the
`> Task :module:task` execution lines, which are the only place "executed" and "restored from
cache" are distinguishable — a `-q` run cannot fail an executed-count check because nothing is
left to check against. Three things travel together:

- `--console=plain` — Gradle's default rich console redraws lines in place, which is not a stable
  string to grep, diff or quote; plain mode is one line per event.
- the executed-task count read from the non-`-q` task lines (count `> Task :…` lines carrying no
  suffix — not `UP-TO-DATE`, `FROM-CACHE`, `NO-SOURCE` or `SKIPPED`), reported in the form already
  landed —
  *"1120 executions, 0 failures, all 141 tasks executed rather than restored"*
  (`16c2e7401d65a9ad8660639a21f5873edee792a1`).
- the tests/failures/errors/skipped tuple parsed from the JUnit XML under
  `*/build/test-results/**/TEST-*.xml`, never eyeballed off the console tail. Gradle's fixed
  format is a `<testsuite>` root carrying all four counts as attributes — confirmed by reading a
  leftover `app/build/test-results/testDebugUnitTest/TEST-com.macrophage.barspeed.record.
  PlanQueueTest.xml` in the primary checkout (`<testsuite name="…PlanQueueTest" tests="5"
  skipped="0" failures="0" errors="0" …>`); the file is from a stale build dated 2026-08-22, so
  its counts are not evidence for any particular SHA, but the emitted schema is Gradle's own
  JUnit-XML writer and does not vary with the SHA that produced it.

Tail the console log only on failure — a green multi-module log is thousands of tokens carrying
zero findings once the XML tuple and the task count both check out.

**One measurement per gate, shared.** A gate measures the suite **once**: one designated agent
runs `./gradlew test --rerun-tasks --no-build-cache` at the SHA under review and publishes four
things — the SHA, the exact command, the executed-task count beside the execution total in the
form the landed bodies already use (*"1120 executions, 0 failures, all 141 tasks executed rather
than restored"*, `16c2e7401d65a9ad8660639a21f5873edee792a1`), and an absolute path to the result
XMLs. **Every other lens reads those XMLs, and no lens accepts a relayed number** — not from a
verdict, a commit body, a brief, or this file. Gradle writes one XML per test class; the layout,
read off the build outputs present in the working checkout, is

```
core/{model,dsp,hrm,witmotion}/build/test-results/test/TEST-*.xml
app/build/test-results/test{Debug,Release}UnitTest/TEST-*.xml
core/data/build/test-results/test{Debug,Release}UnitTest/TEST-*.xml
```

with nothing under `core/ble` — no test source set (§5). Copy them to `<scratch>/suite-<SHA>/`
and hand lenses that path, not a build tree the next Gradle invocation can rewrite.

**Mutation-test every pin you add.** A test that cannot fail is worse than no test, because it
reads as coverage. Break the thing it guards, run the suite, report the numbers actually
observed: *"reverting X reds exactly `SetAnalyzerTest.analysis is deterministic`, N−1/N measured
at `<SHA>`."* Run them; never assert them. If it does not red, the test is decoration.

**Mutation runs are exempt from the shared measurement and stay per-mutation** — one build per
mutation, module-scoped, as `16c2e7401d65a9ad8660639a21f5873edee792a1` did for nine mutations by
`./gradlew -PjvmOnly :core:model:test :core:dsp:test --continue`, and
`96111a7495bb7a945b4d0d94ba3daf383c38f711` for fourteen by `-PjvmOnly :core:model:test --tests
com.macrophage.barspeed.model.TempoAdjustPolicyTest`. One shared run cannot say which pin a given
mutation killed, and that mapping is the whole content of a mutation table.

## 5. What a green suite does not mean

- **`:core:ble` has no test source set at all** — `core/ble/src/` contains only `main`. Its
  `testDebugUnitTest`/`testReleaseUnitTest` report `NO-SOURCE`.
- **`:app` has exactly one test file**: `app/src/test/kotlin/com/macrophage/barspeed/record/
  PlanQueueTest.kt`, 5 `@Test` methods over one pure function. Earlier definitions said `:app`
  had **zero** test source sets; that is false as of this measurement and is retracted here.
  What remains true is the consequence: nothing that draws, records or connects in `:app`'s 37
  Kotlin source files is test-gated, so a green `./gradlew test` **compiled** `:app` and
  asserted almost nothing about it. Compiled is not tested.
- **There is no `androidTest` directory anywhere in the tree.**
- **`./gradlew test` compiles both `:app:compileDebugKotlin` and `:app:compileReleaseKotlin`**, and
  the release variant additionally runs `isMinifyEnabled = true` with `proguard-rules.pro`
  (`app/build.gradle.kts:41-42`) — so a change that compiles debug can still fail release.
- **`:core:data` has a real test source set** — 17 files under
  `core/data/src/test/kotlin/com/macrophage/barspeed/data/`, run twice per push
  (`testDebugUnitTest` and `testReleaseUnitTest`). But its `SessionRepository*` classes take a
  `FakeSessionDao` implementing the DAO interface, and their own KDoc says it plainly:
  *"nothing here executes Room, SQLite or Android."* What is pinned is `SessionRepository`'s own
  mapping and call shape, never what the database did with it.
- **So no test in this repo can verify Android, BLE or Room behaviour.** A comment may state what
  the code *calls*; it may not state what the GATT stack delivered, what thread a callback landed
  on, what Room migrated, or what the lifter saw.
- **A task reported `UP-TO-DATE` or `FROM-CACHE` has not run.** `./gradlew -PjvmOnly test` can
  report `BUILD SUCCESSFUL` in seconds having executed no test at all. Use `--rerun-tasks`
  whenever a number matters, and read the task list rather than the last line.
- **`./gradlew --version` exits 0** and prints a healthy banner while every real task fails. It
  even names the poison — `Launcher JVM: 25.0.3` — without flagging it.
- **`-PjvmOnly` is a presence check.** `settings.gradle.kts:27` tests
  `!providers.gradleProperty("jvmOnly").isPresent` and never reads the value, so `-PjvmOnly=false`
  still excludes the three Android modules — trying to turn the flag off gets you the opposite of
  what you intended.
- **A `--tests` filter fails in the opposite direction**, which is worth knowing so you do not
  misread it as a broken build: one matching nothing **fails** with
  `No tests found for given includes: […](--tests filter)`, exit 1, and a valid class filter
  applied to an aggregate `test` task also fails in every module that does not contain that
  class. Scope it to one module. Quote it — test names are backticked Kotlin strings with spaces;
  unquoted, `is` is parsed as a task name.

## 6. Toolchain

`JAVA_HOME` (jdk-21), `ANDROID_HOME` and `ANDROID_SDK_ROOT` are all persisted at **User** scope on
this machine, and there is no `local.properties` — but a shell started before they were set
inherits none of them. `echo` all three first and export any that are empty rather than
concluding a toolchain is absent.

`C:\Program Files\Eclipse Adoptium\` holds `jdk-17.0.20.8-hotspot`, `jdk-21.0.12.8-hotspot` and
`jdk-25.0.3.9-hotspot`. The SDK is at `%LOCALAPPDATA%\Android\Sdk` with `platforms`,
`build-tools`, `platform-tools`, `emulator`, `system-images` and accepted `licenses`. Treat those
as where to look first, not as a guarantee — the machine changes underneath this file.

**Two toolchains are required and neither can be downloaded.** `:core:{model,dsp,hrm,witmotion}`
declare `jvmToolchain(21)`; `:app`, `:core:ble` and `:core:data` declare `jvmToolchain(17)`. There
is no foojay resolver in `settings.gradle.kts`, so Gradle downloads nothing. Read a "No matching
toolchain" error as a missing JDK, never a build-file defect.

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
./gradlew test                       # CI's command (ci.yml:31), all 7 modules -- re-measure, name your SHA
./gradlew -PjvmOnly test             # faster subset; drops :app, :core:ble, :core:data
./gradlew ktlintCheck detekt         # unrestricted, all 7 -- this is what CI runs FIRST
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew --stop                     # after any JAVA_HOME change
./gradlew -PjvmOnly :core:dsp:test --tests "com.macrophage.barspeed.dsp.SetAnalyzerTest"
```

With those set, **every step of `ci.yml` reproduces locally**: the JVM suite, lint over all seven
modules, `:app:lintDebug`, `:app:assembleDebug`, and the ajv schema validation.

Run `ktlintCheck detekt` **unrestricted**, never under `-PjvmOnly`: that flag excludes `:app`
(37 Kotlin files; `RecordScreen.kt` alone is 2,318 lines), `:core:ble` and `:core:data` from
exactly the check most likely to red CI, and `end_of_line = lf` on Windows bites hardest in the
Android modules local lint historically never reached.

**Failure signatures, each of which has cost a round:**

- With `JAVA_HOME` unset or pointing at 25, **every** task fails with a message whose entire body
  is the string `25.0.3` — no stack, no hint. Not the network, not the SDK. `--stacktrace` shows
  `IllegalArgumentException: 25.0.3` in `JavaVersion.parse` inside
  `KotlinCompilerKt.compileKotlinScriptModuleTo`: it dies compiling the build *script*, before any
  project logic runs. Fix the JDK; do not "fix" build files you never reached.
- With `ANDROID_HOME` unset the failure is different and later:
  `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
  variable…`. That is evidence the variable is unset **in that shell**, never that the SDK is
  missing. Do not conflate the two blockers.
- A JDK switch can leave daemons from the previous JVM alive and corrupt the Kotlin incremental
  caches. `w: Detected multiple Kotlin daemon sessions` appears during Android compiles, and a
  `:core:model:compileKotlin` failure quoting a missing `constants.tab` was observed once after
  such a switch. `./gradlew --stop`, delete the affected `build/` directories — build output
  only, never a tracked file — and retry.
- Two Gradle builds against one clone corrupt the same cache; the signature is
  `Could not delete '…\build\kotlin\compileKotlin\cacheable\caches-jvm'` followed by
  `Using fallback strategy: Compile without Kotlin daemon`. That is a collision, not a code
  defect. Serialise, or `git clone` into scratch.
- **Never kill any java process.** Gradle daemons and a running emulator are shared with other
  work on this machine and are not yours to stop. `./gradlew --stop` when a daemon genuinely
  must go; nothing broader, and never a process sweep.
- **PowerShell mangles native exit codes.** Unpiped, and even with `2>&1`, it reports a failing
  build's `1` faithfully; piping `gradlew.bat` through a truncating cmdlet
  (`| Select-Object -First N`) breaks the pipe and turns that `1` into `$LASTEXITCODE = -1`,
  surfaced as 255 — a wrong number, though still non-zero. `-Last N` reports 1 correctly. Use Git
  Bash when an exit code must be trustworthy.

> When running the emulator, installing an APK or verifying a migration on a device →
> Read `.claude/skills/bench-test/SKILL.md`. It carries the AVD name, the RAM-poor launch flags,
> the `ANDROID_SERIAL` pin and the two-way migration test.

## 7. Release is dispatched, never tagged

```
gh workflow run Release -f tag=v0.1.NN
```

Only after CI is green, because `release.yml` runs `:app:assembleRelease` and dies on the same
compile errors CI would have caught.

`release.yml` **does** also trigger on `push: tags: ["v*"]` (lines 3–4), so a stray
`git push --tags` runs a full signed release build. The dispatch convention exists because, in
the workflow's own comment, *"environments whose git proxy blocks tag pushes can dispatch the
release directly; the tag is then created by the release action itself."*

The tag is created by the action at publish time, and at least once it did not land on the SHA
that was built: `v0.1.5` **and** `v0.1.6` both resolve to
`56448cb9613fe81fab346bd128c4571c157149ab` (`git rev-list -n1`), whose `versionName` is **0.1.6**
— so v0.1.5 names a commit that was never built as 0.1.5. That is one collision among 44 `v*`
tags (`git tag -l 'v*' | wc -l`); later tags landed correctly. The mechanism is readable in
`.github/workflows/release.yml:59` — the release step passes `tag_name` with no
`target_commitish`, so GitHub creates the tag from `main`'s HEAD at the moment that step runs,
the end of the build, not the dispatch — and `.claude/skills/release-cut/SKILL.md` derives the
freeze rule from it. What remains unverified is narrower: no one has read the v0.1.5 run's log,
so the specific incident's causation is inferred from the mechanism, not confirmed against it.
`release.yml` has **no concurrency group**, so do not push to `main` while a release run is in
flight, and after every release verify `git rev-list -n1 vX.Y.Z` is the SHA you intended.

> When cutting a release → Read `.claude/skills/release-cut/SKILL.md`.

## 8. The commit body is the record

Issues exist and are worth reading, but an issue can be edited or relabelled after the fact and a
landed commit body cannot. On a repo that lands by fast-forward with no PR bodies, the commit
body is the only durable artifact of why a change is believed correct; anything not written
there did not happen.

House bar, consistent across the repo's history:

- Imperative, sentence case, **no** conventional-commit prefix, subject ≤72 chars, no trailing
  period.
- Body wrapped at roughly 72–76 columns, explaining the **failure mode**, its mechanism and its
  consequence to the lifter — not the files touched.
- Name the differentials, the review round, the retractions, and the issue number when one
  exists. No emoji.

Trailers, in this order and nothing else:

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: <url>
```

The URL is the current session's. **Never synthesise one and never copy one from an earlier
commit** — omitting it with the reason stated once is correct. There is no
`_Generated by [Claude Code]_` footer anywhere in this repo and nowhere to put one.

**No internal model or vendor identifiers in anything pushed**, and do not assume CI enforces it:
nothing in `ci.yml` scans commit messages for anything. This repo is the proof. Measured at
`849bcc83…` by `git log origin/main --format='%(trailers:key=Co-Authored-By,valueonly)'`: of 348
agent-authored commits, **75 carry `Claude Fable 5`**, an internal codename, on a public
repository. All 75 are on or before `a3f01cda8255d84d8dc17d5ee352fe7b4ce1530e` (2026-08-10); the
**273** commits since carry `Claude Opus 5` and none carries the codename. The convention already
self-corrected once — do not regress it. (Earlier definitions stated this as "75 of 85", true when
written; the numerator still holds, the denominator has moved.)

**Never `git add` a directory, `-A` or `.`** — name every file path explicitly. Issue #97 records
six sweeps of `core/data/schemas`, then untracked, one of which reached a remote branch at 1,212
insertions on an eight-line change (`.claude/skills/land/SKILL.md:33-36`).
`core/data/schemas/com.macrophage.barspeed.data.AppDatabase/10.json` is **tracked now**
(confirmed by `git ls-tree -r --name-only HEAD -- core/data/schemas`); only a `DATABASE_VERSION`
bump writes an untracked sibling `<N>.json`, and that new file must ship in the same commit as
the migration, never swept in separately.

## 9. Red-before-green, and where it is not available

Every new test guarding a behaviour change must be shown failing before the fix. The commit
partition is defined once, in `barspeed-implementer.md` — c0 characterization pins, c1
behaviour-preserving refactor plus green pins, c2 red differentials only, c3 the fix. Use that
definition; do not restate it.

Two constraints belong with it:

- **Push c2 and let its CI run complete before pushing c3.** `ci.yml:8-10` sets
  `cancel-in-progress: true`, so pushing c3 cancels c2's in-flight run outright and the red is
  destroyed rather than merely superseded.
- **c3's "touches no `docs/schemas/`" rule has one carve-out.** A *deliberate* contract change is
  the one case where the schema, the Kotlin constants and both ajv example files must move in the
  SAME commit as the red, because `SchemaContractTest` is an equality assertion between the
  published schemas and `PlanFile`/`SessionExport`, not a subset one. e199119 did exactly that.

**Where the module has no tests, red-before-green is not available at all.** For a change confined
to `:core:ble` — or to any part of `:app` outside `PlanQueue` — either lift the decision into a
pure function in `:core:model`/`:core:dsp` so c0–c2 can exist, or say plainly in the report and
the commit body: *"no red was shown; this change is compile- and lint-gated only, not
test-gated."* Never let the partition's presence in a definition imply it was performed.

**The strongest pins are in `:core:model`.** `SchemaContractTest` does exact `assertEquals` on
enum sets between the *published* `docs/schemas/*.json` and Kotlin constants, wired in by
`core/model/build.gradle.kts` by naming the real documents rather than copying them — its own
comment says why: *"the contract only holds if the documents an LLM is pointed at are the ones
the code is pinned to."* Touching `PlanFile.VALID_STARTS` / `VALID_PLANES` /
`SUPPORTED_SCHEMA_VERSIONS` or `SessionExport.SCHEMA_VERSION` means moving `docs/schemas/*.json`
**and** the example payloads in the same change: updating only the schema passes
`SchemaContractTest` and reds `ajv` four steps later. The ajv step is weaker than it looks — it
validates two hand-written examples the app never produced, themselves stale.

## 10. Scope discipline — churn that looks like cleanup and is not

- Do **not** "fix" magic numbers or split long DSP methods to please detekt. `MagicNumber`,
  `LongMethod`, `LongParameterList`, `CyclomaticComplexMethod`, `TooManyFunctions` and
  `NestedBlockDepth` are deliberately disabled in `config/detekt/detekt.yml`, and such refactors
  are unrequested churn in numerically sensitive signal-processing code.
- Do **not** consolidate the root `build.gradle.kts`. It is intentionally code-free, and its own
  comment says why: AGP must stay off the root classpath for `-PjvmOnly` builds, and ktlint at
  root cannot see Kotlin.
- Do **not** add a `repositories {}` block to a module —
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (`settings.gradle.kts:10`) hard-fails the build.
- Do **not** tighten `ImuCsv`'s `require(f.size >= 10)` (`ImuCsv.kt:46`) to `== 11`, and do not
  regenerate fixtures through `encode()`. `HEADER` declares 11 columns, and 4 of the 34
  `core/dsp/src/test/resources/field-*.csv` fixtures carry 10 — the loose bound is what keeps the
  oldest field captures readable. (Earlier definitions said "all four crown-jewel CSVs"; there are
  34 field fixtures now, of which four are the 10-column ones. The imperative is unchanged.)

Implement the task at hand; name adjacent defects and raise them rather than folding them in
silently.

## 11. Priority is consequence

A lost set or a corrupted recording outranks a cosmetic issue by a wide margin, whatever the
effort ratio. When a display decision and a data decision conflict, the data wins — a wrong pixel
is recoverable, a wrong recorded set is not.

BarSpeed makes the calculus explicit through a recompute/capture asymmetry that should govern
review depth:

- **Recoverable:** anything the DSP derives, because gzipped canonical CSVs for imu, hrm and cues
  are persisted per set. A wrong coefficient in `VelocityEstimator` is a re-run.
- **Unrecoverable:** samples dropped before they reach the buffer, and everything captured once at
  set end — RPE, warm-up, failed, side, load, manual rep count, wall timestamps.

**Review the capture path harder than the maths.**

## 12. Verify before you relay

A finding — a reviewer's, the owner's, an issue's, your own favourite hypothesis — is a
**hypothesis** until you check it. Relaying is how false claims enter a repository, and this
tooling is its own exhibit: the research pass that produced the first agent definitions reported,
as verified fact and with a command as evidence, that the only JDK on this machine was Temurin
25.0.3 and that therefore no Gradle task could run. It was false — `ls "/c/Program Files/Eclipse
Adoptium"` lists jdk-17, jdk-21 **and** jdk-25 — and the wrong environmental premise cost that
whole investigation its ability to run a single test. A later draft corrected that one and then
relayed the *next* environmental claim unchecked, that no Android SDK existed, which was false
too. How many were independent observations and how many were relays is no longer knowable; what
is knowable is that nobody re-ran the cheap check.

**Check findings that would make you look right, too.** e199119's own commit body opens by saying
the field report driving it was partly wrong — several of its root causes turned out to be stale.
The persisted raw export exists per set precisely so that check is possible.

**Own your errors.** Correct plainly at the point the wrong claim lives — the commit body, the
report, the verdict — name it as yours, and move on. Do not bury it, do not over-apologise, do not
tally. The repo models this: `git log --all --merges --oneline` is empty and no `git revert`
commit exists anywhere in its history. d76bc30 discarded fcf1790's root-plugin approach outright;
e199119 deleted the hard tempo rejection 8452ab7 had added six days earlier and said so. Being
wrong is handled by rewriting forward and naming it.

## 13. PROMPT.md is a historical seed prompt, not a description of the code

`README.md:89` calls it "the original engineered specification the app is built against" —
*original* is the operative word, and the README does not say the code has diverged. The evidence
is the absences themselves. **Specced and absent:** Hilt DI (one grep hit repo-wide, in PROMPT.md
itself), instrumented tests (no `androidTest` source set anywhere), committed Room schemas for
versions 1–9, CSV replay mode, TalkBack semantics, haptics, the entire Claude API phase.
**Present and unspecced:** cable geometry, RPE, warm-up/failed flags, HRV, drive power, voice cues,
plate math.

There is no CLAUDE.md, CONTRIBUTING.md or AGENTS.md, so PROMPT.md is the most
authoritative-looking document an agent will find. Citing it as evidence of what exists is itself
a claim stronger than its evidence.

## 14. `[Field]` items and their discharge

A `[Field]` item is anything only a real WitMotion sensor, a real BLE link, a real Android device
or a real lifter can answer. They go in a clearly-marked `[Field]` section of the report and, when
the work lands, in the commit body — **never silently folded into a change claimed as verified.**
Pass criteria must be exact and readable in a gym: which exercise, which sensor mount and plane,
which prescribed tempo, which number to read, which threshold decides.

Question classes CI provably cannot reach, every one of which has already produced a shipped
defect:

- Does the sensor stream at the rate you asked for? WitMotion ignores register writes until
  unlocked, and back-to-back GATT writes get dropped.
- What do arrival timestamps look like on a real link? Bursts sharing one timestamp, median dt
  0 ms.
- Does the ZUPT integrator survive continuous cycling with no quiet window?
- Is measured ROM what you assume? 0.5 m squats read 0.15–0.2 m at 10 Hz.
- Which phase does *this* machine start with?
- Is a controlled 5 s eccentric even above the run threshold (0.10 m/s)? A ~5 s lowering averages
  ~0.08 m/s, so it is not.
- What garbage does the re-rack produce?
- What does the lifter *hear* when two counters overlap?

**The discharge is a fixture.** A hardware-found bug is not fixed until it is pinned: the session's
raw capture becomes a `core/dsp/src/test/resources/field-*.csv` plus a case in
`FieldDataRegressionTest.kt`, in the SAME commit as the fix — as d4aa6ed, a50ddee and 2f15e04 each
did.

Work is tracked by GitHub issues; §16's field-selected `gh issue list` form is the live list (147
issues at the time of writing, with real labels: `P0`–`P6`, `audit`, `crash`,
`data-loss`, `wrong-data`, `export`, `performance`, `concurrency`, `ble`, `schema`, `Field`). An
issue body pins its `file:line` claims to the SHA it was audited against — re-verify against
current `origin/main` before acting, and do not assume an issue is right: #22's prescribed
two-line fix did not fix the defect it described. **File a new issue only if the owner asks.**

> When ingesting a field session's capture → Read `.claude/skills/field-ingest/SKILL.md`.

## 15. Defect classes — the incidents behind the names

Every agent uses these names. A definition keeps its own one-line naming of the classes it must
catch; the incidents live here, once.

**A claim stronger than its evidence.** The dominant class. An absolute where the source had a
qualifier; a universal from one observation. House rule: **a comment may state what the code
computes, never what the sensor or the lifter did**, until a field session has measured it.
Canonical instance: `VelocityEstimator.measureSampleRate` presents `(n-1)/spanS` as the sensor's
rate — true only if no samples are missing, since a dropout is arithmetically indistinguishable
from a slower sensor — and the result is written into the export manifest as fact. Apply the rule
in both directions, including to review findings themselves.

**The JVM-only blind spot.** Co-dominant, and the one this history has produced most often.
`settings.gradle.kts:27` gates `:app`, `:core:ble` and `:core:data` out of the build graph
entirely under `-PjvmOnly`; those three contain every Compose screen, all BLE and all Room. So a
`-PjvmOnly`-green run has not compiled half the repo, has not linted it either, and for
`:core:data` has skipped real executed test runs rather than merely uncompiled sources. e199119
renamed `Tempo.eccentricS`/`concentricS` and left six unresolved references in `app/`; 7f0ded2
fixed them 18 minutes later, its body saying plainly *"the local build gates out :app and
:core:data (no Android SDK), so these only surfaced in CI."* The SDK is present now, so that
excuse is gone; the flag that caused it is not. Note *which* CI step caught it: `Unit tests (all
modules)`, because `./gradlew test` compiles `:app` — `Assemble debug APK` never ran. With
`ANDROID_HOME` set, `./gradlew :app:assembleDebug` reaches every consumer; grep only enumerates
call sites you then have to reason about.

**Green where nothing ran.** A command exits 0 without doing what you think it did. See §5 for
the full list of the ways it happens here.

**The wrong pair.** A figure computed against the wrong reference. Check the *operands*, not just
the arithmetic — this one survives review by people who verify the number and not what it is
measured against. Bar power `P = m(g + a)v` is guarded against down-drives and cable machines but
has no `MovementPlane.HORIZONTAL` term, so a seated row adds gravity along a horizontal travel
axis — and `SetAnalyzer` reasons about `HORIZONTAL` elsewhere, which makes the omission the
plausible kind. Second instance: two different quantities are both called "seconds".
`GuidedCadenceRunner` speaks tempo on wall-clock `delay(1_000)` with `.toInt()` truncation while
`SetAnalyzer.complianceFor` grades those same phases against the DSP's reconstructed sample clock.
The app tells the athlete one duration and scores them against another.

**Absence rendered as a value.** Absence must be a distinct state, never a low number; new no-data
paths pick `null`, not `0.0`. Present in both directions here, which makes it teachable. GOOD:
`RepAnalysis.eccS` is `Double?` — *"Never report an unmeasured phase as 0."* BAD, already
shipping: a timed set recorded *with* the sensor on, or a set with 1–7 samples, constructs
`SetAnalysis(emptyList(), 0.0, …)`; because the raw-stream insert only runs when
`imuSamples.isNotEmpty()`, that `0.0` lands in `RawStreamEntity.sampleRateHz` and `RawExporter`
emits `"sampleRate_hz": 0.0` — the exact number `ImuCsv`'s header tells a downstream consumer to
divide by. (A genuinely sensorless manual set inserts no row and the key is omitted, which is
correct.) `num()` skips nulls and prints zeros, so the difference is one character at the call
site and total in the artifact.

**A gap that cannot be represented.** Nothing in `ImuSample`, `CompletedSet` or `SetRecordEntity`
can express "samples are missing", and no code marks one. So a BLE dropout is not lost — it is
*reinterpreted*: the span-based rate estimator silently rescales the whole set's time base, and
every velocity, ROM, tempo and power number comes out wrong by that factor with no marker.
**Not writing something is not neutral; it fabricates.** PROMPT.md demands "mark data gaps" and
nothing does.

**One flag, several jobs.** A variable driving a display claim, a data-write gate and a state
machine cannot take a single fail-closed answer, because the correct direction of failure differs
per job. Enumerate a flag's consumers before changing how it is set. The set-end cluster is the
highest-churn surface in the repo: `guidedSet` forces `manualSet`; `manualSet` gates which UI
branch draws and which counter `stoppedEarly` is judged against; `setTargetMet` gates whether the
effort grid or END SET EARLY is offered. The repo has already learned the fail-direction lesson —
`autoFailed` (derived) and `tappedFailed` (the lifter's own word) are deliberately kept as two
facts and OR-ed, so correcting a miscount re-derives one and cannot erase the other.

**Silent data loss beats a crash, and is worse.** A crash is visible; a destroyed set is
discovered later or never. Rank it first. All in-progress recording state — `imuBuffer`,
`hrBuffer`, `cueBuffer`, tracker, `sessionId` — lives only in `RecordViewModel`'s heap, scoped to
the `composable("record")` back-stack entry, and `RecordScreen`'s TopAppBar draws an unconditional
Back button in every stage including IN_SET. Nothing reaches Room between `beginSet()` and
`recordSet()`, so the loss window is a full set at ~100 samples/second — and because the session
row is created lazily at the first `endSet`, losing set 1 loses the session, while
`RecordingService` keeps the "Recording session" notification up because `stop()` is reached only
from `finishSession()`. Every precondition is verified in source; the consequence is reasoned from
it and has never been observed on a device — it is itself a `[Field]` item.

**Measured, not designed.** An invariant observed across N sessions then relied on as structural.
*Observed* and *guaranteed* are different words. The uniform-clock reconstruction treats "the
sensor samples uniformly" as structural when it is an observation about a healthy link.
`FieldDataRegressionTest`'s bands were fitted, not derived — `reps.size in 4..6` for a 5-rep set —
so a mutation shifting every rep count by one passes. Treat a green `FieldDataRegressionTest` as
evidence against catastrophe, never as evidence of preserved behaviour.

**The near neighbour.** Every round, the reported defect gets fixed and the thing beside it
survives. e199119 added `plane` and `sensorOnStack` and wired them correctly through parsing,
validation, storage and display; **two hours later**, with an intervening fix commit already
landed, 8b0f75e found that `ExerciseDef.liftDirection()` still built its `LiftDirection` from four
of the six fields, so both new declarations were silently defaulted at the hand-off to the DSP.
The downstream handling was correct and simply unreachable. When you add a field, trace it to the
**LAST** consumer, not the first, and never accept "wired through" as evidence.

**Fixes that create defects.** The norm, not the exception — budget for it. The effort-grid rework
took four follow-ups in under two hours: f7bf6f3 → de08c13 → 7cfbf21 → 63ff796 on `main`, plus
c3c9c52 still unlanded on `claude/strength-training-android-app-11lidw`, every one a runtime
state-machine bug in `:app`. The geometry change took three rounds (e199119 → 7f0ded2 → 8b0f75e).
The Gradle plugin classpath took two rounds on the root script (fcf1790 → d76bc30), and fixing it
then let CI reach `:app`'s linters for the first time, costing two more commits — 7bf0b32 for
ktlint wrapping and indent violations, f8d25a0 for detekt `MatchingDeclarationName`. Never land a
commit that has not itself been gated; *"the previous round approved it and I only changed one
line"* is exactly how those cascades happened. When three rounds running find defects in the fix,
stop patching and take one of two structural moves: **extract a pure seam and pin it** — literal
here, because `:core:model`, `:core:dsp`, `:core:hrm` and `:core:witmotion` are pure JVM and a
decision lifted out of `:app` becomes a pin that runs on every push instead of a rule nothing
enforces — or **split the work**, because rounds landing in code *adjacent* to the original defect
mean that adjacent code is its own task and must not hold a data-loss fix hostage.

**Duplicate documentation drifts.** Two near-complete copies of the same facts will diverge — the
class this file exists to end. The LLM plan contract is stated in four places that already
disagree: `GuideScreen.kt`'s `PLAN_PROMPT`, the copy actually shipped to users, says 1.3 with the
geometry keys; `README.md` and `PROMPTS.md` still instruct the model to emit `"schemaVersion":
"1.1"` with no geometry key; `PROMPT.md` shows 1.0. It has already shipped a real bug — the schema
allowed only the old `start` values while the app's own prompt told the model to emit the new
ones, so every plan written by following the app's instructions failed the app's own schema.
Prefer one canonical statement plus a pointer; `PLAN_PROMPT` is what users receive, so it is the
canonical copy.

**Positional pins go stale within the commit that writes them.** "The last three", "one commit
earlier", ":84 here" have each been false at the SHA asserting them. **Name the thing; never count
to it.** That is why this file names symbols and files in preference to line numbers, and pins the
line numbers it does keep to the SHA at the top.

## 16. Command hygiene — gh field selection and search exclusions

Shape output at the command, not by summarising a wall of it afterward. Two more pins beyond the
gradle rule in §4 and the CI poll in §3, both re-verified live against `f2fde86…` and #166.

**`gh`, field-selected always.** Select fields explicitly rather than reading the plain-text
default:

```
gh issue list --repo Macrophage87/BarSpeed --state all --limit 60 --json number,title,state
gh issue view N --repo Macrophage87/BarSpeed --json title,body
```

Verified live at `gh version 2.96.0`: the first returns a JSON array of `{number,state,title}`
objects, most recent first (`167`, `166`, `165`, …). **A retraction, caught by running the
alternative before writing it down rather than trusting #166's own wording**: the issue's body
says the bare `gh issue view N` form "prints the entire comment thread" — false, checked live on
#165 (one comment). Bare `gh issue view` prints a metadata table (mostly empty fields — labels,
assignees, projects, sub-issues, …) plus the body; its `comments:` line is a bare count, and the
thread itself is withheld unless `-c`/`--comments` is passed, which then prints comments ONLY,
not the body. So neither the bare form nor `--comments` gives you title+body+comments in one
call — `--json title,body,comments`, stated explicitly, is the only form that does, and it is
also the only form immune to the metadata table's wasted lines. The protection check already has
its field-selected form in §1 (`gh api …/protection --jq '{enforce_admins:…, linear:…,
contexts:…}'`); this entry does not repeat it, only cross-references it, per §15's "duplicate
documentation drifts" rule.

**Search exclusions: count first, read only where there are hits.** `!**/build/**`, `!.gradle/**`
and `!**/.git/**` — generated Kotlin, R classes and Room's schema-export JSON under a module's
`build/` are indistinguishable from source to a site-counting grep, and inflate a hit count with
nothing a diff needs to touch. Two forms, measured for the token `DatabaseRescue` at
`c1dd4c4b78ad950264c389e70c247b9c80bcb34e`. This paragraph is itself among the matches — it
names the token more than once — so a live re-run on your own checkout is not expected to
reproduce the pinned figures below; treat them as a record of that one SHA, and re-run rather
than trust them:

```
rg -c "PATTERN" --hidden --glob '!**/build/**' --glob '!.gradle/**' --glob '!**/.git/**'
```

**`--hidden` is not optional and is the sharper finding here.** Plain `rg` skips dot-directories
by default, and `.claude/` — the exact tree these command pins live in — is one. Measured at
`c1dd4c4b78ad950264c389e70c247b9c80bcb34e`: `rg -c "DatabaseRescue" --glob '!**/build/**' …` (no
`--hidden`) finds 9 files and silently omits two dot-directory files, `.claude/skills/bench-test/SKILL.md`
and this file (`.claude/facts/live-state.md`); adding `--hidden` finds all 11 files, 66
occurrences total, matching the `Grep` tool's count exactly. The `Grep` tool searches hidden
directories by default with no flag to opt out, so a raw `rg` count run without `--hidden` reads
as agreement with the `Grep` tool while actually excluding every `.claude/**`, `.github/**` and
dotfile site — invisible unless the two forms are cross-checked, which is exactly what this entry
did. The `Grep` tool takes one `glob` string, not repeated flags, so the equivalent exclusion
there is a single brace group: `glob: "!{**/build/**,.gradle/**,**/.git/**}"`,
`output_mode: "count"`. Two-step protocol: run the count form first; only re-run in `content`
mode, and only for the files the count form flagged, when a hit needs reading.
