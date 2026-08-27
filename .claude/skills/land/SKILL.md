---
name: land
description: Land a gated claude/** branch onto main by fast-forward — the full precondition checklist, the delta read, the push, and the issue close. Use for every landing; skipping a step is how a false claim or a stale ref reaches a protected branch.
---

# /land <branch> [issue#]

Lands a reviewed branch onto `main`. `main` is protected — required context `Build, lint, test`,
strict up-to-date, `enforce_admins`, linear history — so a landed commit and its body are
**permanent**. Every step here but the first has caught a real problem at least once, and most
name it. Run every step; none is decorative.

## Preconditions — verify, never assume

0. **Authorised**: explicit direction from the owner *and* a stated Accept from the gate. The
   green run is the third condition, not the only one (`barspeed-orchestrator.md`).
1. `git fetch origin` — work from `origin/` refs only. A long-lived clone's local `main` can run
   far behind `origin/main` — landed bodies record rebases off bases six and nine commits
   behind; never review or land from a local ref.
2. **`main` unmoved**: `git rev-parse origin/main` still equals the base the gated SHA was built
   on (`git merge-base origin/main <tip>`). If it moved, STOP — the branch rebases and re-gates
   on a fresh green first. A pre-rebase green does not carry over.
3. **Fast-forward**: `git merge-base --is-ancestor origin/main <tip>` must pass. `git log
   --branches --remotes --merges` is empty across 351 commits (`--all` also walks `refs/stash`,
   which is a merge); a landing must not create the first.
4. **CI on the exact SHA, read from the run object** — never from a check-run count:
   `gh run list --repo Macrophage87/BarSpeed --commit <FULL-40-char-SHA> --workflow ci.yml
   --json databaseId,event,headBranch,status,conclusion`
   Require `event=push`, the branch's own name, `conclusion=success`. The `--commit` filter
   needs the full 40 characters — an abbreviated SHA returns `[]` and exits 0 — indistinguishable
   from "no CI ran".
5. **Scope check**: `git diff --name-only origin/main <tip>` — every file explicable.
   `core/data/schemas/.../<N>.json` is **tracked** (`Commit Room's exported schema, ending a trap
   and opening a door`); it must appear in a commit that changes a `:core:data` entity, and must
   not appear in one that does not — any *untracked* file under that directory is a build
   leftover and must never be swept in. Name every file path explicitly; never `git add <dir>`,
   `-A` or `.` (issue #97 records six sweeps, one reaching a remote branch at 1,212 insertions on
   an eight-line change). Read the body's trailers — `Co-Authored-By: Claude Opus 5
   <noreply@anthropic.com>` then `Claude-Session:`, with no internal codename or vendor
   identifier anywhere in the message.

## The delta read — the step that earns the checklist

Read the prose that changed since the last gate verdict: amended commit bodies, new KDoc,
changed captions and schema descriptions. The dominant defect class in this repository is a
false claim shipped in prose, and four consecutive fix rounds once introduced a fresh false
claim each inside their own correction paragraphs (the four rounds ran on #111's branch,
claude/rescued-database-card). For every deletion, check the surviving
sentence parses and holds alone; for every quoted residue of a refuted claim, confirm it sits
inside a retraction, not a standing claim (`grep` the claim, not the line). A residue grep that
returns hits is not a failure by itself — read them; retractions legitimately quote what they
retract.

## Land

Not while a Release run is in flight — `release.yml` has no concurrency group.

```
git push origin <FULL-SHA>:refs/heads/main
```

Push the SHA, not the branch name — it lands exactly what was verified even if the branch
moves underneath.

## After

- Where an issue exists, close it with the **full landed SHA** (paste it from `git rev-parse`
  output — a typed SHA has twice diverged from the real parent immediately after the
  7-character abbreviation and shipped false on `main`, in `Measure power in the frame the load
  is weighed in` and in its own parent `Pin that a pulley ratio must not change the power
  published`; a third, caught before landing, diverged after 13), the red and green CI run ids
  where red-before-green applies, and one line naming any remainder issue.
- If other unlanded branches touch the same files, note that they now need a rebase and a fresh
  green before their own landing.

## Never

- Never land on a fix-then-land verdict with open blocking items.
- Force-push is pre-authorised only for unlanded `claude/**` branches this loop created and you
  were dispatched onto — never `main`, never anything already landed, and deletion is not
  covered by the grant.
- Never substitute a check-run count, a badge, or an agent's report for reading the run object.
