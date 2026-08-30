---
name: bench-test
description: Run device-level verification on the local headless Android emulator — install/upgrade/downgrade paths, migrations against real data, and screen renders — with screenshot evidence. Use for every DATABASE_VERSION bump and any :app screen change before it meets the lifter's phone.
---

# /bench-test

`:core:ble` has no test source set at all, and `:app` has two files (`PlanQueueTest`, 5 tests over
one pure function, and `AppendedSlotTest`, 9 over the appended-set rule) — so almost nothing in
`:app` has executable JVM coverage for behaviour, but
that is not the end of the story: anything reachable by screenshot-driven navigation can be
verified here, on a disposable database, before it meets the only real training history that
exists. This recipe executed `MIGRATION_9_10` under observation for the first time and produced
the rescue UI's first recorded render (issue #118's close) — a migration verified here has NOT
been verified by any repository test; `AppDatabase`'s KDoc still says the first run is on the
lifter's phone, and that sentence needs correcting. What it cannot do: WitMotion/BLE, real TTS
timing, or a lifter's judgment — those stay `[Field]`.

## Launch (the machine is RAM-poor; these flags are load-bearing)

`export ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"; export
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"` — then every `adb`/`emulator`
line below resolves as written. AVD **`barspeed-api35`** exists (API 35, x86_64, google_apis); if
it does not, recreate it: `sdkmanager "system-images;android-35;google_apis;x86_64"` then
`avdmanager create avd -n barspeed-api35 -k "system-images;android-35;google_apis;x86_64" -d
pixel_6` — google_apis, not google_apis_playstore, because `adb root` needs a userdebug image.
Check ≥ ~3 GB free RAM first; the emulator occupies the machine's single builder slot — run no
Gradle beside it.

```
emulator -avd barspeed-api35 -no-window -no-snapshot -no-boot-anim -no-audio \
  -memory 1536 -gpu swiftshader_indirect
```

Background it; `adb wait-for-device`, then poll `adb shell getprop sys.boot_completed` until `1`
(boots in ~1 min). Add `-wipe-data` when the test needs a virgin device. Then pin the target for
everything below: `adb devices` must show exactly one device and it must be the emulator;
`export ANDROID_SERIAL=emulator-5554` (or pass `-s emulator-5554` on every command) so no
invocation can reach a phone on USB.

## Capture/attach split

Screencap at **every** step — `adb exec-out screencap -p > stepN-desc.png` is cheap and local,
so there is no reason to skip one. Attaching a PNG into the model's own context is not cheap, so
attach only at decision points: a state you are about to act on, or one you are about to report
as a finding. Four full-screen attachments are non-negotiable, every run, whether or not the
screen "looks unchanged" from the previous step — because two of the three FAIL states of the
migration two-way test below look exactly like a normal screen, and "it looked fine" is not
evidence against the one you skipped attaching:

1. **Old-version rows recorded** (step 1 of the two-way test) — the baseline the whole test is
   checked against; without it attached, "old sessions listed" in step 2 has nothing to compare
   to.
2. **Post-upgrade history listing** (step 2) — the FAIL states are step 4's list below; "old
   sessions visible as if nothing happened" renders identically to PASS unless the row-for-row
   content is actually attached and read, not glanced at.
3. **Post-downgrade rescue card** (step 4) — the FAIL state "old sessions visible as if nothing
   happened" recurs here for the same reason: a rescue that silently no-ops looks like a normal
   history screen.
4. **The opened discard dialog, before it is cancelled** (step 4) — the only evidence the dialog
   existed and named what it would discard, since the action itself must never be confirmed.

Layout checks (`font_scale`, `wm size`) also stay full-screen, never cropped — clipping is a
whole-screen property; a crop that avoids the clipped edge is the one framing that cannot show
the defect being checked for.

## Techniques, each proven

- **Navigate by screenshot, never by blind taps**: `adb exec-out screencap -p > step.png`,
  view the PNG, `adb shell input tap X Y`, screenshot again. Name evidence `stepN-desc.png`.
- **Release APKs**: `gh release download vX.Y.Z -R Macrophage87/BarSpeed -p '*.apk'` — every
  release from v0.1.1 on is signed with the same key, so cross-version `adb install -r` works;
  v0.1.0's asset is `app-release-unsigned.apk` and will not install at all. Debug APKs from CI
  are signed with a per-runner debug key and share the release `applicationId`, so installing
  one over a release APK is refused outright, not installed alongside.
- **Downgrade**: `adb install -r -d old.apk` is honored on the emulator (retail phones usually
  refuse it for release-signed apps) — this is what makes the rollback/rescue path testable.
