---
name: release-cut
description: Cut and publish a BarSpeed release — the fresh suite measurement, the two-line version commit with its house-format body, the land-freeze, the Release dispatch, and byte-level artifact verification. The cut body is permanent prose; this ritual keeps it true.
---

# /release-cut <version>

A release is a two-line change (`versionCode`, `versionName` in `app/build.gradle.kts`) carrying
a permanent body on a protected branch, followed by a tag whose artifact the owner installs over
their only training history. Every step below has a failure it exists to prevent.

## 1. Measure fresh at the parent — never inherit a total

```
./gradlew test --rerun-tasks --no-build-cache
```
with `test-results` cleared first, `BUILD SUCCESSFUL` and the executed-task count confirmed
**before** parsing XML — the flags make a cache restore impossible; confirm they took by reading
the executed-task count (v0.1.43's body: "141 of 141 tasks executed"). A `FROM-CACHE` line
anywhere means the flags were dropped. State the total in the body as
"N, measured at <full 40-char SHA read from `git rev-parse`> by <command>". Never type a SHA.

## 2. The cut commit, on its own `claude/release-X-Y-Z` branch

One file, two lines, no source, no schema, no test — and say so in the body. House body format
(match the prior cuts):
- The changes a lifter meets, one bullet each, present tense for what the app now does — past
  tense only for the behaviour it replaced — with the issue number where one exists.
- The record-keeping changes (schema versions, contracts) separately.
- **`DATABASE_VERSION`, stated either way**: unchanged means no NEW rollback boundary — the
  last one introduced is still live and may still be owed a bench-test; say which, and say
  that an unchanged number is evidence of no schema change, never evidence that an install is
  safe. Changed means the two-way `/bench-test` is owed before the artifact meets a real phone,
  and the body says so.
- **Deliberate omissions, named with their issues** — "not here, named so the omission is
  deliberate rather than discovered" is the house formula. A remainder outside the tracker does
  not exist.
- Never reuse a prior cut's rollback paragraph without re-deriving it: DATABASE_VERSION held at
  9 through v0.1.41 and moved to 10 at v0.1.42 — the rollback paragraph is re-derived at every
  cut, never carried forward.

## 3. Gate the cut on its own SHA, then land with a freeze

CI green on the cut's exact SHA (`/land` checklist applies). Then the freeze:
**nothing may land on `main` between the cut landing and the Release run completing** —
`release.yml`'s release step (release.yml:58-64) passes `tag_name` with no `target_commitish`,
so GitHub creates the tag from `main`'s HEAD at the moment that step runs — the end of the
build, not the dispatch. v0.1.5 is the proof: v0.1.5 and v0.1.6 both resolve to 56448cb, "Bump
version to 0.1.6", which landed while the run was in flight. Check nothing else is in flight
(`gh run list --workflow release.yml`), then:

```
gh workflow run Release -f tag=vX.Y.Z --repo Macrophage87/BarSpeed
```

## 4. Verify the artifact in the bytes — "the build said so" is not verification

- Tag → commit: `git fetch origin --tags --force`; `git rev-parse vX.Y.Z^{commit}` equals the
  landed cut SHA equals `origin/main`.
- `versionCode`/`versionName` read from the tag's tree.
- Download the APK (`gh release download`), record size and sha256, and **confirm the release's
  features are present in the dex** — grep the dex for user-facing string literals the release
  added (class names are minified in release builds, so symbol absence proves nothing; runtime-
  assembled strings are also absent by construction — pick literals that must survive R8):
  `unzip -p BarSpeed-vX.Y.Z.apk 'classes*.dex' | strings | grep -F "<literal>"`, run against
  both the new and previous APK. Compare against the previous release's APK: the new literals
  absent there, present here.
- Identical file sizes are routine here — v0.1.23 through v0.1.30 all measure 1,909,044 bytes,
  and v0.1.41 and v0.1.42 both 1,975,176 with different digests. Size carries no information;
  digests and content decide.

## 5. Close out

A comment naming the tag on the issues that shipped in it — new with this skill; landed-SHA
comments are `/land`'s step and are already posted. The release summary to the owner with the
artifact digest, and the bench-test reminder if `DATABASE_VERSION` moved.
