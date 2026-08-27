---
name: fix-round
description: Run a corrections round against a gate verdict without minting the next false claim. The constraint set assembled from a four-for-four streak of fix rounds each shipping a fresh error inside its own correction prose. Use for every fix-then-land round.
---

# /fix-round <branch> <verdict>

A round's own correction paragraph is where this repository's false claims have most reliably
reproduced: four consecutive gates on one branch went four-for-four — the code converged while
each round's own explanatory prose carried that round's next false claim. The round that broke
the streak was constrained to subtraction — net −88 words across four bodies, no new
explanatory section (issue #111). The rules below accumulated from that round and the
correction rounds after it.

## The constraints

1. **Use the verdict's own substitutions verbatim.** A gate that names a fix has usually written
   the true sentence; producing a fresh framing is how the same claim goes wrong a third way.
   Exception: when the verdict's wording is itself stronger than the code guarantees — check it
   against the code like any claim, and say so when it fails (this has happened — see
   `b41c6e8`, "One correction to the gate that found these…").
2. **No clause explaining *why* unless the reason was measured this round.** No new "because",
   "which means", "so that", "the reason is". Explanatory prose asserts more than was measured —
   that is what makes it explanatory. Grep your own added lines for these tokens before pushing.
3. **Delete, don't reword.** After the second wrong framing of one claim, the claim goes, not
   another version of it. Ask whether the sentence needs to exist; the surrounding truth usually
   survives without it.
4. **After every deletion, re-read the paragraph** — not the deleted sentence. Three questions:
   does the survivor parse; does it hold **alone**, without the half that was cut; does anything
   else in the same file or the same commit body now contradict it. Deleting half a pair
   silently promotes the other half to sole justification — that has shipped twice in one day
   (`Withdrawing a false sentence is only half the work`, `Three earlier cuts had left a
   survivor altered by the deletion`).
5. **Fix the class, not the quoted line.** Grep the tree and every commit body for the *claim*
   and its near variants; a gate quotes one instance because one proves the defect, never
   because it is the only one. Report the site count.
6. **Check you did not delete a correction instead of a claim.** Diff the bodies
   round-over-round, not just the tree.
7. **Report the word count before and after.** Down unless the verdict mandated expansions;
   when it goes up anyway, say so plainly and account for it — an honest "+450, the growth is
   the corrections themselves" beats a manufactured trim.
8. **Push back when the gate is wrong — with evidence.** An implementer that re-derives a
   finding and correctly refuses it is doing its job (it has happened and been vindicated —
   `58b1fed`, "the session block carries a third figure and the gate is wrong about it").
   Refusal requires the measurement in the reply; "I disagree" is not a finding.

## Numbers and history

- Name a parent by its subject line **and** its full 40-character SHA read from `git rev-parse`
  — the subject survives a rebase, the SHA identifies the tree the total was measured on. Never
  type either — a typed SHA has twice diverged from the real parent immediately after the
  7-character abbreviation and shipped false on `main`, in `Measure power in the frame the load
  is weighed in` and in its own parent `Pin that a pulley ratio must not change the power
  published`; a third, caught before landing, diverged after 13.
- Mutation tables state their **direction** (mutating forward from the old tree vs reverting
  one site from the finished tree). `Set the tempo between sets, and stop it leaking into the
  next lift` mixes both in one table and states neither.
- Amending in place: force-push with `--force-with-lease` on your own unlanded `claude/**` tip
  only. Know that only a pushed *head* gets a CI run — an amended red parent keeps its evidence
  via the superseded run's id, cited in the body, not via a fresh red. Confirm the superseded
  run reached `conclusion=failure` before force-pushing — `ci.yml`'s concurrency group is
  per-ref with `cancel-in-progress: true`, so an in-flight run is cancelled by the amend and its
  evidence is lost.

## The ledger row

The loop keeps `<scratch>/rounds.md`: append-only, one row per round — the SHA, what that round
believed, and what the gate on that SHA found. **Read it before you start.** It is where
constraint 6's round-over-round comparison begins, and it names the last gated SHA that your
delta will be read against.

End your round reply with your own row's two fields, so the ledger is written from your words
rather than reconstructed from the tree:

    SHA:      <40 characters, from git rev-parse, not typed>
    believed: <one line: what you think this round fixed, and on what evidence>

"Fixed the verdict's findings" is not a belief. The row is read later by an agent asking why a
round that thought it was finished was not, and a row naming no belief answers nothing.

## The stop rule

**Body rewrites cap at three rounds** — a policy, not a measured threshold; this repository has
gone to five (`Count a re-sent R-R interval once instead of twice`: "five rounds of rewording
them did not converge"). At the cap, apply constraint 3 to whatever is still contested — the
claim goes — and record the remainder in the tip body or a fresh issue. Never land a body still
carrying a claim a gate has called false.