- **Database verification**: `adb root`, then `adb shell sqlite3
  /data/data/com.macrophage.barspeed/databases/accelerometer_lifting.db` — `PRAGMA
  user_version`, `.schema`, row-level checks. Columns appended *unquoted* at the table end are
  the raw `ALTER TABLE` signature; a normalized recreate means Room created the table fresh
  rather than migrating it — most likely `DatabaseRescue` moved the old file aside, or this was
  not an upgrade at all. Either way it is a finding; note that no destructive fallback is in the
  chain, so a missing migration throws rather than silently recreating.
- **Logcat around the moment, bounded — never bare `logcat -d`**: `adb logcat -c` before, then
  `adb logcat -d -t 300` with either a filterspec (`adb logcat -d -t 300 ActivityManager:I *:S`)
  or `--pid` (`adb logcat -d -t 300 --pid="$(adb shell pidof <process>)"`) scoping the buffer
  before it reaches you, piped to `grep -E "Migration|SQLiteException|Fatal"` for the moment
  under test. Bare `adb logcat -d` on a freshly booted emulator returns the whole buffer —
  measured at 17,459 lines against 327 for the same buffer read with `-t 300` — almost none of it
  from the app under test; `-c` clears the buffer at the moment it runs, but every system process
  keeps writing to it in the seconds between that clear and the `-d` read, so the buffer is noisy
  again by the time you capture it. Save the bounded dump beside the screenshots, not the
  unbounded one.
- **Version/install text twin**: `adb shell dumpsys package <pkg>` is thousands of lines; grep it
  to the four lines that answer "which build is this and when did it land" —
  `grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime"`. Verified live against
  `com.android.settings` on `barspeed-api35`: `versionCode=35 minSdk=35 targetSdk=35` on one
  line, `versionName=15` on the next, then `lastUpdateTime` and `firstInstallTime` as timestamps
  — four lines, not the dump. Never re-paste a log region already quoted earlier this session;
  cite the earlier quote by its step name instead — the buffer has not changed since you read it,
  and a second paste doubles the tokens for zero new information.
- **First-run prompts**: deny BLE/notifications — no sensor is needed; ad-hoc sets with manual
  `+1 REP` and typed tempos write real database rows. The voice guide's cue scheduling is
  reachable without a sensor, but `-no-audio` plus a possibly-absent TTS engine means
  `VoiceCounter.speak` can no-op silently — verify it in logcat or not at all, and never report
  it as heard. For layout screenshots, instead pre-grant so the permission banner does not
  contaminate them: `adb shell pm grant com.macrophage.barspeed android.permission.BLUETOOTH_CONNECT`
  (and `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS`).
- **Font-scale and size variants** for layout checks:
  `adb shell settings put system font_scale 2.0`, `adb shell wm size 360x800` (reset with
  `adb shell wm size reset` and `adb shell settings put system font_scale 1.0` — an un-reset
  override survives reboot).

## The migration two-way test (run for every DATABASE_VERSION bump)

1. Install the last release carrying the OLD `DATABASE_VERSION` — find it with `git show
   vX.Y.Z:core/data/src/main/kotlin/com/macrophage/barspeed/data/AppDatabase.kt | grep
   DATABASE_VERSION`; it is often NOT the immediately-previous tag (for the 9→10 bump it was
   v0.1.41, two back from v0.1.43). Record one guided and one unguided set (real rows at old
   version).
2. `adb install -r` the new build — the migration's first execution. PASS: opens, old session
   listed, logcat clean, and logcat shows the migration actually ran — a clean log with
   `user_version` already at the new number proves nothing; verify `user_version` and the
   appended columns via sqlite3.
3. Record one more set on the new build.
4. `adb install -r -d` the last release carrying the OLD `DATABASE_VERSION` (same tag as step
   1), cold launch. PASS: the rescued-database card, with a size consistent with the rescued
   files (`ByteSize.format`, decimal 1000-based, one decimal place — 119,008 B renders
   `119.0 KB`, so check the sum against that precision, not against digits), and a discard
   dialog you open, screenshot, and **cancel** — never confirm. FAIL: crash, or old sessions
   visible as if nothing happened, or silent wipe.

## Teardown

`adb emu kill` (after `adb unroot` if rooted); verify `adb devices` is empty and no qemu process
remains. **Never kill any java process** — the emulator is qemu; Gradle daemons are shared with
other work and are not yours to stop.

## Evidence

Screenshots and logcat dumps go to the session scratchpad, listed by filename in the report; the
four mandatory attachments plus any other decision-point capture go to the owner. A bench claim
without its screenshot is a report, not evidence.
